// Outside Iris's package, so that types derive as a user's would.
package com.alecdorrington.example

import com.alecdorrington.iris.{Part, Schema}
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

final case class Person
  (
    name: String,
    age: Int,
    nickname: Option[String],
  )
  derives Schema

enum Colour derives Schema:
  case Red, Green, Blue

sealed trait Figure derives Schema

object Figure:

  final case class Circle(radius: Double) extends Figure

  final case class Square(side: Double) extends Figure

  case object Dot extends Figure

final case class Outline(title: String, sections: List[Outline]) derives Schema

final case class Tally[A]
  (
    item: A,
    tags: Set[String],
    counts: Map[String, Long],
  )
  derives Schema

final case class Score(value: Int)

object Score:

  given Schema[Score] = Schema[Int].emap(value =>
    Either.cond(
      value >= 1 && value <= 10,
      Score(value),
      s"$value is not from 1 to 10",
    ),
  )(_.value)

/** Derived wherever it is needed, as is the address it holds. */
final case class Customer(name: String, address: Address)

final case class Address(city: String)

final case class Branch(label: String, children: List[Branch])

final case class Ping(pong: Option[Pong])

final case class Pong(pings: List[Ping])

sealed trait Sum

object Sum:

  final case class Number(value: Int) extends Sum

  final case class Plus(left: Sum, right: Sum) extends Sum

final case class Settings
  (
    name: String,
    level: Int = 3,
    tags: List[String] = Nil,
    note: Option[String] = None,
    title: Option[String] = Some("Untitled"),
  )

final case class Boxed[A](item: A, count: Int = 1)

/** A type with no schema, nor any way to derive one. */
final class Token(val value: String)

final case class Stamped(token: Token)

