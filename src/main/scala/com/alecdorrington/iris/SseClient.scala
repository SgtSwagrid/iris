package com.alecdorrington.iris

import cats.effect.Async
import fs2.Stream
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
    .eval(request.send(backend))
    .flatMap: response =>
      response
        .body
        .fold(
          error =>
            Stream.raiseError[F](
              JsonHttp.unsuccessful(api.provider, response, error),
            ),
          identity,
        )
