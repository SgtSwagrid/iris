package com.alecdorrington.iris
package api

import cats.effect.{Async, Resource, Sync}
import com.alecdorrington.iris.prompt.*
import scala.concurrent.duration.FiniteDuration

/**
  * A model Iris can prompt. Each belongs to the one provider which serves it,
  * so a model is never sent to a provider that does not have it. The models
  * Iris lists are constants of their provider's
  * (`AnthropicModel.ClaudeSonnet5_5`), and any other is named by its provider,
  * as `AnthropicModel.of(id)`.
  */
trait ProviderModel:

  /** The provider which serves the model. */
  def provider: Provider

  /** The model's name in its provider's API. */
  def id: String

  /** The model's name after its provider's, as `anthropic:claude-opus-5-5`. */
  def name: String = s"${ provider.id }:$id"

  /**
    * Makes this model's prompt service, with an API service of its own, made by
    * [[Provider.api]], whose key is the provider's own variable's unless the
    * API's configuration gives one.
    *
    * {{{
    * AnthropicModel.ClaudeHaiku5_5.prompt[IO]().use(_.send("Hello!"))
    * }}}
    *
    * @tparam F
    *   The effect type.
    *
    * @param api
    *   How the provider's API is reached.
    *
    * @param config
    *   The settings every request takes, unless it sets its own.
    *
    * @return
    *   A resource holding the service and its HTTP client.
    */
  def prompt[F[_] : Async]
    (
      api: ApiConfig = ApiConfig(),
      config: PromptConfig = PromptConfig(),
    )
    : Resource[F, PromptService[F]] = provider
    .api[F](api)
    .map(prompt(_, config))

  /**
    * Makes this model's prompt service over an API service, which several
    * models of its provider may share.
    *
    * @tparam F
    *   The effect type.
    *
    * @param api
    *   The API service of this model's provider.
    *
    * @param config
    *   The settings every request takes, unless it sets its own.
    *
    * @return
    *   The service.
    */
  def prompt[F[_] : Sync]
    (api: ApiService[F], config: PromptConfig)
    : PromptService[F] = ProviderPrompt(api, provider, this, config)

  /**
    * Makes this model's token counter over an API service, where its provider
    * can count, as Anthropic and Gemini can, and OpenAI cannot.
    *
    * @tparam F
    *   The effect type.
    *
    * @param api
    *   The API service of this model's provider.
    *
    * @return
    *   The counter, or `None` where the provider cannot count.
    */
  def counter[F[_] : Sync](api: ApiService[F]): Option[TokenCounter[F]] = Option
    .when(provider.counting(this, Conversation()).isDefined)(
      ProviderCounter(api, provider, this),
    )

  /**
    * The rates Iris lists for this model, as its provider charged them at a
    * moment, such as for estimating a request before it is sent.
    *
    * @param at
    *   The moment, since the start of 1970 in UTC, as `Clock.realTime` gives
    *   it.
    *
    * @return
    *   The rates, or `None` for a model Iris does not list.
    */
  def rates(at: FiniteDuration): Option[Rates] = provider.rates(this, at)

private[iris] object ProviderModel:

  /**
    * The model of a provider that a name names: its listed case, in any case,
    * or else one Iris does not list.
    */
  def of[M <: ProviderModel]
    (
      id: String,
      listed: List[M],
      other: String => M,
    )
    : Option[M] = Some(id.trim)
    .filter(_.nonEmpty)
    .map(id => listed.find(_.id.equalsIgnoreCase(id)).getOrElse(other(id)))
