package com.alecdorrington.iris

import cats.syntax.all.*
import com.alecdorrington.cadmus.Scaladoc
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json, JsonObject}
import io.circe.DecodingFailure.Reason
import io.circe.syntax.*
import scala.compiletime.{
  constValue, constValueTuple, erasedValue, summonAll, summonFrom,
}
import scala.deriving.Mirror

/**
  * A type whose values travel to and from the model as JSON: how a value is
  * written, how one is read back, and the [JSON
  * Schema](https://json-schema.org) the model is told they conform to.
  * [[LlmClient.ask]] asks for one, and [[Content]] sends one.
  *
  * Strings, numbers, Booleans, options, lists, vectors, sequences, sets and
  * maps keyed by strings have one, and case classes, sealed traits and enums
  * derive one, provided every field's type has one:
  *
  * {{{
  * case class Person(name: String, age: Int, nickname: Option[String])
  *   derives Structured
  * }}}
  *
  * A case class is an object of its fields, of which an optional one may be
  * left out. A sealed trait or enum whose cases have no fields is the name of
  * one of them, as `"Red"`; any other is an object holding one case under its
  * name, as `{"Circle":{"radius":1.0}}`. A recursive type refers to itself
  * through definitions in the schema, named for its type, so that a type within
  * another of the same name, as `Page[Page[A]]`, is taken for it.
  *
  * The schema describes a derived type, each of its cases and each of its
  * fields as its Scaladoc does: a type or case by its description, and a field
  * by its class's `@param` tag. Code examples are left out, and links written
  * as their labels. A comment is read only where its type is compiled, which is
  * always so for `derives Structured`.
  *
  * @tparam A
  *   The type.
  *
  * @param encoder
  *   The encoder writing a value as JSON.
  *
  * @param decoder
  *   The decoder reading a value from JSON, which reports every failure at once
  *   where accumulating.
  */
final class Structured[A] private (
  val encoder: Encoder[A],
  val decoder: Decoder[A],
  shaped: => Shape,
  private[iris] val name: Option[String],
  private[iris] val optional: Boolean,
  private[iris] val description: Option[String] = None,
):

  /** What this type's schema is made of, read when first needed. */
  private[iris] lazy val shape: Shape = shaped

  /** The JSON Schema of this type's values. */
  lazy val schema: Json = Shape.render(this)

  /**
    * Writes and reads another type as this one, with the same schema, as for a
    * wrapper of a value. The other type's Scaladoc describes it, where it has
    * one, and otherwise this type's.
    *
    * @tparam B
    *   The other type.
    *
    * @param to
    *   The conversion of a value read into the other type.
    *
    * @param from
    *   The conversion of the other type's value to write.
    *
    * @return
    *   A structured type converted from this one.
    */
  inline def imap[B](to: A => B)(from: B => A): Structured[B] =
    emap(to.andThen(Right(_)))(from)

  /**
    * Writes and reads another type as this one, with the same schema, refusing
    * some values read, as for a number within bounds. The model is told why a
    * value was refused, as for any other that could not be read. The other
    * type's Scaladoc describes it, where it has one, and otherwise this type's.
    *
    * @tparam B
    *   The other type.
    *
    * @param to
    *   The conversion of a value read into the other type, or why it cannot be.
    *
    * @param from
    *   The conversion of the other type's value to write.
    *
    * @return
    *   A structured type converted from this one.
    */
  inline def emap[B](to: A => Either[String, B])(from: B => A): Structured[B] =
    converted(
      to,
      from,
      Structured.described[B].flatMap(_.description),
    )

  private def converted[B]
    (
      to: A => Either[String, B],
      from: B => A,
      described: Option[String],
    )
    : Structured[B] = new Structured(
    encoder.contramap(from),
    decoder.emap(to),
    shape,
    name,
    optional,
    described.orElse(description),
  )

