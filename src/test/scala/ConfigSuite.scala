package com.alecdorrington.iris

import munit.FunSuite
import scala.concurrent.duration.DurationInt

class ConfigSuite extends FunSuite:

  test("a token limit is a positive whole number, else no limit at all"):
    List(
      " 8192 " -> Some(8192),
      "1"      -> Some(1),
      "0"      -> None,
      "-1"     -> None,
      "8k"     -> None,
      "8192.0" -> None,
      ""       -> None,
    ).foreach((written, limit) =>
      assertEquals(
        LlmConfig.positive(written),
        limit,
        written,
      ),
    )

  test("configurations never reveal their api key"):
    val secret = LlmConfig(
      LlmModel.ClaudeHaiku4_5,
      "sk-do-not-print",
      512,
    )
    assert(!secret.toString.contains("sk-do-not-print"))
    assert(secret.toString.contains("anthropic:claude-haiku-4-5"))

  test("a timeout is read as a positive number of seconds, else none at all"):
    List(
      " 600 " -> Some(10.minutes),
      "30s"   -> None,
      "0"     -> None,
      "-5"    -> None,
    ).foreach((written, timeout) =>
      assertEquals(
        LlmConfig.seconds(written),
        timeout,
        written,
      ),
    )

  test("providers are parsed by name, case and space insensitively"):
    List(
      " Anthropic " -> Some(LlmProvider.Anthropic),
      "OPENAI"      -> Some(LlmProvider.OpenAi),
      "google"      -> Some(LlmProvider.Gemini),
      "claude"      -> None,
      ""            -> None,
    ).foreach((name, provider) =>
      assertEquals(LlmProvider.parse(name), provider, name),
    )

  test("a configuration is read through any lookup, its provider its model's"):
    val mini = Map(
      "OPENAI_API_KEY" -> "key",
      "LLM_MODEL"      -> "gpt-5-mini",
      "LLM_TIMEOUT"    -> "30",
    )
    List(
      mini -> LlmModel.Gpt5Mini,
      // A model Iris does not list is named with its provider.
      Map(
        "OPENAI_API_KEY" -> "key",
        "LLM_MODEL"      -> "openai:gpt-6",
      ) -> LlmModel.of(LlmProvider.OpenAi, "gpt-6").get,
      // A provider which is not named is the one serving the model named.
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "OPENAI_API_KEY"    -> "key",
        "LLM_MODEL"         -> "gpt-5",
      ) -> LlmModel.Gpt5,
    ).foreach: (variables, model) =>
      assertEquals(
        LlmConfig
          .from(variables.get)
          .map(config => (config.provider, config.model)),
        Some((LlmProvider.OpenAi, model)),
        variables,
      )
    assertEquals(
      LlmConfig.from(mini.get).map(_.timeout),
      Some(30.seconds),
    )

  test("an unset token limit is the host's default, else Iris's own"):
    val keyed = Map("ANTHROPIC_API_KEY" -> "key")
    assertEquals(
      LlmConfig.from(keyed.get).map(_.maxTokens),
      Some(LlmConfig.defaultMaxTokens),
    )
    assertEquals(
      LlmConfig.from(keyed.get, defaultMaxTokens = 16_000).map(_.maxTokens),
      Some(16_000),
    )
    assertEquals(
      LlmConfig
        .from(
          (keyed + ("LLM_MAX_TOKENS" -> "500")).get,
          defaultMaxTokens = 16_000,
        )
        .map(_.maxTokens),
      Some(500),
    )

  test(
    "a lookup with no key, or an unknown or mismatched name, configures nothing",
  ):
    List(
      Map.empty[String, String],
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "LLM_PROVIDER"      -> "claude",
      ),
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "LLM_MODEL"         -> "claude-sonet-5-5",
      ),
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "OPENAI_API_KEY"    -> "key",
        "LLM_PROVIDER"      -> "anthropic",
        "LLM_MODEL"         -> "gpt-5",
      ),
    ).foreach(variables =>
      assertEquals(
        LlmConfig.from(variables.get),
        None,
        variables,
      ),
    )

  test(
    "a model is parsed by its name, alone if listed, else after its provider",
  ):
    List(
      " Claude-Opus-5-5 "       -> Some(LlmModel.ClaudeOpus5_5),
      "claude-sonet-5-5"        -> None,
      "ClaudeSonnet5_5"         -> None,
      ""                        -> None,
      "anthropic:claude-opus-6" ->
        LlmModel.of(LlmProvider.Anthropic, "claude-opus-6"),
      " OpenAI:ft:gpt-5:team:x " ->
        LlmModel.of(LlmProvider.OpenAi, "ft:gpt-5:team:x"),
      "anthropic:claude-opus-5-5" -> Some(LlmModel.ClaudeOpus5_5),
      "openai:claude-opus-5-5"    -> None,
      "claude:opus"               -> None,
      "anthropic: "               -> None,
      // A Gemini model may be named after models/, as Gemini names it.
      "gemini:models/gemini-2.5-pro" -> Some(LlmModel.Gemini2_5Pro),
      "gemini:models/"               -> None,
    ).foreach((name, model) => assertEquals(LlmModel.parse(name), model, name))
    // Only Gemini's own names begin with models/.
    assertEquals(
      List(
        "gemini:models/gemini-3-pro",
        "openai:models/gpt-6",
      ).map(name => LlmModel.parse(name).map(_.id)),
      List(
        Some("gemini-3-pro"),
        Some("models/gpt-6"),
      ),
    )
    // A provider's own name for a listed model is that model, in any case.
    assertEquals(
      LlmModel.of(
        LlmProvider.Anthropic,
        " Claude-Haiku-4-5 ",
      ),
      Some(LlmModel.ClaudeHaiku4_5),
    )

  test("every model is read back from its id, if listed, and its full name"):
    assertEquals(
      LlmModel.ClaudeOpus5_5.name,
      "anthropic:claude-opus-5-5",
    )
    LlmModel
      .listed
      .foreach(model => assertEquals(LlmModel.parse(model.id), Some(model)))
    (LlmModel.listed ++ LlmModel.of(LlmProvider.OpenAi, "ft:gpt-5:x")).foreach(
      model =>
        assertEquals(
          LlmModel.parse(model.name),
          Some(model),
        ),
    )
