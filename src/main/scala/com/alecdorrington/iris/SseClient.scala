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

/** An [[LlmStreamer]] for any provider, as its [[ProviderApi]] says. */
private[iris] final class SseClient[F[_] : Async]
  (
    api: ProviderApi,
    config: LlmConfig,
    backend: StreamBackend[F, Fs2Streams[F]],
  )
  extends LlmStreamer[F]:

  override def stream(chat: Chat, options: ReplyOptions): Stream[F, Delta] =
    Stream
      .eval(Async[F].fromEither(request(chat, options)))
      .flatMap(sent)
      .through(Sse.events)
      .through(api.reply[F])
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
    (chat: Chat, options: ReplyOptions)
    : Either[LlmError, SseRequest[F]] =
    for
      _        <- api.acceptable(config, chat, options)
      endpoint <- api.endpoint(config, options, true)
    yield api
      .post(
        config,
        endpoint,
        api.body(config, chat, options, true),
      )
      .response(asStreamUnsafe(Fs2Streams[F]))

  private def sent(request: SseRequest[F]): Stream[F, Byte] = Stream
    .eval(
      request
        .send(backend)
        .adaptError { case _: SttpClientException.TimeoutException => timedOut },
    )
    .flatMap: response =>
      response
        .body
        .fold(
          error =>
            Stream.raiseError[F](
              JsonHttp.unsuccessful(api.provider, response, error),
            ),
          bounded,
        )

  /**
    * The body, failing with [[LlmError.TimedOut]] where any part of it takes
    * longer than the configured timeout to arrive, as the JDK's client waits
    * only until a reply begins. A fiber of its own reads the body, and is given
    * up on rather than waited for, as a read waiting for the next part cannot
    * be interrupted: that fiber, and the one cancelling it, may then wait for
    * as long as the backend lives.
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

  private def timedOut: LlmError = LlmError.TimedOut(
    api.provider.displayName,
    config.timeout,
  )