object Structured:

  /**
    * Summons the structured type of `A`.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   The structured type of `A`.
    */
  def apply[A](using structured: Structured[A]): Structured[A] = structured

  /**
    * Creates a structured type of a codec and a schema of one's own, which the
    * codec must keep to.
    *
    * @tparam A
    *   The type.
    *
    * @param schema
    *   The JSON Schema of the type's values.
    *
    * @return
    *   A structured type writing and reading through circe's codec.
    */
  def of[A : {Encoder, Decoder}](schema: Json): Structured[A] = new Structured(
    Encoder[A],
    Decoder[A],
    Shape.Fixed(schema),
    None,
    false,
  )

  given Structured[String]     = simple("string")
  given Structured[Boolean]    = simple("boolean")
  given Structured[Int]        = simple("integer")
  given Structured[Long]       = simple("integer")
  given Structured[BigInt]     = simple("integer")
  given Structured[Double]     = simple("number")
  given Structured[Float]      = simple("number")
  given Structured[BigDecimal] = simple("number")

  /** An optional value, which as a field may also be left out. */
  given [A : Structured as value]: Structured[Option[A]] = new Structured(
    Encoder.encodeOption(using value.encoder),
    Decoder.decodeOption(using value.decoder),
    Shape.Nullable(value),
    None,
    true,
  )

  given [A : Structured as item]: Structured[List[A]] = listed(
    Encoder.encodeList(using item.encoder),
    Decoder.decodeList(using item.decoder),
    item,
  )

  given [A : Structured as item]: Structured[Vector[A]] = listed(
    Encoder.encodeVector(using item.encoder),
    Decoder.decodeVector(using item.decoder),
    item,
  )

  given [A : Structured as item]: Structured[Seq[A]] = listed(
    Encoder.encodeSeq(using item.encoder),
    Decoder.decodeSeq(using item.decoder),
    item,
  )

  given [A : Structured as item]: Structured[Set[A]] = listed(
    Encoder.encodeSet(using item.encoder),
    Decoder.decodeSet(using item.decoder),
    item,
    unique = true,
  )

  given [A : Structured as value]: Structured[Map[String, A]] = new Structured(
    Encoder.encodeMap[String, A](using summon, value.encoder),
    Decoder.decodeMap[String, A](using summon, value.decoder),
    Shape.Keyed(value),
    None,
    false,
  )

  /**
    * Derives the structured type of a case class, sealed trait or enum, for
    * `derives Structured`. Every field's type must have one, and a case of a
    * sealed trait derives its own where it has none.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   A structured type of `A`.
    */
  inline def derived[A](using mirror: Mirror.Of[A]): Structured[A] =
    inline mirror match
      case sum: Mirror.SumOf[A] => union(
          constValue[sum.MirroredLabel],
          described[A].flatMap(_.description),
          labels[sum.MirroredElemLabels],
          cases[sum.MirroredElemTypes],
          sum.ordinal,
        )
      case product: Mirror.ProductOf[A] => record(
          constValue[product.MirroredLabel],
          described[A],
          labels[product.MirroredElemLabels],
          summonAll[Tuple.Map[product.MirroredElemTypes, Structured]]
            .toList
            .asInstanceOf[List[Structured[?]]],
          product.fromProduct,
        )

  /** A type's Scaladoc in plain words, where it is compiled alongside. */
  private inline def described[A]: Option[Scaladoc] = Scaladoc
    .of[A]
    .map(_.plain)

  private inline def labels[Labels <: Tuple]: List[String] =
    constValueTuple[Labels].toList.asInstanceOf[List[String]]

  private inline def cases[Cases <: Tuple]: List[Structured[?]] =
    inline erasedValue[Cases] match
      case _: EmptyTuple    => Nil
      case _: (c *: others) => summonedOrDerived[c] :: cases[others]

  private inline def summonedOrDerived[A]: Structured[A] = summonFrom:
    case structured: Structured[A] => structured
    case mirror: Mirror.Of[A]      => derived[A](using mirror)

  private def simple[A : {Encoder, Decoder}](kind: String): Structured[A] =
    of(Json.obj("type" -> kind.asJson))

  private def listed[C]
    (
      encoder: Encoder[C],
      decoder: Decoder[C],
      item: Structured[?],
      unique: Boolean = false,
    )
    : Structured[C] = new Structured(
    encoder,
    decoder,
    Shape.Listed(item, unique),
    None,
    false,
  )

  /**
    * The structured type of a case class, its fields summoned only when first
    * needed, so that a recursive type can be made before the fields that refer
    * back to it. Each field is described by its class's `@param` tag.
    */
  private def record[A]
    (
      name: String,
      doc: Option[Scaladoc],
      labels: List[String],
      fields: => List[Structured[?]],
      build: Product => A,
    )
    : Structured[A] =
    lazy val named = labels
      .lazyZip(fields.map(_.asInstanceOf[Structured[Any]]))
      .lazyZip(labels.map(label => doc.flatMap(_.params.get(label))))
      .map(Shape.Field.apply)
    new Structured(
      Encoder
        .AsObject
        .instance(value =>
          JsonObject.fromIterable(
            named
              .zip(value.asInstanceOf[Product].productIterator)
              .flatMap(written),
          ),
        ),
      accumulating(cursor =>
        if !cursor.value.isObject then expected("object", cursor).invalidNel
        else
          named
            .traverse(field =>
              field
                .structured
                .decoder
                .tryDecodeAccumulating(cursor.downField(field.label)),
            )
            .map(values => build(Tuple.fromArray(values.toArray))),
      ),
      Shape.Record(named),
      Some(name),
      false,
      doc.flatMap(_.description),
    )

  /** A field as written, left out where it is optional and unset. */
  private def written(field: Shape.Field, value: Any): Option[(String, Json)] =
    Some(field.structured.encoder(value))
      .filterNot(json => field.structured.optional && json.isNull)
      .map(field.label -> _)

  /**
    * The structured type of a sealed trait or enum: the name of a case where no
    * case has fields, and otherwise an object holding the case under its name.
    */
  private def union[A]
    (
      name: String,
      description: Option[String],
      labels: List[String],
      cases: => List[Structured[?]],
      ordinal: A => Int,
    )
    : Structured[A] =
    lazy val named = labels.zip(cases.map(_.asInstanceOf[Structured[Any]]))
    lazy val bare  = named.forall(_._2.shape == Shape.Record(Nil))
    new Structured(
      Encoder.instance(value => labelled(named(ordinal(value)), bare, value)),
      accumulating(cursor =>
        if bare then
          cursor.as[String].toValidatedNel.andThen(enumerated(named, cursor))
        else tagged(named, cursor),
      ),
      if bare then Shape.Enumerated(named) else Shape.Tagged(named),
      Some(name),
      false,
      description,
    )

  /** A case as written, by its name alone where no case has fields. */
  private def labelled
    (
      named: (String, Structured[Any]),
      bare: Boolean,
      value: Any,
    )
    : Json =
    val (label, structured) = named
    if bare then label.asJson else Json.obj(label -> structured.encoder(value))

  /** The case a bare union names, read from an object of no fields. */
  private def enumerated[A]
    (
      cases: List[(String, Structured[Any])],
      cursor: HCursor,
    )
    (label: String)
    : Decoder.AccumulatingResult[A] = cases
    .find(_._1 == label)
    .fold(oneOf(cases, cursor).invalidNel)(
      _._2.decoder.decodeAccumulating(Json.obj().hcursor),
    )
    .map(_.asInstanceOf[A])

  /** The case an object holds under its name. */
  private def tagged[A]
    (
      cases: List[(String, Structured[Any])],
      cursor: HCursor,
    )
    : Decoder.AccumulatingResult[A] = cases
    .find((label, _) => cursor.keys.exists(_.exists(_ == label)))
    .fold(oneOf(cases, cursor).invalidNel)((label, structured) =>
      structured.decoder.tryDecodeAccumulating(cursor.downField(label)),
    )
    .map(_.asInstanceOf[A])

  private def oneOf
    (
      cases: List[(String, Structured[Any])],
      cursor: HCursor,
    )
    : DecodingFailure = DecodingFailure(
    Reason.CustomReason(s"Expected one of ${ cases.map(_._1).mkString(", ") }"),
    cursor,
  )

  private def expected(kind: String, cursor: HCursor): DecodingFailure =
    DecodingFailure(
      Reason.WrongTypeExpectation(kind, cursor.value),
      cursor,
    )

  /** A decoder that, where accumulating, reports every failure at once. */
  private def accumulating[A]
    (read: HCursor => Decoder.AccumulatingResult[A])
    : Decoder[A] = new Decoder[A]:

    override def apply(cursor: HCursor): Decoder.Result[A] = read(cursor)
      .toEither
      .leftMap(_.head)

    override def decodeAccumulating
      (cursor: HCursor)
      : Decoder.AccumulatingResult[A] = read(cursor)

