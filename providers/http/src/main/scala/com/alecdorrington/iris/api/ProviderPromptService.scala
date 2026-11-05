package com.alecdorrington.iris
package api

import cats.effect.Sync
import cats.syntax.all.*
import com.alecdorrington.iris.prompt.*
import fs2.Stream
import io.circe.{Decoder, Json}
import scala.Conversion.into

/**
  * The [[PromptService]] of a model, speaking its provider's API through an
  * [[ApiService]] as its [[Provider]] says, each response priced as it ends, at
  * the configuration's rates, else those listed for the model.
  */
private[iris] final class ProviderPromptService[F[_] : Sync]
  (
    api: ApiService[F],
    provider: Provider,
    model: ProviderModel,
    config: PromptConfig,
  )
  extends PromptService[F]:

  override def stream(request: into[PromptRequest]): Stream[F, PromptEvent] =
    val sent     = closed(request)
    val asked    = config.settings(request)
    val settings =
      asked.copy(maxTokens = asked.maxTokens max provider.leastMaxTokens)
    Stream.fromEither[F](provider.refuseUnsendable(model, sent, settings)) >>
      api
        .stream(ApiRequest(
          provider.path(model),
          provider.body(model, sent, settings),
          streamed = true,
        ))
        .through(provider.deltas[F])
        .through(gathered)

  /** The conversation as sent, closed with a breakpoint where asked. */
  private def closed(request: PromptRequest): Conversation =
    val conversation = request.conversation
    if !request.cacheAll || conversation.messages.isEmpty then conversation
    else
      conversation.copy(messages =
        conversation.messages.init :+ conversation.messages.last.closed,
      )

  /**
    * The events deltas make, the response gathered and priced at their end,
    * failing where they end before the provider says the response did.
    */
  private def gathered
    (deltas: Stream[F, ProviderEvent])
    : Stream[F, PromptEvent] = deltas
    .evalMapAccumulate(PromptResponse("")):
      case (so, ProviderEvent.Text(text)) => (
          so.copy(text = so.text + text),
          PromptEvent.Written(PromptEvent.Part.Text, text),
        ).pure[F]
      case (so, ProviderEvent.ToolCall(call)) => (
          so.copy(toolCalls = so.toolCalls :+ call),
          PromptEvent.Written(
            PromptEvent.Part.Call(call.id, call.tool, call.signature),
            call.arguments,
          ),
        ).pure[F]
      case (so, ProviderEvent.End(stopReason, usage)) => Sync[F]
          .realTime
          .map(now =>
            val ended = so.copy(
              stopReason = stopReason,
              usage = usage,
              cost = config
                .rates
                .orElse(provider.rates(model, now))
                .map(_.cost(usage)),
            )
            (ended, PromptEvent.Ended(ended)),
          )
    .map(_._2)
    .noneTerminate
    .zipWithPrevious
    .flatMap:
      case (_, Some(event))                         => Stream.emit(event)
      case (Some(Some(_: PromptEvent.Ended)), None) => Stream.empty
      case (_, None) => Stream.raiseError[F](ApiError.Malformed(
          provider.displayName,
          "the stream ended before it said the response had",
        ))

private[iris] object ProviderPromptService:

  /** A body read by a decoder, or why it cannot be. */
  def read[A]
    (
      provider: Provider,
      body: Json,
      decoder: Decoder[A],
    )
    : Either[ApiError, A] = decoder
    .decodeJson(body)
    .leftMap(failure =>
      ApiError.Malformed(
        provider.displayName,
        failure.getMessage,
      ),
    )

/** The [[TokenCounter]] of a model, whose provider counts as it says. */
private[iris] final class ProviderTokenCounter[F[_] : Sync]
  (
    api: ApiService[F],
    provider: Provider,
    model: ProviderModel,
  )
  extends TokenCounter[F]:

  override def countTokens(request: into[PromptRequest]): F[Int] = provider
    .counting(model, request.conversation)
    .getOrElse(Left(PromptError.Unsupported(
      provider.displayName,
      "counting tokens",
    )))
    .liftTo[F]
    .flatMap(counting =>
      api
        .send(ApiRequest(counting.path, counting.body))
        .flatMap(
          ProviderPromptService.read(provider, _, counting.tokens).liftTo[F],
        ),
    )
