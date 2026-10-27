package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import com.alecdorrington.example.{Colour, Person}
import com.alecdorrington.iris.Fixtures.{config, json}
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}

class AskSuite extends FunSuite:

  private val prompt = Prompt("Invent a person.")

  private val ada = Person("Ada", 36, None)

  private val adaJson = """{"name":"Ada","age":36}"""

  private val instructions = Answer.json[Person].instructions.get

  /** Anthropic's answer of the text, stopping as said. */
  private def said(text: String, stop: String = "end_turn"): String = Json
    .obj(
      "content" -> Json.arr(Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      )),
      "stop_reason" -> stop.asJson,
    )
    .noSpaces

  /** A client answering each request as `answer` does its messages. */
  private def client(answer: List[Message] => String): LlmClient[SyncIO] =
    LlmClient[SyncIO](
      config,
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespondF(request =>
          request.body match
            case StringBody(sent, _, _) =>
              SyncIO(ResponseStub.adjust(answer(messages(json(sent)))))
            case _ => SyncIO.raiseError(Exception("No JSON was sent.")),
        ),
    )

  /** The messages Anthropic was sent, each of text alone. */
  private def messages(body: Json): List[Message] = body
    .hcursor
    .get[List[Json]]("messages")
    .toOption
    .get
    .map(message =>
      Message(
        if message.hcursor.get[String]("role").contains("user") then Role.User
        else Role.Assistant,
        message.hcursor.get[String]("content").getOrElse(""),
      ),
    )

  private def asked[A : Answer]
    (
      client: LlmClient[SyncIO],
      attempts: Int = Answer.attempts,
    )
    : Either[Throwable, A] = client
    .ask[A](prompt.toChat, attempts = attempts)
    .attempt
    .unsafeRunSync()

  test("a structured answer is read from the json the model replies with"):
    assertEquals(
      asked[Person](client(_ => said(adaJson))),
      Right(ada),
    )

  test("the schema closes the user's message, as a paragraph of its own"):
    assert(instructions.contains(Structured[Person].schema.noSpaces))
    val told = client(sent =>
      if sent == List(Message(
          Role.User,
          s"${ prompt.user }\n\n$instructions",
        ))
      then said(adaJson)
      else said("Not told."),
    )
    assertEquals(
      asked[Person](told, attempts = 1),
      Right(ada),
    )

  test("a chat the assistant ended is followed by the instructions"):
    val chat = Chat().user("Invent a person.").assistant("Let me think.")
    val told = client(sent =>
      if sent.lastOption.contains(Message(Role.User, instructions)) then
        said(adaJson)
      else said("Not told."),
    )
    assertEquals(
      told.ask[Person](chat, attempts = 1).attempt.unsafeRunSync(),
      Right(ada),
    )

  test("an answer in a code fence is read from within it"):
    val fenced = client(_ => said(s"```json\n$adaJson\n```"))
    assertEquals(asked[Person](fenced), Right(ada))

  test("an unreadable reply is followed by why, and the model asked again"):
    val corrected = client: sent =>
      val again = sent.drop(1) match
        case List(
            Message(Role.Assistant, reply),
            Message(Role.User, why),
          ) => reply == List(Part.Text("Ada, 36.")) &&
          Message(Role.User, why)
            .text
            .startsWith("That reply could not be read:\nIt is not JSON")
        case _ => false
      if again then said(adaJson) else said("Ada, 36.")
    assertEquals(asked[Person](corrected), Right(ada))

  test("the model is told of every field that could not be read"):
    val corrected = client: sent =>
      val told = sent.lastOption.map(_.text).getOrElse("")
      if told.contains("At .name: ") && told.contains("At .age: ") then
        said(adaJson)
      else said("""{"name":1}""")
    assertEquals(asked[Person](corrected), Right(ada))

  test("the last unreadable reply fails the ask"):
    asked[Person](client(_ => said("Ada, 36."))) match
      case Left(LlmError.Unreadable(attempts, detail, text)) =>
        assertEquals(attempts, 3)
        assert(detail.startsWith("It is not JSON"))
        assertEquals(text, "Ada, 36.")
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  test("a reply cut off at the token limit is unreadable"):
    val cut = client(_ => said("""{"name":""", "max_tokens"))
    assertEquals(
      asked[Person](cut, attempts = 1),
      Left(LlmError.Unreadable(
        1,
        "It was cut off at the token limit before it was complete.",
        """{"name":""",
      )),
    )

  test("a text answer is the reply as written, the model told nothing"):
    assert(summon[Answer[String]] eq Answer.text)
    val plain = client(sent =>
      if sent == List(Message(Role.User, prompt.user)) then said("Ada, 36.")
      else said("Told something."),
    )
    assertEquals(
      asked[String](plain),
      Right("Ada, 36."),
    )

  test("a value asked about is sent as json, followed by the instructions"):
    val told = client(sent =>
      if sent == List(Message(
          Role.User,
          s"$adaJson\n\n$instructions",
        ))
      then said(adaJson)
      else said("Not told."),
    )
    assertEquals(
      told.ask[Person](Prompt(ada), ReplyOptions()).attempt.unsafeRunSync(),
      Right(ada),
    )

  test("a structured answer may be any structured type"):
    val colours = client(_ => said("""["Red","Blue"]"""))
    assertEquals(
      asked[List[Colour]](colours),
      Right(List(Colour.Red, Colour.Blue)),
    )
    assertEquals(
      asked[Int](client(_ => said("42"))),
      Right(42),
    )

  test("a wrapper around the client sees every attempt"):
    val corrected =
      client(sent => if sent.size > 1 then said(adaJson) else said("Ada, 36."))
    val counted =
      for
        sent <- Ref.of[SyncIO, Int](0)
        counting = new LlmClient.Forwarding(corrected):
          override def send(chat: Chat, options: ReplyOptions): SyncIO[Reply] =
            sent.update(_ + 1) *> underlying.send(chat, options)
        person <- counting.ask[Person](prompt)
        total  <- sent.get
      yield (person, total)
    assertEquals(counted.unsafeRunSync(), (ada, 2))