class SchemaSuite extends FunSuite:

  private def json(text: String): Json = parse(text).toOption.get

  private def read[A : Schema as A](text: String) = A
    .decoder
    .decodeJson(json(text))

  private def written[A : Schema as A](value: A): String = A
    .encoder(value)
    .noSpaces

  test("simple types have simple schemas"):
    assertEquals(
      Schema[String].json,
      json("""{"type":"string"}"""),
    )
    assertEquals(
      Schema[Int].json,
      json("""{"type":"integer"}"""),
    )
    assertEquals(
      Schema[Double].json,
      json("""{"type":"number"}"""),
    )
    assertEquals(
      Schema[Boolean].json,
      json("""{"type":"boolean"}"""),
    )

  test("a case class is an object requiring the fields not optional"):
    assertEquals(
      Schema[Person].json,
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
      Schema[Colour].json,
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
    val cases = Schema[Figure].json.hcursor.get[List[Json]]("anyOf")
    assertEquals(cases.map(_.size), Right(3))
    assertEquals(cases.map(_.head), Right(circle))
    assertEquals(cases.map(_.last), Right(dot))

  test("a recursive type refers to itself through its definitions"):
    assertEquals(
      Schema[Outline].json,
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
      Schema[Tally[Colour]].json,
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
    val failures = Schema[List[Person]]
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
    assertEquals(Schema[Score].json, Schema[Int].json)
    assertEquals(read[Score]("7"), Right(Score(7)))
    assertEquals(
      read[Score]("11").left.map(_.message),
      Left("11 is not from 1 to 10"),
    )

  test("a value is sent to the model as its json"):
    assertEquals(
      Part.of(Person("Ada", 36, None)),
      Part.Text("""{"name":"Ada","age":36}"""),
    )

  test("a type needs no derives clause, nor do the types of its fields"):
    assertEquals(
      Schema[Customer].json,
      json("""{"description":"Derived wherever it is needed, as is the address it holds.",
        "type":"object","properties":{
        "name":{"type":"string"},
        "address":{"type":"object","properties":{"city":{"type":"string"}},
          "required":["city"],"additionalProperties":false}},
        "required":["name","address"],"additionalProperties":false}"""),
    )
    val customer = Customer("Ada", Address("London"))
    assertEquals(
      read[Customer](written(customer)),
      Right(customer),
    )

  test("a type derived where it is needed may refer to itself"):
    assertEquals(
      Schema[Branch].json,
      json("""{"$ref":"#/$defs/Branch","$defs":{"Branch":{
        "type":"object","properties":{
          "label":{"type":"string"},
          "children":{"type":"array","items":{"$ref":"#/$defs/Branch"}}},
        "required":["label","children"],"additionalProperties":false}}}"""),
    )
    val branch = Branch("root", List(Branch("leaf", Nil)))
    assertEquals(
      read[Branch](written(branch)),
      Right(branch),
    )

  test("types derived where they are needed may refer to one another"):
    val ping = Ping(Some(Pong(List(Ping(None)))))
    assertEquals(read[Ping](written(ping)), Right(ping))
    assertEquals(
      Schema[Ping].json.hcursor.downField("$defs").keys.map(_.toSet),
      Some(Set("Ping")),
    )
    val sum = Sum.Plus(
      Sum.Number(1),
      Sum.Plus(Sum.Number(2), Sum.Number(3)),
    )
    assertEquals(
      read[Sum](written[Sum](sum)),
      Right(sum),
    )

  test("a field with a default may be left out, and is read as its default"):
    assertEquals(
      read[Settings]("""{"name":"Ada"}"""),
      Right(Settings("Ada")),
    )
    val schema = Schema[Settings].json.hcursor
    assertEquals(
      schema.get[List[String]]("required"),
      Right(List("name")),
    )
    assertEquals(
      schema.downField("properties").downField("level").focus,
      Some(json("""{"type":"integer","default":3}""")),
    )
    assertEquals(
      written(Settings("Ada")),
      """{"name":"Ada","level":3,"tags":[],"title":"Untitled"}""",
    )

  test("an optional field unset, though its default is set, is written null"):
    val untitled = Settings("Ada", title = None)
    assertEquals(
      written(untitled),
      """{"name":"Ada","level":3,"tags":[],"title":null}""",
    )
    assertEquals(
      read[Settings](written(untitled)),
      Right(untitled),
    )

  test("a generic type's defaults are read as its own"):
    assertEquals(
      read[Boxed[String]]("""{"item":"pen"}"""),
      Right(Boxed("pen")),
    )

  test("a tuple is an array of its elements"):
    assertEquals(
      Schema[(Int, String)].json,
      json(
        """{"type":"array","prefixItems":[{"type":"integer"},{"type":"string"}],
        "items":false,"minItems":2}""",
      ),
    )
    assertEquals(written((1, "one")), """[1,"one"]""")
    assertEquals(
      read[(Int, String)]("""[2,"two"]"""),
      Right((2, "two")),
    )
    assert(read[(Int, String)]("""[2]""").isLeft)

  test("an either holds its value under its side"):
    assertEquals(
      Schema[Either[String, Int]].json,
      json("""{"anyOf":[
        {"type":"object","properties":{"Left":{"type":"string"}},
          "required":["Left"],"additionalProperties":false},
        {"type":"object","properties":{"Right":{"type":"integer"}},
          "required":["Right"],"additionalProperties":false}]}"""),
    )
    assertEquals(
      written[Either[String, Int]](Left("no")),
      """{"Left":"no"}""",
    )
    assertEquals(
      read[Either[String, Int]]("""{"Right":2}"""),
      Right(Right(2)),
    )

  test("a union is any one of its alternatives"):
    assertEquals(
      Schema[Int | String].json,
      json("""{"anyOf":[{"type":"integer"},{"type":"string"}]}"""),
    )
    assertEquals(written[Int | String](1), "1")
    assertEquals(written[Int | String]("one"), "\"one\"")
    assertEquals(
      read[Int | String]("\"two\""),
      Right("two"),
    )
    assertEquals(
      read[Int | String]("true").left.map(_.message),
      Left("Expected one of Int, String"),
    )

  test("a literal type is its value alone"):
    assertEquals(
      Schema["low" | "high"].json,
      json("""{"anyOf":[{"const":"low"},{"const":"high"}]}"""),
    )
    assertEquals(
      read["low" | "high"]("\"high\"").map(identity[String]),
      Right("high"),
    )
    assert(read["low" | "high"]("\"medium\"").isLeft)

  test("a union whose alternatives cannot be told apart has no schema"):
    assert(compileErrors("Schema[List[Int] | List[String]]").nonEmpty)

  test("a type with a field of no schema has none, and derives names it"):
    assert(compileErrors("Schema[Stamped]").contains("Stamped has no Schema"))
    val errors =
      compileErrors("final case class Unsayable(token: Token) derives Schema")
    assert(
      errors.contains("Token has no Schema"),
      errors,
    )
