package com.alecdorrington.iris

import cats.effect.{Async, Resource, Sync}

/**
  * A model Iris can prompt. Each belongs to the one provider which serves it,
  * so a model is never sent to a provider that does not have it. The models
  * Iris lists are constants (`Model.ClaudeSonnet5_5`), so a name which is not
  * one is refused when it is read rather than when a request is spent finding
  * out; any other is named with its provider, as [[Model.Other]].
  */
sealed trait Model:

  /** The provider which serves the model. */
  def provider: ModelProvider

  /** The model's name in its provider's API. */
  def id: String

  /** The model's name after its provider's, as `anthropic:claude-opus-5-5`. */
  def name: String = s"${ provider.id }:$id"

  /**
    * Makes this model's prompt service, with an API service of its own, made by
    * [[ModelProvider.api]], whose key is the provider's own variable's unless
    * the API's configuration gives one.
    *
    * {{{
    * Model.ClaudeHaiku5_5.prompt[IO]().use(_.send("Hello!"))
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
    : PromptService[F] = ProviderPrompt(
    api,
    ProviderApi.of(provider),
    this,
    config,
  )

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
  def counter[F[_] : Sync](api: ApiService[F]): Option[TokenCounter[F]] =
    val spoken = ProviderApi.of(provider)
    Option.when(spoken.counting(this, Conversation()).isDefined)(
      ProviderCounter(api, spoken, this),
    )

object Model:

  /**
    * The models Iris lists.
    *
    * @param provider
    *   The provider which serves the model.
    *
    * @param id
    *   The model's name in its provider's API.
    */
  enum Listed
    (
      val provider: ModelProvider,
      val id: String,
    )
    extends Model:

    case ClaudeFable5_1
      extends Listed(
        ModelProvider.Anthropic,
        "claude-fable-5-1",
      )

    case ClaudeFable5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-fable-5",
      )

    case ClaudeMythos5_1
      extends Listed(
        ModelProvider.Anthropic,
        "claude-mythos-5-1",
      )

    case ClaudeMythos5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-mythos-5",
      )

    case ClaudeOpus5_5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-opus-5-5",
      )

    case ClaudeOpus5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-opus-5",
      )

    case ClaudeOpus4_8
      extends Listed(
        ModelProvider.Anthropic,
        "claude-opus-4-8",
      )

    case ClaudeOpus4_7
      extends Listed(
        ModelProvider.Anthropic,
        "claude-opus-4-7",
      )

    case ClaudeOpus4_6
      extends Listed(
        ModelProvider.Anthropic,
        "claude-opus-4-6",
      )

    case ClaudeSonnet5_5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-sonnet-5-5",
      )

    case ClaudeSonnet5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-sonnet-5",
      )

    case ClaudeSonnet4_6
      extends Listed(
        ModelProvider.Anthropic,
        "claude-sonnet-4-6",
      )

    case ClaudeHaiku5_5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-haiku-5-5",
      )

    case ClaudeHaiku4_5
      extends Listed(
        ModelProvider.Anthropic,
        "claude-haiku-4-5",
      )

    case Gpt6Astra extends Listed(ModelProvider.OpenAi, "gpt-6-astra")

    case Gpt6_1Sol extends Listed(ModelProvider.OpenAi, "gpt-6.1-sol")

    case Gpt6Sol extends Listed(ModelProvider.OpenAi, "gpt-6-sol")

    case Gpt5_6Sol extends Listed(ModelProvider.OpenAi, "gpt-5.6-sol")

    case Gpt6_1Terra extends Listed(ModelProvider.OpenAi, "gpt-6.1-terra")

    case Gpt5_6Terra extends Listed(ModelProvider.OpenAi, "gpt-5.6-terra")

    case Gpt6Luna extends Listed(ModelProvider.OpenAi, "gpt-6-luna")

    case Gpt5_6Luna extends Listed(ModelProvider.OpenAi, "gpt-5.6-luna")

    case Gpt5 extends Listed(ModelProvider.OpenAi, "gpt-5")

    case Gpt5Mini extends Listed(ModelProvider.OpenAi, "gpt-5-mini")

    case Gemini4Argon extends Listed(ModelProvider.Gemini, "gemini-4-argon")

    case Gemini3_8Flash
      extends Listed(
        ModelProvider.Gemini,
        "gemini-3.8-flash",
      )

    case Gemini3_6Flash
      extends Listed(
        ModelProvider.Gemini,
        "gemini-3.6-flash",
      )

    case Gemini3_5FlashLite
      extends Listed(
        ModelProvider.Gemini,
        "gemini-3.5-flash-lite",
      )

    case Gemini3_1FlashLite
      extends Listed(
        ModelProvider.Gemini,
        "gemini-3.1-flash-lite",
      )

    case Gemini2_5Pro extends Listed(ModelProvider.Gemini, "gemini-2.5-pro")

    case Gemini2_5Flash
      extends Listed(
        ModelProvider.Gemini,
        "gemini-2.5-flash",
      )

    case Gemini2_5FlashLite
      extends Listed(
        ModelProvider.Gemini,
        "gemini-2.5-flash-lite",
      )

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A model Iris does not list, such as one newer than Iris or a proxy's own.
    * Nothing is known of it but its name, so it is sent as its provider's
    * newest models are: without Anthropic's sampling parameters, for one. Made
    * by [[Model.of]], so that a listed model is always its listed case.
    *
    * @param provider
    *   The provider which serves the model.
    *
    * @param id
    *   The model's name in its provider's API.
    */
  final case class Other private[Model] (provider: ModelProvider, id: String)
    extends Model

  /** Every model Iris lists. */
  val listed: List[Model] = Listed.values.toList

  /**
    * Names a model its provider serves, as its listed case when Iris lists it.
    *
    * @param provider
    *   The provider which serves the model.
    *
    * @param id
    *   The model's name in its provider's API, in any case for a listed model,
    *   and for Gemini optionally after `models/`.
    *
    * @return
    *   A model, or `None` when the name is blank or names a model another
    *   provider serves.
    */
  def of(provider: ModelProvider, id: String): Option[Model] =
    Some(bare(provider, id)).filter(_.nonEmpty).flatMap(served(provider, _))

  /**
    * Parses a model from its name in its provider's API, which must be one Iris
    * lists, in any case, or else from that name after its provider's and a
    * colon, as `anthropic:claude-sonnet-5-5`, which may name any model.
    *
    * @param name
    *   The model's name, alone or after its provider's.
    *
    * @return
    *   A model, or `None` when the name is neither a model Iris lists nor after
    *   a provider's, or names a model another provider serves.
    */
  def parse(name: String): Option[Model] =
    listedAs(name).orElse(qualified(name))

  /** The model a name after its provider's and a colon names, if any. */
  private def qualified(name: String): Option[Model] = name.split(":", 2) match
    case Array(provider, id) => ModelProvider.parse(provider).flatMap(of(_, id))
    case _                   => None

  /** The model of the given name, refused when another provider serves it. */
  private def served(provider: ModelProvider, id: String): Option[Model] =
    listedAs(id) match
      case Some(model) => Option.when(model.provider == provider)(model)
      case None        => Some(Other(provider, id))

  /** A name trimmed, and without the `models/` Gemini's API may begin it with. */
  private def bare(provider: ModelProvider, id: String): String =
    if provider == ModelProvider.Gemini then id.trim.stripPrefix("models/").trim
    else id.trim

  /** The model Iris lists under the given name, in any case. */
  private def listedAs(id: String): Option[Model] =
    listed.find(_.id.equalsIgnoreCase(id.trim))

  /**
    * Reads the model from the environment: by `LLM_MODEL`, as [[parse]] reads
    * it, else the default model of the provider `LLM_PROVIDER` names, else that
    * of the first provider whose key is set.
    *
    * @return
    *   The model, or `None` where none is configured, or a variable is set but
    *   unrecognised, as a set but unknown provider or model is never taken to
    *   mean another.
    */
  def fromEnv(): Option[Model] = from(env)

  /**
    * Reads the model from variables, as [[fromEnv]] does.
    *
    * @param variable
    *   Looks up a variable by name.
    *
    * @return
    *   The model, or `None` where none is configured.
    */
  def from(variable: String => Option[String]): Option[Model] =
    val named = variable("LLM_MODEL")
    for
      provider <- variable("LLM_PROVIDER").fold(
        named.fold(
          ModelProvider.values.find(_.key(variable).isDefined),
        )(parse(_).map(_.provider)),
      )(ModelProvider.parse)
      model <- named.fold(Some(provider.defaultModel))(parse(_).filter(
        _.provider == provider,
      ))
      _ <- provider.key(variable)
    yield model

  /** An environment variable, where it is set and not blank. */
  private[iris] def env(name: String): Option[String] = sys
    .env
    .get(name)
    .filter(_.nonEmpty)

  /** A positive whole number, as a variable may give one. */
  private[iris] def positive(value: String): Option[Int] = value
    .trim
    .toIntOption
    .filter(_ > 0)
