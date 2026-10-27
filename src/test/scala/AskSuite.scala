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

  private val asking = "Invent a person."

  private val prompt = Chat.of(asking)

  private val ada = Person("Ada", 36, None)

  private val adaJson = """{"name":"Ada","age":36}"""

  private val instructions = Asking.instructions(Structured[Person])

  /** Anthropic's answer of the text, stopping as said, costing as said. */
  private def said
    (
      text: String,
      stop: String = "end_turn",
      cost: Option[(Int, Int)] = None,
    )
    : String = Json
    .obj(
      "content" -> Json.arr(Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      )),
      "stop_reason" -> stop.asJson,
      "usage"       ->
        cost
          .map((input, output) =>
            Json.obj(
              "input_tokens"  -> input.asJson,
              "output_tokens" -> output.asJson,
            ),
          )
          .asJson,
    )
    .dropNullValues
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

  /** The answer an ask gives, or why it gave none. */
  private def asked[A : Structured]
    (
      client: LlmClient[SyncIO],
      attempts: Int = Asking.attempts,
    )
    : Either[Throwable, Option[A]] = client
    .ask[A](prompt, attempts = attempts)
    .map(_.answer)
    .attempt
    .unsafeRunSync()

  test("a structured answer is read from the json the model replies with"):
    assertEquals(
      asked[Person](client(_ => said(adaJson))),
      Right(Some(ada)),
    )

  test("the schema closes the user's message, as a paragraph of its own"):
    assert(instructions.contains(Structured[Person].schema.noSpaces))
    val told = client(sent =>
      if sent == List(Message(Role.User, s"$asking\n\n$instructions")) then
        said(adaJson)
      else said("Not told."),
    )
    assertEquals(
      asked[Person](told, attempts = 1),
      Right(Some(ada)),
    )

  test("a chat the assistant ended is followed by the instructions"):
    val chat = Chat().user("Invent a person.").assistant("Let me think.")
    val told = client(sent =>
      if sent.lastOption.contains(Message(Role.User, instructions)) then
        said(adaJson)
      else said("Not told."),
    )
    assertEquals(
      told
        .ask[Person](chat, attempts = 1)
        .map(_.answer)
        .attempt
        .unsafeRunSync(),
      Right(Some(ada)),
    )

  test("an answer in a code fence is read from within it"):
    val fenced = client(_ => said(s"```json\n$adaJson\n```"))
    assertEquals(
      asked[Person](fenced),
      Right(Some(ada)),
    )

  test("an answer in a fence of one line, or none, is read alike"):
    for written <- List(
        s"```json $adaJson```",
        s"```$adaJson```",
        s"Here it is:\n```json\n$adaJson\n```\nI hope it helps.",
        s"Here it is: $adaJson. I hope it helps.",
      )
    do
      assertEquals(
        asked[Person](client(_ => said(written))),
        Right(Some(ada)),
      )

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
    assertEquals(
      asked[Person](corrected),
      Right(Some(ada)),
    )

  test("the model is told of every field that could not be read"):
    val corrected = client: sent =>
      val told = sent.lastOption.map(_.text).getOrElse("")
      if told.contains("At .name: ") && told.contains("At .age: ") then
        said(adaJson)
      else said("""{"name":1}""")
    assertEquals(
      asked[Person](corrected),
      Right(Some(ada)),
    )

  test("the last unreadable reply fails the ask, keeping every reply"):
    asked[Person](client(_ => said("Ada, 36."))) match
      case Left(LlmError.Unreadable(attempts, detail, replies)) =>
        assertEquals(attempts, 3)
        // JavaScript's parser says nowhere where it failed.
        assert(detail.startsWith("It is not JSON"))
        assertEquals(
          replies.map(_.text),
          List.fill(3)("Ada, 36."),
        )
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  test("why a reply could not be read repeats none of it"):
    val wrong = client(_ => said("""{"name":"Ada","age":"thirty-six"}"""))
    asked[Person](wrong, attempts = 1) match
      case Left(failure @ LlmError.Unreadable(_, detail, _)) =>
        assertEquals(
          detail,
          "At .age: Expected an integer, not a string",
        )
        assert(!failure.getMessage.contains("thirty-six"))
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  /** Whether a reply fails the ask at once, though more attempts are left. */
  private def failsAtOnce(answer: String, detail: String): Unit =
    asked[Person](client(_ => answer)) match
      case Left(LlmError.Unreadable(attempts, said, replies)) => assertEquals(
          (attempts, said, replies.size),
          (1, detail, 1),
        )
      case other => fail(s"Expected the ask to fail at once, not $other.")

  test("a reply cut off at the token limit fails at once"):
    failsAtOnce(
      said("""{"name":""", "max_tokens"),
      "It was cut off at the token limit before it was complete.",
    )

  test("a reply stopped by the provider fails at once"):
    failsAtOnce(
      said("I would rather not.", "refusal"),
      "It stopped, as `refusal`.",
    )

  test("a reply of nothing fails at once, never asked again as it was"):
    failsAtOnce(said(""), "It said nothing.")

  private val calling = """{"content":[{"type":"text","text":"Let me look."},
    {"type":"tool_use","id":"call_1","name":"weather","input":{"city":"Zug"}}],
    "stop_reason":"tool_use"}"""

  test("a reply asking for a tool is given at once, with no answer"):
    for asking <- List(
        client(_ => calling).ask[Person](prompt),
        client(_ => calling).ask[String](prompt),
      )
    do
      asking.attempt.unsafeRunSync() match
        case Right(reply) =>
          assertEquals(reply.answer, None)
          assertEquals(reply.text, "Let me look.")
          assertEquals(reply.stopReason, StopReason.ToolCall)
          assertEquals(
            reply.toolCalls.map(_.tool),
            List("weather"),
          )
          assertEquals(reply.attempts, 1)
        case other => fail(s"Expected the call, not $other.")

  test("a fence within a JSON string is part of it, not the answer"):
    val fenced = """{"name":"```Ada```","age":36}"""
    assertEquals(
      asked[Person](client(_ => said(fenced))),
      Right(Some(Person("```Ada```", 36, None))),
    )

  test("of two fences, the last is the answer"):
    val drafted = s"Draft:\n```json\n{\"name\":\"Bea\",\"age\":1}\n```\n" +
      s"Final:\n```json\n$adaJson\n```"
    assertEquals(
      asked[Person](client(_ => said(drafted))),
      Right(Some(ada)),
    )

  test("a reply ended by a stop sequence is asked for again, told so"):
    val stopped = client: sent =>
      val told = sent.lastOption.map(_.text).getOrElse("")
      if told.contains("It stopped at a stop sequence. It is not JSON") then
        said(adaJson)
      else said("Ada, 36", "stop_sequence")
    assertEquals(
      asked[Person](stopped),
      Right(Some(ada)),
    )

  test("a map's keys may be told the model, but stay out of the message"):
    asked[Map[String, Int]](
      client(_ => said("""{"secret":"x"}""")),
      1,
    ) match
      case Left(failure @ LlmError.Unreadable(_, detail, _)) =>
        assertEquals(
          detail,
          "At .secret: Expected an integer, not a string",
        )
        assert(!failure.getMessage.contains("secret"))
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  test("a number out of range or not whole is told so"):
    for (written, detail) <- List(
        "3000000000" -> "At the top: Expected an integer that fits in Int",
        "3.5"        -> "At the top: Expected a whole number",
      )
    do
      asked[Int](client(_ => said(written)), 1) match
        case Left(LlmError.Unreadable(_, said, _)) => assertEquals(said, detail)
        case other => fail(s"Expected the ask to be unreadable, not $other.")

  test("an ask of no attempts is refused before any request"):
    val never = client(_ => throw AssertionError("A request was made."))
    assert(
      asked[Person](never, attempts = 0) match
        case Left(_: IllegalArgumentException) => true
        case _                                 => false,
    )

  test("text is asked for as JSON, as every type is"):
    val schema = Asking.instructions(Structured[String])
    assert(
      schema.endsWith("""{"type":"string"}"""),
      schema,
    )
    val told = client(sent =>
      if sent == List(Message(Role.User, s"$asking\n\n$schema")) then
        said("\"Ada, 36.\"")
      else said("Not told."),
    )
    assertEquals(
      asked[String](told, attempts = 1),
      Right(Some("Ada, 36.")),
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
      told
        .ask[Person](Chat.of(ada), ReplyOptions())
        .map(_.answer)
        .attempt
        .unsafeRunSync(),
      Right(Some(ada)),
    )

  test("a structured answer may be any structured type"):
    val colours = client(_ => said("""["Red","Blue"]"""))
    assertEquals(
      asked[List[Colour]](colours),
      Right(Some(List(Colour.Red, Colour.Blue))),
    )
    assertEquals(
      asked[Int](client(_ => said("42"))),
      Right(Some(42)),
    )

  test("every ask gives the last reply whole, its usage that of every attempt"):
    val corrected = client(sent =>
      if sent.size > 1 then said(adaJson, cost = Some((20, 5)))
      else said("Ada, 36.", cost = Some((10, 2))),
    )
    assertEquals(
      corrected.ask[Person](prompt).attempt.unsafeRunSync(),
      Right(Reply(
        text = adaJson,
        stopReason = StopReason.Completed,
        usage = Some(Usage(30, 7)),
        answer = Some(ada),
        attempts = 2,
      )),
    )

  test("a wrapper around the client sees every attempt"):
    val corrected =
      client(sent => if sent.size > 1 then said(adaJson) else said("Ada, 36."))
    val counted =
      for
        sent <- Ref.of[SyncIO, Int](0)
        counting = new LlmClient.Forwarding(corrected):
          override protected def send
            (chat: Chat, options: ReplyOptions)
            : SyncIO[Reply[Nothing]] = sent.update(_ + 1) *>
            super.send(chat, options)
        reply <- counting.ask[Person](prompt)
        total <- sent.get
      yield (reply.answer, total)
    assertEquals(counted.unsafeRunSync(), (Some(ada), 2))

  /** A client failing the test if it is ever asked to send. */
  private def never: LlmClient[SyncIO] =
    client(_ => throw AssertionError("A request was made."))

  test("a chat in which the user says nothing is refused before any request"):
    for (chat, why) <- List(
        Chat()                                 -> "it has no messages",
        Chat().withSystem("Answer in French.") -> "it has no messages",
        Chat().user(Part.CacheBreakpoint) -> "one of its messages says nothing",
        Chat().assistant("Bonjour.")      -> "the user says nothing in it",
      )
    do
      assertEquals(
        never.ask[String](chat).attempt.unsafeRunSync(),
        Left(LlmError.Unsendable("Anthropic", why)),
      )

  test("text unquoted is not JSON, so is asked for again, then unreadable"):
    asked[String](client(_ => said("Paris"))) match
      case Left(LlmError.Unreadable(attempts, detail, replies)) =>
        assertEquals(
          (attempts, replies.map(_.text)),
          (3, List.fill(3)("Paris")),
        )
        assert(
          detail.startsWith("It is not JSON"),
          detail,
        )
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  test("text cut off at the token limit fails at once"):
    failsAtOnceAs[String](
      said(
        "\"Paris is the capital of",
        "max_tokens",
      ),
      "It was cut off at the token limit before it was complete.",
    )

  test("a reply cut off at the token limit is never read, though it parses"):
    failsAtOnceAs[Int](
      said("4", "max_tokens"),
      "It was cut off at the token limit before it was complete.",
    )

  /** Whether a reply fails an ask of a type at once. */
  private def failsAtOnceAs[A : Structured]
    (answer: String, detail: String)
    : Unit = asked[A](client(_ => answer)) match
    case Left(LlmError.Unreadable(attempts, said, replies)) => assertEquals(
        (attempts, said, replies.size),
        (1, detail, 1),
      )
    case other => fail(s"Expected the ask to fail at once, not $other.")

  test(
    "a reply calling a tool, whatever it says of why it stopped, has no answer",
  ):
    val forced = """{"content":[{"type":"tool_use","id":"call_1",
      "name":"weather","input":{}}],"stop_reason":"end_turn"}"""
    client(_ => forced).ask[Person](prompt).attempt.unsafeRunSync() match
      case Right(reply) =>
        assertEquals(reply.answer, None)
        assertEquals(
          reply.toolCalls.map(_.tool),
          List("weather"),
        )
      case other => fail(s"Expected the call, not $other.")

  test(
    "a tool asked for on a later attempt is given with every attempt's usage",
  ):
    val withUsage = """{"content":[{"type":"tool_use","id":"call_1",
      "name":"weather","input":{}}],"stop_reason":"tool_use",
      "usage":{"input_tokens":20,"output_tokens":5}}"""
    val later     = client(sent =>
      if sent.size > 1 then withUsage
      else said("Ada, 36.", cost = Some((10, 2))),
    )
    later.ask[Person](prompt).attempt.unsafeRunSync() match
      case Right(reply) => assertEquals(
          (reply.answer, reply.attempts, reply.usage),
          (None, 2, Some(Usage(30, 7))),
        )
      case other => fail(s"Expected the call, not $other.")

  test("usage is none where an attempt reports none, as no sum would be whole"):
    val partly = client(sent =>
      if sent.size > 1 then said(adaJson, cost = Some((20, 5)))
      else said("Ada, 36."),
    )
    assertEquals(
      partly.ask[Person](prompt).map(_.usage).attempt.unsafeRunSync(),
      Right(None),
    )

  test("usages add up, cached tokens with the rest"):
    assertEquals(
      Usage(10, 2, 4) + Usage(20, 5, 6),
      Usage(30, 7, 10),
    )

  test("the request after a tool's result carries it, then the instructions"):
    val calling  = Part.ToolCall("call_1", "weather", Json.obj())
    val answered = Chat()
      .user("What is the weather?")
      .assistant(Reply(
        "",
        StopReason.ToolCall,
        None,
        List(calling),
      ))
      .user(Part.ToolResult("call_1", "weather", "Snowing."))
    val checked = LlmClient[SyncIO](
      config,
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespondF(request =>
          request.body match
            case StringBody(sent, _, _) =>
              val last  = json(sent).hcursor.downField("messages").downN(2)
              val kinds = last
                .downField("content")
                .as[List[Json]]
                .map(_.flatMap(_.hcursor.get[String]("type").toOption))
              val told = last
                .downField("content")
                .downN(1)
                .get[String]("text")
                .exists(_.contains("Reply with JSON alone"))
              SyncIO(ResponseStub.adjust(
                if kinds == Right(List("tool_result", "text")) && told then
                  said("\"Snowing in Zug.\"")
                else said("Not told."),
              ))
            case _ => SyncIO.raiseError(Exception("No JSON was sent.")),
        ),
    )
    assertEquals(
      checked
        .ask[String](answered, attempts = 1)
        .map(_.answer)
        .attempt
        .unsafeRunSync(),
      Right(Some("Snowing in Zug.")),
    )

  /** A client of a provider answering every request with the body. */
  private def answering(model: LlmModel, body: String): LlmClient[SyncIO] =
    LlmClient[SyncIO](
      config.copy(model = model),
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespond(ResponseStub.adjust(body)),
    )

  test("every provider's reply is asked for and read alike"):
    val written = adaJson.asJson.noSpaces
    val openAi = s"""{"choices":[{"message":{"content":$written},
      "finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
    val gemini = s"""{"candidates":[{"content":{"parts":[{"text":$written}]},
      "finishReason":"STOP"}],
      "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}}"""
    for (model, body) <- List(
        LlmModel.Gpt5           -> openAi,
        LlmModel.Gemini2_5Flash -> gemini,
      )
    do
      assertEquals(
        answering(model, body).ask[Person](prompt).attempt.unsafeRunSync(),
        Right(Reply(
          adaJson,
          StopReason.Completed,
          Some(Usage(5, 3)),
          answer = Some(ada),
        )),
      )

  test(
    "openai's call it was made to make, though it says it stopped, is a call",
  ):
    val forced = """{"choices":[{"message":{"content":null,"tool_calls":[
      {"id":"call_1","type":"function",
       "function":{"name":"weather","arguments":"{}"}}]},
      "finish_reason":"stop"}]}"""
    assertEquals(
      answering(LlmModel.Gpt5, forced)
        .ask[Person](prompt)
        .map(reply => (reply.answer, reply.toolCalls.map(_.tool)))
        .attempt
        .unsafeRunSync(),
      Right((None, List("weather"))),
    )
