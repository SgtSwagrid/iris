package com.alecdorrington.iris

import cats.effect.{Async, Sync}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.Json
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri

/** A request whose response is read as a stream of bytes, as it arrives. */
private[iris] type SseRequest[F[_]] = StreamRequest[
  Either[String, Stream[F, Byte]],
  Fs2Streams[F],
]

/**
  * A backend for one streamed response, and how to abort its exchange: closing
  * its connection and freeing whatever waits on its next part, which neither
  * sttp's Fetch backend nor its JDK one does when the response is dropped.
  */
private[iris] final case class Exchange[F[_]]
  (
    backend: StreamBackend[F, Fs2Streams[F]],
    abort: F[Unit],
  )

/**
  * The backends a service made by Iris sends through: one for every whole
  * response, and a fresh exchange for each streamed one.
  */
private[iris] final case class Backends[F[_]]
  (
    sending: Backend[F],
    streaming: F[Exchange[F]],
  )

/**
  * The [[ApiService]] of a provider over HTTP, reached as its [[ProviderApi]]
  * says. A streamed request goes through an exchange of its own where there are
  * any, so that giving it up closes its connection, and is otherwise read
  * whole, its events all at once, as over a host's backend.
  */
private[iris] final class HttpApi[F[_] : Sync]
  (
    provider: ProviderApi,
    config: ApiConfig,
    key: Option[String],
    sending: Backend[F],
    streaming: Option[Streaming[F]],
  )
  extends ApiService[F]:

  override def stream(request: ApiRequest): Stream[F, Json] = Stream
    .eval(Sync[F].fromEither(uri(request).product(keyed)))
    .flatMap((uri, key) =>
      if !request.streamed then
        Stream.eval(read(uri, key, request.body).flatMap(parsed))
      else
        streaming
          .fold(
            Stream
              .eval(read(uri, key, request.body))
              .flatMap(body => Stream.emits(body.getBytes("UTF-8")))
              .through(Sse.events),
          )(_.events(posted(uri, key, request.body).response(asStreamUnsafe(
            Fs2Streams[F],
          ))))
          .flatMap(event =>
            Stream.fromEither[F](provider.failure(event).toLeft(event)),
          )
          .through(held),
    )

  /**
    * Events, those only opening a stream held back until another arrives, so
    * that a refusal after them fails the stream before anything is given, and
    * the request may be sent again.
    */
  private def held(events: Stream[F, Json]): Stream[F, Json] = events
    .noneTerminate
    .mapAccumulate(Option(Vector.empty[Json])):
      case (Some(opening), Some(event)) if provider.opening(event) =>
        (Some(opening :+ event), Nil)
      case (Some(opening), event) => (None, opening.toList ++ event)
      case (None, event)          => (None, event.toList)
    .flatMap((_, released) => Stream.emits(released))

  /** Where a request goes, beneath the configured origin. */
  private def uri(request: ApiRequest): Either[ApiError, Uri] = JsonHttp
    .endpoint(
      provider.provider,
      config.baseUrl.getOrElse(provider.provider.origin),
      request.path*,
    )
    .map(uri => if request.streamed then provider.streaming(uri) else uri)

  /** The key, or why there is none. */
  private def keyed: Either[ApiError, String] =
    key.toRight(ApiError.Misconfigured(
      provider.provider.displayName,
      s"no API key: give one, or set ${ provider
          .provider
          .keyVariables
          .mkString(" or ") }",
    ))

  /** Posts JSON with the provider's credentials and the configured timeout. */
  private def posted
    (uri: Uri, key: String, body: Json)
    : Request[Either[String, String]] = basicRequest
    .post(uri)
    .body(body.noSpaces)
    .contentType("application/json")
    .headers(provider.credentials(key)*)
    .readTimeout(config.timeout)

  /** A response's body, failing on an unsuccessful response or a timeout. */
  private def read(uri: Uri, key: String, body: Json): F[String] =
    posted(uri, key, body)
      .send(sending)
      .adaptError { case _: SttpClientException.TimeoutException =>
        JsonHttp.timedOut(provider, config)
      }
      .flatMap(response =>
        Sync[F]
          .realTime
          .flatMap(now =>
            response
              .body
              .leftMap(JsonHttp.unsuccessful(provider, response, _, now))
              .liftTo[F],
          ),
      )

  private def parsed(body: String): F[Json] = io
    .circe
    .parser
    .parse(body)
    .leftMap(failure =>
      ApiError.Malformed(
        provider.provider.displayName,
        failure.getMessage,
      ),
    )
    .liftTo[F]

/**
  * The streamed responses of a provider: each read through an exchange of its
  * own, aborted once its stream ends, however it ends, or once its request is
  * cancelled before its response begins, and given at most the configured
  * timeout for each next part, never waiting for the read it gives up on, as
  * neither sttp's nor fs2's waits can be cancelled.
  */
private[iris] final class Streaming[F[_] : Async]
  (
    provider: ProviderApi,
    config: ApiConfig,
    exchanges: F[Exchange[F]],
  ):

  def events(request: SseRequest[F]): Stream[F, Json] = Stream
    .bracket(exchanges)(_.abort)
    .flatMap(sent(request, _))
    .through(Sse.events)

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
          JsonHttp.timedOut(provider, config)
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
                  JsonHttp.unsuccessful(provider, response, error, now),
                ),
              ),
          bounded,
        )

  /**
    * The body, each next part of it waited for at most the configured timeout,
    * read on a fiber of its own, which is cancelled without being waited for.
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
                  Async[F].raiseError(JsonHttp.timedOut(provider, config)),
                ),
            )
            .unNoneTerminate
            .flatMap(_.fold[Stream[F, Byte]](
              Stream.raiseError[F](_),
              Stream.chunk(_),
            )),
        )
