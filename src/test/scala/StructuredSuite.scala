// Outside Iris's package, so that types derive as a user's would.
package com.alecdorrington.example

import com.alecdorrington.iris.{Part, Structured}
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

final case class Person
  (
    name: String,
    age: Int,
    nickname: Option[String],
  )
  derives Structured

enum Colour derives Structured:
  case Red, Green, Blue

sealed trait Figure derives Structured

object Figure:

  final case class Circle(radius: Double) extends Figure

  final case class Square(side: Double) extends Figure

  case object Dot extends Figure

final case class Outline(title: String, sections: List[Outline])
  derives Structured

final case class Tally[A]
  (
    item: A,
    tags: Set[String],
    counts: Map[String, Long],
  )
  derives Structured

final case class Score(value: Int)

object Score:

  given Structured[Score] = Structured[Int].emap(value =>
    Either.cond(
      value >= 1 && value <= 10,
      Score(value),
      s"$value is not from 1 to 10",
    ),
  )(_.value)

class StructuredSuite extends FunSuite:

  private def json(text: String): Json = parse(text).toOption.get

  private def read[A : Structured as structured](text: String) = structured
    .decoder
    .decodeJson(json(text))

  private def written[A : Structured as structured](value: A): String =
    structured.encoder(value).noSpaces

  test("simple types have simple schemas"):
    assertEquals(
      Structured[String].schema,
      json("""{"type":"string"}"""),
    )
    assertEquals(
      Structured[Int].schema,
      json("""{"type":"integer"}"""),
    )
    assertEquals(
      Structured[Double].schema,
      json("""{"type":"number"}"""),
    )
    assertEquals(
      Structured[Boolean].schema,
      json("""{"type":"boolean"}"""),
    )

  test("a case class is an object requiring the fields not optional"):
    assertEquals(
      Structured[Person].schema,
      json("""{"type":"object","properties":{
        "name":{"type":"string"},
        "age":{"type":"integer"},
        "nickname":{"anyOf":[{"type":"string"},{"type":"null"}]}},
        "required":["name","age"],"additionalProperties":false}"""),
    )

  test("an unset optional field is left out, and read back left out or null"):
    val person = Person("Ada", 36, None)
    assertEquals(
      written(person),
      """{"name":"Ada","age":36}""",
    )
    assertEquals(
      read[Person]("""{"name":"Ada","age":36}"""),
      Right(person),
    )
    assertEquals(
      read[Person]("""{"name":"Ada","age":36,"nickname":null}"""),
      Right(person),
    )
    assertEquals(
      written(person.copy(nickname = Some("Countess"))),
      """{"name":"Ada","age":36,"nickname":"Countess"}""",
    )

  test("a case class is read only from an object"):
    assert(read[Person]("""[]""").isLeft)

  test("an enum of bare cases is the name of one"):
    assertEquals(
      Structured[Colour].schema,
      json("""{"type":"string","enum":["Red","Green","Blue"]}"""),
    )
    assertEquals(written(Colour.Green), "\"Green\"")
    assertEquals(
      read[Colour]("\"Blue\""),
      Right(Colour.Blue),
    )

  test("a case of no such name is refused, naming those there are"):
    val failure = read[Colour]("\"Purple\"").left.map(_.message)
    assertEquals(
      failure,
      Left("Expected one of Red, Green, Blue"),
    )

  test("a sealed trait holds one case under its name"):
    assertEquals(
      written[Figure](Figure.Circle(1.5)),
      """{"Circle":{"radius":1.5}}""",
    )
    assertEquals(
      written[Figure](Figure.Dot),
      """{"Dot":{}}""",
    )
    assertEquals(
      read[Figure]("""{"Square":{"side":2}}"""),
      Right(Figure.Square(2)),
    )
    assertEquals(
      read[Figure]("""{"Dot":{}}"""),
      Right(Figure.Dot),
    )
    assert(read[Figure]("""{"Triangle":{}}""").isLeft)

  test("a sealed trait's schema is any one of its cases"):
    val circle = json("""{"type":"object","properties":{"Circle":{
      "type":"object","properties":{"radius":{"type":"number"}},
      "required":["radius"],"additionalProperties":false}},
      "required":["Circle"],"additionalProperties":false}""")
    val dot = json("""{"type":"object","properties":{"Dot":{
      "type":"object","properties":{},"required":[],
      "additionalProperties":false}},
      "required":["Dot"],"additionalProperties":false}""")
    val cases = Structured[Figure].schema.hcursor.get[List[Json]]("anyOf")
    assertEquals(cases.map(_.size), Right(3))
    assertEquals(cases.map(_.head), Right(circle))
    assertEquals(cases.map(_.last), Right(dot))

  test("a recursive type refers to itself through its definitions"):
    assertEquals(
      Structured[Outline].schema,
      json("""{"$ref":"#/$defs/Outline","$defs":{"Outline":{
        "type":"object","properties":{
          "title":{"type":"string"},
          "sections":{"type":"array","items":{"$ref":"#/$defs/Outline"}}},
        "required":["title","sections"],"additionalProperties":false}}}"""),
    )
    val outline = Outline(
      "Iris",
      List(Outline("Usage", List.empty)),
    )
    assertEquals(
      read[Outline](written(outline)),
      Right(outline),
    )

  test("collections and generic types have schemas of their items"):
    assertEquals(
      Structured[Tally[Colour]].schema,
      json("""{"type":"object","properties":{
        "item":{"type":"string","enum":["Red","Green","Blue"]},
        "tags":{"type":"array","items":{"type":"string"},"uniqueItems":true},
        "counts":{"type":"object","additionalProperties":{"type":"integer"}}},
        "required":["item","tags","counts"],"additionalProperties":false}"""),
    )
    val tally = Tally(
      Colour.Red,
      Set("warm"),
      Map("seen" -> 3L),
    )
    assertEquals(
      read[Tally[Colour]](written(tally)),
      Right(tally),
    )

  test("every failure to read is reported at once"):
    val failures = Structured[List[Person]]
      .decoder
      .decodeAccumulating(
        json("""[{"name":1},{"name":"Ada","age":"old"}]""").hcursor,
      )
      .toEither
      .left
      .map(_.toList.map(_.pathToRootString))
    assertEquals(
      failures,
      Left(List(
        Some("[0].name"),
        Some("[0].age"),
        Some("[1].age"),
      )),
    )

  test("a converted type keeps the schema, and refuses what it is told to"):
    assertEquals(
      Structured[Score].schema,
      Structured[Int].schema,
    )
    assertEquals(read[Score]("7"), Right(Score(7)))
    assertEquals(
      read[Score]("11").left.map(_.message),
      Left("11 is not from 1 to 10"),
    )

  test("a value is sent to the model as its json"):
    assertEquals(
      Part.json(Person("Ada", 36, None)),
      Part.Text("""{"name":"Ada","age":36}"""),
    )
