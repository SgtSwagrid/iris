package com.alecdorrington.iris

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * A supported LLM provider.
  *
  * @param displayName
  *   The provider's name for display and in errors.
  *
  * @param origin
  *   The origin of the provider's API, used unless one is configured.
  */
enum LlmProvider
  (
    val displayName: String,
    val origin: String,
  ):

  /** Anthropic's Messages API. */
  case Anthropic
    extends LlmProvider(
      "Anthropic",
      "https://api.anthropic.com",
    )

  /** OpenAI's Chat Completions API. */
  case OpenAi extends LlmProvider("OpenAI", "https://api.openai.com")

  /** Google's Gemini API. */
  case Gemini
    extends LlmProvider(
      "Gemini",
      "https://generativelanguage.googleapis.com",
    )

  /** The model prompted when none is configured. */
  def defaultModel: LlmModel = this match
    case Anthropic => LlmModel.ClaudeSonnet5_5
    case OpenAi    => LlmModel.Gpt5_6Sol
    case Gemini    => LlmModel.Gemini3_8Flash

  /** The provider's name as [[LlmProvider.parse]] reads it, such as `openai`. */
  def key: String = toString.map(_.toLower)

object LlmProvider:

  /**
    * Parses a provider from its name, ignoring case.
    *
    * @param name
    *   The name: `anthropic`, `openai`, or `gemini` or `google`.
    *
    * @return
    *   A provider, or `None` for an unknown name.
    */
  def parse(name: String): Option[LlmProvider] =
    // By character, not by the default locale's rules, by which `OPENAI` lowers
    // to `openaı` in Turkish; nor by `Locale.ROOT`'s, as JavaScript has none.
    name.trim.map(_.toLower) match
      case "anthropic"         => Some(Anthropic)
      case "openai"            => Some(OpenAi)
      case "gemini" | "google" => Some(Gemini)
      case _                   => None

/**
  * A configuration for connecting to the LLM provider serving its model. Its
  * `toString` leaves out the API key, so it is safe to log.
  *
  * @param model
  *   The model to prompt; another model needs another configuration.
  *
  * @param apiKey
  *   The API key to authenticate with.
  *
  * @param maxTokens
  *   The reply's token limit, unless overridden per request.
  *
  * @param baseUrl
  *   The API origin (scheme and host, with no trailing `/`) overriding the
  *   provider's, such as a proxy's or a test stub's.
  *
  * @param timeout
  *   The longest to wait for a reply, from sending the request to reading the
  *   reply whole, or, for a stream, for each next part of it.
  */
final case class LlmConfig
  (
    model: LlmModel,
    apiKey: String,
    maxTokens: Int,
    baseUrl: Option[String] = None,
    timeout: FiniteDuration = LlmConfig.defaultTimeout,
  ):

  /** The provider whose API to use: the one serving [[model]]. */
  def provider: LlmProvider = model.provider

  private[iris] def limit(options: ReplyOptions): Int = options
    .maxTokens
    .getOrElse(maxTokens)

  /** The API origin to send to: [[baseUrl]] when set, else the provider's. */
  def origin: String = baseUrl.getOrElse(provider.origin)

  override def toString: String =
    s"LlmConfig(${ model.name }, <redacted>, $maxTokens, $baseUrl, $timeout)"

object LlmConfig:

  /** The environment variables that may hold provider API keys. */
  val apiKeyVariables: List[String] = LlmProvider
    .values
    .toList
    .flatMap(keyVariables)

  /** The reply's token limit when neither the variable nor the host sets one. */
  val defaultMaxTokens: Int = 8192

  /** How long to wait for a reply when no timeout is configured. */
  val defaultTimeout: FiniteDuration = 5.minutes

  /** The optional environment variables read by [[fromEnv]]. */
  val optionalVariables: List[String] = List(
    "LLM_PROVIDER",
    "LLM_MODEL",
    "LLM_MAX_TOKENS",
    "LLM_BASE_URL",
    "LLM_TIMEOUT",
  )

  /**
    * Loads a configuration from environment variables:
    *   - `LLM_PROVIDER`: `anthropic`, `openai` or `gemini`, else the provider
    *     of `LLM_MODEL`, else inferred from whichever API key is set.
    *   - `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY` (or
    *     `GOOGLE_API_KEY`): the API key.
    *   - `LLM_MODEL`: the model, as [[LlmModel.parse]] reads it, else the
    *     provider's default.
    *   - `LLM_MAX_TOKENS`: the reply's token limit, else `defaultMaxTokens`.
    *   - `LLM_BASE_URL`: the API origin, else the provider's.
    *   - `LLM_TIMEOUT`: the timeout in seconds, else [[defaultTimeout]].
    *
    * @param defaultMaxTokens
    *   The reply's token limit when `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   A configuration, or `None` when no API key is set, or when a set
    *   variable is invalid: an unknown provider or model, a model the provider
    *   does not serve, or a limit or timeout that is not a positive whole
    *   number.
    */
  def fromEnv
    (defaultMaxTokens: Int = LlmConfig.defaultMaxTokens)
    : Option[LlmConfig] = from(env, defaultMaxTokens)

  /**
    * Loads configuration as [[fromEnv]] does, reading each variable through the
    * given lookup instead of the environment, so that several configurations
    * can be read from one environment under names of the caller's choosing.
    *
    * @param variable
    *   The value of the variable of the given name, or `None` when it is unset.
    *
    * @param defaultMaxTokens
    *   The reply's token limit when `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   A configuration, or `None` when no provider API key is set, or when a
    *   variable which is set cannot be used.
    */
  def from
    (
      variable: String => Option[String],
      defaultMaxTokens: Int = LlmConfig.defaultMaxTokens,
    )
    : Option[LlmConfig] =
    val named = variable("LLM_MODEL")
    for
      provider <- variable("LLM_PROVIDER").fold(
        named.fold(inferProvider(variable))(LlmModel.parse(_).map(_.provider)),
      )(LlmProvider.parse)
      model <- named.fold(
        Some(provider.defaultModel),
      )(LlmModel.parse(_).filter(_.provider == provider))
      apiKey    <- apiKey(variable, provider)
      maxTokens <-
        variable("LLM_MAX_TOKENS").fold(Some(defaultMaxTokens))(positive)
      timeout <- variable("LLM_TIMEOUT").fold(Some(defaultTimeout))(seconds)
    yield LlmConfig(
      model = model,
      apiKey = apiKey,
      maxTokens = maxTokens,
      baseUrl = variable("LLM_BASE_URL"),
      timeout = timeout,
    )

  private[iris] def seconds(value: String): Option[FiniteDuration] =
    positive(value).map(_.seconds)

  /** Reads a positive whole number, or `None`, never a hidden default. */
  private[iris] def positive(value: String): Option[Int] = value
    .trim
    .toIntOption
    .filter(_ > 0)

  private def inferProvider
    (variable: String => Option[String])
    : Option[LlmProvider] = LlmProvider
    .values
    .find(apiKey(variable, _).isDefined)

  private def apiKey
    (
      variable: String => Option[String],
      provider: LlmProvider,
    )
    : Option[String] = keyVariables(provider).flatMap(variable).headOption

  private def keyVariables(provider: LlmProvider): List[String] = provider match
    case LlmProvider.Anthropic => List("ANTHROPIC_API_KEY")
    case LlmProvider.OpenAi    => List("OPENAI_API_KEY")
    case LlmProvider.Gemini    => List("GEMINI_API_KEY", "GOOGLE_API_KEY")

  private def env(name: String): Option[String] = sys
    .env
    .get(name)
    .filter(_.nonEmpty)
