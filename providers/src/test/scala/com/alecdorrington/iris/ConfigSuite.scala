package com.alecdorrington.iris

import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.providers.*
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
        Environment.positive(written),
        limit,
        written,
      ),
    )

  test("configurations never reveal their api key"):
    val secret = ApiConfig(
      key = Some("sk-do-not-print"),
      baseUrl = Some("https://proxy.test"),
    )
    assert(!secret.toString.contains("sk-do-not-print"))
    assert(secret.toString.contains("https://proxy.test"))

  test("a timeout is read as a positive number of seconds, else none at all"):
    List(
      " 600 " -> Some(10.minutes),
      "30s"   -> None,
      "0"     -> None,
      "-5"    -> None,
    ).foreach((written, timeout) =>
      assertEquals(
        ApiConfig.from(Map("LLM_TIMEOUT" -> written).get).map(_.timeout),
        timeout,
        written,
      ),
    )

  test("providers are parsed by name, case and space insensitively"):
    List(
      " Anthropic " -> Some(Anthropic),
      "OPENAI"      -> Some(OpenAi),
      "google"      -> Some(Gemini),
      "claude"      -> None,
      ""            -> None,
    ).foreach((name, provider) =>
      assertEquals(
        Providers.provider(name),
        provider,
        name,
      ),
    )

  test("a model is read through any lookup, its provider its model's"):
    val mini = Map(
      "OPENAI_API_KEY" -> "key",
      "LLM_MODEL"      -> "gpt-5-mini",
      "LLM_TIMEOUT"    -> "30",
    )
    List(
      mini -> OpenAiModel.Gpt5Mini,
      // A model Iris does not list is named with its provider.
      Map(
        "OPENAI_API_KEY" -> "key",
        "LLM_MODEL"      -> "openai:gpt-6",
      ) -> OpenAi.model("gpt-6").get,
      // A provider which is not named is the one serving the model named.
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "OPENAI_API_KEY"    -> "key",
        "LLM_MODEL"         -> "gpt-5",
      ) -> OpenAiModel.Gpt5,
    ).foreach: (variables, model) =>
      assertEquals(
        Providers.from(variables.get).map(model => (model.provider, model)),
        Some((OpenAi, model)),
        variables,
      )
    assertEquals(
      ApiConfig.from(mini.get).map(config => (config.baseUrl, config.timeout)),
      Some((None, 30.seconds)),
    )
    assertEquals(
      ApiConfig
        .from(Map("LLM_BASE_URL" -> "https://proxy.test").get)
        .map(config => (config.baseUrl, config.timeout)),
      Some((Some("https://proxy.test"), ApiConfig.defaultTimeout)),
    )

  test("an unset token limit is the host's default, else Iris's own"):
    val keyed = Map("ANTHROPIC_API_KEY" -> "key")
    assertEquals(
      PromptConfig.from(keyed.get).map(_.maxTokens),
      Some(PromptConfig.defaultMaxTokens),
    )
    assertEquals(
      PromptConfig.from(keyed.get, defaultMaxTokens = 16_000).map(_.maxTokens),
      Some(16_000),
    )
    // A limit set to no limit at all configures nothing.
    assertEquals(
      PromptConfig.from((keyed + ("LLM_MAX_TOKENS" -> "0")).get),
      None,
    )
    assertEquals(
      PromptConfig
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
        Providers.from(variables.get),
        None,
        variables,
      ),
    )

  test(
    "a model is parsed by its name, alone if listed, else after its provider",
  ):
    List(
      " Claude-Opus-5-5 "         -> Some(AnthropicModel.ClaudeOpus5_5),
      "claude-sonet-5-5"          -> None,
      "ClaudeSonnet5_5"           -> None,
      ""                          -> None,
      "anthropic:claude-opus-6"   -> Anthropic.model("claude-opus-6"),
      " OpenAI:ft:gpt-5:team:x "  -> OpenAi.model("ft:gpt-5:team:x"),
      "anthropic:claude-opus-5-5" -> Some(AnthropicModel.ClaudeOpus5_5),
      "openai:claude-opus-5-5"    -> None,
      "claude:opus"               -> None,
      "anthropic: "               -> None,
      // A Gemini model may be named after models/, as Gemini names it.
      "gemini:models/gemini-2.5-pro" -> Some(GeminiModel.Gemini2_5Pro),
      "gemini:models/"               -> None,
    ).foreach((name, model) => assertEquals(Providers.model(name), model, name))
    // Only Gemini's own names begin with models/.
    assertEquals(
      List(
        "gemini:models/gemini-3-pro",
        "openai:models/gpt-6",
      ).map(name => Providers.model(name).map(_.id)),
      List(
        Some("gemini-3-pro"),
        Some("models/gpt-6"),
      ),
    )
    // A provider's own name for a listed model is that model, in any case.
    assertEquals(
      Anthropic.model(" Claude-Haiku-4-5 "),
      Some(AnthropicModel.ClaudeHaiku4_5),
    )

  test("every model is read back from its id, if listed, and its full name"):
    assertEquals(
      AnthropicModel.ClaudeOpus5_5.name,
      "anthropic:claude-opus-5-5",
    )
    Providers
      .all
      .flatMap(_.listed)
      .foreach(model => assertEquals(Providers.model(model.id), Some(model)))
    (Providers.all.flatMap(_.listed) ++ OpenAi.model("ft:gpt-5:x")).foreach(
      model =>
        assertEquals(
          Providers.model(model.name),
          Some(model),
        ),
    )
