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
      provider: LlmProvider,
      origin: String,
      path: String*,
    )
    : Either[LlmError, Uri] = Uri
    .parse(origin)
    .bimap(
      LlmError.Misconfigured(provider.displayName, _),
      _.addPath(path),
    )

  /** Refuses an empty chat, or one with a message of breakpoints alone. */
  def answerable(provider: LlmProvider, chat: Chat): Either[LlmError, Unit] =
    for
      _ <- Either.cond(
        chat.messages.nonEmpty,
        (),
        LlmError.Unsendable(
          provider.displayName,
          "it has no messages",
        ),
      )
      _ <- Either.cond(
        !chat.messages.exists(_.silent),
        (),
        LlmError.Unsendable(
          provider.displayName,
          "one of its messages says nothing",
        ),
      )
    yield ()

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
  def plain(parts: Seq[Part]): Option[String] =
    val texts = parts.collect:
      case Part.Text(text) => text
    Option.when(parts.forall(_.isInstanceOf[Part.Text]))(texts.mkString)

  /** A tool's declaration, its schema under the field the provider reads. */
  def declared(tool: Tool, schema: String): Json = Json.obj(
    "name"        -> tool.name.asJson,
    "description" -> tool.description.asJson,
    schema        -> tool.parameters,
  )

  def unsuccessful
    (
      provider: LlmProvider,
      response: Response[?],
      detail: String,
    )
    : LlmError = LlmError.Unsuccessful(
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
      provider: LlmProvider,
      response: Response[Either[String, String]],
    )
    : Either[LlmError, R] = response
    .body
    .leftMap(unsuccessful(provider, response, _))
    .flatMap: body =>
      decode[R](body).leftMap: error =>
        LlmError.Malformed(provider.displayName, error.getMessage)
