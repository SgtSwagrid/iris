package com.alecdorrington.iris
package providers

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*

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
    * Reads the model from the environment: by `LLM_MODEL`, as [[model]] reads
    * it, else the default model of the provider `LLM_PROVIDER` names, else that
    * of the first provider whose key is set.
    *
    * @return
    *   The model, or `None` where none is configured, or a variable is set but
    *   unrecognised, as a set but unknown provider or model is never taken to
    *   mean another.
    */
  def fromEnv(): Option[ProviderModel] = from(Environment.variable)

  /**
    * Reads the model from variables, as [[fromEnv]] does.
    *
    * @param variable
    *   Looks up a variable by name.
    *
    * @return
    *   The model, or `None` where none is configured.
    */
  def from(variable: String => Option[String]): Option[ProviderModel] =
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
      _ <- provider.key(variable)
    yield model

  /**
    * Makes the prompt service the environment configures: its model by
    * [[fromEnv]], its API by `ApiConfig.fromEnv` and its settings by
    * `PromptConfig.fromEnv`.
    *
    * @tparam F
    *   The effect type.
    *
    * @param defaultMaxTokens
    *   The most tokens a response may take where `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   A resource holding the service, or `None` where the environment
    *   configures none.
    */
  def prompt[F[_] : Async]
    (defaultMaxTokens: Int = PromptConfig.defaultMaxTokens)
    : Resource[F, Option[PromptService[F]]] =
    (fromEnv(), ApiConfig.fromEnv(), PromptConfig.fromEnv(defaultMaxTokens))
      .tupled
      .traverse((model, api, config) => model.prompt[F](api, config))
