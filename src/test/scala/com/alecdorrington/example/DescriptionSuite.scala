// Outside Iris's package, so that types derive as a user's would.
package com.alecdorrington.example

import com.alecdorrington.iris.Schema
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/**
  * A review of a piece of writing.
  *
  * {{{
  * Review("Clear.", Mood.Pleased, Verdict.Accept)
  * }}}
  *
  * Kept short.
  *
  * @param summary
  *   The review in a sentence, as the [[Mood]] allows and
  *   [[Verdict.Revise any changes]] need.
  *
  * @param mood
  *   How the reviewer felt.
  */
final case class Review
  (
    summary: String,
    mood: Mood,
    verdict: Verdict,
  )
  derives Schema

/** How a reader may feel. */
enum Mood derives Schema:

  /** Glad to have read it. */
  case Pleased

  case Indifferent

/** What should become of a piece. */
sealed trait Verdict derives Schema

object Verdict:

  /** Published as it is. */
  case object Accept extends Verdict

  /**
    * Sent back for changes.
    *
    * @param changes
    *   The changes asked for.
    */
  final case class Revise(changes: List[String]) extends Verdict

/**
  * A section of a document.
  *
  * @param parts
  *   The sections within it.
  */
final case class Section(heading: String, parts: List[Section]) derives Schema

/** A rating out of five. */
final case class Rating(stars: Int)

object Rating:

  given Schema[Rating] = Schema[Int].imap(Rating(_))(_.stars)

/** A grade. */
final case class Grade(value: Int)

object Grade:

  given Schema[Grade] = Schema[Int]
    .imap(Grade(_))(_.value)
    .described("A whole number from 1 to 10.")

/**
  * A marked essay.
  *
  * @param grade
  *   Out of ten, where five is passable.
  */
final case class Marked(grade: Grade) derives Schema

class DescriptionSuite extends FunSuite:

  private def json(text: String): Json = parse(text).toOption.get

  private val accept = json("""{"type":"object","properties":{"Accept":{
    "description":"Published as it is.",
    "type":"object","properties":{},"required":[],
    "additionalProperties":false}},
    "required":["Accept"],"additionalProperties":false}""")

  private val revise = json("""{"type":"object","properties":{"Revise":{
    "description":"Sent back for changes.",
    "type":"object","properties":{"changes":{
      "description":"The changes asked for.",
      "type":"array","items":{"type":"string"}}},
    "required":["changes"],"additionalProperties":false}},
    "required":["Revise"],"additionalProperties":false}""")

  test("a case class is described by its scaladoc, and its fields by tags"):
    val schema = Schema[Review].json.hcursor
    assertEquals(
      schema.get[String]("description"),
      Right("A review of a piece of writing.\n\nKept short."),
    )
    assertEquals(
      schema.downField("properties").downField("summary").focus,
      Some(json("""{
        "description":"The review in a sentence, as the Mood allows and any changes need.",
        "type":"string"}""")),
    )

  test("a field is described in its own words, then its type's"):
    assertEquals(
      Schema[Review]
        .json
        .hcursor
        .downField("properties")
        .downField("mood")
        .get[String]("description"),
      Right("How the reviewer felt.\n\nHow a reader may feel."),
    )

  test("a field of no description of its own keeps its type's"):
    assertEquals(
      Schema[Review]
        .json
        .hcursor
        .downField("properties")
        .downField("verdict")
        .focus,
      Some(Schema[Verdict].json),
    )

  test("an enum's cases are each described where documented"):
    assertEquals(
      Schema[Mood].json,
      json("""{"description":"How a reader may feel.","anyOf":[
        {"description":"Glad to have read it.","const":"Pleased"},
        {"const":"Indifferent"}]}"""),
    )

  test("a sealed trait's cases are described as types of their own"):
    assertEquals(
      Schema[Verdict].json,
      Json.obj(
        "description" -> Json.fromString("What should become of a piece."),
        "anyOf"       -> Json.arr(accept, revise),
      ),
    )

  test("a recursive type is described in its definition"):
    assertEquals(
      Schema[Section].json,
      json("""{"$ref":"#/$defs/Section","$defs":{"Section":{
        "description":"A section of a document.",
        "type":"object","properties":{
          "heading":{"type":"string"},
          "parts":{"description":"The sections within it.",
            "type":"array","items":{"$ref":"#/$defs/Section"}}},
        "required":["heading","parts"],"additionalProperties":false}}}"""),
    )

  test("a converted type is described by its own scaladoc"):
    assertEquals(
      Schema[Rating].json,
      json("""{"description":"A rating out of five.","type":"integer"}"""),
    )

  test("descriptions say nothing of how a value is written"):
    assertEquals(
      Schema[Review]
        .encoder(Review("Clear.", Mood.Pleased, Verdict.Accept))
        .noSpaces,
      """{"summary":"Clear.","mood":"Pleased","verdict":{"Accept":{}}}""",
    )

  test("words a type is described in stay beside its scaladoc and a field's"):
    assertEquals(
      Schema[Grade].json.hcursor.get[String]("description"),
      Right("A grade.\n\nA whole number from 1 to 10."),
    )
    assertEquals(
      Schema[Marked]
        .json
        .hcursor
        .downField("properties")
        .downField("grade")
        .get[String]("description"),
      Right(
        "Out of ten, where five is passable.\n\nA grade.\n\nA whole number from 1 to 10.",
      ),
    )
