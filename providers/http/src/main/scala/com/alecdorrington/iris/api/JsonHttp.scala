package com.alecdorrington.iris
package api

import cats.syntax.all.*
import com.alecdorrington.iris.prompt.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.parser.decode
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.client4.*
import sttp.model.Uri

/** Shared HTTP plumbing for the JSON APIs of all providers. */
private[iris] object JsonHttp:

  /** Resolves an endpoint beneath an origin, failing on one that is no URL. */
  def endpoint
    (
      provider: Provider,
      origin: String,
      path: String*,
    )
    : Either[ApiError, Uri] = Uri
    .parse(origin)
    .bimap(
      ApiError.Misconfigured(provider.displayName, _),
      _.addPath(path),
    )

  /**
    * Refuses a conversation no provider would answer, as
    * [[Conversation.unanswerable]] says.
    */
  def refuseUnanswerable
    (
      provider: Provider,
      conversation: Conversation,
    )
    : Either[PromptError, Unit] = conversation
    .unanswerable
    .map(PromptError.Unsendable(provider.displayName, _))
    .toLeft(())

  /** Refuses a conversation holding media of a kind the provider does not take. */
  def refuseUnsupportedMedia
    (
      provider: Provider,
      conversation: Conversation,
      taken: Set[MediaType],
    )
    : Either[PromptError, Unit] = conversation
    .messages
    .flatMap(_.parts)
    .collectFirst:
      case MessagePart.Media(mediaType, _) if !taken.contains(mediaType) =>
        PromptError.Unsupported(
          provider.displayName,
          s"sending ${ mediaType.name } in a conversation",
        )
    .toLeft(())

  /**
    * Refuses a conversation holding a tool call whose arguments are no JSON
    * object, as a provider taking them as one could not be sent it.
    */
  def refuseUnreadableArguments
    (
      provider: Provider,
      conversation: Conversation,
    )
    : Either[PromptError, Unit] = conversation
    .messages
    .flatMap(_.parts)
    .collectFirst:
      case MessagePart.ToolCall(_, tool, arguments, _)
        if decode[JsonObject](arguments).isLeft =>
        PromptError.Unsendable(
          provider.displayName,
          s"the arguments of its call of `$tool` are no JSON object",
        )
    .toLeft(())

  /** A provider's role, and the parts of a turn in it. */
  type Turn = (String, List[MessagePart])

  /**
    * The turns of messages, each of a role, those in a row of one role run
    * together into one, as the tools' results and the user's next words are
    * where the tools have no role of their own. The tools' results open a turn
    * holding them, as Anthropic requires, whichever message came first.
    */
  def turns(messages: List[Turn]): List[Turn] = messages
    .foldLeft(Vector.empty[Turn]):
      case (earlier :+ ((role, parts)), (next, more)) if role == next =>
        val (results, others) = (parts ++ more).partition:
          case _: MessagePart.ToolResult => true
          case _                         => false
        earlier :+ (role -> (results ++ others))
      case (earlier, turn) => earlier :+ turn
    .toList

  /**
    * A JSON object of the fields, leaving out null ones. Nulls nested inside a
    * field, as in a tool's schema, are values and are kept.
    */
  def objectOf(fields: (String, Json)*): Json = Json.obj(fields*).dropNullValues

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

  /**
    * The error an unsuccessful response is, as [[Refusal.of]] says, with the
    * wait its provider asks for, read at a moment since 1970, and as its
    * `x-should-retry` header says, if it says.
    */
  def unsuccessful
    (
      api: Provider,
      response: Response[?],
      detail: String,
      now: FiniteDuration,
    )
    : ApiError = Refusal.of(
    api,
    response.code,
    detail,
    api.retryAfter(response.headers, detail, now),
    response.header("x-should-retry").flatMap(Refusal.shouldRetry),
  )

  /** The error a request is that no answer came to within its timeout. */
  def timedOut(api: Provider, config: ApiConfig): ApiError =
    ApiError.TimedOut(api.displayName, config.timeout)
