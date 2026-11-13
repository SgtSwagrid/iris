package com.alecdorrington.iris
package anthropic

import com.alecdorrington.iris.api.ProviderModel

/**
  * A model Anthropic serves: one Iris lists, as
  * [[AnthropicModel.ClaudeSonnet5_5]], or another named by
  * [[AnthropicModel.of]].
  */
sealed trait AnthropicModel extends ProviderModel:

  override def provider: Anthropic.type = Anthropic

object AnthropicModel:

  /**
    * The models Iris lists.
    *
    * @param id
    *   The model's name in Anthropic's API.
    */
  enum Listed(val id: String) extends AnthropicModel:

    case ClaudeFable5_2 extends Listed("claude-fable-5-2")

    case ClaudeFable5_1 extends Listed("claude-fable-5-1")

    case ClaudeFable5 extends Listed("claude-fable-5")

    case ClaudeMythos5_2 extends Listed("claude-mythos-5-2")

    case ClaudeMythos5_1 extends Listed("claude-mythos-5-1")

    case ClaudeMythos5 extends Listed("claude-mythos-5")

    case ClaudeOpus5_5 extends Listed("claude-opus-5-5")

    case ClaudeOpus5 extends Listed("claude-opus-5")

    case ClaudeOpus4_8 extends Listed("claude-opus-4-8")

    case ClaudeOpus4_7 extends Listed("claude-opus-4-7")

    case ClaudeOpus4_6 extends Listed("claude-opus-4-6")

    case ClaudeSonnet5_5 extends Listed("claude-sonnet-5-5")

    case ClaudeSonnet5 extends Listed("claude-sonnet-5")

    case ClaudeSonnet4_6 extends Listed("claude-sonnet-4-6")

    case ClaudeHaiku5_5 extends Listed("claude-haiku-5-5")

    case ClaudeHaiku4_5 extends Listed("claude-haiku-4-5")

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A model Iris does not list, such as one newer than Iris or a proxy's own.
    * Nothing is known of it but its name, so it is sent as Anthropic's newest
    * models are. Made by [[of]], so that a listed model is always its listed
    * case.
    *
    * @param id
    *   The model's name in Anthropic's API.
    */
  final case class Other private[AnthropicModel] (id: String)
    extends AnthropicModel

  /** Every model Iris lists. */
  val listed: List[AnthropicModel] = Listed.values.toList

  /**
    * Names a model, as its listed case where Iris lists it.
    *
    * @param id
    *   The model's name in Anthropic's API, in any case for a listed model.
    *
    * @return
    *   The model, or `None` where the name is blank.
    */
  def of(id: String): Option[AnthropicModel] =
    ProviderModel.of(id.trim, listed, Other(_))
