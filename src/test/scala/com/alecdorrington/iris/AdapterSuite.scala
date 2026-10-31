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

  test("each provider is sent the history, system and options in its own form"):
    val anthropic = json(AnthropicApi.body(
      config,
      chat,
      ReplyOptions(
        temperature = Some(0.5),
        stopSequences = List("END"),
      ),
    ))
    assertEquals(
      anthropic.hcursor.get[String]("model").toOption,
      Some("claude-haiku-4-5"),
    )
    assertEquals(
      anthropic.hcursor.get[Int]("max_tokens").toOption,
      Some(512),
    )
    assertEquals(
      anthropic.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )
    assertEquals(
      anthropic.hcursor.get[String]("system").toOption,
      Some("Be brief."),
    )
    assertEquals(
      anthropic.hcursor.get[List[String]]("stop_sequences").toOption,
      Some(List("END")),
    )
    assertEquals(
      roles(anthropic, "messages"),
      Some(List("user", "assistant", "user")),
    )
    // A token limit named for a request outranks the configured one.
    val openAi = json(OpenAiApi.body(
      config,
      chat,
      ReplyOptions(maxTokens = Some(64)),
    ))
    assertEquals(
      openAi.hcursor.get[Int]("max_completion_tokens").toOption,
      Some(64),
    )
    assertEquals(
      roles(openAi, "messages"),
      Some(List("system", "user", "assistant", "user")),
    )
    val google = json(GeminiApi.body(gemini, chat, options))
    assertEquals(
      roles(google, "contents"),
      Some(List("user", "model", "user")),
    )
    assert(google.hcursor.downField("system_instruction").succeeded)
    assertEquals(
      google
        .hcursor
        .downField("generationConfig")
        .get[Int]("maxOutputTokens")
        .toOption,
      Some(512),
    )

  test("unset options are omitted from every provider's request"):
    val anthropic = json(AnthropicApi.body(config, chat, options)).hcursor
    List(
      "temperature",
      "top_p",
      "stop_sequences",
      "tools",
      "output_config",
      "stream",
    ).foreach(field =>
      assert(
        anthropic.downField(field).failed,
        field,
      ),
    )
    val openAi = json(OpenAiApi.body(config, chat, options)).hcursor
    List(
      "temperature",
      "top_p",
      "stop",
      "tools",
      "reasoning_effort",
      "stream",
      "stream_options",
    ).foreach(field => assert(openAi.downField(field).failed, field))
    val google = json(GeminiApi.body(gemini, chat, options)).hcursor
    assert(google.downField("tools").failed)
    val generation = google.downField("generationConfig")
    assert(generation.downField("maxOutputTokens").succeeded)
    List(
      "temperature",
      "topP",
      "stopSequences",
      "thinkingConfig",
    ).foreach(field =>
      assert(
        generation.downField(field).failed,
        field,
      ),
    )

  test("anthropic sends sampling options only to the models that accept them"):
    val sampled = ReplyOptions(temperature = Some(0.5), topP = Some(0.9))
    List(
      LlmModel.ClaudeHaiku4_5 -> true,
      LlmModel.ClaudeSonnet5  -> false,
      unlisted                -> false,
    ).foreach: (model, samples) =>
      val body =
        json(AnthropicApi.body(config.copy(model = model), chat, sampled))
          .hcursor
      assertEquals(
        body.get[String]("model").toOption,
        Some(model.id),
      )
      assertEquals(
        (
          body.get[Double]("temperature").toOption,
          body.get[Double]("top_p").toOption,
        ),
        if samples then (Some(0.5), Some(0.9)) else (None, None),
        model,
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

  test(
    "openai is asked for its most effort, or high of a model going no higher",
  ):
    assertEquals(most(LlmModel.Gpt6Astra), Some("max"))
    assertEquals(
      most(LlmModel.of(LlmProvider.OpenAi, "gpt-new").get),
      Some("max"),
    )
    assertEquals(most(LlmModel.Gpt5), Some("high"))

  private val picture = Part.Media(MediaType.Png, "aGVsbG8=")

  /** Two text parts in a row, then the picture they ask about. */
  private val looking = Chat().user(
    Part.Text("Look."),
    Part.Text("What is this?"),
    picture,
  )

  private val paper = Chat().user(Part.Media(MediaType.Pdf, "JVBERi0="))

  private val recording =
    Chat().user(Part.Media(MediaType.of("audio/mp3").get, "SUQz"))

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
    // A message's text holds each text part as a paragraph of its own.
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

  /** The parts of the first message of a body, under the fields named. */
  private def parts
    (
      body: String,
      messages: String,
      content: String,
    )
    : List[Json] = json(body)
    .hcursor
    .downField(messages)
    .downN(0)
    .get[List[Json]](content)
    .getOrElse(List.empty)

  private def texts(parts: List[Json]): List[String] =
    parts.flatMap(_.hcursor.get[String]("text").toOption)

  test("each provider is sent a picture its own way, after texts kept apart"):
    val apart     = List("Look.", "\n\nWhat is this?")
    val anthropic = parts(
      AnthropicApi.body(config, looking, options),
      "messages",
      "content",
    )
    assertEquals(texts(anthropic), apart)
    assertEquals(
      anthropic.map(_.hcursor.get[String]("type").toOption),
      List(
        Some("text"),
        Some("text"),
        Some("image"),
      ),
    )
    assertEquals(
      anthropic(2)
        .hcursor
        .downField("source")
        .get[String]("media_type")
        .toOption,
      Some("image/png"),
    )
    val openAi = parts(
      OpenAiApi.body(config, looking, options),
      "messages",
      "content",
    )
    assertEquals(texts(openAi), apart)
    assertEquals(
      openAi(2).hcursor.get[String]("type").toOption,
      Some("image_url"),
    )
    assertEquals(
      openAi(2).hcursor.downField("image_url").get[String]("url").toOption,
      Some("data:image/png;base64,aGVsbG8="),
    )
    val google = parts(
      GeminiApi.body(config, looking, options),
      "contents",
      "parts",
    )
    assertEquals(texts(google), apart)
    assertEquals(
      google(2)
        .hcursor
        .downField("inline_data")
        .get[String]("mime_type")
        .toOption,
      Some("image/png"),
    )

  test(
    "anthropic sends a document as one, which openai cannot carry in a chat",
  ):
    assertEquals(
      parts(
        AnthropicApi.body(config, paper, options),
        "messages",
        "content",
      ).map(_.hcursor.get[String]("type").toOption),
      List(Some("document")),
    )
    assertEquals(
      OpenAiApi.acceptable(config, paper, options),
      Left(LlmError.Unsupported(
        "OpenAI",
        "sending application/pdf in a chat",
      )),
    )
    assert(OpenAiApi.acceptable(config, looking, options).isRight)

  test("only gemini is sent media iris does not list, which it judges itself"):
    assertEquals(
      AnthropicApi.acceptable(config, recording, options),
      Left(LlmError.Unsupported(
        "Anthropic",
        "sending audio/mp3 in a chat",
      )),
    )
    assert(AnthropicApi.acceptable(config, paper, options).isRight)
    assert(OpenAiApi.acceptable(config, recording, options).isLeft)
    assert(GeminiApi.acceptable(config, recording, options).isRight)
    assertEquals(
      parts(
        GeminiApi.body(config, recording, options),
        "contents",
        "parts",
      ).map(
        _.hcursor.downField("inline_data").get[String]("mime_type").toOption,
      ),
      List(Some("audio/mp3")),
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

  test("each provider's response is read as an answer, with its token counts"):
    List(
      // Anthropic leaves its cache's reads and writes out of its input tokens,
      // and a block of thinking out of the reply, but not out of its count.
      (
        AnthropicApi,
        """{"content":[{"type":"thinking","text":"hmm"},
           {"type":"text","text":"Hello!"}],"stop_reason":"end_turn",
           "usage":{"input_tokens":10,"output_tokens":12,
                    "cache_creation_input_tokens":20,
                    "cache_read_input_tokens":30,
                    "output_tokens_details":{"thinking_tokens":10}}}""",
        Reply(
          "Hello!",
          StopReason.Completed,
          Usage(
            uncachedTokens = 10,
            cacheReadTokens = 30,
            cacheWriteTokens = 20,
            visibleTokens = 2,
            thinkingTokens = 10,
          ),
        ),
      ),
      // OpenAI counts its cache reads within its prompt, and its reasoning
      // within its completion.
      (
        OpenAiApi,
        """{"choices":[{"message":{"content":"Hello!"},"finish_reason":"length"}],
           "usage":{"prompt_tokens":40,"completion_tokens":12,
                    "prompt_tokens_details":{"cached_tokens":32},
                    "completion_tokens_details":{"reasoning_tokens":10}}}""",
        Reply(
          "Hello!",
          StopReason.MaxTokens,
          Usage(
            uncachedTokens = 8,
            cacheReadTokens = 32,
            visibleTokens = 2,
            thinkingTokens = 10,
          ),
        ),
      ),
      // Gemini counts its cache reads within its prompt, but its thoughts apart
      // from its candidates.
      (
        GeminiApi,
        """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},
           "finishReason":"STOP"}],
           "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":2,
                            "cachedContentTokenCount":32,
                            "thoughtsTokenCount":10}}""",
        Reply(
          "Hello!",
          StopReason.Completed,
          Usage(
            uncachedTokens = 8,
            cacheReadTokens = 32,
            visibleTokens = 2,
            thinkingTokens = 10,
          ),
        ),
      ),
    ).foreach: (api, body, reply) =>
      assertEquals(
        answer(api, body),
        Right(reply),
        api.provider,
      )

  test("an unrecognised stop reason is kept, and one withheld is unknown"):
    assertEquals(
      answer(
        AnthropicApi,
        """{"content":[],"stop_reason":"refusal","usage":null}""",
      ).map(_.stopReason),
      Right(StopReason.Other("refusal")),
    )
    assertEquals(
      answer(
        AnthropicApi,
        """{"content":[{"type":"text","text":"Hi"}]}""",
      ).map(_.stopReason),
      Right(StopReason.Unknown),
    )

  test("a response with no answer in it is malformed, and says why"):
    List(
      (OpenAiApi, """{"choices":[],"usage":null}""", "no choices"),
      (GeminiApi, """{"candidates":[]}""", "no candidates"),
      (
        GeminiApi,
        """{"promptFeedback":{"blockReason":"SAFETY"}}""",
        "no candidates, blocked as SAFETY",
      ),
    ).foreach: (api, body, why) =>
      assertEquals(
        answer(api, body),
        Left(LlmError.Malformed(api.provider.displayName, why)),
        why,
      )

  test(
    "a count left out is 0, and usage left out wholly is no broken response",
  ):
    assertEquals(
      answer(
        AnthropicApi,
        """{"content":[],"stop_reason":"end_turn",
         "usage":{"input_tokens":10}}""",
      ).map(_.usage),
      Right(Usage(uncachedTokens = 10)),
    )
    // Gemini leaves out a count of 0, as of a reply cut off while it thought.
    assertEquals(
      answer(
        GeminiApi,
        """{"candidates":[{"finishReason":"MAX_TOKENS"}],
         "usageMetadata":{"promptTokenCount":12,"thoughtsTokenCount":30}}""",
      ).map(_.usage),
      Right(Usage(uncachedTokens = 12, thinkingTokens = 30)),
    )
    assertEquals(
      answer(
        OpenAiApi,
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
      ),
      Right(Reply("Hi", StopReason.Completed, Usage())),
    )

  test("gemini's audio is counted apart, read afresh or from the cache"):
    assertEquals(
      answer(
        GeminiApi,
        """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},
           "finishReason":"STOP"}],
           "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":2,
             "cachedContentTokenCount":40,
             "promptTokensDetails":[{"modality":"TEXT","tokenCount":60},
                                    {"modality":"AUDIO","tokenCount":40}],
             "cacheTokensDetails":[{"modality":"TEXT","tokenCount":30},
                                   {"modality":"AUDIO","tokenCount":10}]}}""",
      ).map(_.usage),
      Right(Usage(
        uncachedTokens = 30,
        cacheReadTokens = 30,
        uncachedAudioTokens = 30,
        cacheReadAudioTokens = 10,
        visibleTokens = 2,
      )),
    )
    // A cache not broken down by kind of media holds audio in the prompt's
    // share.
    assertEquals(
      answer(
        GeminiApi,
        """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},
           "finishReason":"STOP"}],
           "usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":2,
             "cachedContentTokenCount":800,
             "promptTokensDetails":[{"modality":"AUDIO","tokenCount":1000}]}}""",
      ).map(_.usage),
      Right(Usage(
        uncachedAudioTokens = 200,
        cacheReadAudioTokens = 800,
        visibleTokens = 2,
      )),
    )

  test("openai's cache writes are counted apart from its prompt"):
    assertEquals(
      answer(
        OpenAiApi,
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":12000,"completion_tokens":5,
             "prompt_tokens_details":{"cached_tokens":4000,
                                      "cache_write_tokens":7000}}}""",
      ).map(_.usage),
      Right(Usage(
        uncachedTokens = 1000,
        cacheReadTokens = 4000,
        cacheWriteTokens = 7000,
        visibleTokens = 5,
      )),
    )

  test("a provider's request to wait is read where it is given in seconds"):
    List(
      Some("30")                            -> Some(30.seconds),
      Some(" 0 ")                           -> Some(0.seconds),
      None                                  -> None,
      Some("Wed, 21 Oct 2026 07:28:00 GMT") -> None,
      Some("-1")                            -> None,
    ).foreach((header, delay) =>
      assertEquals(
        JsonHttp.retryAfter(header),
        delay,
        header,
      ),
    )

  private val prefilled = Chat().user("Hello").assistant("Once upon a")

  test("only a model which accepts a prefill is asked to continue one"):
    List(
      ("haiku 4.5, prefilled", prefilled, LlmModel.ClaudeHaiku4_5, true),
      ("sonnet 5, prefilled", prefilled, LlmModel.ClaudeSonnet5, false),
      // Opus 4.6 still samples, but no longer continues a prefill.
      ("opus 4.6, prefilled", prefilled, LlmModel.ClaudeOpus4_6, false),
      ("an unlisted model, prefilled", prefilled, unlisted, false),
      ("sonnet 5, asked by the user", chat, LlmModel.ClaudeSonnet5, true),
    ).foreach: (row, asked, model, continues) =>
      assertEquals(
        AnthropicApi.continuable(asked, model).isRight,
        continues,
        row,
      )

  test(
    "gemini addresses each method of the model it is given, beneath an origin",
  ):
    val origin = "https://generativelanguage.googleapis.com"
    assertEquals(
      GeminiApi.endpoint(gemini, LlmModel.Gemini2_5Flash).map(_.toString),
      Right(s"$origin/v1beta/models/gemini-2.5-flash:generateContent"),
    )
    assertEquals(
      GeminiApi.streaming(gemini, LlmModel.Gemini2_5Flash).map(_.toString),
      Right(
        s"$origin/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
      ),
    )
    assertEquals(
      GeminiApi
        .endpoint(
          gemini.copy(baseUrl = Some("https://proxy.test")),
          LlmModel.Gemini2_5Flash,
        )
        .map(_.toString),
      Right("https://proxy.test/v1beta/models/gemini-2.5-flash:generateContent"),
    )

  test("a model name cannot escape the path segment it names"):
    val endpoint = GeminiApi.endpoint(
      gemini,
      LlmModel.of(LlmProvider.Gemini, "../v1/elsewhere").get,
    )
    assert(endpoint.exists(uri => !uri.toString.contains("/v1/elsewhere")))
