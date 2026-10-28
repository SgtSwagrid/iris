package com.alecdorrington.iris

import io.circe.Encoder
import scala.annotation.implicitNotFound
import scala.util.NotGiven

/**
  * What may be said to the model: how a value is written as a part of a
  * message, wherever a [[Chat]] or [[Message]] takes one.
  *
  * A `String` is said as text, and a [[Part]] as itself. A value of a type with
  * a [[Schema]] is said as JSON as that writes it, so that it is written as the
  * schema the model may be told describes it, and a value of another type with
  * a circe `Encoder` as that writes it. No other value can be said.
  *
  * @tparam A
  *   The type of the value.
  */
@implicitNotFound(
  "${A} cannot be said to the model: say a String, a Part, or a value with a Schema or a circe Encoder. Values of different kinds go in one message as parts, made with Part.of.",
)
trait Content[A]:

  /**
    * Writes a value as a part of a message.
    *
    * @param value
    *   The value.
    *
    * @return
    *   A part saying the value.
    */
  def part(value: A): Part

object Content:

  /** Text, said as it is. */
  given text: Content[String] = Part.Text(_)

  /**
    * A part, said as itself.
    *
    * @tparam P
    *   The kind of part.
    *
    * @return
    *   The content of a part.
    */
  given part[P <: Part]: Content[P] = identity(_)

  /**
    * A value with a schema, said as JSON, for every type but parts, which are
    * [[part]], and those text may be, which are [[text]] where `String` and
    * otherwise none, so that text inferred with other values into a union, as
    * `String | Double`, is never said as JSON, but asked for as parts.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The content of a value with a schema.
    */
  given schema[A]
    (
      using schema: Schema[A],
      notText: NotGiven[String <:< A],
      notPart: NotGiven[A <:< Part],
    )
    : Content[A] = json(schema.encoder)

  /**
    * A value of a type with an encoder but no [[Schema]], said as JSON, for
    * every type but parts, which are [[part]].
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The content of an encoded value.
    */
  given encoded[A]
    (
      using encoder: Encoder[A],
      noSchema: NotGiven[Schema[A]],
      notPart: NotGiven[A <:< Part],
    )
    : Content[A] = json(encoder)

  private def json[A](encoder: Encoder[A]): Content[A] =
    value => Part.Text(encoder(value).noSpaces)
