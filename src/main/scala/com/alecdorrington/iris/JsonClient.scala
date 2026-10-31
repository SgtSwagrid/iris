package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.all.*
import fs2.Stream
import io.circe.Decoder
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[ModelClient]] for any provider's JSON API, as its [[ProviderApi]] says,
  * streaming through an [[SseClient]] where its backend can, and otherwise
  * giving each reply whole.
  */
private[iris] final class JsonClient[F[_] : MonadThrow : Clock]
  (
    api: ProviderApi,
    config: ModelConfig,
    backend: Backend[F],
    streamer: Option[SseClient[F]],
  )
  extends ModelClient[F]:

  override protected def send(request: ModelRequest): F[ModelResponse] =
    val ModelRequest(chat, options) = request
    MonadThrow[F]
      .fromEither(
        api
          .refuseUnsendable(config, chat, options)
          .flatMap(_ => api.endpoint(config, options, false)),
      )
      .flatMap(endpoint =>
        fetch[Either[ModelError, ModelResponse]](
          endpoint,
          api.body(config, chat, options),
        )(using api.wholeResponse),
      )
      .flatMap(MonadThrow[F].fromEither(_))
      .flatMap(response =>
        Clock[F]
          .realTime
          .map(now =>
            response.copy(cost = api.cost(config, response.usage, now)),
          ),
      )

  override def countTokens(request: ModelRequest): F[Int] = MonadThrow[F]
    .fromEither(api.counting(config, request.chat, request.options))
    .flatMap(counting =>
      fetch[Int](counting.endpoint, counting.body)(using counting.tokens),
    )

  override def stream(request: ModelRequest): Stream[F, Delta] = streamer.fold(
    super.stream(request),
  )(_.stream(request.chat, request.options))

  /** Warms with the shortest response the provider allows. */
  override def warmCache(request: ModelRequest): F[ModelResponse] =
    send(ModelRequest(
      request.chat.cachePrefix,
      request.options.copy(maxTokens = Some(api.leastMaxTokens)),
    ))

  /**
    * Posts JSON with the provider's credentials, reading an `A` back, failing
    * with [[ModelError.TimedOut]] where no answer came in time.
    */
  private def fetch[A : Decoder](endpoint: Uri, body: String): F[A] = api
    .post(config, endpoint, body)
    .send(backend)
    .adaptError { case _: SttpClientException.TimeoutException =>
      ModelError.TimedOut(
        api.provider.displayName,
        config.timeout,
      )
    }
    .flatMap(response =>
      MonadThrow[F].fromEither(JsonHttp.parse[A](api.provider, response)),
    )
