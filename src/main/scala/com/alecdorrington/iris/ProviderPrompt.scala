package com.alecdorrington.iris

import cats.effect.Sync
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Decoder, Json}
import scala.Conversion.into

/**
  * The [[PromptService]] of a model, speaking its provider's API through an
  * [[ApiService]] as its [[ProviderApi]] says, each response priced as it ends,
  * at the configuration's rates, else those listed for the model.
  */
private[iris] final class ProviderPrompt[F[_] : Sync]
  (
    api: ApiService[F],
    provider: ProviderApi,
    model: Model,
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
          provider.path(model, settings.streamed),
          provider.body(model, sent, settings),
          settings.streamed,
        ))
        .through(if settings.streamed then provider.deltas[F] else wholes)
        .through(gathered)

  /** The conversation as sent, closed with a breakpoint where asked. */
  private def closed(request: PromptRequest): Conversation =
    val conversation = request.conversation
    if !request.cacheAll || conversation.messages.isEmpty then conversation
    else
      conversation.copy(messages =
        conversation.messages.init :+ conversation.messages.last.closed,
      )

  /** The deltas of whole responses, each as it would have arrived. */
  private def wholes(bodies: Stream[F, Json]): Stream[F, Delta] = bodies
    .flatMap(body =>
      Stream
        .fromEither[F](read(body, provider.wholeResponse).flatten)
        .flatMap(response => Stream.emits(Delta.of(response))),
    )

  /** The events deltas make, the response gathered and priced at their end. */
  private def gathered(deltas: Stream[F, Delta]): Stream[F, PromptEvent] =
    deltas
      .evalMapAccumulate(PromptResponse("")):
        case (so, Delta.Text(text)) => (
            so.copy(text = so.text + text),
            PromptEvent.Written(PromptEvent.Part.Text, text),
          ).pure[F]
        case (so, Delta.ToolCall(call)) => (
            so.copy(toolCalls = so.toolCalls :+ call),
            PromptEvent.Written(
              PromptEvent.Part.Call(call.id, call.tool, call.signature),
              call.arguments,
            ),
          ).pure[F]
        case (so, Delta.End(stopReason, usage)) => Sync[F]
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

  /** A body read as the provider's response, or why it cannot be. */
  private def read[A](body: Json, decoder: Decoder[A]): Either[ApiError, A] =
    ProviderPrompt.read(provider, body, decoder)

private[iris] object ProviderPrompt:

  /** A body read by a decoder, or why it cannot be. */
  def read[A]
    (
      provider: ProviderApi,
      body: Json,
      decoder: Decoder[A],
    )
    : Either[ApiError, A] = decoder
    .decodeJson(body)
    .leftMap(failure =>
      ApiError.Malformed(
        provider.provider.displayName,
        failure.getMessage,
      ),
    )

/** The [[TokenCounter]] of a model, whose provider counts as it says. */
private[iris] final class ProviderCounter[F[_] : Sync]
  (
    api: ApiService[F],
    provider: ProviderApi,
    model: Model,
  )
  extends TokenCounter[F]:

  override def countTokens(request: into[PromptRequest]): F[Int] = provider
    .counting(model, request.conversation)
    .getOrElse(Left(PromptError.Unsupported(
      provider.provider.displayName,
      "counting tokens",
    )))
    .liftTo[F]
    .flatMap(counting =>
      api
        .send(ApiRequest(counting.path, counting.body))
        .flatMap(ProviderPrompt.read(provider, _, counting.tokens).liftTo[F]),
    )
