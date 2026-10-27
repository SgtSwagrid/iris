package com.alecdorrington.iris

import io.circe.Encoder
import scala.util.NotGiven

/**
  * What may be said to the model: how a value is written as a part of a
  * message, wherever a [[Prompt]], [[Chat]] or [[Message]] takes one.
  *
  * A `String` is said as text, and a [[Part]] as itself. Any other value is
  * said as JSON: as its [[Structured]] type writes it, where it has one, so
  * that it is written as the schema the model may be told describes it, and
  * otherwise as its circe `Encoder` writes it.
  *
  * @tparam A
  *   The type of the value.
  */
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
    * [[text]].
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
    )
    : Content[A] = value => Part.Text(structured.encoder(value).noSpaces)

  /**
    * A value of a type with an encoder but no [[Structured]] type, said as
    * JSON.
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
    )
    : Content[A] = value => Part.Text(encoder(value).noSpaces)
