package com.alecdorrington.iris

import cats.~>
import cats.arrow.FunctionK
import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.{config, json}
import io.circe.Json
import munit.FunSuite
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

class CacheSuite extends FunSuite:

  private val options = ModelRequest.Options()

  private val chat = Chat()
    .withInstructions("Be brief.")
    .userSays(
      MessagePart.Text("A long document."),
      MessagePart.CacheBreakpoint,
      MessagePart.Text("A question about it."),
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

  /** The system message, and each message's content, Anthropic is sent. */
  private def sent(chat: Chat): (Option[Json], List[Json]) =
    val body = json(AnthropicApi.body(config, chat, options)).hcursor
    (
      body.downField("system").focus,
      body
        .get[List[Json]]("messages")
        .map(_.flatMap(_.hcursor.downField("content").focus))
        .getOrElse(List.empty),
    )

  test(
    "anthropic marks the block before a breakpoint, else the system message",
  ):
    val opening = List[MessagePart](
      MessagePart.CacheBreakpoint,
      MessagePart.Text("Hi"),
    )
    List[(Chat, Option[Json], List[Json])](
      (
        chat,
        Some(Json.fromString("Be brief.")),
        List(Json.arr(
          marked("A long document."),
          text("\n\nA question about it."),
        )),
      ),
      // A breakpoint opening the first message marks the system message.
      (
        Chat().withInstructions("Be brief.").userSays(opening*),
        Some(Json.arr(marked("Be brief."))),
        List(Json.fromString("Hi")),
      ),
      (
        Chat().userSays("Hello").modelSays("Hi!").userSays(opening*),
        None,
        List(
          Json.fromString("Hello"),
          Json.arr(marked("Hi!")),
          Json.fromString("Hi"),
        ),
      ),
      // A breakpoint with nothing at all before it marks nothing.
      (Chat().userSays(opening*), None, List(Json.fromString("Hi"))),
    ).foreach: (asked, system, messages) =>
      assertEquals(sent(asked), (system, messages), asked)

  test("a cached block is sent alike whatever follows it"):
    val warmed = json(AnthropicApi.body(config, chat.cachePrefix, options))
    val asked  = json(AnthropicApi.body(config, chat, options))
    assertEquals(
      content(warmed, 0).hcursor.downN(0).focus,
      content(asked, 0).hcursor.downN(0).focus,
    )

  test("anthropic refuses to mark more blocks than it keeps, not breakpoints"):
    val many = Chat().userSays(
      List
        .fill(AnthropicApi.maxBreakpoints + 1)(List(
          MessagePart.Text("x"),
          MessagePart.CacheBreakpoint,
        ))
        .flatten*,
    )
    assert(AnthropicApi.refuseExcessBreakpoints(many).isLeft)
    val breakpoints =
      List.fill(AnthropicApi.maxBreakpoints + 1)(MessagePart.CacheBreakpoint)
    val leading = Chat().userSays(breakpoints :+ MessagePart.Text("x")*)
    List(
      chat,
      Chat().userSays(MessagePart.Text("x") +: breakpoints*),
      leading,
      leading.withInstructions("Be brief."),
    ).foreach(marks =>
      assert(
        AnthropicApi.refuseExcessBreakpoints(marks).isRight,
        marks,
      ),
    )

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
      .modelSays("An answer.")
      .userSays(
        MessagePart.Text("Another."),
        MessagePart.CacheBreakpoint,
        MessagePart.Text("More."),
      )
    assertEquals(
      longer.cachePrefix,
      Chat(
        chat.messages :+ Message(Role.Model, "An answer.") :+ Message(
          Role.User,
          List(
            MessagePart.Text("Another."),
            MessagePart.CacheBreakpoint,
          ),
        ),
        chat.instructions,
      ),
    )
    assertEquals(
      chat.cachePrefix.messages,
      List(Message(
        Role.User,
        List(
          MessagePart.Text("A long document."),
          MessagePart.CacheBreakpoint,
        ),
      )),
    )
    assertEquals(
      Chat().userSays("Hi").cachePrefix.messages,
      Nil,
    )
    // A breakpoint opening a message after the user's closes theirs.
    assertEquals(
      Chat()
        .userSays("Hello")
        .modelSays(
          MessagePart.CacheBreakpoint,
          MessagePart.Text("Hi!"),
        )
        .userSays("How are you?")
        .cachePrefix
        .messages,
      List(Message(
        Role.User,
        List(
          MessagePart.Text("Hello"),
          MessagePart.CacheBreakpoint,
        ),
      )),
    )

  test("a prefix not ending with the user saying something is followed by it"):
    val opening = Chat()
      .userSays("Hello")
      .modelSays("Hi!")
      .userSays(
        MessagePart.CacheBreakpoint,
        MessagePart.Text("How are you?"),
      )
    val closing = Chat()
      .userSays("Hello")
      .modelSays(
        MessagePart.Text("Hi!"),
        MessagePart.CacheBreakpoint,
      )
      .userSays("How are you?")
    List(opening, closing).foreach: whole =>
      val prefix = whole.cachePrefix
      assertEquals(
        prefix.messages.map(_.role),
        List(Role.User, Role.Model, Role.User),
      )
      assert(
        JsonHttp.refuseUnanswerable(ModelProvider.Anthropic, prefix).isRight,
      )
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

  /** A client answering `body` to the requests it accepts, refusing others. */
  private def expecting
    (config: ModelConfig, body: String)
    (accepts: Json => Boolean)
    : ModelClient[SyncIO] = ModelClient[SyncIO](
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

  test("each provider warms a prefix asking for as little as it allows"):
    List(
      // Anthropic writes its cache without replying when allowed no tokens.
      (
        config,
        """{"content":[],"stop_reason":"max_tokens",
           "usage":{"input_tokens":0,"output_tokens":0,
                    "cache_creation_input_tokens":900}}""",
        "max_tokens",
        0,
        Usage(cacheWriteTokens = 900),
      ),
      (
        config.copy(model = Model.Gpt5),
        """{"choices":[{"message":{"content":""},"finish_reason":"length"}],
           "usage":{"prompt_tokens":900,"completion_tokens":1}}""",
        "max_completion_tokens",
        1,
        Usage(uncachedTokens = 900, visibleTokens = 1),
      ),
    ).foreach: (configured, reply, limit, least, usage) =>
      val warming = expecting(configured, reply)(body =>
        body.hcursor.get[Int](limit).toOption.contains(least) &&
        prefixOnly(body),
      )
      // The reply is passed back, with what writing the cache cost.
      assertEquals(
        warming
          .warmCache(ModelRequest(chat))
          .attempt
          .unsafeRunSync()
          .map(_.usage),
        Right(usage),
        configured.provider,
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
      new ModelClient.Forwarding(warming) {}
        .warmCache(ModelRequest(chat))
        .attempt
        .unsafeRunSync()
        .isRight,
    )
    assert(
      warming
        .mapK(FunctionK.id)
        .warmCache(ModelRequest(chat))
        .attempt
        .unsafeRunSync()
        .isRight,
    )
    assert(
      warming
        .mapK(refusing)
        .warmCache(ModelRequest(chat))
        .attempt
        .unsafeRunSync()
        .isLeft,
    )

  /** A chat whose leading breakpoint caches the system message alone. */
  private val briefed = Chat()
    .withInstructions("A long document.")
    .userSays(
      MessagePart.CacheBreakpoint,
      MessagePart.Text("A question about it."),
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
      config.copy(model = Model.Gpt5),
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body.hcursor.downField("messages").downN(1).succeeded,
    )
    val gemini = expecting(
      config.copy(model = Model.Gemini2_5Flash),
      """{"candidates":[{"content":{"parts":[{"text":""}]},
         "finishReason":"MAX_TOKENS"}]}""",
    )(briefOnly(_, "contents", "parts"))
    List(anthropic, openAi, gemini).foreach: client =>
      assert(
        client.warmCache(ModelRequest(briefed)).attempt.unsafeRunSync().isRight,
      )

  test("a chat with nothing marked has nothing to warm"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(_ => true)
    assert(
      warming
        .warmCache(ModelRequest(Chat().userSays("Hi")))
        .attempt
        .unsafeRunSync()
        .isLeft,
    )
