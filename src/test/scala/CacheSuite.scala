package com.alecdorrington.iris

import cats.~>
import cats.arrow.FunctionK
import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.{answer, config, json}
import io.circe.Json
import munit.FunSuite
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

class CacheSuite extends FunSuite:

  private val options = ReplyOptions()

  private val chat = Chat()
    .withSystem("Be brief.")
    .user(
      Part.Text("A long document."),
      Part.CacheBreakpoint,
      Part.Text("A question about it."),
    )

  private def content(body: Json, message: Int): Json = body
    .hcursor
    .downField("messages")
    .downN(message)
    .downField("content")
    .focus
    .get

  private val ephemeral = Json.obj("type" -> Json.fromString("ephemeral"))

  private def text(text: String): Json = Json.obj(
    "type" -> Json.fromString("text"),
    "text" -> Json.fromString(text),
  )

  private def marked(text: String): Json = this
    .text(text)
    .deepMerge(Json.obj("cache_control" -> ephemeral))

  test("anthropic marks the block just before a breakpoint"):
    val body = json(AnthropicApi.body(config, chat, options))
    assertEquals(
      content(body, 0),
      Json.arr(
        marked("A long document."),
        text("\n\nA question about it."),
      ),
    )
    assertEquals(
      body.hcursor.get[String]("system").toOption,
      Some("Be brief."),
    )

  test("a cached block is sent alike whatever follows it"):
    val warmed = json(AnthropicApi.body(config, chat.cacheable, options))
    val asked  = json(AnthropicApi.body(config, chat, options))
    assertEquals(
      content(warmed, 0).hcursor.downN(0).focus,
      content(asked, 0).hcursor.downN(0).focus,
    )

  test("a breakpoint opening the first message marks the system message"):
    val body = json(AnthropicApi.body(
      config,
      Chat()
        .withSystem("Be brief.")
        .user(Part.CacheBreakpoint, Part.Text("Hi")),
      options,
    ))
    assertEquals(
      body.hcursor.downField("system").focus,
      Some(Json.arr(marked("Be brief."))),
    )
    assertEquals(content(body, 0), Json.fromString("Hi"))

  test("a breakpoint opening a later message marks the one before it"):
    val body = json(AnthropicApi.body(
      config,
      Chat()
        .user("Hello")
        .assistant("Hi!")
        .user(
          Part.CacheBreakpoint,
          Part.Text("How are you?"),
        ),
      options,
    ))
    assertEquals(
      content(body, 1),
      Json.arr(marked("Hi!")),
    )
    assertEquals(
      content(body, 2),
      Json.fromString("How are you?"),
    )

  test("a breakpoint with nothing at all before it marks nothing"):
    val body = json(AnthropicApi.body(
      config,
      Chat().user(Part.CacheBreakpoint, Part.Text("Hi")),
      options,
    ))
    assert(body.hcursor.downField("system").failed)
    assertEquals(content(body, 0), Json.fromString("Hi"))

  test("anthropic refuses more breakpoints than it keeps"):
    val many = Chat().user(
      List
        .fill(AnthropicApi.maxBreakpoints + 1)(
          List(Part.Text("x"), Part.CacheBreakpoint),
        )
        .flatten*,
    )
    assert(AnthropicApi.markable(many).isLeft)
    assert(AnthropicApi.markable(chat).isRight)

  test("anthropic counts the blocks breakpoints mark, not the breakpoints"):
    val breakpoints =
      List.fill(AnthropicApi.maxBreakpoints + 1)(Part.CacheBreakpoint)
    val together = Chat().user(Part.Text("x") +: breakpoints*)
    val leading  = Chat().user(breakpoints :+ Part.Text("x")*)
    assert(AnthropicApi.markable(together).isRight)
    assert(AnthropicApi.markable(leading).isRight)
    assert(AnthropicApi.markable(leading.withSystem("Be brief.")).isRight)

  test("openai and gemini are sent no breakpoints"):
    val openAi = json(OpenAiApi.body(config, chat, options))
    assertEquals(
      content(openAi, 1),
      Json.fromString("A long document.\n\nA question about it."),
    )
    val gemini = json(GeminiApi.body(config, chat, options))
    assertEquals(
      gemini.hcursor.downField("contents").downN(0).downField("parts").focus,
      Some(Json.arr(
        Json.obj("text" -> Json.fromString("A long document.")),
        Json.obj("text" -> Json.fromString("\n\nA question about it.")),
      )),
    )

  test("a chat's cacheable part runs to its last breakpoint"):
    val longer = chat
      .assistant("An answer.")
      .user(
        Part.Text("Another."),
        Part.CacheBreakpoint,
        Part.Text("More."),
      )
    assertEquals(
      longer.cacheable,
      Chat(
        chat.messages :+ Message(Role.Assistant, "An answer.") :+ Message(
          Role.User,
          List(
            Part.Text("Another."),
            Part.CacheBreakpoint,
          ),
        ),
        chat.system,
      ),
    )
    assertEquals(
      chat.cacheable.messages,
      List(Message(
        Role.User,
        List(
          Part.Text("A long document."),
          Part.CacheBreakpoint,
        ),
      )),
    )
    assertEquals(
      Chat().user("Hi").cacheable.messages,
      Nil,
    )

  test("a prefix not ending with the user saying something is followed by it"):
    val opening = Chat()
      .user("Hello")
      .assistant("Hi!")
      .user(
        Part.CacheBreakpoint,
        Part.Text("How are you?"),
      )
    val closing = Chat()
      .user("Hello")
      .assistant(Part.Text("Hi!"), Part.CacheBreakpoint)
      .user("How are you?")
    List(opening, closing).foreach: whole =>
      val prefix = whole.cacheable
      assertEquals(
        prefix.messages.map(_.role),
        List(Role.User, Role.Assistant, Role.User),
      )
      assert(JsonHttp.answerable(LlmProvider.Anthropic, prefix).isRight)
      assert(!prefix.messages.map(_.text).mkString.contains("How are you?"))
      assertEquals(
        content(
          json(AnthropicApi.body(config, prefix, options)),
          1,
        ),
        content(
          json(AnthropicApi.body(config, whole, options)),
          1,
        ),
      )

  test("a breakpoint opening a message after the user's closes theirs"):
    val whole = Chat()
      .user("Hello")
      .assistant(Part.CacheBreakpoint, Part.Text("Hi!"))
      .user("How are you?")
    assertEquals(
      whole.cacheable.messages,
      List(Message(
        Role.User,
        List(
          Part.Text("Hello"),
          Part.CacheBreakpoint,
        ),
      )),
    )

  test("anthropic reports cache reads and writes as input"):
    val body = """{"content":[{"type":"text","text":"Hi"}],
         "stop_reason":"end_turn",
         "usage":{"input_tokens":10,"output_tokens":5,
                  "cache_creation_input_tokens":20,
                  "cache_read_input_tokens":30}}"""
    assertEquals(
      answer(AnthropicApi, body).map(_.usage),
      Right(Some(Usage(60, 5, 30))),
    )

  test("openai reports the prompt tokens read from its cache"):
    val body =
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":40,"completion_tokens":2,
                  "prompt_tokens_details":{"cached_tokens":32}}}"""
    assertEquals(
      answer(OpenAiApi, body).map(_.usage),
      Right(Some(Usage(40, 2, 32))),
    )

  test("gemini reports the prompt tokens read from its cache"):
    val body = """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":2,
                          "cachedContentTokenCount":32}}"""
    assertEquals(
      answer(GeminiApi, body).map(_.usage),
      Right(Some(Usage(40, 2, 32))),
    )

  /** A client answering `body` to the requests it accepts, refusing others. */
  private def expecting
    (config: LlmConfig, body: String)
    (accepts: Json => Boolean)
    : LlmClient[SyncIO] = LlmClient[SyncIO](
    config,
    BackendStub(CatsMonadError[SyncIO]())
      .whenRequestMatches(request =>
        request.body match
          case StringBody(sent, _, _) => accepts(json(sent))
          case _                      => false,
      )
      .thenRespond(ResponseStub.adjust(body, StatusCode.Ok))
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust("unexpected", StatusCode.BadRequest)),
  )

  private def prefixOnly(body: Json): Boolean =
    val sent = body.noSpaces
    sent.contains("A long document.") && !sent.contains("A question about it.")

  test("anthropic warms a prefix without asking for a reply"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens",
         "usage":{"input_tokens":0,"output_tokens":0,
                  "cache_creation_input_tokens":900}}""",
    )(body =>
      body.hcursor.get[Int]("max_tokens").toOption.contains(0) &&
      prefixOnly(body),
    )
    assertEquals(
      warming.warm(chat).attempt.unsafeRunSync().map(_.usage),
      Right(Some(Usage(900, 0))),
    )

  test("a wrapped client warms as the client it wraps does"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(body =>
      body.hcursor.get[Int]("max_tokens").toOption.contains(0) &&
      prefixOnly(body),
    )
    val refusing = new (SyncIO ~> SyncIO):
      override def apply[A](request: SyncIO[A]): SyncIO[A] =
        SyncIO.raiseError(Exception("Refused."))
    assert(
      new LlmClient.Forwarding(warming) {}
        .warm(chat)
        .attempt
        .unsafeRunSync()
        .isRight,
    )
    assert(
      warming.mapK(FunctionK.id).warm(chat).attempt.unsafeRunSync().isRight,
    )
    assert(warming.mapK(refusing).warm(chat).attempt.unsafeRunSync().isLeft)

  test("other providers warm a prefix asking for as little as they allow"):
    val warming = expecting(
      config.copy(model = LlmModel.Gpt5),
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}],
         "usage":{"prompt_tokens":900,"completion_tokens":1}}""",
    )(body =>
      body.hcursor.get[Int]("max_completion_tokens").toOption.contains(1) &&
      prefixOnly(body),
    )
    assert(warming.warm(chat).attempt.unsafeRunSync().isRight)

  /** A chat whose leading breakpoint caches the system message alone. */
  private val briefed = Chat()
    .withSystem("A long document.")
    .user(
      Part.CacheBreakpoint,
      Part.Text("A question about it."),
    )

  /**
    * Whether a body carries only [[briefed]]'s system message, with each
    * message under `messages` saying something in its `parts`.
    */
  private def briefOnly
    (
      body: Json,
      messages: String,
      parts: String,
    )
    : Boolean =
    val sent = body.noSpaces
    sent.contains("A long document.") &&
    !sent.contains("A question about it.") &&
    body
      .hcursor
      .downField(messages)
      .as[List[Json]]
      .exists(all =>
        all.nonEmpty && all.forall(
          _.hcursor
            .downField(parts)
            .focus
            .exists(said =>
              said.asString.exists(_.nonEmpty) ||
              said.asArray.exists(_.nonEmpty),
            ),
        ),
      )

  test("a breakpoint opening the first message warms the system message"):
    val anthropic = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body
        .hcursor
        .downField("system")
        .focus
        .contains(Json.arr(marked("A long document."))),
    )
    val openAi = expecting(
      config.copy(model = LlmModel.Gpt5),
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body.hcursor.downField("messages").downN(1).succeeded,
    )
    val gemini = expecting(
      config.copy(model = LlmModel.Gemini2_5Flash),
      """{"candidates":[{"content":{"parts":[{"text":""}]},
         "finishReason":"MAX_TOKENS"}]}""",
    )(briefOnly(_, "contents", "parts"))
    List(anthropic, openAi, gemini).foreach: client =>
      assert(client.warm(briefed).attempt.unsafeRunSync().isRight)

  test("a chat with nothing marked has nothing to warm"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(_ => true)
    assert(warming.warm(Chat().user("Hi")).attempt.unsafeRunSync().isLeft)
