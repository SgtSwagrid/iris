package com.alecdorrington.iris

/**
  * The kind of a picture, document or other media in a [[Part.Media]], by its
  * IANA media type. The kinds Iris lists are constants (`MediaType.Png`), so
  * that a provider refuses one it cannot take before any request is spent
  * finding out; any other is an [[MediaType.Other]], sent only to Gemini, which
  * takes audio and video as well, and judges for itself.
  */
sealed trait MediaType:

  /** The media type's name, as `image/png`. */
  def name: String

object MediaType:

  /**
    * The media types Iris lists: the pictures and documents more than one
    * provider takes.
    *
    * @param name
    *   The media type's name.
    */
  enum Listed(val name: String) extends MediaType:

    /** A PNG picture, which every provider takes. */
    case Png extends Listed("image/png")

    /** A JPEG picture, which every provider takes. */
    case Jpeg extends Listed("image/jpeg")

    /**
      * A GIF picture, which Anthropic and OpenAI take, but only its first frame
      * of an animation (OpenAI refuses an animated one), and Gemini does not
      * list.
      */
    case Gif extends Listed("image/gif")

    /** A WebP picture, which every provider takes. */
    case Webp extends Listed("image/webp")

    /** A PDF document, which Anthropic and Gemini take. */
    case Pdf extends Listed("application/pdf")

  export Listed.{fromOrdinal as _, valueOf as _, values as _, *}

  /**
    * A media type Iris does not list, such as `audio/mp3`. Made by
    * [[MediaType.of]], so that a listed media type is always its listed case.
    *
    * @param name
    *   The media type's name, in lower case.
    */
  final case class Other private[MediaType] (name: String) extends MediaType

  /** Every media type Iris lists. */
  val listed: List[MediaType] = Listed.values.toList

  /**
    * Names a media type, as its listed case when Iris lists it.
    *
    * @param name
    *   The media type's name, as `image/png`, in any case, and with any
    *   parameters after a `;` left out.
    *
    * @return
    *   A media type, or `None` where the name is no type and subtype.
    */
  def of(name: String): Option[MediaType] =
    val bare = name.takeWhile(_ != ';').trim.map(_.toLower)
    bare.split('/') match
      case Array(kind, subtype) if kind.nonEmpty && subtype.nonEmpty =>
        Some(listed.find(_.name == bare).getOrElse(Other(bare)))
      case _ => None
