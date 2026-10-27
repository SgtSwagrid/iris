// Outside Iris's package, so that types derive as a user's would.
package com.alecdorrington.example

import com.alecdorrington.iris.Structured
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

object github:

  final case class User(login: String) derives Structured

  final case class Account(login: String, followers: List[Account])
    derives Structured

final case class User
  (
    name: String,
    account: github.User,
    follows: List[User],
  )
  derives Structured

final case class Account
  (
    name: String,
    github: com.alecdorrington.example.github.Account,
    friends: List[Account],
  )
  derives Structured

final case class Tree[A](value: A, children: List[Tree[A]]) derives Structured

final case class Forest(ints: Tree[Int], names: Tree[String]) derives Structured

enum Weather derives Structured:

  case Sunny
  case Rainy(millimetres: Double)

sealed trait Event derives Structured

object Event:

  final case class Noted
    (
      text: Option[String],
      tags: Map[String, Int],
    )
    extends Event

class NamingSuite extends FunSuite:

  private def json(text: String): Json = parse(text).toOption.get

  private def definitions[A : Structured as A]: Set[String] = A
    .schema
    .hcursor
    .downField("$defs")
    .keys
    .fold(Set.empty)(_.toSet)

  test("a type is not taken for another of its name"):
    val schema = Structured[User].schema
    assertEquals(definitions[User], Set("User"))
    assertEquals(
      schema
        .hcursor
        .downField("$defs")
        .downField("User")
        .downField("properties")
        .downField("account")
        .focus,
      Some(json("""{"type":"object","properties":{"login":{"type":"string"}},
        "required":["login"],"additionalProperties":false}""")),
    )

  test("recurring types of one name are defined by their full names"):
    assertEquals(
      definitions[Account],
      Set(
        "com.alecdorrington.example.Account",
        "com.alecdorrington.example.github.Account",
      ),
    )

  test("each instance of a generic type is defined apart"):
    val defined = Structured[Forest].schema.hcursor.downField("$defs")
    assertEquals(
      definitions[Forest],
      Set("Tree(Int)", "Tree(String)"),
    )
    assertEquals(
      defined
        .downField("Tree(Int)")
        .downField("properties")
        .downField("value")
        .focus,
      Some(json("""{"type":"integer"}""")),
    )
    assertEquals(
      defined
        .downField("Tree(String)")
        .downField("properties")
        .downField("children")
        .downField("items")
        .focus,
      Some(json("""{"$ref":"#/$defs/Tree(String)"}""")),
    )

  test("an enum of bare and parameterised cases holds each under its name"):
    val weather = Structured[Weather]
    assertEquals(
      weather.encoder(Weather.Sunny).noSpaces,
      """{"Sunny":{}}""",
    )
    assertEquals(
      weather.decoder.decodeJson(json("""{"Rainy":{"millimetres":2.5}}""")),
      Right(Weather.Rainy(2.5)),
    )

  test("a tagged case reads its optional fields and maps as its schema says"):
    val events = Structured[Event]
    assertEquals(
      events.decoder.decodeJson(json("""{"Noted":{"tags":{"a":1}}}""")),
      Right(Event.Noted(None, Map("a" -> 1))),
    )
    assertEquals(
      events.encoder(Event.Noted(Some("Hi"), Map.empty)).noSpaces,
      """{"Noted":{"text":"Hi","tags":{}}}""",
    )

  test("a type may be described in so many words"):
    assertEquals(
      Structured[Int].described("From 1 to 10.").schema,
      json("""{"description":"From 1 to 10.","type":"integer"}"""),
    )
