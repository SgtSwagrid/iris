package com.alecdorrington.iris

import cats.syntax.all.*
import com.alecdorrington.cadmus.Scaladoc
import fs2.Stream
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json, JsonObject}
import io.circe.DecodingFailure.Reason
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import scala.annotation.{implicitNotFound, unused}
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror
import scala.util.NotGiven

/**
  * How a type's values travel to and from the model as JSON: how a value is
  * written, how one is read back, and the [JSON
  * Schema](https://json-schema.org) the model is told they conform to.
  * [[LlmClient.ask]] asks for a type with one, and [[Content]] says one.
  *
  * Strings, numbers, Booleans, options, lists, vectors, sequences, sets, maps
  * keyed by strings, tuples, eithers, unions and literal types have one, and so
  * does every case class, sealed trait and enum whose fields have one, derived
  * wherever it is needed, but for one with a circe `Encoder` of its own, which
  * is said as that writes it:
  *
  * {{{
  * case class Person(name: String, age: Int, nickname: Option[String] = None)
  *
  * client.ask[Person](Chat.of("Who wrote Middlemarch?"))
  * }}}
  *
  * `derives Schema` derives one once, beside its type, rather than wherever it
  * is needed, even for a type with an `Encoder` of its own, and names any field
  * whose type has none. Circe's `Json` has none, as it would tell the model
  * nothing.
  *
  * A case class, or a named tuple, is an object of its fields, of which one
  * that is optional or has a default may be left out, and is read as `None` or
  * its default. A sealed trait or enum whose cases have no fields is the name
  * of one of them, as `"Red"`; any other is an object holding one case under
  * its name, as `{"Circle":{"radius":1.0}}`, as an `Either` holds its value
  * under `Left` or `Right`, and a union under the name of its alternative, as
  * `{"Int":1}`, which a value's class must tell, but for a union of literal
  * types alone, which is one of their values. A tuple is an array of its
  * elements, and an fs2 `Stream` an array of its values, which
  * [[LlmClient.askStreamed]] delivers as the model writes them, but which
  * cannot be said as JSON. A recursive type refers to itself through
  * definitions in the schema, each type told apart by its full name and type
  * arguments, so that `Tree[Int]` and `Tree[String]` are each defined.
  *
  * The schema describes a derived type, each of its cases and each of its
  * fields as its Scaladoc does: a type or case by its description, and a field
  * by its class's `@param` tag, followed by its type's description. Code
  * examples are left out, and links written as their labels. A comment is read
  * only where its type is compiled alongside, as it always is for
  * `derives Schema`, or anywhere under `-Xread-docs`, and never under
  * `-Xdrop-docs`.
  *
  * In JavaScript, a reply's numbers are read as doubles, so a `Long`, `BigInt`
  * or `BigDecimal` beyond 2^53^ is read imprecisely.
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
@implicitNotFound(
  "${A} has no Schema, so cannot be asked for or said as JSON. A case class, sealed trait or enum has one where each of its fields does, unless it has a circe Encoder of its own, and another type has one made by Schema.of or converted from one by imap or emap. Write `derives Schema` on a type to derive one anyway and be told which field has none; a type nested very deeply may need a higher -Xmax-inlines.",
)
final class Schema[A] private (
  val encoder: Encoder[A],
  val decoder: Decoder[A],
  private[iris] val name: TypeName,
  private[iris] val outline: (Schema[?] => Json) => Json,
  within: => List[Schema[?]] = Nil,
  private[iris] val optional: Boolean = false,
  private[iris] val definable: Boolean = false,
  private[iris] val fieldless: Boolean = false,
  private[iris] val description: Option[String] = None,
  private[iris] val note: Option[String] = None,
  arrives: => Arrival = Arrival.Whole,
):

  /**
    * The schemas this one is made of, read when first needed, so that a
    * recursive type can be made before the fields that refer back to it.
    * `outline` writes this schema's own JSON, given how to write each of them.
    */
  private[iris] lazy val parts: List[Schema[?]] = within

  /** How a value is read while its JSON is still arriving, read when needed. */
  private[iris] lazy val arrival: Arrival = arrives

  /**
    * Whether a value can be read before its JSON is whole, as one holding a
    * stream may be, other than within a list or map.
    */
  private[iris] lazy val gradual: Boolean = Schema.unfolding(this, Set.empty)

  /** The JSON Schema of this type's values. */
  lazy val json: Json = Schema.rendered(this)

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
    *   A schema converted from this one.
    */
  inline def imap[B](to: A => B)(from: B => A): Schema[B] =
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
    *   A schema converted from this one.
    */
  inline def emap[B](to: A => Either[String, B])(from: B => A): Schema[B] =
    converted(
      to,
      from,
      TypeName.of[B],
      Schema.documented[B].flatMap(_.description),
    )

  /**
    * Describes this type's values in words, as of bounds an [[emap]] keeps them
    * within, beside whatever else describes them: the type's Scaladoc, or the
    * `@param` of a field of the type.
    *
    * @param text
    *   The words.
    *
    * @return
    *   This schema, described.
    */
  def described(text: String): Schema[A] = copied(
    encoder,
    decoder,
    noted = Some(note.fold(text)(Schema.joined(_, text))),
  )

  /**
    * A type converted from this one, as this one is but for its name and its
    * own Scaladoc, and still defined where it recurs through this one.
    */
  private def converted[B]
    (
      to: A => Either[String, B],
      from: B => A,
      named: TypeName,
      described: Option[String],
    )
    : Schema[B] = copied(
    encoder.contramap(from),
    decoder.emap(to),
    named,
    described.orElse(description),
  )

  private def copied[B]
    (
      encoder: Encoder[B],
      decoder: Decoder[B],
      named: TypeName = name,
      described: Option[String] = description,
      noted: Option[String] = note,
    )
    : Schema[B] = new Schema(
    encoder,
    decoder,
    named,
    outline,
    parts,
    optional,
    definable,
    fieldless,
    described,
    noted,
    arrival,
  )

