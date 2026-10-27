// Outside Iris's package, so that content resolves as it would for a user.
package com.alecdorrington.example

import com.alecdorrington.iris.{Chat, Message, Part, Role, Structured}
import io.circe.Encoder
import munit.FunSuite

/** A type with an encoder, but no structured type. */
final case class Note(text: String) derives Encoder.AsObject

/** A type with both, which write it differently. */
enum Shade derives Structured, Encoder.AsObject:
  case Light, Dark

class ContentSuite extends FunSuite:

  private val ada = Person("Ada", 36, None)

  private val adaJson = Part.Text("""{"name":"Ada","age":36}""")

  private def user(content: Part*): Chat =
    Chat(List(Message(Role.User, content.toList)))

  test("text is said as it is, never as json"):
    assertEquals(Chat.of("Hi"), user(Part.Text("Hi")))
    assertEquals(Part.of("Hi"), Part.Text("Hi"))

  test("a structured value is said as its json, in a chat or a message"):
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

  test("a structured type is said as its schema describes it"):
    assertEquals(
      Part.of(Shade.Light),
      Part.Text("\"Light\""),
    )

  test("a part is said as itself"):
    val picture = Part.Media("image/png", "iVBORw0KGgo=")
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

  test("values of different kinds together are told to be made parts"):
    assert(compileErrors("Chat().user(\"Look:\", 1.5)").contains("Part.of"))

  test("values of different kinds are said as parts of one message"):
    assertEquals(
      Chat().user(
        Part.of("Review this person."),
        Part.of(ada),
      ),
      user(
        Part.Text("Review this person."),
        adaJson,
      ),
    )

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