/** What a [[Structured]] type's schema is made of. */
private[iris] enum Shape:

  /** A schema of its own, as of a number. */
  case Fixed(schema: Json)

  /** A value that may be null. */
  case Nullable(value: Structured[?])

  /** An array of items, which may have to differ. */
  case Listed(item: Structured[?], unique: Boolean)

  /** An object of any keys, whose values are alike. */
  case Keyed(value: Structured[?])

  /** An object of named fields, those not optional required. */
  case Record(fields: List[Shape.Field])

  /** The name of one of several cases of no fields, each as it is described. */
  case Enumerated(cases: List[(String, Structured[?])])

  /** An object holding one of several cases under its name. */
  case Tagged(cases: List[(String, Structured[?])])

  /** The types this schema is made of. */
  def parts: List[Structured[?]] = this match
    case Fixed(_) | Enumerated(_) => Nil
    case Nullable(value)          => List(value)
    case Listed(item, _)          => List(item)
    case Keyed(value)             => List(value)
    case Record(fields)           => fields.map(_.structured)
    case Tagged(cases)            => cases.map(_._2)

  /** This schema, its parts described as given. */
  def schema(describe: Structured[?] => Json): Json = this match
    case Fixed(schema)   => schema
    case Nullable(value) => Json.obj(
        "anyOf" -> Json.arr(
          describe(value),
          Json.obj("type" -> "null".asJson),
        ),
      )
    case Listed(item, unique) => JsonHttp.objectOf(
        "type"        -> "array".asJson,
        "items"       -> describe(item),
        "uniqueItems" -> Option.when(unique)(true).asJson,
      )
    case Keyed(value) => Json.obj(
        "type"                 -> "object".asJson,
        "additionalProperties" -> describe(value),
      )
    case Record(fields) => Shape.closed(
        fields.map(field =>
          field.label -> Shape.describedAs(
            describe(field.structured),
            field.description,
          ),
        ),
        fields.filterNot(_.structured.optional).map(_.label),
      )
    case Enumerated(cases) if cases.forall(_._2.description.isEmpty) =>
      Json.obj(
        "type" -> "string".asJson,
        "enum" -> cases.map(_._1).asJson,
      )
    case Enumerated(cases) => Json.obj(
        "anyOf" ->
          cases
            .map((label, value) =>
              Shape.describedAs(
                Json.obj("const" -> label.asJson),
                value.description,
              ),
            )
            .asJson,
      )
    case Tagged(cases) => Json.obj(
        "anyOf" ->
          cases
            .map((label, value) =>
              Shape.closed(
                List(label -> describe(value)),
                List(label),
              ),
            )
            .asJson,
      )

