package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import fs2.Stream
import io.circe.Decoder
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] for any provider's JSON API, as its [[ProviderApi]] says,
  * streaming through an [[SseClient]] where its backend can, and otherwise
  * giving each reply whole.
  */
private[iris] final class JsonClient[F[_] : MonadThrow]
  (
    api: ProviderApi,
    config: LlmConfig,
    backend: Backend[F],
    streamer: Option[SseClient[F]],
  )
  extends LlmClient[F]:

  override protected def send(chat: Chat, options: ReplyOptions): F[Reply] =
    MonadThrow[F]
      .fromEither(
        api
          .acceptable(config, chat, options)
          .flatMap(_ => api.endpoint(config, options, false)),
      )
      .flatMap(endpoint =>
        ask[Either[LlmError, Reply]](
          endpoint,
          api.body(config, chat, options),
        )(using api.replies),
      )
      .flatMap(MonadThrow[F].fromEither(_))

  override def count(chat: Chat, options: ReplyOptions): F[Int] = MonadThrow[F]
    .fromEither(api.counting(config, chat, options))
    .flatMap(counting =>
      ask[Int](counting.endpoint, counting.body)(using counting.tokens),
    )

  override def stream(chat: Chat, options: ReplyOptions): Stream[F, Delta] =
    streamer.fold(super.stream(chat, options))(_.stream(chat, options))

  /** Warms with the shortest reply the provider allows. */
  override def warm(chat: Chat, options: ReplyOptions): F[Reply] = send(
    chat.cacheable,
    options.copy(maxTokens = Some(api.leastReply)),
  )

  /**
    * Posts JSON with the provider's credentials, reading an `A` back, failing
    * with [[LlmError.TimedOut]] where no answer came in time.
    */
  private def ask[A : Decoder](endpoint: Uri, body: String): F[A] = api
    .post(config, endpoint, body)
    .send(backend)
    .adaptError { case _: SttpClientException.TimeoutException =>
      LlmError.TimedOut(
        api.provider.displayName,
        config.timeout,
      )
    }
    .flatMap(response =>
      MonadThrow[F].fromEither(JsonHttp.parse[A](api.provider, response)),
    )
