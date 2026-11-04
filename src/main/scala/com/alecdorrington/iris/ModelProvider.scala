package com.alecdorrington.iris

import cats.effect.{Async, Resource, Sync}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}

/**
  * A supported LLM provider.
  *
  * @param displayName
  *   The provider's name for display and in errors.
  *
  * @param origin
  *   The origin of the provider's API, used unless one is configured.
  */
enum ModelProvider
  (
    val displayName: String,
    val origin: String,
  ):

  /** Anthropic's Messages API. */
  case Anthropic
    extends ModelProvider(
      "Anthropic",
      "https://api.anthropic.com",
    )

  /** OpenAI's Chat Completions API. */
  case OpenAi extends ModelProvider("OpenAI", "https://api.openai.com")

  /** Google's Gemini API. */
  case Gemini
    extends ModelProvider(
      "Gemini",
      "https://generativelanguage.googleapis.com",
    )

  /** The model prompted when none is configured. */
  def defaultModel: Model = this match
    case Anthropic => Model.ClaudeSonnet5_5
    case OpenAi    => Model.Gpt5_6Sol
    case Gemini    => Model.Gemini3_8Flash

  /** The provider's name as [[ModelProvider.parse]] reads it, such as `openai`. */
  def id: String = toString.map(_.toLower)

  /**
    * Makes the provider's API service, with an HTTP client of its own, which
    * gives each whole response at most the configured timeout, from sending the
    * request to reading the response whole, and each next part of a streamed
    * one as long, and sends a request again where the provider refuses or fails
    * it for now, as the configuration says.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @return
    *   A resource holding the service and its HTTP client.
    */
  def api[F[_] : Async]
    (config: ApiConfig = ApiConfig())
    : Resource[F, ApiService[F]] = Platform
    .backends[F]
    .map(backends =>
      HttpApi(
        ProviderApi.of(this),
        config,
        key(config),
        Deadlined(backends.sending),
        Some(Streaming(
          ProviderApi.of(this),
          config,
          backends.streaming,
        )),
      ).retrying(config),
    )

  /**
    * Makes the provider's API service over a host's backend that cannot stream,
    * which times requests out as it does itself, and sends each once. A
    * streamed request is answered whole, its events all at once.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @param backend
    *   The backend to send requests through.
    *
    * @return
    *   The service.
    */
  def api[F[_] : Sync](config: ApiConfig, backend: Backend[F]): ApiService[F] =
    HttpApi(
      ProviderApi.of(this),
      config,
      key(config),
      backend,
      None,
    )

  /**
    * Makes the provider's API service over a host's backend that can stream,
    * which times requests out as it does itself, but for each next part of a
    * streamed response, given the configured timeout. It sends a request again
    * where the provider refuses or fails it for now, as the configuration says,
    * but aborts nothing of the backend's when a stream is given up on.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @param backend
    *   The backend to send requests through.
    *
    * @return
    *   The service.
    */
  def api[F[_] : Async]
    (
      config: ApiConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : ApiService[F] = HttpApi(
    ProviderApi.of(this),
    config,
    key(config),
    backend,
    Some(Streaming(
      ProviderApi.of(this),
      config,
      Async[F].pure(Exchange(backend, Async[F].unit)),
    )),
  ).retrying(config)

  /** The configured key, else the one in the provider's own variable. */
  private def key(config: ApiConfig): Option[String] = config
    .key
    .orElse(key(Model.env))

  /** The key in the provider's own variable, read by a lookup. */
  private[iris] def key(variable: String => Option[String]): Option[String] =
    keyVariables.flatMap(variable).headOption

  /** The variables the provider's key may be in, in the order read. */
  def keyVariables: List[String] = this match
    case Anthropic => List("ANTHROPIC_API_KEY")
    case OpenAi    => List("OPENAI_API_KEY")
    case Gemini    => List("GEMINI_API_KEY", "GOOGLE_API_KEY")

object ModelProvider:

  /**
    * Parses a provider from its name, ignoring case.
    *
    * @param name
    *   The name: `anthropic`, `openai`, or `gemini` or `google`.
    *
    * @return
    *   A provider, or `None` for an unknown name.
    */
  def parse(name: String): Option[ModelProvider] =
    // By character, not by the default locale's rules, by which `OPENAI` lowers
    // to `openaı` in Turkish; nor by `Locale.ROOT`'s, as JavaScript has none.
    name.trim.map(_.toLower) match
      case "anthropic"         => Some(Anthropic)
      case "openai"            => Some(OpenAi)
      case "gemini" | "google" => Some(Gemini)
      case _                   => None