object Schema extends DerivedSchemas:

  /**
    * Summons the schema of `A`.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   The schema of `A`.
    */
  def apply[A](using schema: Schema[A]): Schema[A] = schema

  /**
    * Creates a schema of a codec and a JSON Schema of one's own, which the
    * codec must keep to.
    *
    * @tparam A
    *   The type.
    *
    * @param json
    *   The JSON Schema of the type's values.
    *
    * @return
    *   A schema writing and reading through circe's codec.
    */
  inline def of[A : {Encoder, Decoder}](json: Json): Schema[A] =
    fixed(json, TypeName.of[A])

  given Schema[String]     = simple("string", "String")
  given Schema[Boolean]    = simple("boolean", "Boolean")
  given Schema[Int]        = simple("integer", "Int")
  given Schema[Long]       = simple("integer", "Long")
  given Schema[BigInt]     = simple("integer", "BigInt")
  given Schema[Double]     = simple("number", "Double")
  given Schema[Float]      = simple("number", "Float")
  given Schema[BigDecimal] = simple("number", "BigDecimal")

  /** An optional value, which as a field may also be left out. */
  given [A : Schema as A]: Schema[Option[A]] = new Schema(
    Encoder.encodeOption(using A.encoder),
    Decoder.decodeOption(using A.decoder),
    applied("Option", A),
    describe =>
      Json.obj(
        "anyOf" -> Json.arr(
          describe(A),
          Json.obj("type" -> "null".asJson),
        ),
      ),
    List(A),
    optional = true,
    arrives = A.arrival,
  )

  given [A : Schema as A]: Schema[List[A]] = listed(
    Encoder.encodeList(using A.encoder),
    Decoder.decodeList(using A.decoder),
    applied("List", A),
    A,
  )

  given [A : Schema as A]: Schema[Vector[A]] = listed(
    Encoder.encodeVector(using A.encoder),
    Decoder.decodeVector(using A.decoder),
    applied("Vector", A),
    A,
  )

  given [A : Schema as A]: Schema[Seq[A]] = listed(
    Encoder.encodeSeq(using A.encoder),
    Decoder.decodeSeq(using A.decoder),
    applied("Seq", A),
    A,
  )

  given [A : Schema as A]: Schema[Set[A]] = listed(
    Encoder.encodeSet(using A.encoder),
    Decoder.decodeSet(using A.decoder),
    applied("Set", A),
    A,
    unique = true,
  )

  /**
    * A stream of values, as an array of them. [[LlmClient.askStreamed]] hands
    * it over as soon as its array begins, each value then read as soon as the
    * model has written it; a stream read from a reply already whole holds its
    * values from the start. A stream cannot be said as JSON, as it would have
    * to be run: say a list of its values instead.
    *
    * @tparam F
    *   The effect type, which [[LlmClient.askStreamed]] must share.
    *
    * @tparam A
    *   The type of the values.
    *
    * @return
    *   The schema of the stream.
    */
  given [F[_], A : Schema as A]: Schema[Stream[F, A]] = new Schema(
    Encoder.instance(_ =>
      throw UnsupportedOperationException(
        "A Stream cannot be said as JSON, as it would have to be run: say a list of its values instead.",
      ),
    ),
    Unfinished.filled(
      Decoder.decodeList(using A.decoder).map[Stream[F, A]](Stream.emits),
    ),
    TypeName("fs2.Stream", "Stream").applied(List(A.name)),
    arrayOf(A),
    List(A),
    arrives = Arrival.Streamed(A),
  )

  given [A : Schema as A]: Schema[Map[String, A]] = new Schema(
    Encoder.encodeMap[String, A](using summon, A.encoder),
    Decoder.decodeMap[String, A](using summon, A.decoder),
    applied("Map", Schema[String], A),
    describe =>
      Json.obj(
        "type"                 -> "object".asJson,
        "additionalProperties" -> describe(A),
      ),
    List(A),
  )

  /** Either of two values, held under `Left` or `Right`. */
  given [L : Schema as L, R : Schema as R]: Schema[Either[L, R]] = union(
    applied("Either", L, R),
    None,
    List("Left", "Right"),
    List(
      wrapped[L, Left[L, R]](L, "Left", Left(_), _.value),
      wrapped[R, Right[L, R]](R, "Right", Right(_), _.value),
    ),
    either => if either.isLeft then 0 else 1,
    definable = false,
  )

  /**
    * A tuple, as an array of its elements.
    *
    * @tparam T
    *   The type of the tuple.
    *
    * @return
    *   The schema of the tuple.
    */
  transparent inline given [T <: Tuple]
    (using mirror: Mirror.ProductOf[T])
    : Schema[T] = tuple(
    TypeName.of[T],
    instances(summonAll[Tuple.Map[mirror.MirroredElemTypes, Schema]]),
    mirror.fromProduct,
  )

  /**
    * Derives the schema of a case class, sealed trait or enum, as a schema is
    * derived wherever it is needed, but once, for `derives Schema`, which names
    * any field whose type has none.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   The schema of `A`.
    */
  transparent inline def derived[A](using mirror: Mirror.Of[A]): Schema[A] =
    lazy val self: Schema[A] =
      // Where the type recurs, its fields find this given rather than derive it again.
      @unused
      given Schema[A] = self
      inline mirror match
        case sum: Mirror.SumOf[A] => union(
            TypeName.of[A],
            documented[A].flatMap(_.description),
            labels[sum.MirroredElemLabels],
            instances(summonAll[Tuple.Map[sum.MirroredElemTypes, Schema]]),
            sum.ordinal,
            definable = true,
          )
        case product: Mirror.ProductOf[A] => record(
            TypeName.of[A],
            documented[A],
            labels[product.MirroredElemLabels],
            instances(summonAll[Tuple.Map[product.MirroredElemTypes, Schema]]),
            Defaults.of[A],
            product.fromProduct,
          )
    self

  /**
    * The schema of a literal type, which reads its value alone and writes it as
    * it is written.
    */
  private[iris] def literal[A]
    (name: TypeName, value: A, written: Json)
    : Schema[A] = new Schema(
    Encoder.instance(_ => written),
    Decoder.instance(cursor =>
      Either.cond(
        cursor.value == written,
        value,
        DecodingFailure(
          Reason.CustomReason(s"Expected ${ written.noSpaces }"),
          cursor,
        ),
      ),
    ),
    name,
    _ => Json.obj("const" -> written),
  )

  /**
    * The schema of a union of literal types: any one of their values, a value
    * written as the first whose test it passes, and read as the first that
    * reads it, which no other could.
    */
  private[iris] def anyOf[A]
    (
      name: TypeName,
      schemas: List[Schema[?]],
      tests: List[Any => Boolean],
    )
    : Schema[A] =
    val alternatives = schemas.map(_.asInstanceOf[Schema[Any]]).zip(tests)
    new Schema(
      Encoder.instance(value =>
        alternatives
          .find((_, test) => test(value))
          .fold(Json.Null)((schema, _) => schema.encoder(value)),
      ),
      Decoder.instance(cursor =>
        alternatives
          .view
          .map((schema, _) => schema.decoder(cursor))
          .collectFirst { case Right(value) => value.asInstanceOf[A] }
          .toRight(DecodingFailure(
            Reason.CustomReason(s"Expected one of ${ alternatives
                .map(_._1.name.short)
                .mkString(", ") }"),
            cursor,
          )),
      ),
      name,
      describe =>
        Json.obj("anyOf" -> alternatives.map(_._1).map(describe).asJson),
      alternatives.map(_._1),
    )

  /**
    * The schema of any other union: an object holding one of its alternatives
    * under its name, as a sealed trait holds a case, so that a value is read as
    * the alternative it was written as. A value is written as the first
    * alternative whose test it passes, and a literal named by its value.
    */
  private[iris] def tagged[A]
    (
      name: TypeName,
      schemas: List[Schema[?]],
      literals: List[Option[String]],
      tests: List[Any => Boolean],
    )
    : Schema[A] =
    val names = schemas
      .lazyZip(literals)
      .map((schema, literal) =>
        literal.fold(schema.name)(text => TypeName(text, text)),
      )
    val shown = showing(names)
    union(
      name,
      None,
      names.map(name => shown(name.full)),
      schemas,
      value => tests.indexWhere(_(value)),
      definable = false,
    )

  /**
    * A type's Scaladoc in plain words, read by Cadmus where the type is
    * compiled alongside, and never under `-Xdrop-docs`.
    */
  private inline def documented[A]: Option[Scaladoc] = Scaladoc
    .of[A]
    .map(_.plain)

  private inline def labels[Labels <: Tuple]: List[String] =
    constValueTuple[Labels].toList.asInstanceOf[List[String]]

  /**
    * The schemas `summonAll` gave. Each is summoned there, in the transparent
    * expansion, so that one missing makes a schema derived wherever it is
    * needed not given, rather than not compile, and all at once, by the
    * compiler itself, as each inline expansion nested within another counts
    * towards its limit, which a type nested deeply reaches.
    */
  private def instances(summoned: Tuple): List[Schema[?]] = summoned
    .toList
    .asInstanceOf[List[Schema[?]]]

  private def fixed[A : {Encoder, Decoder}]
    (json: Json, name: TypeName)
    : Schema[A] = new Schema(Encoder[A], Decoder[A], name, _ => json)

  private def simple[A : {Encoder, Decoder}]
    (kind: String, name: String)
    : Schema[A] = new Schema(
    Encoder[A],
    expecting(kind, name, Decoder[A]),
    TypeName.standard(name),
    _ => Json.obj("type" -> kind.asJson),
  )

  /** A decoder saying what it expected, where circe's names only the type. */
  private def expecting[A]
    (
      kind: String,
      name: String,
      decoder: Decoder[A],
    )
    : Decoder[A] = Decoder.instance(cursor =>
    decoder(cursor).leftMap(_ => mismatched(kind, name, cursor)),
  )

  /**
    * Why a simple type could not be read: a number out of its range or not
    * whole, or a value of another kind, as circe names only the type.
    */
  private def mismatched
    (
      kind: String,
      name: String,
      cursor: HCursor,
    )
    : DecodingFailure = cursor.value.asNumber match
    case Some(number) if kind == "integer" && number.toBigInt.isEmpty =>
      DecodingFailure(
        Reason.CustomReason("Expected a whole number"),
        cursor,
      )
    case Some(_) if kind == "integer" || kind == "number" =>
      val noun = if kind == "integer" then "an integer" else "a number"
      DecodingFailure(
        Reason.CustomReason(s"Expected $noun that fits in $name"),
        cursor,
      )
    case _ => expected(kind, cursor)

  /** The name of a standard type constructor applied to types with schemas. */
  private def applied(name: String, arguments: Schema[?]*): TypeName = TypeName
    .standard(name)
    .applied(arguments.map(_.name).toList)

  private def listed[C]
    (
      encoder: Encoder[C],
      decoder: Decoder[C],
      name: TypeName,
      item: Schema[?],
      unique: Boolean = false,
    )
    : Schema[C] = new Schema(
    encoder,
    decoder,
    name,
    arrayOf(item, unique),
    List(item),
  )

  /** An array of items, each as the item's schema describes it. */
  private def arrayOf
    (item: Schema[?], unique: Boolean = false)
    (describe: Schema[?] => Json)
    : Json = JsonHttp.objectOf(
    "type"        -> "array".asJson,
    "items"       -> describe(item),
    "uniqueItems" -> Option.when(unique)(true).asJson,
  )

  /** A case holding a value of another type, written as that value alone. */
  private def wrapped[A, B]
    (
      inner: Schema[A],
      name: String,
      to: A => B,
      from: B => A,
    )
    : Schema[B] = new Schema(
    inner.encoder.contramap(from),
    inner.decoder.map(to),
    applied(name, inner),
    describe => describe(inner),
    List(inner),
    arrives = inner.arrival,
  )

  private def tuple[T]
    (
      name: TypeName,
      elements: List[Schema[?]],
      build: Product => T,
    )
    : Schema[T] =
    val schemas = elements.map(_.asInstanceOf[Schema[Any]])
    new Schema(
      Encoder.instance(value =>
        Json.fromValues(
          schemas
            .zip(value.asInstanceOf[Product].productIterator)
            .map(_.encoder(_)),
        ),
      ),
      accumulating(cursor =>
        if !cursor.values.exists(_.size == schemas.size) then
          DecodingFailure(
            Reason.CustomReason(s"Expected an array of ${ schemas.size }"),
            cursor,
          ).invalidNel
        else
          schemas
            .zipWithIndex
            .traverse((schema, index) =>
              schema.decoder.tryDecodeAccumulating(cursor.downN(index)),
            )
            .map(values => build(Tuple.fromArray(values.toArray))),
      ),
      name,
      describe =>
        Json.obj(
          "type"        -> "array".asJson,
          "prefixItems" -> schemas.map(describe).asJson,
          "items"       -> false.asJson,
          "minItems"    -> schemas.size.asJson,
        ),
      schemas,
      arrives = Arrival.Assembled(
        {
          case index: Int => schemas.lift(index)
          case _          => None
        },
        awaited(
          schemas
            .zipWithIndex
            .map((schema, index) => (index, schema, !schema.optional)),
        ),
      ),
    )

  /**
    * The schema of a case class, its fields summoned only when first needed, so
    * that a recursive type can be made before the fields that refer back to it.
    */
  private def record[A]
    (
      name: TypeName,
      doc: Option[Scaladoc],
      labels: List[String],
      schemas: => List[Schema[?]],
      defaults: List[Option[() => Any]],
      build: Product => A,
    )
    : Schema[A] =
    lazy val fields = labels
      .lazyZip(schemas.map(_.asInstanceOf[Schema[Any]]))
      .lazyZip(defaults.padTo(labels.size, None))
      .map((label, schema, default) =>
        Field(
          label,
          schema,
          doc.flatMap(_.params.get(label)),
          default,
        ),
      )
    new Schema(
      Encoder
        .AsObject
        .instance(value =>
          JsonObject.fromIterable(
            fields
              .zip(value.asInstanceOf[Product].productIterator)
              .flatMap(_.written(_)),
          ),
        ),
      accumulating(cursor =>
        if !cursor.value.isObject then expected("object", cursor).invalidNel
        else
          fields
            .traverse(_.read(cursor))
            .map(values => build(Tuple.fromArray(values.toArray))),
      ),
      name,
      describe =>
        closed(
          fields.map(field => field.label -> field.described(describe)),
          fields.filter(_.required).map(_.label),
        ),
      fields.map(_.schema),
      definable = true,
      fieldless = labels.isEmpty,
      description = doc.flatMap(_.description),
      arrives = Arrival.Assembled(
        {
          case label: String => fields.find(_.label == label).map(_.schema)
          case _             => None
        },
        awaited(
          fields.map(field => (field.label, field.schema, field.required)),
        ),
      ),
    )

  /**
    * The members still to come of a value read from its members, written in
    * order, where the rest are enough to make it up: each up to the last begun
    * has settled, or has not begun and may be left out, and each after it is a
    * stream that must be written, handed over before it begins.
    *
    * @param members
    *   Each member's key, schema, and whether it must be written.
    */
  private def awaited
    (members: List[(String | Int, Schema[?], Boolean)])
    (
      settled: Set[String | Int],
      begun: Set[String | Int],
    )
    : Option[List[String | Int]] =
    val (written, unwritten) =
      members.splitAt(members.lastIndexWhere((key, _, _) => begun(key)) + 1)
    Option.when(
      written.forall((key, _, required) =>
        settled(key) || !required && !begun(key),
      ) && unwritten.forall((_, schema, required) =>
        required && schema.arrival.streamed,
      ),
    )(unwritten.map(_._1))

  /**
    * A field of a case class.
    *
    * @param label
    *   The field's name.
    *
    * @param schema
    *   The field's schema.
    *
    * @param description
    *   The field's description, which comes before its type's.
    *
    * @param default
    *   The field's default, read where it is left out.
    */
  private final case class Field
    (
      label: String,
      schema: Schema[Any],
      description: Option[String],
      default: Option[() => Any],
    ):

    /** Whether the field must be given, being neither optional nor defaulted. */
    def required: Boolean = !schema.optional && default.isEmpty

    /** The field as written, left out where it is unset and so read back. */
    def written(value: Any): Option[(String, Json)] =
      val json  = schema.encoder(value)
      val unset = json.isNull && schema.optional &&
        default.forall(value => schema.encoder(value()).isNull)
      Option.unless(unset)(label -> json)

    /** The field read from its object, or its default where it is left out. */
    def read(cursor: HCursor): Decoder.AccumulatingResult[Any] =
      val field = cursor.downField(label)
      default
        .filter(_ => field.failed)
        .fold(schema.decoder.tryDecodeAccumulating(field))(_().validNel)

    /**
      * The field's schema, described in its own words and then its type's, so
      * that the model reads both. Its default is not shown, as one made anew
      * each time, as of the time, would make the schema differ each time, and
      * so never be cached.
      */
    def described(describe: Schema[?] => Json): Json =
      val json = describe(schema)
      description.fold(json)(text =>
        describedAs(
          json,
          Some(
            json
              .hcursor
              .get[String]("description")
              .fold(_ => text, joined(text, _)),
          ),
        ),
      )

  /**
    * The schema of a sealed trait or enum: the name of a case where no case has
    * fields, and otherwise an object holding the case under its name.
    */
  private def union[A]
    (
      name: TypeName,
      description: Option[String],
      labels: List[String],
      schemas: => List[Schema[?]],
      ordinal: A => Int,
      definable: Boolean,
    )
    : Schema[A] =
    lazy val cases = labels.zip(schemas.map(_.asInstanceOf[Schema[Any]]))
    lazy val bare  = cases.forall(_._2.fieldless)
    new Schema(
      Encoder.instance(value =>
        val (label, schema) = cases(ordinal(value))
        if bare then label.asJson else Json.obj(label -> schema.encoder(value)),
      ),
      accumulating(cursor =>
        if bare then
          cursor.as[String].toValidatedNel.andThen(enumerated(cases, cursor))
        else tagged(cases, cursor),
      ),
      name,
      describe => if bare then enumeration(cases) else tagging(cases, describe),
      if bare then Nil else cases.map(_._2),
      definable = definable,
      description = description,
      arrives =
        if bare then Arrival.Whole
        else
          Arrival.Assembled(
            {
              case label: String => cases.find(_._1 == label).map(_._2)
              case _             => None
            },
            (settled, _) =>
              Option.when(cases.exists((label, _) => settled(label)))(Nil),
          ),
    )

  /** The case a bare union names, read from an object of no fields. */
  private def enumerated[A]
    (
      cases: List[(String, Schema[Any])],
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
      cases: List[(String, Schema[Any])],
      cursor: HCursor,
    )
    : Decoder.AccumulatingResult[A] = cases
    .find((label, _) => cursor.keys.exists(_.exists(_ == label)))
    .fold(oneOf(cases, cursor).invalidNel)((label, schema) =>
      schema.decoder.tryDecodeAccumulating(cursor.downField(label)),
    )
    .map(_.asInstanceOf[A])

  /** The name of one of a bare union's cases, each as it is described. */
  private def enumeration(cases: List[(String, Schema[Any])]): Json =
    if cases.forall(_._2.description.isEmpty) then
      Json.obj(
        "type" -> "string".asJson,
        "enum" -> cases.map(_._1).asJson,
      )
    else
      Json.obj(
        "anyOf" ->
          cases
            .map((label, schema) =>
              describedAs(
                Json.obj("const" -> label.asJson),
                schema.description,
              ),
            )
            .asJson,
      )

  /** An object holding one of several cases under its name. */
  private def tagging
    (
      cases: List[(String, Schema[Any])],
      describe: Schema[?] => Json,
    )
    : Json = Json.obj(
    "anyOf" ->
      cases
        .map((label, schema) =>
          closed(
            List(label -> describe(schema)),
            List(label),
          ),
        )
        .asJson,
  )

  private def oneOf
    (
      cases: List[(String, Schema[Any])],
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

  /**
    * The JSON Schema of a type. Each type that recurs is described once in
    * `$defs`, and referred to by name wherever it appears. A type is told apart
    * by its full name, its type arguments applied, and shown by its short name
    * where no other recurring type shares it.
    */
  private def rendered(schema: Schema[?]): Json =
    val defined = recurring(schema, Set.empty).values.toList
    val shown   = showing(defined.map(_.name))
    val root    = referenced(schema, shown)
    if defined.isEmpty then root
    else
      root.deepMerge(Json.obj(
        "$defs" -> Json.obj(
          defined
            .map(recurs => shown(recurs.name.full) -> own(recurs, shown))
            .sortBy(_._1)*,
        ),
      ))

  /** A schema with a description first, in place of any of its own. */
  private def describedAs(json: Json, description: Option[String]): Json =
    description.fold(json)(text =>
      json.mapObject(("description" -> text.asJson) +: _.remove("description")),
    )

  /** The full name a derived type would be defined by, if it recurs. */
  private def definition(schema: Schema[?]): Option[String] =
    Option.when(schema.definable)(schema.name.full)

  /**
    * Whether a value can be read before its JSON is whole: it is a stream, or
    * is read from its members, one of which can be, a type met again within
    * itself having been looked into already.
    */
  private def unfolding(schema: Schema[?], within: Set[String]): Boolean =
    schema.arrival match
      case Arrival.Streamed(_)     => true
      case Arrival.Assembled(_, _) => !definition(schema).exists(within) &&
        schema.parts.exists(unfolding(_, within ++ definition(schema)))
      case Arrival.Whole => false

  /** The types, by full name, met again within themselves. */
  private def recurring
    (schema: Schema[?], within: Set[String])
    : Map[String, Schema[?]] = definition(schema).filter(within) match
    case Some(full) => Map(full -> schema)
    case None       => schema
        .parts
        .map(recurring(_, within ++ definition(schema)))
        .foldLeft(Map.empty)(_ ++ _)

  /**
    * The names recurring types are defined by, by full name: each short, but in
    * full where two would otherwise share one.
    */
  private def showing(names: List[TypeName]): Map[String, String] = names
    .groupBy(_.short)
    .values
    .flatMap(alike =>
      alike.map(name =>
        name.full -> (if alike.size == 1 then name.short else name.full),
      ),
    )
    .toMap

  /**
    * A type's schema where it appears: a reference to its definition where it
    * recurs, or else its own.
    */
  private def referenced
    (
      schema: Schema[?],
      shown: Map[String, String],
    )
    : Json = definition(schema)
    .flatMap(shown.get)
    .fold(own(schema, shown))(reference)

  /** A type's own schema, its parts described within it. */
  private def own
    (
      schema: Schema[?],
      shown: Map[String, String],
    )
    : Json = describedAs(
    schema.outline(referenced(_, shown)),
    (schema.description ++ schema.note).reduceOption(joined),
  )

  /** Two descriptions, as paragraphs of one. */
  private def joined(first: String, second: String): String =
    s"$first\n\n$second"

  private def reference(name: String): Json =
    Json.obj("$ref" -> s"#/$$defs/${ pointed(name) }".asJson)

  /**
    * A definition's name as a JSON Pointer token in a URI fragment: `~` and `/`
    * escaped as the pointer does, and anything else a fragment cannot hold
    * percent-encoded.
    */
  private def pointed(name: String): String = name
    .replace("~", "~0")
    .replace("/", "~1")
    .getBytes(StandardCharsets.UTF_8)
    .map(byte =>
      val char = (byte & 0xFF).toChar
      if byte >= 0 && fragment.contains(char) then char.toString
      else f"%%${ byte & 0xFF }%02X",
    )
    .mkString

  private val fragment: Set[Char] =
    (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "-._~!$&'()*+,;=:@/?")
      .toSet

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

/**
  * The schemas derived for a type that has none of its own, ranked below every
  * other, so that a type's own schema is always the one used.
  */
sealed trait DerivedSchemas:

  /**
    * Derives the schema of a case class, sealed trait or enum wherever one is
    * needed, where each of its fields has one, but never over a circe `Encoder`
    * of its own, which says how it is written already.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   The schema of `A`.
    */
  transparent inline given derivedSchema[A]
    (
      using Mirror.Of[A],
      NotGiven[Encoder[A]],
    )
    : Schema[A] = Schema.derived[A]

  /**
    * Makes the schema of a union, any one of its alternatives, each of which
    * has one, or of a literal type, its value alone.
    *
    * @tparam A
    *   The type.
    *
    * @return
    *   The schema of `A`.
    */
  transparent inline given alternativeSchema[A]: Schema[A] = Alternatives.of[A]
