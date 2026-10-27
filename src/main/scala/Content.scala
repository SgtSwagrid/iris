package com.alecdorrington.iris

import io.circe.Encoder
import scala.annotation.implicitNotFound
import scala.util.NotGiven

/**
  * What may be said to the model: how a value is written as a part of a
  * message, wherever a [[Chat]] or [[Message]] takes one.
  *
  * A `String` is said as text, and a [[Part]] as itself. A value of a
  * [[Structured]] type is said as JSON as it writes it, so that it is written
  * as the schema the model may be told describes it, and a value of another
  * type with a circe `Encoder` as that writes it. No other value can be said.
  *
  * @tparam A
  *   The type of the value.
  */
@implicitNotFound(
  "${A} cannot be said to the model: say a String, a Part, or a value that is Structured or has a circe Encoder. Values of different kinds go in one message as parts, made with Part.of.",
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
    * A structured value, said as JSON, for every type but `String`, which is
    * [[text]], and parts, which are [[part]].
    *
    * @tparam A
    *   The structured type.
    *
    * @return
    *   The content of a structured value.
    */
  given structured[A]
    (
      using structured: Structured[A],
      notText: NotGiven[A =:= String],
      notPart: NotGiven[A <:< Part],
    )
    : Content[A] = json(structured.encoder)

  /**
    * A value of a type with an encoder but no [[Structured]] type, said as
    * JSON, for every type but parts, which are [[part]].
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
      notStructured: NotGiven[Structured[A]],
      notPart: NotGiven[A <:< Part],
    )
    : Content[A] = json(encoder)

  private def json[A](encoder: Encoder[A]): Content[A] =
    value => Part.Text(encoder(value).noSpaces)