private[iris] object Shape:

  /**
    * A field of a case class.
    *
    * @param label
    *   The field's name.
    *
    * @param structured
    *   The field's structured type.
    *
    * @param description
    *   The field's description, which replaces its type's.
    */
  final case class Field
    (
      label: String,
      structured: Structured[Any],
      description: Option[String],
    )

  /**
    * The schema of a structured type. Each type that recurs is described once
    * in `$defs`, and referred to by name wherever it appears.
    */
  def render(structured: Structured[?]): Json =
    val defined = recurring(structured, Set.empty)
    val names   = defined.keySet
    val root    = described(structured, names)
    if defined.isEmpty then root
    else
      root.deepMerge(Json.obj(
        "$defs" -> Json.obj(
          defined
            .toList
            .sortBy(_._1)
            .map((name, recurs) => name -> body(recurs, names))*,
        ),
      ))

  /** A schema with a description first, in place of any of its own. */
  def describedAs
    (
      schema: Json,
      description: Option[String],
    )
    : Json = description.fold(schema)(text =>
    schema.mapObject(("description" -> text.asJson) +: _.remove("description")),
  )

  /** The types, by name, met again within themselves. */
  private def recurring
    (
      structured: Structured[?],
      within: Set[String],
    )
    : Map[String, Structured[?]] = structured.name.filter(within) match
    case Some(name) => Map(name -> structured)
    case None       => structured
        .shape
        .parts
        .map(recurring(_, within ++ structured.name))
        .foldLeft(Map.empty)(_ ++ _)

  private def described
    (
      structured: Structured[?],
      defined: Set[String],
    )
    : Json = structured
    .name
    .filter(defined)
    .fold(body(structured, defined))(reference)

  /** A type's own schema, its parts described within it. */
  private def body
    (
      structured: Structured[?],
      defined: Set[String],
    )
    : Json = describedAs(
    structured.shape.schema(described(_, defined)),
    structured.description,
  )

  private def reference(name: String): Json =
    Json.obj("$ref" -> s"#/$$defs/$name".asJson)

  /** An object of exactly the given properties. */
  private def closed
    (
      properties: List[(String, Json)],
      required: List[String],
    )
    : Json = Json.obj(
    "type"                 -> "object".asJson,
    "properties"           -> Json.obj(properties*),
    "required"             -> required.asJson,
    "additionalProperties" -> false.asJson,
  )
