package com.alecdorrington.iris
package openai

import com.alecdorrington.iris.api.ProviderModel

/**
  * A model OpenAi serves: one Iris lists, as [[OpenAiModel.Gpt5_6Sol]], or
  * another named by [[OpenAiModel.of]].
  */
sealed trait OpenAiModel extends ProviderModel:

  override def provider: OpenAi.type = OpenAi

object OpenAiModel:

  /**
    * The models Iris lists.
    *
    * @param id
    *   The model's name in OpenAi's API.
    */
  enum Listed(val id: String) extends OpenAiModel:

    case Gpt6Astra extends Listed("gpt-6-astra")

    case Gpt6_1Sol extends Listed("gpt-6.1-sol")

    case Gpt6Sol extends Listed("gpt-6-sol")

    case Gpt5_6Sol extends Listed("gpt-5.6-sol")

    case Gpt6_1Terra extends Listed("gpt-6.1-terra")

    case Gpt5_6Terra extends Listed("gpt-5.6-terra")

    case Gpt6Luna extends Listed("gpt-6-luna")

    case Gpt5_6Luna extends Listed("gpt-5.6-luna")

    case Gpt5 extends Listed("gpt-5")

    case Gpt5Mini extends Listed("gpt-5-mini")

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A model Iris does not list, such as one newer than Iris or a proxy's own.
    * Nothing is known of it but its name, so it is sent as OpenAi's newest
    * models are. Made by [[of]], so that a listed model is always its listed
    * case.
    *
    * @param id
    *   The model's name in OpenAi's API.
    */
  final case class Other private[OpenAiModel] (id: String) extends OpenAiModel

  /** Every model Iris lists. */
  val listed: List[OpenAiModel] = Listed.values.toList

  /**
    * Names a model, as its listed case where Iris lists it.
    *
    * @param id
    *   The model's name in OpenAi's API, in any case for a listed model.
    *
    * @return
    *   The model, or `None` where the name is blank.
    */
  def of(id: String): Option[OpenAiModel] =
    ProviderModel.of(id.trim, listed, Other(_))
