package com.alecdorrington.iris

import com.alecdorrington.iris.Fixtures.{answer, config, json}
import io.circe.Json
import munit.FunSuite
import scala.concurrent.duration.DurationInt

class AdapterSuite extends FunSuite:

  private val gemini = config.copy(model = LlmModel.Gemini2_5Flash)

  private val options = ReplyOptions()

  /** An Anthropic model Iris does not list. */
  private val unlisted = LlmModel.of(LlmProvider.Anthropic, "claude-new").get

  private val chat = Chat()
    .withSystem("Be brief.")
    .user("Hello")
    .assistant("Hi!")
    .user("How are you?")

  private def roles(json: Json, field: String): Option[List[String]] = json
    .hcursor
    .downField(field)
    .as[List[Json]]
    .toOption
    .map(_.flatMap(_.hcursor.get[String]("role").toOption))

  test("anthropic requests include history, system and tuning options"):
    val body = json(AnthropicApi.body(
      config,
      chat,
      ReplyOptions(
        temperature = Some(0.5),
        stopSequences = List("END"),
      ),
    ))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("claude-haiku-4-5"),
    )
    assertEquals(
      body.hcursor.get[Int]("max_tokens").toOption,
      Some(512),
    )
    assertEquals(
      body.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )
    assertEquals(
      body.hcursor.get[String]("system").toOption,
      Some("Be brief."),
    )
    assertEquals(
      body.hcursor.get[List[String]]("stop_sequences").toOption,
      Some(List("END")),
    )
    assertEquals(
      roles(body, "messages"),
      Some(List("user", "assistant", "user")),
    )

  test("unset options are omitted from request bodies"):
    val body = json(AnthropicApi.body(config, chat, ReplyOptions()))
    assert(body.hcursor.downField("temperature").failed)
    assert(body.hcursor.downField("top_p").failed)
    assert(body.hcursor.downField("stop_sequences").failed)
    val generation = json(GeminiApi.body(config, chat, options))
      .hcursor
      .downField("generationConfig")
    assert(generation.downField("maxOutputTokens").succeeded)
    assert(generation.downField("temperature").failed)
    assert(generation.downField("topP").failed)
    assert(generation.downField("stopSequences").failed)

  test("anthropic omits sampling options for models that reject them"):
    val body = json(AnthropicApi.body(
      config.copy(model = LlmModel.ClaudeSonnet5),
      chat,
      ReplyOptions(temperature = Some(0.5), topP = Some(0.9)),
    ))
    assert(body.hcursor.downField("temperature").failed)
    assert(body.hcursor.downField("top_p").failed)

  test("anthropic omits sampling options for a model iris does not list"):
    val body = json(AnthropicApi.body(
      config.copy(model = unlisted),
      chat,
      ReplyOptions(temperature = Some(0.5)),
    ))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("claude-new"),
    )
    assert(body.hcursor.downField("temperature").failed)

  test("anthropic omits sampling options for mythos 5"):
    val body = json(AnthropicApi.body(
      config.copy(model = LlmModel.ClaudeMythos5),
      chat,
      ReplyOptions(temperature = Some(0.5)),
    ))
    assert(body.hcursor.downField("temperature").failed)

  test("a listed model named with its provider samples as the listed one does"):
    val body = json(AnthropicApi.body(
      config.copy(model =
        LlmModel
          .of(
            LlmProvider.Anthropic,
            "claude-haiku-4-5",
          )
          .get,
      ),
      chat,
      ReplyOptions(temperature = Some(0.5)),
    ))
    assertEquals(
      body.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )

  test("anthropic keeps sampling options for models that accept them"):
    val body = json(AnthropicApi.body(
      config.copy(model = LlmModel.ClaudeHaiku4_5),
      chat,
      ReplyOptions(temperature = Some(0.5), topP = Some(0.9)),
    ))
    assertEquals(
      body.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )
    assertEquals(
      body.hcursor.get[Double]("top_p").toOption,
      Some(0.9),
    )

  test("an effort is sent as each provider asks for one"):
    val thoughtful = ReplyOptions(effort = Some(Effort.Medium))
    assertEquals(
      json(AnthropicApi.body(config, chat, thoughtful))
        .hcursor
        .downField("output_config")
        .get[String]("effort")
        .toOption,
      Some("medium"),
    )
    assertEquals(
      json(OpenAiApi.body(config, chat, thoughtful))
        .hcursor
        .get[String]("reasoning_effort")
        .toOption,
      Some("medium"),
    )
    assertEquals(
      json(GeminiApi.body(gemini, chat, thoughtful))
        .hcursor
        .downField("generationConfig")
        .downField("thinkingConfig")
        .get[Int]("thinkingBudget")
        .toOption,
      Some(4_096),
    )

  /** The reasoning effort OpenAI is asked of a model for the most effort. */
  private def most(model: LlmModel): Option[String] = json(OpenAiApi.body(
    config.copy(model = model),
    chat,
    ReplyOptions(effort = Some(Effort.Max)),
  )).hcursor.get[String]("reasoning_effort").toOption

  test("openai is asked for its most effort when asked for the most"):
    assertEquals(most(LlmModel.Gpt6Astra), Some("max"))
    assertEquals(most(LlmModel.Gpt5_6Luna), Some("max"))
    assertEquals(
      most(LlmModel.of(LlmProvider.OpenAi, "gpt-new").get),
      Some("max"),
    )

  test("an openai model going no higher than high is asked for high"):
    assertEquals(most(LlmModel.Gpt5), Some("high"))
    assertEquals(most(LlmModel.Gpt5Mini), Some("high"))

  test("no effort is sent unless one is asked for"):
    assert(
      json(AnthropicApi.body(config, chat, options))
        .hcursor
        .downField("output_config")
        .failed,
    )
    assert(
      json(OpenAiApi.body(config, chat, options))
        .hcursor
        .downField("reasoning_effort")
        .failed,
    )
    assert(
      json(GeminiApi.body(gemini, chat, options))
        .hcursor
        .downField("generationConfig")
        .downField("thinkingConfig")
        .failed,
    )

  private val picture = Part.Media("image/png", "aGVsbG8=")

  private val looking = Chat().user(Part.Text("What is this?"), picture)

  private val paper = Chat().user(Part.Media("application/pdf", "JVBERi0="))

  test("a message of text alone is still sent as plain text"):
    val body  = json(AnthropicApi.body(config, chat, options))
    val first = body.hcursor.downField("messages").downN(0)
    assertEquals(
      first.get[String]("content").toOption,
      Some("Hello"),
    )

  private val named = Chat().user(
    Part.Text("BOOK 1: notes.txt"),
    Part.Text("Chapter one."),
  )

  private def said(body: Json, field: String): Option[String] = body
    .hcursor
    .downField(field)
    .downN(0)
    .get[String]("content")
    .toOption

  test("text parts in a row reach every provider a blank line apart"):
    val apart = "BOOK 1: notes.txt\n\nChapter one."
    assertEquals(
      said(
        json(AnthropicApi.body(config, named, options)),
        "messages",
      ),
      Some(apart),
    )
    assertEquals(
      said(
        json(OpenAiApi.body(config, named, options)),
        "messages",
      ),
      Some(apart),
    )
    assertEquals(
      json(GeminiApi.body(config, named, options))
        .hcursor
        .downField("contents")
        .downN(0)
        .downField("parts")
        .as[List[Json]]
        .map(_.flatMap(_.hcursor.get[String]("text").toOption).mkString),
      Right(apart),
    )

  test("text parts in a row stay apart beside a picture"):
    val shown = named.messages.head.content :+ picture
    val asked = Chat().user(shown*)
    val texts = (body: Json) =>
      body
        .hcursor
        .downField("messages")
        .downN(0)
        .downField("content")
        .as[List[Json]]
        .map(_.flatMap(_.hcursor.get[String]("text").toOption))
    val apart = Right(List(
      "BOOK 1: notes.txt",
      "\n\nChapter one.",
    ))
    assertEquals(
      texts(json(AnthropicApi.body(config, asked, options))),
      apart,
    )
    assertEquals(
      texts(json(OpenAiApi.body(config, asked, options))),
      apart,
    )

  test(
    "a picture keeps text parts apart, while breakpoints and empty text do not",
  ):
    assertEquals(
      Message.separated(List(
        Part.Text("Page 1"),
        picture,
        Part.Text("Page 2"),
        Part.CacheBreakpoint,
        Part.Text(""),
        Part.Text("End."),
      )),
      List(
        Part.Text("Page 1"),
        picture,
        Part.Text("Page 2"),
        Part.CacheBreakpoint,
        Part.Text(""),
        Part.Text("\n\nEnd."),
      ),
    )

  test(
    "openai sends text around a tool call apart, once the call is taken out",
  ):
    val call  = Part.ToolCall("1", "weather", Json.obj())
    val asked = Chat()
      .user("Hi")
      .assistant(
        Part.Text("Looking."),
        call,
        Part.Text("Done."),
      )
    assertEquals(
      json(OpenAiApi.body(config, asked, options))
        .hcursor
        .downField("messages")
        .downN(1)
        .get[String]("content")
        .toOption,
      Some("Looking.\n\nDone."),
    )

  test("a message's text holds each text part as a paragraph of its own"):
    assertEquals(
      Message(
        Role.User,
        List(
          Part.Text("Page 1"),
          picture,
          Part.Text(""),
          Part.Text("End."),
        ),
      ).text,
      "Page 1\n\nEnd.",
    )

  test("anthropic sends a picture beside the text which asks about it"):
    val body  = json(AnthropicApi.body(config, looking, options))
    val parts = body.hcursor.downField("messages").downN(0).downField("content")
    assertEquals(
      parts.downN(0).get[String]("type").toOption,
      Some("text"),
    )
    assertEquals(
      parts.downN(1).get[String]("type").toOption,
      Some("image"),
    )
    assertEquals(
      parts.downN(1).downField("source").get[String]("media_type").toOption,
      Some("image/png"),
    )

  test("anthropic sends anything which is not a picture as a document"):
    val body = json(AnthropicApi.body(config, paper, options))
    assertEquals(
      body
        .hcursor
        .downField("messages")
        .downN(0)
        .downField("content")
        .downN(0)
        .get[String]("type")
        .toOption,
      Some("document"),
    )

  test("openai sends a picture as a data url"):
    val body  = json(OpenAiApi.body(config, looking, options))
    val parts = body.hcursor.downField("messages").downN(0).downField("content")
    assertEquals(
      parts.downN(1).get[String]("type").toOption,
      Some("image_url"),
    )
    assertEquals(
      parts.downN(1).downField("image_url").get[String]("url").toOption,
      Some("data:image/png;base64,aGVsbG8="),
    )

  test("openai says it cannot carry a document in a chat"):
    assertEquals(
      OpenAiApi.pictorial(paper),
      Left(LlmError.Unsupported(
        "OpenAI",
        "sending application/pdf in a chat",
      )),
    )
    assert(OpenAiApi.pictorial(looking).isRight)

  test("gemini sends a picture inline beside its text"):
    val body  = json(GeminiApi.body(config, looking, options))
    val parts = body.hcursor.downField("contents").downN(0).downField("parts")
    assertEquals(
      parts.downN(0).get[String]("text").toOption,
      Some("What is this?"),
    )
    assertEquals(
      parts.downN(1).downField("inline_data").get[String]("mime_type").toOption,
      Some("image/png"),
    )

  test("counting asks for no reply, so needs no limit"):
    val body = json(AnthropicApi.countBody(config, chat))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("claude-haiku-4-5"),
    )
    assert(body.hcursor.downField("max_tokens").failed)
    assertEquals(
      roles(body, "messages"),
      Some(List("user", "assistant", "user")),
    )

  test("gemini counts its system message among its contents"):
    val body = json(GeminiApi.countBody(chat))
    assertEquals(
      roles(body, "contents"),
      Some(List("user", "user", "model", "user")),
    )

  test("openai requests put the system message first"):
    val body = json(OpenAiApi.body(config, chat, ReplyOptions()))
    assertEquals(
      roles(body, "messages"),
      Some(List("system", "user", "assistant", "user")),
    )

  test("gemini requests use the model role and nested parts"):
    val body = json(GeminiApi.body(config, chat, ReplyOptions()))
    assertEquals(
      roles(body, "contents"),
      Some(List("user", "model", "user")),
    )
    assert(body.hcursor.downField("system_instruction").succeeded)
    assertEquals(
      body
        .hcursor
        .downField("generationConfig")
        .get[Int]("maxOutputTokens")
        .toOption,
      Some(512),
    )

  test("per-request options override the configured token limit"):
    val body = json(OpenAiApi.body(
      config,
      chat,
      ReplyOptions(maxTokens = Some(64)),
    ))
    assertEquals(
      body.hcursor.get[Int]("max_completion_tokens").toOption,
      Some(64),
    )

  test("anthropic responses parse into replies"):
    val body = """{"content":[{"type":"text","text":"Hello!"}],
         "stop_reason":"end_turn",
         "usage":{"input_tokens":10,"output_tokens":5}}"""
    assertEquals(
      answer(AnthropicApi, body),
      Right(Reply(
        "Hello!",
        StopReason.Completed,
        Some(Usage(10, 5)),
      )),
    )

  test("openai responses parse into replies"):
    val body =
      """{"choices":[{"message":{"content":"Hello!"},"finish_reason":"length"}],
         "usage":{"prompt_tokens":4,"completion_tokens":2}}"""
    assertEquals(
      answer(OpenAiApi, body),
      Right(Reply(
        "Hello!",
        StopReason.MaxTokens,
        Some(Usage(4, 2)),
      )),
    )

  test("gemini responses parse into replies"):
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}"""
    assertEquals(
      answer(GeminiApi, body),
      Right(Reply(
        "Hello!",
        StopReason.Completed,
        Some(Usage(7, 3)),
      )),
    )

  test("unrecognised stop reasons are preserved"):
    val body = """{"content":[],"stop_reason":"refusal","usage":null}"""
    assertEquals(
      answer(AnthropicApi, body).map(_.stopReason),
      Right(StopReason.Other("refusal")),
    )

  test("a reply with no choices is a malformed openai response"):
    val body = """{"choices":[],"usage":null}"""
    assertEquals(
      answer(OpenAiApi, body),
      Left(LlmError.Malformed("OpenAI", "no choices")),
    )

  test("a blocked gemini prompt is malformed, and says why"):
    val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
    assertEquals(
      answer(GeminiApi, body),
      Left(LlmError.Malformed(
        "Gemini",
        "no candidates, blocked as SAFETY",
      )),
    )

  test("a gemini reply with no candidates is malformed"):
    val body = """{"candidates":[]}"""
    assertEquals(
      answer(GeminiApi, body),
      Left(LlmError.Malformed("Gemini", "no candidates")),
    )

  test("a provider's request to wait is carried, where it gives one"):
    assertEquals(
      JsonHttp.retryAfter(Some("30")),
      Some(30.seconds),
    )
    assertEquals(
      JsonHttp.retryAfter(Some(" 0 ")),
      Some(0.seconds),
    )

  test("a request to wait which is not seconds is left unread"):
    assertEquals(JsonHttp.retryAfter(None), None)
    assertEquals(
      JsonHttp.retryAfter(Some("Wed, 21 Oct 2026 07:28:00 GMT")),
      None,
    )
    assertEquals(JsonHttp.retryAfter(Some("-1")), None)

  test("a chat with nothing in it is not sent"):
    assertEquals(
      JsonHttp.answerable(LlmProvider.Gemini, Chat()),
      Left(LlmError.Unsendable("Gemini", "it has no messages")),
    )
    assert(JsonHttp.answerable(LlmProvider.Gemini, chat).isRight)

  test("a chat with a message saying nothing is not sent"):
    assertEquals(
      JsonHttp.answerable(
        LlmProvider.Gemini,
        chat.user(Part.CacheBreakpoint).user("Well?"),
      ),
      Left(LlmError.Unsendable(
        "Gemini",
        "one of its messages says nothing",
      )),
    )

  private val prefilled = Chat().user("Hello").assistant("Once upon a")

  test("a model which refuses a prefill is not asked to continue one"):
    assert(AnthropicApi.continuable(prefilled, LlmModel.ClaudeSonnet5).isLeft)
    assert(AnthropicApi.continuable(prefilled, LlmModel.ClaudeOpus4_6).isLeft)

  test("a model iris does not list is not asked to continue a prefill"):
    assert(AnthropicApi.continuable(prefilled, unlisted).isLeft)
    assert(AnthropicApi.continuable(prefilled, LlmModel.ClaudeMythos5).isLeft)

  test("a model which accepts a prefill still may be given one"):
    assert(AnthropicApi.continuable(prefilled, LlmModel.ClaudeHaiku4_5).isRight)
    assert(AnthropicApi.continuable(chat, LlmModel.ClaudeSonnet5).isRight)

  test("a stop reason the provider withheld is not one it gave"):
    val body = """{"content":[{"type":"text","text":"Hi"}]}"""
    assertEquals(
      answer(AnthropicApi, body).map(_.stopReason),
      Right(StopReason.Unknown),
    )

  test("partial token counts are no usage, not a broken response"):
    val body = """{"content":[],"stop_reason":"end_turn",
         "usage":{"input_tokens":10}}"""
    assertEquals(
      answer(AnthropicApi, body).map(_.usage),
      Right(None),
    )

  test("a response which reports no usage still parses"):
    val body =
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}"""
    assertEquals(
      answer(OpenAiApi, body),
      Right(Reply("Hi", StopReason.Completed, None)),
    )

  test("only blocks of text contribute to the reply"):
    val body = """{"content":[{"type":"thinking","text":"hmm"},
         {"type":"text","text":"Hello!"}],"stop_reason":"end_turn"}"""
    assertEquals(
      answer(AnthropicApi, body).map(_.text),
      Right("Hello!"),
    )

  test("gemini addresses the generate endpoint of the model it is given"):
    assertEquals(
      GeminiApi.endpoint(gemini, LlmModel.Gemini2_5Flash).map(_.toString),
      Right(
        "https://generativelanguage.googleapis.com" +
          "/v1beta/models/gemini-2.5-flash:generateContent",
      ),
    )

  test("a model name cannot escape the path segment it names"):
    val endpoint = GeminiApi.endpoint(
      gemini,
      LlmModel.of(LlmProvider.Gemini, "../v1/elsewhere").get,
    )
    assert(endpoint.exists(uri => !uri.toString.contains("/v1/elsewhere")))

  test("gemini addresses a model named after models/ by its name alone"):
    val model = LlmModel.parse("gemini:models/gemini-3-pro").get
    assertEquals(
      GeminiApi.endpoint(gemini, model).map(_.toString),
      Right(
        "https://generativelanguage.googleapis.com" +
          "/v1beta/models/gemini-3-pro:generateContent",
      ),
    )

  test("a base url which is not a url is a configuration error"):
    val endpoint = GeminiApi.endpoint(
      gemini.copy(baseUrl = Some("https://proxy.test/%")),
      LlmModel.Gemini2_5Flash,
    )
    assert(endpoint.left.exists(_.isInstanceOf[LlmError.Misconfigured]))

  test("a base url is the origin beneath which endpoints are addressed"):
    assertEquals(
      GeminiApi
        .endpoint(
          gemini.copy(baseUrl = Some("https://proxy.test")),
          LlmModel.Gemini2_5Flash,
        )
        .map(_.toString),
      Right("https://proxy.test/v1beta/models/gemini-2.5-flash:generateContent"),
    )
