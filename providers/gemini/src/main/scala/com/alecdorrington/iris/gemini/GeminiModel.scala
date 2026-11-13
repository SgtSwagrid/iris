package com.alecdorrington.iris
package gemini

import com.alecdorrington.iris.api.ProviderModel

/**
  * A model Gemini serves: one Iris lists, as [[GeminiModel.Gemini3_8Flash]], or
  * another named by [[GeminiModel.of]].
  */
sealed trait GeminiModel extends ProviderModel:

  override def provider: Gemini.type = Gemini

object GeminiModel:

  /**
    * The models Iris lists.
    *
    * @param id
    *   The model's name in Gemini's API.
    */
  enum Listed(val id: String) extends GeminiModel:

    case Gemini4Carbon extends Listed("gemini-4-carbon")

    case Gemini4Argon extends Listed("gemini-4-argon")

    case Gemini4Hydrogen extends Listed("gemini-4-hydrogen")

    case Gemini3_8Flash extends Listed("gemini-3.8-flash")

    case Gemini3_6Flash extends Listed("gemini-3.6-flash")

    case Gemini3_5FlashLite extends Listed("gemini-3.5-flash-lite")

    case Gemini3_1FlashLite extends Listed("gemini-3.1-flash-lite")

    case Gemini2_5Pro extends Listed("gemini-2.5-pro")

    case Gemini2_5Flash extends Listed("gemini-2.5-flash")

    case Gemini2_5FlashLite extends Listed("gemini-2.5-flash-lite")

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A model Iris does not list, such as one newer than Iris or a proxy's own.
    * Nothing is known of it but its name, so it is sent as Gemini's newest
    * models are. Made by [[of]], so that a listed model is always its listed
    * case.
    *
    * @param id
    *   The model's name in Gemini's API.
    */
  final case class Other private[GeminiModel] (id: String) extends GeminiModel

  /** Every model Iris lists. */
  val listed: List[GeminiModel] = Listed.values.toList

  /**
    * Names a model, as its listed case where Iris lists it.
    *
    * @param id
    *   The model's name in Gemini's API, in any case for a listed model, and
    *   optionally after `models/`.
    *
    * @return
    *   The model, or `None` where the name is blank.
    */
  def of(id: String): Option[GeminiModel] = ProviderModel.of(
    id.trim.stripPrefix("models/"),
    listed,
    Other(_),
  )
