package com.alecdorrington.iris

import cats.effect.Async
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*

private[iris] type SseRequest[F[_]] = StreamRequest[
  Either[String, Stream[F, Byte]],
  Fs2Streams[F],
]

/**
  * A backend for one streamed reply, and an effect aborting the reply's request
  * at once, closing its connection though a read waits on it.
  *
  * @param backend
  *   The backend to send the reply's request through.
  *
  * @param abort
  *   Aborts the request, doing nothing once its reply has ended.
  */
private[iris] final case class Exchange[F[_]]
  (
    backend: StreamBackend[F, Fs2Streams[F]],
    abort: F[Unit],
  )

/**
  * The backends of a client's own: one sending requests whole, and one of each
  * streamed reply's own, which can abort it.
  *
  * @param sending
  *   The backend sending requests whole.
  *
  * @param streaming
  *   Makes a backend for one streamed reply.
  */
private[iris] final case class Backends[F[_]]
  (
    sending: Backend[F],
    streaming: F[Exchange[F]],
  )

/**
  * The streaming of replies for a [[ModelClient]] whose backend can stream, for
  * any provider, as its [[ProviderApi]] says. Each reply has an [[Exchange]] of
  * its own, which is aborted once its stream ends, however it ends, so that a
  * reply that is cancelled, dropped or stalled closes its connection, rather
  * than go on being written, and charged for, with nobody reading it.
  */
private[iris] final class SseClient[F[_] : Async]
  (
    api: ProviderApi,
    config: ModelConfig,
    exchanges: F[Exchange[F]],
  ):

  def stream
    (
      chat: Chat,
      options: ModelRequest.Options,
    )
    : Stream[F, Delta] = Stream
    .eval(Async[F].fromEither(request(chat, options)))
    .flatMap(request =>
      Stream.bracket(exchanges)(_.abort).flatMap(sent(request, _)),
    )
    .through(Sse.events)
    .through(api.deltas[F])
    .evalMap(priced)

  /** The reply's end with what it cost, at the rates of the moment it came. */
  private def priced(delta: Delta): F[Delta] = delta match
    case Delta.End(stopReason, usage, _) => Async[F]
        .realTime
        .map(now =>
          Delta.End(
            stopReason,
            usage,
            api.cost(config, usage, now),
          ),
        )
    case text => Async[F].pure(text)

  private def request
    (
      chat: Chat,
      options: ModelRequest.Options,
    )
    : Either[ModelError, SseRequest[F]] =
    for
      _        <- api.refuseUnsendable(config, chat, options)
      endpoint <- api.endpoint(config, options, true)
    yield api
      .post(
        config,
        endpoint,
        api.body(config, chat, options, true),
      )
      .response(asStreamUnsafe(Fs2Streams[F]))

  /**
    * The reply's body. A cancel before the reply begins aborts the request, as
    * a backend may otherwise wait for the reply before heeding it.
    */
  private def sent
    (
      request: SseRequest[F],
      exchange: Exchange[F],
    )
    : Stream[F, Byte] = Stream
    .eval(Async[F].cancelable(
      request
        .send(exchange.backend)
        .adaptError { case _: SttpClientException.TimeoutException =>
          timedOut
        },
      exchange.abort,
    ))
    .flatMap: response =>
      response
        .body
        .fold(
          error =>
            Stream
              .eval(Async[F].realTime)
              .flatMap(now =>
                Stream.raiseError[F](
                  JsonHttp.unsuccessful(api, response, error, now),
                ),
              ),
          bounded,
        )

  /**
    * The body, failing with [[ModelError.TimedOut]] where any part of it takes
    * longer than the configured timeout to arrive, as the JDK's client waits
    * only until a reply begins. A fiber of its own reads the body, and is given
    * up on rather than waited for, as a read waiting for the next part cannot
    * be interrupted: aborting the exchange, as the stream's end then does,
    * frees it on the backends [[Platform]] makes, but on another, that fiber,
    * and the one cancelling it, may wait for as long as the backend lives.
    */
  private def bounded(body: Stream[F, Byte]): Stream[F, Byte] = Stream
    .eval(Queue.synchronous[
      F,
      Option[Either[Throwable, Chunk[Byte]]],
    ])
    .flatMap: parts =>
      val reading = body
        .chunks
        .evalMap(chunk => parts.offer(Some(Right(chunk))))
        .compile
        .drain
        .attempt
        .flatMap:
          case Left(failure) => parts.offer(Some(Left(failure)))
          case Right(())     => parts.offer(None)
      Stream
        .bracket(reading.start)(_.cancel.start.void)
        .flatMap(_ =>
          Stream
            .repeatEval(
              parts
                .take
                .timeoutTo(
                  config.timeout,
                  Async[F].raiseError(timedOut),
                ),
            )
            .unNoneTerminate
            .flatMap(_.fold[Stream[F, Byte]](
              Stream.raiseError[F](_),
              Stream.chunk(_),
            )),
        )

  private def timedOut: ModelError = ModelError.TimedOut(
    api.provider.displayName,
    config.timeout,
  )
