// Outside Iris's package, so that content resolves as it would for a user.
package com.alecdorrington.example

import com.alecdorrington.iris.{
  Chat, Content, MediaType, Message, Part, Role, Schema,
}
import io.circe.{Encoder, Json}
import munit.FunSuite

/** A type with an encoder, but no schema, as it has no mirror to derive one. */
final class Note(val text: String)

/** A type with neither, so that it cannot be said. */
final class Mute

/** A picture of one's own, said by a content of its own. */
final case class Scan(base64: String) derives Encoder.AsObject

object Scan:

  given Content[Scan] = scan => Part.Media(MediaType.Png, scan.base64)

object Note:

  given Encoder[Note] =
    Encoder.instance(note => Json.obj("text" -> Json.fromString(note.text)))

/** A type with both, which write it differently. */
enum Shade derives Schema, Encoder.AsObject:
  case Light, Dark

/** A type with an encoder of its own, written as an array. */
final case class Point(x: Int, y: Int)

object Point:

  given Encoder[Point] = Encoder.instance(point =>
    Json.arr(
      Json.fromInt(point.x),
      Json.fromInt(point.y),
    ),
  )

/** A type with an encoder of its own, whose default is private. */
final case class Secret private (value: Int = 1)

object Secret:

  def of(value: Int): Secret = new Secret(value)

  val unset: Secret = new Secret()

  given Encoder[Secret] = Encoder.instance(secret => Json.fromInt(secret.value))

class ContentSuite extends FunSuite:

  private val ada = Person("Ada", 36, None)

  private val adaJson = Part.Text("""{"name":"Ada","age":36}""")

  private def user(content: Part*): Chat =
    Chat(List(Message(Role.User, content.toList)))

  test("text is said as it is, never as json"):
    assertEquals(Chat.of("Hi"), user(Part.Text("Hi")))
    assertEquals(Part.of("Hi"), Part.Text("Hi"))

  test("a value with a schema is said as its json, in a chat or a message"):
    assertEquals(Chat.of(ada), user(adaJson))
    assertEquals(Chat().user(ada), user(adaJson))
    assertEquals(
      Message(Role.User, ada),
      Message(Role.User, List(adaJson)),
    )

  test("a chat of one message is a chat to which it was said"):
    assertEquals(
      Chat.of(ada).withSystem("Review the person."),
      Chat().withSystem("Review the person.").user(ada),
    )

  test("chats saying the same are equal"):
    assertEquals(
      Chat.of(ada),
      Chat.of(Person("Ada", 36, None)),
    )

  test("a value with an encoder alone is said as its json"):
    assertEquals(
      Part.of(Note("Hi")),
      Part.Text("""{"text":"Hi"}"""),
    )

  test("a type with an encoder of its own is said as that writes it"):
    assertEquals(
      Part.of(Point(1, 2)),
      Part.Text("[1,2]"),
    )
    assertEquals(Part.of(Secret.of(3)), Part.Text("3"))
    assertEquals(Part.of(Secret.unset), Part.Text("1"))
    val errors = compileErrors("Schema[Point]")
    assert(
      errors.contains("has no Schema"),
      errors,
    )

  test("a value is said as its schema describes it"):
    assertEquals(
      Part.of(Shade.Light),
      Part.Text("\"Light\""),
    )

  test("a part is said as itself"):
    val picture = Part.Media(MediaType.Png, "iVBORw0KGgo=")
    assertEquals(Chat().user(picture), user(picture))
    assertEquals(Part.of(picture), picture)

  test("a part is said as itself, whatever encoder of parts there is"):
    given Encoder[Part]   = Encoder.instance(_ => io.circe.Json.Null)
    val parts: List[Part] = List(Part.Text("x"), Part.CacheBreakpoint)
    assertEquals(Part.of(parts.head), Part.Text("x"))
    assertEquals(Chat().user(parts*), user(parts*))
    assertEquals(
      summon[Encoder[Part]](parts.head),
      io.circe.Json.Null,
    )

  test("values of different kinds are said together, each as its own type"):
    assertEquals(
      Chat().user("Look:", 1.5),
      user(Part.Text("Look:"), Part.Text("1.5")),
    )
    assertEquals(
      Chat.of(
        "Review this person.",
        ada,
        Part.CacheBreakpoint,
        "Be brief.",
      ),
      user(
        Part.Text("Review this person."),
        adaJson,
        Part.CacheBreakpoint,
        Part.Text("Be brief."),
      ),
    )
    assertEquals(
      Message(Role.User, "Review this person.", ada),
      Message(
        Role.User,
        List(
          Part.Text("Review this person."),
          adaJson,
        ),
      ),
    )

  test("a value with no content is refused where it is given"):
    val errors = compileErrors("Chat().user(\"Look:\", new Mute)")
    assert(
      errors.contains("Mute cannot be said to the model"),
      errors,
    )

  test("a content of a type's own outranks its encoder"):
    assertEquals(
      Chat().user("What is this?", Scan("iVBORw0KGgo=")),
      user(
        Part.Text("What is this?"),
        Part.Media(MediaType.Png, "iVBORw0KGgo="),
      ),
    )

  test("a list of values is said as parts made of each"):
    assertEquals(
      Chat().user(List("First.", "Second.").map(Part.of)*),
      user(
        Part.Text("First."),
        Part.Text("Second."),
      ),
    )

  test("a value of a union is said as json, unless text may be one"):
    assertEquals(
      Part.of[Int | Boolean](true),
      Part.Text("""{"Boolean":true}"""),
    )
    val errors = compileErrors("Part.of[String | Int](1)")
    assert(errors.contains("union"), errors)

  test("several values of one kind are said as parts of one message"):
    assertEquals(
      Chat().user("First.", "Second."),
      user(
        Part.Text("First."),
        Part.Text("Second."),
      ),
    )
    val parts = List(Part.Text("x"), Part.CacheBreakpoint)
    assertEquals(Chat().user(parts*), user(parts*))

  test("an answer given is said back as the assistant's, to continue from"):
    assertEquals(
      Chat().user("Invent a person.").assistant(ada).messages.last,
      Message(Role.Assistant, List(adaJson)),
    )

  test("a media type is named as its listed case, in any case"):
    assertEquals(
      MediaType.of("image/png"),
      Some(MediaType.Png),
    )
    assertEquals(
      MediaType.of(" Image/JPEG; q=1 "),
      Some(MediaType.Jpeg),
    )
    assertEquals(
      MediaType.of("audio/MP3").map(_.name),
      Some("audio/mp3"),
    )
    assert(!MediaType.listed.contains(MediaType.of("audio/mp3").get))
    assertEquals(MediaType.of("png"), None)
    assertEquals(MediaType.of("image/"), None)
    assertEquals(MediaType.of(" "), None)

  test("media made of bytes is sent base64 encoded"):
    assertEquals(
      Part.Media(
        MediaType.Png,
        Array[Byte](104, 101, 108, 108, 111),
      ),
      Part.Media(MediaType.Png, "aGVsbG8="),
    )
