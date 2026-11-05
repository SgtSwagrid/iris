package com.alecdorrington.iris
package providers

import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import scala.concurrent.duration.DurationInt

/**
  * Every provider Iris has, for choosing one and its model at runtime, as from
  * the environment, where a host need not know which it will prompt.
  */
object Providers:

  /** Every provider, in the order a key in the environment chooses one. */
  val all: List[Provider] = List(Anthropic, OpenAi, Gemini)

  /**
    * Parses a provider from its name, in any case: `anthropic`, `openai`, and
    * `gemini` or `google`.
    *
    * @param name
    *   The provider's name.
    *
    * @return
    *   The provider, or `None` where the name is none of theirs.
    */
  def provider(name: String): Option[Provider] =
    // By character, not by the default locale's rules, by which `OPENAI` lowers
    // to `openaı` in Turkish; nor by `Locale.ROOT`'s, as JavaScript has none.
    name.trim.map(_.toLower) match
      case "google" => Some(Gemini)
      case id       => all.find(_.id == id)

  /**
    * Parses a model from its name in its provider's API, which must be one Iris
    * lists, in any case, or else from that name after its provider's and a
    * colon, as `anthropic:claude-sonnet-5-5`, which may name any model.
    *
    * @param name
    *   The model's name, alone or after its provider's.
    *
    * @return
    *   The model, or `None` where the name is neither a model Iris lists nor
    *   after a provider's, or names a model another provider lists.
    */
  def model(name: String): Option[ProviderModel] = listed(name).orElse(
    name.split(":", 2) match
      case Array(named, id) => provider(named).flatMap(provider =>
          listed(id).fold(provider.model(id))(model =>
            Option.when(model.provider == provider)(model),
          ),
        )
      case _ => None,
  )

  /** The model some provider lists under a name, in any case. */
  private def listed(name: String): Option[ProviderModel] = all
    .flatMap(_.listed)
    .find(_.id.equalsIgnoreCase(name.trim))

  /**
    * Reads what the environment configures, every variable through one lookup:
    * the model by `LLM_MODEL`, as [[model]] reads it, else the default model of
    * the provider `LLM_PROVIDER` names, else that of the first provider whose
    * key is set; how its API is reached by the provider's key, `LLM_BASE_URL`
    * and `LLM_TIMEOUT` (in whole seconds); and its settings by
    * `LLM_MAX_TOKENS`.
    *
    * {{{
    * Providers.from().traverse(found => found.model.prompt[IO](found.api, found.config))
    * }}}
    *
    * @param variable
    *   Looks up a variable by name, as from the environment, by default.
    *
    * @param defaultMaxTokens
    *   The most tokens a response may take where `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   The model, how its API is reached and its settings, or `None` where none
    *   is configured, or a variable is set but cannot be used, as a set but
    *   unknown provider or model is never taken to mean another.
    */
  def from
    (
      variable: String => Option[String] = Environment.variable,
      defaultMaxTokens: Int = PromptConfig.defaultMaxTokens,
    )
    : Option[(model: ProviderModel, api: ApiConfig, config: PromptConfig)] =
    val named = variable("LLM_MODEL")
    for
      provider <- variable("LLM_PROVIDER").fold(
        named.fold(
          all.find(_.key(variable).isDefined),
        )(model(_).map(_.provider)),
      )(this.provider)
      model <- named.fold(Some(provider.defaultModel))(model(_).filter(
        _.provider == provider,
      ))
      key     <- provider.key(variable)
      timeout <- variable("LLM_TIMEOUT").fold(Some(ApiConfig.defaultTimeout))(
        Environment.positive(_).map(_.seconds),
      )
      maxTokens <- variable("LLM_MAX_TOKENS").fold(
        Some(defaultMaxTokens),
      )(Environment.positive)
    yield (
      model = model,
      api = ApiConfig(
        key = Some(key),
        baseUrl = variable("LLM_BASE_URL"),
        timeout = timeout,
      ),
      config = PromptConfig(maxTokens = maxTokens),
    )

  /** Every variable [[from]] reads, the providers' keys among them. */
  val variables: List[String] = List("LLM_PROVIDER", "LLM_MODEL") ++
    all.flatMap(_.keyVariables) ++ List(
      "LLM_BASE_URL",
      "LLM_TIMEOUT",
      "LLM_MAX_TOKENS",
    )
