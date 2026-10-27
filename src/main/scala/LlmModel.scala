package com.alecdorrington.iris

/**
  * A model Iris can prompt. Each belongs to the one provider which serves it,
  * so a model is never sent to a provider that does not have it. The models
  * Iris lists are constants (`LlmModel.ClaudeSonnet5_5`), so a name which is
  * not one is refused when it is read rather than when a request is spent
  * finding out; any other is named with its provider, as [[LlmModel.Other]].
  */
sealed trait LlmModel:

  /** The provider which serves the model. */
  def provider: LlmProvider

  /** The model's name in its provider's API. */
  def id: String

  /** The model's name after its provider's, as `anthropic:claude-opus-5-5`. */
  def name: String = s"${ provider.key }:$id"

object LlmModel:

  /**
    * The models Iris lists.
    *
    * @param provider
    *   The provider which serves the model.
    *
    * @param id
    *   The model's name in its provider's API.
    */
  enum Listed(val provider: LlmProvider, val id: String) extends LlmModel:

    case ClaudeFable5_1
      extends Listed(
        LlmProvider.Anthropic,
        "claude-fable-5-1",
      )

    case ClaudeFable5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-fable-5",
      )

    case ClaudeMythos5_1
      extends Listed(
        LlmProvider.Anthropic,
        "claude-mythos-5-1",
      )

    case ClaudeMythos5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-mythos-5",
      )

    case ClaudeOpus5_5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-opus-5-5",
      )

    case ClaudeOpus5 extends Listed(LlmProvider.Anthropic, "claude-opus-5")

    case ClaudeOpus4_8
      extends Listed(
        LlmProvider.Anthropic,
        "claude-opus-4-8",
      )

    case ClaudeOpus4_7
      extends Listed(
        LlmProvider.Anthropic,
        "claude-opus-4-7",
      )

    case ClaudeOpus4_6
      extends Listed(
        LlmProvider.Anthropic,
        "claude-opus-4-6",
      )

    case ClaudeSonnet5_5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-sonnet-5-5",
      )

    case ClaudeSonnet5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-sonnet-5",
      )

    case ClaudeSonnet4_6
      extends Listed(
        LlmProvider.Anthropic,
        "claude-sonnet-4-6",
      )

    case ClaudeHaiku5_5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-haiku-5-5",
      )

    case ClaudeHaiku4_5
      extends Listed(
        LlmProvider.Anthropic,
        "claude-haiku-4-5",
      )

    case Gpt6Astra extends Listed(LlmProvider.OpenAi, "gpt-6-astra")

    case Gpt6_1Sol extends Listed(LlmProvider.OpenAi, "gpt-6.1-sol")

    case Gpt6Sol extends Listed(LlmProvider.OpenAi, "gpt-6-sol")

    case Gpt5_6Sol extends Listed(LlmProvider.OpenAi, "gpt-5.6-sol")

    case Gpt6_1Terra extends Listed(LlmProvider.OpenAi, "gpt-6.1-terra")

    case Gpt5_6Terra extends Listed(LlmProvider.OpenAi, "gpt-5.6-terra")

    case Gpt6Luna extends Listed(LlmProvider.OpenAi, "gpt-6-luna")

    case Gpt5_6Luna extends Listed(LlmProvider.OpenAi, "gpt-5.6-luna")

    case Gpt5 extends Listed(LlmProvider.OpenAi, "gpt-5")

    case Gpt5Mini extends Listed(LlmProvider.OpenAi, "gpt-5-mini")

    case Gemini4Argon extends Listed(LlmProvider.Gemini, "gemini-4-argon")

    case Gemini3_8Flash extends Listed(LlmProvider.Gemini, "gemini-3.8-flash")

    case Gemini3_6Flash extends Listed(LlmProvider.Gemini, "gemini-3.6-flash")

    case Gemini3_5FlashLite
      extends Listed(
        LlmProvider.Gemini,
        "gemini-3.5-flash-lite",
      )

    case Gemini3_1FlashLite
      extends Listed(
        LlmProvider.Gemini,
        "gemini-3.1-flash-lite",
      )

    case Gemini2_5Pro extends Listed(LlmProvider.Gemini, "gemini-2.5-pro")

    case Gemini2_5Flash extends Listed(LlmProvider.Gemini, "gemini-2.5-flash")

    case Gemini2_5FlashLite
      extends Listed(
        LlmProvider.Gemini,
        "gemini-2.5-flash-lite",
      )

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A model Iris does not list, such as one newer than Iris or a proxy's own.
    * Nothing is known of it but its name, so it is sent as its provider's
    * newest models are: without Anthropic's sampling parameters, for one. Made
    * by [[LlmModel.of]], so that a listed model is always its listed case.
    *
    * @param provider
    *   The provider which serves the model.
    *
    * @param id
    *   The model's name in its provider's API.
    */
  final case class Other private[LlmModel] (provider: LlmProvider, id: String)
    extends LlmModel

  /** Every model Iris lists. */
  val listed: List[LlmModel] = Listed.values.toList

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
  def of(provider: LlmProvider, id: String): Option[LlmModel] =
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
  def parse(name: String): Option[LlmModel] =
    listedAs(name).orElse(qualified(name))

  /** The model a name after its provider's and a colon names, if any. */
  private def qualified(name: String): Option[LlmModel] =
    name.split(":", 2) match
      case Array(provider, id) => LlmProvider.parse(provider).flatMap(of(_, id))
      case _                   => None

  /** The model of the given name, refused when another provider serves it. */
  private def served(provider: LlmProvider, id: String): Option[LlmModel] =
    listedAs(id) match
      case Some(model) => Option.when(model.provider == provider)(model)
      case None        => Some(Other(provider, id))

  /** A name trimmed, and without the `models/` Gemini's API may begin it with. */
  private def bare(provider: LlmProvider, id: String): String =
    if provider == LlmProvider.Gemini then id.trim.stripPrefix("models/").trim
    else id.trim

  /** The model Iris lists under the given name, in any case. */
  private def listedAs(id: String): Option[LlmModel] =
    listed.find(_.id.equalsIgnoreCase(id.trim))
