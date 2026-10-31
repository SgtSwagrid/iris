package com.alecdorrington.iris

import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sttp.client4.*
import sttp.model.Uri

/** Shared HTTP plumbing for the JSON APIs of all providers. */
private[iris] object JsonHttp:

  /** Resolves an endpoint beneath an origin, failing on one that is no URL. */
  def endpoint
    (
      provider: ModelProvider,
      origin: String,
      path: String*,
    )
    : Either[ModelError, Uri] = Uri
    .parse(origin)
    .bimap(
      ModelError.Misconfigured(provider.displayName, _),
      _.addPath(path),
    )

  /** Refuses a chat no provider would answer, as [[Chat.unanswerable]] says. */
  def refuseUnanswerable
    (provider: ModelProvider, chat: Chat)
    : Either[ModelError, Unit] = chat
    .unanswerable
    .map(ModelError.Unsendable(provider.displayName, _))
    .toLeft(())

  /** Refuses a chat holding media of a kind the provider does not take. */
  def refuseUnsupportedMedia
    (
      provider: ModelProvider,
      chat: Chat,
      taken: Set[MediaType],
    )
    : Either[ModelError, Unit] = chat
    .messages
    .flatMap(_.parts)
    .collectFirst:
      case MessagePart.Media(mediaType, _) if !taken.contains(mediaType) =>
        ModelError.Unsupported(
          provider.displayName,
          s"sending ${ mediaType.name } in a chat",
        )
    .toLeft(())

  /**
    * A JSON object of the fields, leaving out null ones. Nulls nested inside a
    * field, as in a tool's schema, are values and are kept.
    */
  def objectOf(fields: (String, Json)*): Json = Json.obj(fields*).dropNullValues

  def body(fields: (String, Json)*): String = objectOf(fields*).noSpaces

  def unlessEmpty[A : Encoder](items: List[A]): Json = Option
    .when(items.nonEmpty)(items)
    .asJson

  /**
    * The text of parts already [[Message.separated]], where they are text
    * alone.
    */
  def plain(parts: Seq[MessagePart]): Option[String] =
    val texts = parts.collect:
      case MessagePart.Text(text) => text
    Option.when(parts.forall(_.isInstanceOf[MessagePart.Text]))(texts.mkString)

  /** A tool's declaration, its schema under the field the provider reads. */
  def declared(tool: Tool, schema: String): Json = Json.obj(
    "name"        -> tool.name.asJson,
    "description" -> tool.description.asJson,
    schema        -> tool.parameters,
  )

  def unsuccessful
    (
      provider: ModelProvider,
      response: Response[?],
      detail: String,
    )
    : ModelError = ModelError.Unsuccessful(
    provider.displayName,
    response.code,
    retryAfter(response.header("Retry-After")),
    detail,
  )

  /** The `Retry-After` delay, where given in seconds; a date is not read. */
  private[iris] def retryAfter(header: Option[String]): Option[FiniteDuration] =
    header.map(_.trim).flatMap(_.toIntOption).filter(_ >= 0).map(_.seconds)

  /** Decodes a response's body, failing on an unsuccessful or unreadable one. */
  def parse[R : Decoder]
    (
      provider: ModelProvider,
      response: Response[Either[String, String]],
    )
    : Either[ModelError, R] = response
    .body
    .leftMap(unsuccessful(provider, response, _))
    .flatMap: body =>
      decode[R](body).leftMap: error =>
        ModelError.Malformed(provider.displayName, error.getMessage)
