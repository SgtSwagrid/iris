package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import com.alecdorrington.example.{Colour, Person}
import com.alecdorrington.iris.Fixtures.{config, json}
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import scala.util.control.NonFatal
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}

class AskSuite extends FunSuite:

  private val asking = "Invent a person."

  private val prompt = Chat.of(asking)

  private val ada = Person("Ada", 36, None)

  private val adaJson = """{"name":"Ada","age":36}"""

  private val instructions = Question.instructions(Schema[Person])

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
  private def asked[A : Schema]
    (
      client: LlmClient[SyncIO],
      attempts: Int = Question.attempts,
    )
    : Either[Throwable, Option[A]] = client
    .ask[A](prompt, attempts = attempts)
    .map(_.value)
    .attempt
    .unsafeRunSync()

  test("a typed answer is read from the json the model answers with"):
    assertEquals(
      asked[Person](client(_ => said(adaJson))),
      Right(Some(ada)),
    )

  test("the schema closes the user's message, as a paragraph of its own"):
    assert(instructions.contains(Schema[Person].json.noSpaces))
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
      told.ask[Person](chat, attempts = 1).map(_.value).attempt.unsafeRunSync(),
      Right(Some(ada)),
    )

  /** The person read from a reply at the first attempt, or why none was. */
  private def read(written: String): Either[String, Option[Person]] =
    asked[Person](client(_ => said(written)), attempts = 1)
      .left
      .map:
        case LlmError.Unreadable(_, detail, _) => detail
        case other                             => other.toString

  test("JSON is read from the whole reply, else its last fence, and no other"):
    List(
      s"```json\n$adaJson\n```" -> Right(Some(ada)),
      s"```json $adaJson```"    -> Right(Some(ada)),
      s"```$adaJson```"         -> Right(Some(ada)),
      // Prose around a fence is no matter.
      s"Here it is:\n```json\n$adaJson\n```\nI hope it helps." ->
        Right(Some(ada)),
      // A fence within a JSON string is part of it, not the answer.
      """{"name":"```Ada```","age":36}""" ->
        Right(Some(Person("```Ada```", 36, None))),
      // Of two fences, the last is the answer.
      (s"Draft:\n```json\n{\"name\":\"Bea\",\"age\":1}\n```\n" +
        s"Final:\n```json\n$adaJson\n```") -> Right(Some(ada)),
      // JSON amid prose, with no fence, is not read.
      s"Here it is: $adaJson. I hope it helps." -> Left("It is not JSON."),
    ).foreach((written, answer) => assertEquals(read(written), answer, written))

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

  test("the last unreadable reply fails the ask, keeping every reply"):
    // Text is no exception: unquoted, it is not JSON.
    asked[String](client(_ => said("Paris"))) match
      case Left(LlmError.Unreadable(attempts, detail, answers)) =>
        assertEquals(attempts, 3)
        assertEquals(detail, "It is not JSON.")
        assertEquals(
          answers.map(_.text),
          List.fill(3)("Paris"),
        )
      case other => fail(s"Expected the ask to be unreadable, not $other.")

  test(
    "why a reply could not be read is circe's, in full, kept from the message",
  ):
    List[(String, Either[Throwable, Option[?]], List[String], Option[String])](
      (
        "a field of the wrong type",
        asked[Person](
          client(_ => said("""{"name":"Ada","age":"thirty-six"}""")),
          1,
        ),
        List(".age"),
        Some("thirty-six"),
      ),
      // The model is told of every field that could not be read.
      (
        "two fields unread",
        asked[Person](client(_ => said("""{"name":1}""")), 1),
        List(".name", ".age"),
        None,
      ),
      // A map's keys may be told the model, but stay out of the message.
      (
        "a map's key",
        asked[Map[String, Int]](
          client(_ => said("""{"secret":"x"}""")),
          1,
        ),
        List(".secret"),
        Some("secret"),
      ),
      (
        "a number out of range",
        asked[Int](client(_ => said("3000000000")), 1),
        List("Expected an integer that fits in Int"),
        None,
      ),
      (
        "a number not whole",
        asked[Int](client(_ => said("3.5")), 1),
        List("Expected a whole number"),
        None,
      ),
    ).foreach: (row, result, told, withheld) =>
      result match
        case Left(failure @ LlmError.Unreadable(_, detail, _)) =>
          told.foreach(field =>
            assert(
              detail.contains(field),
              s"$row: $detail",
            ),
          )
          withheld.foreach(value =>
            assert(
              !failure.getMessage.contains(value),
              s"$row: ${ failure.getMessage }",
            ),
          )
        case other =>
          fail(s"Expected the ask to be unreadable ($row), not $other.")

  test("a reply no reading of which could be trusted fails at once"):
    val cut = "It was cut off at the token limit before it was complete."
    List(
      // What parses of a reply cut off may itself be cut, as 4 of 42.
      ("a number cut off", said("4", "max_tokens"), cut),
      // A call cut off at the token limit is cut off, not a call.
      (
        "a call cut off",
        """{"content":[{"type":"tool_use","id":"call_1","name":"weather",
        "input":{"city":"Zu"}}],"stop_reason":"max_tokens"}""",
        cut,
      ),
      (
        "a refusal",
        said("I would rather not.", "refusal"),
        "It stopped, as `refusal`.",
      ),
      // A reply of nothing is never asked for again as it was.
      ("nothing", said(""), "It said nothing."),
    ).foreach: (row, answer, detail) =>
      try failsAtOnceAs[Int](answer, detail)
      catch
        case NonFatal(failure) => fail(
            s"$row: ${ failure.getMessage }",
            failure,
          )

  private val calling = """{"content":[{"type":"text","text":"Let me look."},
    {"type":"tool_use","id":"call_1","name":"weather","input":{"city":"Zug"}}],
    "stop_reason":"tool_use"}"""

  /** OpenAI's call it was made to make, though it says it stopped as ever. */
  private val forced = """{"choices":[{"message":{"content":null,"tool_calls":[
    {"id":"call_1","type":"function",
     "function":{"name":"weather","arguments":"{}"}}]},
    "finish_reason":"stop"}]}"""

  test("a reply asking for a tool is given at once, with no answer"):
    List(
      (
        "a person asked of anthropic",
        client(_ => calling).ask[Person](prompt),
        "Let me look.",
        StopReason.ToolCall,
      ),
      (
        "text asked of anthropic",
        client(_ => calling).ask[String](prompt),
        "Let me look.",
        StopReason.ToolCall,
      ),
      (
        "a person asked of openai",
        answering(LlmModel.Gpt5, forced).ask[Person](prompt),
        "",
        StopReason.Completed,
      ),
    ).foreach: (row, asking, text, stopReason) =>
      asking.attempt.unsafeRunSync() match
        case Right(reply) => assertEquals(
            (
              reply.value,
              reply.text,
              reply.stopReason,
              reply.toolCalls.map(_.tool),
              reply.attempts,
            ),
            (None, text, stopReason, List("weather"), 1),
            row,
          )
        case other => fail(s"Expected the call ($row), not $other.")

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

  test("an ask of no attempts is refused before any request"):
    val never = client(_ => throw AssertionError("A request was made."))
    assert(
      asked[Person](never, attempts = 0) match
        case Left(_: IllegalArgumentException) => true
        case _                                 => false,
    )

  test("text is asked for as JSON, as every type is"):
    val schema = Question.instructions(Schema[String])
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
        .map(_.value)
        .attempt
        .unsafeRunSync(),
      Right(Some(ada)),
    )

  test("a typed answer may be of any type with a schema"):
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
      Right(Answer(
        text = adaJson,
        stopReason = StopReason.Completed,
        usage = Usage(uncachedTokens = 30, visibleTokens = 7),
        // Claude Haiku 4.5's $1 and $5 per million tokens.
        cost = Some(Usd(BigDecimal("0.000065"))),
        value = Some(ada),
        attempts = 2,
      )),
    )
    // A tool asked for on a later attempt is given with every attempt's usage.
    val withUsage = """{"content":[{"type":"tool_use","id":"call_1",
      "name":"weather","input":{}}],"stop_reason":"tool_use",
      "usage":{"input_tokens":20,"output_tokens":5}}"""
    val later     = client(sent =>
      if sent.size > 1 then withUsage
      else said("Ada, 36.", cost = Some((10, 2))),
    )
    assertEquals(
      later
        .ask[Person](prompt)
        .map(reply => (reply.value, reply.attempts, reply.usage))
        .attempt
        .unsafeRunSync(),
      Right((None, 2, Usage(uncachedTokens = 30, visibleTokens = 7))),
    )
    // An attempt reporting no usage adds none.
    val partly = client(sent =>
      if sent.size > 1 then said(adaJson, cost = Some((20, 5)))
      else said("Ada, 36."),
    )
    assertEquals(
      partly
        .ask[Person](prompt)
        .map(reply => (reply.usage, reply.cost))
        .attempt
        .unsafeRunSync(),
      Right((
        Usage(uncachedTokens = 20, visibleTokens = 5),
        Some(Usd(BigDecimal("0.000045"))),
      )),
    )
    // Usage adds up count by count, and so do its totals.
    val sum = Usage(1, 2, 3, 4, 5, 6, 7) + Usage(10, 20, 30, 40, 50, 60, 70)
    assertEquals(sum, Usage(11, 22, 33, 44, 55, 66, 77))
    assertEquals(
      (sum.inputTokens, sum.cachedTokens, sum.outputTokens),
      (11 + 22 + 33 + 44 + 55, 22 + 55, 66 + 77),
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
            : SyncIO[Answer[Nothing]] = sent.update(_ + 1) *>
            super.send(chat, options)
        reply <- counting.ask[Person](prompt)
        total <- sent.get
      yield (reply.value, total)
    assertEquals(counted.unsafeRunSync(), (Some(ada), 2))

  /** A client failing the test if it is ever asked to send. */
  private def never: LlmClient[SyncIO] =
    client(_ => throw AssertionError("A request was made."))

  test("a chat in which the user says nothing is refused before any request"):
    for (chat, why) <- List(
        Chat()                                 -> "it has no messages",
        Chat().withSystem("Answer in French.") -> "it has no messages",
        Chat().user(Part.CacheBreakpoint) -> "one of its messages says nothing",
        // Not the last message alone: any of them saying nothing.
        Chat().user(Part.CacheBreakpoint).user("Hi") ->
          "one of its messages says nothing",
        Chat().assistant("Bonjour.") -> "the user says nothing in it",
      )
    do
      assertEquals(
        never.ask[String](chat).attempt.unsafeRunSync(),
        Left(LlmError.Unsendable("Anthropic", why)),
        chat,
      )

  /** Whether a reply fails an ask of a type at once. */
  private def failsAtOnceAs[A : Schema](answer: String, detail: String): Unit =
    asked[A](client(_ => answer)) match
      case Left(LlmError.Unreadable(attempts, said, answers)) => assertEquals(
          (attempts, said, answers.size),
          (1, detail, 1),
        )
      case other => fail(s"Expected the ask to fail at once, not $other.")

  test("the request after a tool's result carries it, then the instructions"):
    val calling  = Part.ToolCall("call_1", "weather", Json.obj())
    val answered = Chat()
      .user("What is the weather?")
      .assistant(Answer(
        "",
        StopReason.ToolCall,
        toolCalls = List(calling),
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
        .map(_.value)
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
    for (model, body, cost) <- List(
        (LlmModel.Gpt5, openAi, "0.00003625"),
        (LlmModel.Gemini2_5Flash, gemini, "0.000009"),
      )
    do
      assertEquals(
        answering(model, body).ask[Person](prompt).attempt.unsafeRunSync(),
        Right(Answer(
          adaJson,
          StopReason.Completed,
          Usage(uncachedTokens = 5, visibleTokens = 3),
          Some(Usd(BigDecimal(cost))),
          value = Some(ada),
        )),
        model,
      )

  test("a model iris does not list costs nothing known, unless configured"):
    val next = LlmModel.of(LlmProvider.Anthropic, "claude-next").get
    def costing(configured: LlmConfig) = LlmClient[SyncIO](
      configured,
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespond(ResponseStub.adjust(
          said(adaJson, cost = Some((1000, 100))),
        )),
    ).ask[Person](prompt).map(_.cost).attempt.unsafeRunSync()
    assertEquals(
      costing(config.copy(model = next)),
      Right(None),
    )
    val rates = Rates.of(
      Usd(BigDecimal(3)),
      Usd(BigDecimal(15)),
    )
    assertEquals(
      costing(config.copy(model = next, rates = Some(rates))),
      Right(Some(Usd(BigDecimal("0.0045")))),
    )
    // A configuration's rates stand in for those listed, too.
    assertEquals(
      costing(config.copy(rates = Some(rates))),
      Right(Some(Usd(BigDecimal("0.0045")))),
    )
