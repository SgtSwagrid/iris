// Outside Iris's package, so that content resolves as it would for a user.
package com.alecdorrington.example

import com.alecdorrington.iris.{
  Chat, Content, MediaType, Message, MessagePart, Role, Schema,
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

  given Content[Scan] = scan => MessagePart.Media(MediaType.Png, scan.base64)

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

  private val adaJson = MessagePart.Text("""{"name":"Ada","age":36}""")

  private def userMessage(content: MessagePart*): Chat =
    Chat(List(Message(Role.User, content.toList)))

  test("text is said as it is, never as json"):
    assertEquals(
      Chat.userSays("Hi"),
      userMessage(MessagePart.Text("Hi")),
    )
    assertEquals(
      MessagePart.of("Hi"),
      MessagePart.Text("Hi"),
    )

  test("a value with a schema is said as its json, in a chat or a message"):
    assertEquals(
      Chat.userSays(ada),
      userMessage(adaJson),
    )
    assertEquals(
      Chat().userSays(ada),
      userMessage(adaJson),
    )
    assertEquals(
      Message(Role.User, ada),
      Message(Role.User, List(adaJson)),
    )

  test("a chat of one message is a chat to which it was said"):
    assertEquals(
      Chat.userSays(ada).withInstructions("Review the person."),
      Chat().withInstructions("Review the person.").userSays(ada),
    )

  test("chats saying the same are equal"):
    assertEquals(
      Chat.userSays(ada),
      Chat.userSays(Person("Ada", 36, None)),
    )

  test("a value with an encoder alone is said as its json"):
    assertEquals(
      MessagePart.of(Note("Hi")),
      MessagePart.Text("""{"text":"Hi"}"""),
    )

  test("a type with an encoder of its own is said as that writes it"):
    assertEquals(
      MessagePart.of(Point(1, 2)),
      MessagePart.Text("[1,2]"),
    )
    assertEquals(
      MessagePart.of(Secret.of(3)),
      MessagePart.Text("3"),
    )
    assertEquals(
      MessagePart.of(Secret.unset),
      MessagePart.Text("1"),
    )
    val errors = compileErrors("Schema[Point]")
    assert(
      errors.contains("has no Schema"),
      errors,
    )

  test("a value is said as its schema describes it"):
    assertEquals(
      MessagePart.of(Shade.Light),
      MessagePart.Text("\"Light\""),
    )

  test("a part is said as itself"):
    val picture = MessagePart.Media(MediaType.Png, "iVBORw0KGgo=")
    assertEquals(
      Chat().userSays(picture),
      userMessage(picture),
    )
    assertEquals(MessagePart.of(picture), picture)

  test("a part is said as itself, whatever encoder of parts there is"):
    given Encoder[MessagePart]   = Encoder.instance(_ => io.circe.Json.Null)
    val parts: List[MessagePart] = List(
      MessagePart.Text("x"),
      MessagePart.CacheBreakpoint,
    )
    assertEquals(
      MessagePart.of(parts.head),
      MessagePart.Text("x"),
    )
    assertEquals(
      Chat().userSays(parts*),
      userMessage(parts*),
    )
    assertEquals(
      summon[Encoder[MessagePart]](parts.head),
      io.circe.Json.Null,
    )

  test("values of different kinds are said together, each as its own type"):
    assertEquals(
      Chat().userSays("Look:", 1.5),
      userMessage(
        MessagePart.Text("Look:"),
        MessagePart.Text("1.5"),
      ),
    )
    assertEquals(
      Chat.userSays(
        "Review this person.",
        ada,
        MessagePart.CacheBreakpoint,
        "Be brief.",
      ),
      userMessage(
        MessagePart.Text("Review this person."),
        adaJson,
        MessagePart.CacheBreakpoint,
        MessagePart.Text("Be brief."),
      ),
    )
    assertEquals(
      Message(Role.User, "Review this person.", ada),
      Message(
        Role.User,
        List(
          MessagePart.Text("Review this person."),
          adaJson,
        ),
      ),
    )

  test("a value with no content is refused where it is given"):
    val errors = compileErrors("Chat().userSays(\"Look:\", new Mute)")
    assert(
      errors.contains("Mute cannot be said to the model"),
      errors,
    )

  test("a content of a type's own outranks its encoder"):
    assertEquals(
      Chat().userSays("What is this?", Scan("iVBORw0KGgo=")),
      userMessage(
        MessagePart.Text("What is this?"),
        MessagePart.Media(MediaType.Png, "iVBORw0KGgo="),
      ),
    )

  test("a list of values is said as parts made of each"):
    assertEquals(
      Chat().userSays(List("First.", "Second.").map(MessagePart.of)*),
      userMessage(
        MessagePart.Text("First."),
        MessagePart.Text("Second."),
      ),
    )

  test("a value of a union is said as json, unless text may be one"):
    assertEquals(
      MessagePart.of[Int | Boolean](true),
      MessagePart.Text("""{"Boolean":true}"""),
    )
    val errors = compileErrors("MessagePart.of[String | Int](1)")
    assert(errors.contains("union"), errors)

  test("several values of one kind are said as parts of one message"):
    assertEquals(
      Chat().userSays("First.", "Second."),
      userMessage(
        MessagePart.Text("First."),
        MessagePart.Text("Second."),
      ),
    )
    val parts = List(
      MessagePart.Text("x"),
      MessagePart.CacheBreakpoint,
    )
    assertEquals(
      Chat().userSays(parts*),
      userMessage(parts*),
    )

  test("an answer given is said back as the assistant's, to continue from"):
    assertEquals(
      Chat().userSays("Invent a person.").modelSays(ada).messages.last,
      Message(Role.Model, List(adaJson)),
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
      MessagePart.Media(
        MediaType.Png,
        Array[Byte](104, 101, 108, 108, 111),
      ),
      MessagePart.Media(MediaType.Png, "aGVsbG8="),
    )
