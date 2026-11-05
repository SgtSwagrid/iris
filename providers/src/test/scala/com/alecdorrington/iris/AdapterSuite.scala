package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import com.alecdorrington.iris.Fixtures.{
  access, answer, model, prompting, settings, unended,
}
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import io.circe.Json
import munit.FunSuite
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}

class AdapterSuite extends FunSuite:

  private val gemini = GeminiModel.Gemini2_5Flash

  /** A request's settings where it sets none. */
  private val unset = settings()

  /** An Anthropic model Iris does not list. */
  private val unlisted = Anthropic.model("claude-new").get

  private val conversation = Conversation()
    .withInstructions("Be brief.")
    .userSays("Hello")
    .modelSays("Hi!")
    .userSays("How are you?")

  private def roles(json: Json, field: String): Option[List[String]] = json
    .hcursor
    .downField(field)
    .as[List[Json]]
    .toOption
    .map(_.flatMap(_.hcursor.get[String]("role").toOption))

  test("each provider is sent the history, system and options in its own form"):
    val anthropic = Anthropic.body(
      model,
      conversation,
      settings(PromptRequest(
        conversation,
        temperature = Some(0.5),
        stopSequences = Some(List("END")),
      )),
    )
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
    val openAi = OpenAi.body(
      model,
      conversation,
      settings(PromptRequest(conversation, maxTokens = Some(64))),
    )
    assertEquals(
      openAi.hcursor.get[Int]("max_completion_tokens").toOption,
      Some(64),
    )
    assertEquals(
      roles(openAi, "messages"),
      Some(List("system", "user", "assistant", "user")),
    )
    val google = Gemini.body(gemini, conversation, unset)
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

  test("a configuration's settings are sent where a request sets none"):
    val configured = PromptConfig(
      maxTokens = 100,
      temperature = Some(0.2),
      stopSequences = List("STOP"),
      effort = Some(Effort.Low),
    )
    val body = Anthropic
      .body(
        model,
        conversation,
        configured.settings(PromptRequest(conversation)),
      )
      .hcursor
    assertEquals(
      (
        body.get[Int]("max_tokens").toOption,
        body.get[Double]("temperature").toOption,
        body.get[List[String]]("stop_sequences").toOption,
        body.downField("output_config").get[String]("effort").toOption,
      ),
      (Some(100), Some(0.2), Some(List("STOP")), Some("low")),
    )
    // A request's own outrank them, an empty list of stop sequences too.
    val own = Anthropic
      .body(
        model,
        conversation,
        configured.settings(PromptRequest(
          conversation,
          maxTokens = Some(64),
          temperature = Some(0.9),
          stopSequences = Some(Nil),
          effort = Some(Effort.High),
        )),
      )
      .hcursor
    assertEquals(
      (
        own.get[Int]("max_tokens").toOption,
        own.get[Double]("temperature").toOption,
        own.downField("stop_sequences").failed,
        own.downField("output_config").get[String]("effort").toOption,
      ),
      (Some(64), Some(0.9), true, Some("high")),
    )

  test("unset options are omitted from every provider's request"):
    val anthropic = Anthropic.body(model, conversation, unset).hcursor
    List(
      "temperature",
      "top_p",
      "stop_sequences",
      "tools",
      "output_config",
    ).foreach(field =>
      assert(
        anthropic.downField(field).failed,
        field,
      ),
    )
    val openAi = OpenAi.body(model, conversation, unset).hcursor
    List(
      "temperature",
      "top_p",
      "stop",
      "tools",
      "reasoning_effort",
    ).foreach(field => assert(openAi.downField(field).failed, field))
    val google = Gemini.body(gemini, conversation, unset).hcursor
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
    val sampled = settings(PromptRequest(
      conversation,
      temperature = Some(0.5),
      topP = Some(0.9),
    ))
    List(
      AnthropicModel.ClaudeHaiku4_5 -> true,
      AnthropicModel.ClaudeSonnet5  -> false,
      unlisted                      -> false,
    ).foreach: (model, samples) =>
      val body = Anthropic.body(model, conversation, sampled).hcursor
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
    val thoughtful = settings(PromptRequest(
      conversation,
      effort = Some(Effort.Medium),
    ))
    assertEquals(
      Anthropic
        .body(model, conversation, thoughtful)
        .hcursor
        .downField("output_config")
        .get[String]("effort")
        .toOption,
      Some("medium"),
    )
    assertEquals(
      OpenAi
        .body(model, conversation, thoughtful)
        .hcursor
        .get[String]("reasoning_effort")
        .toOption,
      Some("medium"),
    )
    assertEquals(
      Gemini
        .body(gemini, conversation, thoughtful)
        .hcursor
        .downField("generationConfig")
        .downField("thinkingConfig")
        .get[Int]("thinkingBudget")
        .toOption,
      Some(4_096),
    )

  /** The reasoning effort OpenAI is asked of a model for the most effort. */
  private def most(model: ProviderModel): Option[String] = OpenAi
    .body(
      model,
      conversation,
      settings(PromptRequest(conversation, effort = Some(Effort.Max))),
    )
    .hcursor
    .get[String]("reasoning_effort")
    .toOption

  test(
    "openai is asked for its most effort, or high of a model going no higher",
  ):
    assertEquals(
      most(OpenAiModel.Gpt6Astra),
      Some("max"),
    )
    assertEquals(
      most(OpenAi.model("gpt-new").get),
      Some("max"),
    )
    assertEquals(most(OpenAiModel.Gpt5), Some("high"))

  private val picture = MessagePart.Media(MediaType.Png, "aGVsbG8=")

  /** Two text parts in a row, then the picture they ask about. */
  private val looking = Conversation().userSays(
    MessagePart.Text("Look."),
    MessagePart.Text("What is this?"),
    picture,
  )

  private val paper =
    Conversation().userSays(MessagePart.Media(MediaType.Pdf, "JVBERi0="))

  private val recording = Conversation().userSays(
    MessagePart.Media(MediaType.of("audio/mp3").get, "SUQz"),
  )

  private val named = Conversation().userSays(
    MessagePart.Text("BOOK 1: notes.txt"),
    MessagePart.Text("Chapter one."),
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
        Anthropic.body(model, named, unset),
        "messages",
      ),
      Some(apart),
    )
    assertEquals(
      said(
        OpenAi.body(model, named, unset),
        "messages",
      ),
      Some(apart),
    )
    assertEquals(
      Gemini
        .body(model, named, unset)
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
        MessagePart.Text("Page 1"),
        picture,
        MessagePart.Text("Page 2"),
        MessagePart.CacheBreakpoint,
        MessagePart.Text(""),
        MessagePart.Text("End."),
      )),
      List(
        MessagePart.Text("Page 1"),
        picture,
        MessagePart.Text("Page 2"),
        MessagePart.CacheBreakpoint,
        MessagePart.Text(""),
        MessagePart.Text("\n\nEnd."),
      ),
    )
    // A message's text holds each text part as a paragraph of its own.
    assertEquals(
      Message
        .User(List(
          MessagePart.Text("Page 1"),
          picture,
          MessagePart.Text(""),
          MessagePart.Text("End."),
        ))
        .text,
      "Page 1\n\nEnd.",
    )

  test(
    "openai sends text around a tool call apart, once the call is taken out",
  ):
    val call  = MessagePart.ToolCall("1", "weather", "{}")
    val asked = Conversation()
      .userSays("Hi")
      .modelSays(
        MessagePart.Text("Looking."),
        call,
        MessagePart.Text("Done."),
      )
    assertEquals(
      OpenAi
        .body(model, asked, unset)
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
      body: Json,
      messages: String,
      content: String,
    )
    : List[Json] = body
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
      Anthropic.body(model, looking, unset),
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
      OpenAi.body(model, looking, unset),
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
      Gemini.body(model, looking, unset),
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
    "anthropic sends a document as one, which openai cannot carry in a conversation",
  ):
    assertEquals(
      parts(
        Anthropic.body(model, paper, unset),
        "messages",
        "content",
      ).map(_.hcursor.get[String]("type").toOption),
      List(Some("document")),
    )
    assertEquals(
      OpenAi.refuseUnsendable(OpenAiModel.Gpt5, paper, unset),
      Left(PromptError.Unsupported(
        "OpenAI",
        "sending application/pdf in a conversation",
      )),
    )
    assert(OpenAi.refuseUnsendable(OpenAiModel.Gpt5, looking, unset).isRight)

  test("only gemini is sent media iris does not list, which it judges itself"):
    assertEquals(
      Anthropic.refuseUnsendable(model, recording, unset),
      Left(PromptError.Unsupported(
        "Anthropic",
        "sending audio/mp3 in a conversation",
      )),
    )
    assert(Anthropic.refuseUnsendable(model, paper, unset).isRight)
    assert(OpenAi.refuseUnsendable(OpenAiModel.Gpt5, recording, unset).isLeft)
    assert(Gemini.refuseUnsendable(gemini, recording, unset).isRight)
    assertEquals(
      parts(
        Gemini.body(gemini, recording, unset),
        "contents",
        "parts",
      ).map(
        _.hcursor.downField("inline_data").get[String]("mime_type").toOption,
      ),
      List(Some("audio/mp3")),
    )

  test("counting asks for no reply, so needs no limit"):
    val body = Anthropic.countBody(model, conversation)
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
        Anthropic,
        """{"content":[{"type":"thinking","text":"hmm"},
           {"type":"text","text":"Hello!"}],"stop_reason":"end_turn",
           "usage":{"input_tokens":10,"output_tokens":12,
                    "cache_creation_input_tokens":20,
                    "cache_read_input_tokens":30,
                    "output_tokens_details":{"thinking_tokens":10}}}""",
        PromptResponse(
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
        OpenAi,
        """{"choices":[{"message":{"content":"Hello!"},"finish_reason":"length"}],
           "usage":{"prompt_tokens":40,"completion_tokens":12,
                    "prompt_tokens_details":{"cached_tokens":32},
                    "completion_tokens_details":{"reasoning_tokens":10}}}""",
        PromptResponse(
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
        Gemini,
        """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},
           "finishReason":"STOP"}],
           "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":2,
                            "cachedContentTokenCount":32,
                            "thoughtsTokenCount":10}}""",
        PromptResponse(
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
      assertEquals(answer(api, body), Right(reply), api)

  test("an unrecognised stop reason is kept, and one withheld is unknown"):
    assertEquals(
      answer(
        Anthropic,
        """{"content":[],"stop_reason":"refusal","usage":null}""",
      ).map(_.stopReason),
      Right(StopReason.Other("refusal")),
    )
    assertEquals(
      answer(
        Anthropic,
        """{"content":[{"type":"text","text":"Hi"}]}""",
      ).map(_.stopReason),
      Right(StopReason.Unknown),
    )

  test("a response with no answer in it is malformed, and says why"):
    List(
      (OpenAi, """{"choices":[],"usage":null}""", unended),
      (Gemini, """{"candidates":[]}""", unended),
      (
        Gemini,
        """{"promptFeedback":{"blockReason":"SAFETY"}}""",
        "no candidates, blocked as SAFETY",
      ),
    ).foreach: (api, body, why) =>
      assertEquals(
        answer(api, body),
        Left(ApiError.Malformed(api.displayName, why)),
        why,
      )

  test(
    "a count left out is 0, and usage left out wholly is no broken response",
  ):
    assertEquals(
      answer(
        Anthropic,
        """{"content":[],"stop_reason":"end_turn",
         "usage":{"input_tokens":10}}""",
      ).map(_.usage),
      Right(Usage(uncachedTokens = 10)),
    )
    // Gemini leaves out a count of 0, as of a reply cut off while it thought.
    assertEquals(
      answer(
        Gemini,
        """{"candidates":[{"finishReason":"MAX_TOKENS"}],
         "usageMetadata":{"promptTokenCount":12,"thoughtsTokenCount":30}}""",
      ).map(_.usage),
      Right(Usage(uncachedTokens = 12, thinkingTokens = 30)),
    )
    assertEquals(
      answer(
        OpenAi,
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
      ),
      Right(PromptResponse("Hi", StopReason.Completed, Usage())),
    )

  test("gemini's audio is counted apart, read afresh or from the cache"):
    assertEquals(
      answer(
        Gemini,
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
        Gemini,
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
        OpenAi,
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

  private val prefilled = Conversation()
    .userSays("Hello")
    .modelSays("Once upon a")

  test("only a model which accepts a prefill is asked to continue one"):
    List(
      ("haiku 4.5, prefilled", prefilled, AnthropicModel.ClaudeHaiku4_5, true),
      ("sonnet 5, prefilled", prefilled, AnthropicModel.ClaudeSonnet5, false),
      // Opus 4.6 still samples, but no longer continues a prefill.
      ("opus 4.6, prefilled", prefilled, AnthropicModel.ClaudeOpus4_6, false),
      ("an unlisted model, prefilled", prefilled, unlisted, false),
      (
        "sonnet 5, asked by the user",
        conversation,
        AnthropicModel.ClaudeSonnet5,
        true,
      ),
    ).foreach: (row, asked, model, continues) =>
      assertEquals(
        Anthropic.refusePrefill(asked, model).isRight,
        continues,
        row,
      )

  /**
    * Where a model's request is sent, beneath the configured origin, if
    * anywhere.
    */
  private def addressed
    (
      model: ProviderModel,
      origin: Option[String] = None,
    )
    : Option[String] = (for
    uris <- Ref.of[SyncIO, List[String]](Nil)
    _    <- prompting(
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespondF(request =>
          uris.update(_ :+ request.uri.toString).as(ResponseStub.adjust("{}")),
        ),
      model,
      access = access.copy(baseUrl = origin),
    ).stream(PromptRequest(Conversation.userSays("Hi"))).compile.drain.attempt
    sent <- uris.get
  yield sent.headOption).unsafeRunSync()

  test(
    "gemini addresses each method of the model it is given, beneath an origin",
  ):
    val origin = "https://generativelanguage.googleapis.com"
    assertEquals(
      addressed(GeminiModel.Gemini2_5Flash),
      Some(
        s"$origin/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
      ),
    )
    assertEquals(
      addressed(
        GeminiModel.Gemini2_5Flash,
        Some("https://proxy.test"),
      ),
      Some(
        "https://proxy.test/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
      ),
    )

  test("a model name cannot escape the path segment it names"):
    val endpoint = addressed(Gemini.model("../v1/elsewhere").get)
    assert(
      endpoint.exists(uri => !uri.contains("/v1/elsewhere")),
      endpoint,
    )
