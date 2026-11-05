package com.alecdorrington.iris

import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.{json, model, prompting, settings}
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import fs2.Stream
import io.circe.{ACursor, Json}
import munit.FunSuite
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

class CacheSuite extends FunSuite:

  private val options =
    settings(PromptRequest(Conversation(), streamed = false))

  private val conversation = Conversation()
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
  private def sent(conversation: Conversation): (Option[Json], List[Json]) =
    val body = Anthropic.body(model, conversation, options).hcursor
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
    val opening = List[UserPart](
      MessagePart.CacheBreakpoint,
      MessagePart.Text("Hi"),
    )
    List[(Conversation, Option[Json], List[Json])](
      (
        conversation,
        Some(Json.fromString("Be brief.")),
        List(Json.arr(
          marked("A long document."),
          text("\n\nA question about it."),
        )),
      ),
      // A breakpoint opening the first message marks the system message.
      (
        Conversation().withInstructions("Be brief.").userSays(opening*),
        Some(Json.arr(marked("Be brief."))),
        List(Json.fromString("Hi")),
      ),
      (
        Conversation().userSays("Hello").modelSays("Hi!").userSays(opening*),
        None,
        List(
          Json.fromString("Hello"),
          Json.arr(marked("Hi!")),
          Json.fromString("Hi"),
        ),
      ),
      // A breakpoint with nothing at all before it marks nothing.
      (Conversation().userSays(opening*), None, List(Json.fromString("Hi"))),
    ).foreach: (asked, system, messages) =>
      assertEquals(sent(asked), (system, messages), asked)

  test("a cached block is sent alike whatever follows it"):
    val warmed = Anthropic.body(
      model,
      conversation.cachePrefix,
      options,
    )
    val asked = Anthropic.body(model, conversation, options)
    assertEquals(
      content(warmed, 0).hcursor.downN(0).focus,
      content(asked, 0).hcursor.downN(0).focus,
    )

  test("anthropic refuses to mark more blocks than it keeps, not breakpoints"):
    val many = Conversation().userSays(
      List
        .fill(Anthropic.maxBreakpoints + 1)(List(
          MessagePart.Text("x"),
          MessagePart.CacheBreakpoint,
        ))
        .flatten :+ MessagePart.Text("x")*,
    )
    assert(Anthropic.refuseExcessBreakpoints(many).isLeft)
    val breakpoints =
      List.fill(Anthropic.maxBreakpoints + 1)(MessagePart.CacheBreakpoint)
    val leading = Conversation().userSays(breakpoints :+ MessagePart.Text("x")*)
    List(
      conversation,
      Conversation().userSays(MessagePart.Text("x") +: breakpoints*),
      leading,
      leading.withInstructions("Be brief."),
    ).foreach(marks =>
      assert(
        Anthropic.refuseExcessBreakpoints(marks).isRight,
        marks,
      ),
    )

  test(
    "anthropic marks the block breakpoints closing a conversation follow if it can",
  ):
    // The host's breakpoints, then two in a row closing the conversation, as an ask
    // closes each request after a round of tools.
    def closed(own: Int): Conversation = Conversation().userSays(
      (1 to own)
        .toList
        .flatMap(n =>
          List(
            MessagePart.Text(n.toString),
            MessagePart.CacheBreakpoint,
          ),
        ) ++ List(
          MessagePart.Text("Last."),
          MessagePart.CacheBreakpoint,
          MessagePart.CacheBreakpoint,
        )*,
    )
    def blocks(own: Int, last: Boolean): Json = Json.arr(
      (1 to own).toList.map(n => marked(if n == 1 then "1" else s"\n\n$n")) :+
        (if last then marked("\n\nLast.") else text("\n\nLast."))*,
    )
    val most = Anthropic.maxBreakpoints
    assertEquals(
      sent(closed(most - 1)),
      (None, List(blocks(most - 1, true))),
    )
    assertEquals(
      sent(closed(most)),
      (None, List(blocks(most, false))),
    )
    assert(Anthropic.refuseExcessBreakpoints(closed(most)).isRight)
    assert(Anthropic.refuseExcessBreakpoints(closed(most + 1)).isLeft)

  test("openai and gemini are sent no breakpoints"):
    val openAi = OpenAi.body(model, conversation, options)
    assertEquals(
      content(openAi, 1),
      Json.fromString("A long document.\n\nA question about it."),
    )
    val gemini = Gemini.body(model, conversation, options)
    assertEquals(
      gemini.hcursor.downField("contents").downN(0).downField("parts").focus,
      Some(Json.arr(
        Json.obj("text" -> Json.fromString("A long document.")),
        Json.obj("text" -> Json.fromString("\n\nA question about it.")),
      )),
    )

  test("a conversation's cacheable part runs to its last breakpoint"):
    val longer = conversation
      .modelSays("An answer.")
      .userSays(
        MessagePart.Text("Another."),
        MessagePart.CacheBreakpoint,
        MessagePart.Text("More."),
      )
    assertEquals(
      longer.cachePrefix,
      Conversation(
        conversation.messages :+
          Message.Model(List(MessagePart.Text("An answer."))) :+
          Message.User(List(
            MessagePart.Text("Another."),
            MessagePart.CacheBreakpoint,
          )),
        conversation.instructions,
      ),
    )
    assertEquals(
      conversation.cachePrefix.messages,
      List(Message.User(List(
        MessagePart.Text("A long document."),
        MessagePart.CacheBreakpoint,
      ))),
    )
    assertEquals(
      Conversation().userSays("Hi").cachePrefix.messages,
      Nil,
    )
    // A breakpoint opening a message after the user's closes theirs.
    assertEquals(
      Conversation()
        .userSays("Hello")
        .modelSays(
          MessagePart.CacheBreakpoint,
          MessagePart.Text("Hi!"),
        )
        .userSays("How are you?")
        .cachePrefix
        .messages,
      List(Message.User(List(
        MessagePart.Text("Hello"),
        MessagePart.CacheBreakpoint,
      ))),
    )

  /** Who owns a message. */
  private def owner(message: Message): String = message match
    case _: Message.User  => "user"
    case _: Message.Model => "model"
    case _: Message.Tool  => "tool"

  test("a prefix not ending with the user saying something is followed by it"):
    val opening = Conversation()
      .userSays("Hello")
      .modelSays("Hi!")
      .userSays(
        MessagePart.CacheBreakpoint,
        MessagePart.Text("How are you?"),
      )
    val closing = Conversation()
      .userSays("Hello")
      .modelSays(
        MessagePart.Text("Hi!"),
        MessagePart.CacheBreakpoint,
      )
      .userSays("How are you?")
    List(opening, closing).foreach: whole =>
      val prefix = whole.cachePrefix
      assertEquals(
        prefix.messages.map(owner),
        List("user", "model", "user"),
      )
      assert(JsonHttp.refuseUnanswerable(Anthropic, prefix).isRight)
      assert(!prefix.messages.map(_.text).mkString.contains("How are you?"))
      assertEquals(
        content(
          Anthropic.body(model, prefix, options),
          1,
        ),
        content(Anthropic.body(model, whole, options), 1),
      )

  /** A client answering `body` to the requests it accepts, refusing others. */
  private def expecting
    (model: ProviderModel, body: String)
    (accepts: Json => Boolean)
    : PromptService[SyncIO] = prompting(
    BackendStub(CatsMonadError[SyncIO]())
      .whenRequestMatches(request =>
        request.body match
          case StringBody(sent, _, _) => accepts(json(sent))
          case _                      => false,
      )
      .thenRespond(ResponseStub.adjust(body, StatusCode.Ok))
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust("unexpected", StatusCode.BadRequest)),
    model,
  )

  /** The token limit a body asks for, where the path through it leads. */
  private def limit(body: Json, path: List[String]): Option[Int] = path
    .foldLeft(body.hcursor: ACursor)(_.downField(_))
    .as[Int]
    .toOption

  private def prefixOnly(body: Json): Boolean =
    val sent = body.noSpaces
    sent.contains("A long document.") && !sent.contains("A question about it.")

  test("each provider warms a prefix asking for as little as it allows"):
    List(
      // Anthropic writes its cache without replying when allowed no tokens.
      (
        model,
        """{"content":[],"stop_reason":"max_tokens",
           "usage":{"input_tokens":0,"output_tokens":0,
                    "cache_creation_input_tokens":900}}""",
        List("max_tokens"),
        0,
        Usage(cacheWriteTokens = 900),
      ),
      (
        OpenAiModel.Gpt5,
        """{"choices":[{"message":{"content":""},"finish_reason":"length"}],
           "usage":{"prompt_tokens":900,"completion_tokens":1}}""",
        List("max_completion_tokens"),
        1,
        Usage(uncachedTokens = 900, visibleTokens = 1),
      ),
      (
        GeminiModel.Gemini2_5Flash,
        """{"candidates":[{"content":{"parts":[{"text":""}]},
           "finishReason":"MAX_TOKENS"}],
           "usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":1}}""",
        List("generationConfig", "maxOutputTokens"),
        1,
        Usage(uncachedTokens = 900, visibleTokens = 1),
      ),
    ).foreach: (model, reply, path, least, usage) =>
      val warming = expecting(model, reply)(body =>
        limit(body, path).contains(least) && prefixOnly(body),
      )
      // The reply is passed back, with what writing the cache cost.
      assertEquals(
        warming.warmCache(conversation).attempt.unsafeRunSync().map(_.usage),
        Right(usage),
        model.provider,
      )

  test("a request allowed no tokens is allowed the fewest its provider takes"):
    List(
      (
        model,
        """{"content":[],"stop_reason":"max_tokens"}""",
        List("max_tokens"),
        0,
      ),
      (
        OpenAiModel.Gpt5,
        """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
        List("max_completion_tokens"),
        1,
      ),
    ).foreach: (model, reply, path, least) =>
      assert(
        expecting(model, reply)(limit(_, path).contains(least))
          .send(PromptRequest(conversation, maxTokens = Some(0)))
          .attempt
          .unsafeRunSync()
          .isRight,
        model,
      )

  test("a wrapped service warms as the service it wraps does"):
    val warming = expecting(
      model,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(body => limit(body, List("max_tokens")).contains(0) && prefixOnly(body))
    val forwarding: PromptService[SyncIO] = warming.stream(_)
    val refusing: PromptService[SyncIO]   =
      _ => Stream.raiseError[SyncIO](Exception("Refused."))
    List(
      forwarding,
      warming.metered((_, _) => SyncIO.unit),
    ).foreach(wrapped =>
      assert(wrapped.warmCache(conversation).attempt.unsafeRunSync().isRight),
    )
    assert(refusing.warmCache(conversation).attempt.unsafeRunSync().isLeft)

  /** A conversation whose leading breakpoint caches the system message alone. */
  private val briefed = Conversation()
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
      model,
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
      OpenAiModel.Gpt5,
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body.hcursor.downField("messages").downN(1).succeeded,
    )
    val gemini = expecting(
      GeminiModel.Gemini2_5Flash,
      """{"candidates":[{"content":{"parts":[{"text":""}]},
         "finishReason":"MAX_TOKENS"}]}""",
    )(briefOnly(_, "contents", "parts"))
    List(anthropic, openAi, gemini).foreach: client =>
      assert(client.warmCache(briefed).attempt.unsafeRunSync().isRight)

  test("a conversation with nothing marked has nothing to warm"):
    val warming = expecting(
      model,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(_ => true)
    assert(
      warming
        .warmCache(Conversation().userSays("Hi"))
        .attempt
        .unsafeRunSync()
        .isLeft,
    )
