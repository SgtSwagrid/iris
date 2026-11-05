package com.alecdorrington.iris
package api

import cats.MonadThrow
import cats.effect.{Async, Ref, Temporal}
import cats.syntax.all.*
import fs2.{Compiler, Stream}
import io.circe.Json
import scala.concurrent.duration.*
import scala.util.Random
import sttp.model.StatusCode

/**
  * A request to a provider's API in its own terms: one exchange of its JSON. A
  * [[PromptService]] makes one for each prompt, and a host may make its own to
  * reach what Iris does not model.
  *
  * @param path
  *   The path of the endpoint, beneath the provider's origin.
  *
  * @param body
  *   The JSON to send.
  *
  * @param streamed
  *   Whether the response is to arrive as server-sent events, each read as it
  *   comes, rather than as one body.
  */
final case class ApiRequest
  (
    path: List[String],
    body: Json,
    streamed: Boolean = false,
  )

/**
  * A provider's API, the lowest layer of Iris: one exchange of the provider's
  * own JSON, with its credentials, endpoints, timeouts and refusals handled.
  * Its one method, [[stream]], is what an implementation or a middleware
  * writes, which [[send]] gathers.
  *
  * @tparam F
  *   The effect type.
  */
trait ApiService[F[_]]:

  /**
    * Sends a request, giving its response's JSON: the body whole, or each event
    * of a streamed one as it arrives. A refusal or failure of the provider's
    * fails the stream with an [[ApiError]].
    *
    * @param request
    *   The request.
    *
    * @return
    *   A stream of the response's JSON.
    */
  def stream(request: ApiRequest): Stream[F, Json]

  /**
    * Sends a request for one body, giving it whole.
    *
    * @param request
    *   The request, sent unstreamed.
    *
    * @return
    *   An effect producing the response's body.
    */
  final def send
    (request: ApiRequest)
    (using Compiler[F, F], MonadThrow[F])
    : F[Json] = stream(request.copy(streamed = false)).compile.lastOrError

  /**
    * This service, sending a request again where the provider refused or failed
    * it for now ([[ApiError.RateLimited]], [[ApiError.Overloaded]],
    * [[ApiError.Transient]]), as the configuration says, while nothing of its
    * response has arrived. A transport failure or [[ApiError.TimedOut]] is not
    * sent again, as a request given up on may have been answered, and charged
    * for.
    *
    * @param config
    *   The configuration, whose `maxIterations`, `backoff` and `patience` say
    *   how often and how long to try.
    *
    * @return
    *   A service trying each request until it is answered or may not be tried
    *   again.
    */
  final def retrying(config: ApiConfig)(using Async[F]): ApiService[F] =
    Retrying(
      this,
      config,
      Async[F].delay(Random.nextDouble()),
    )

/**
  * How a provider's API is reached: the defaults every request takes.
  *
  * @param key
  *   The API key, or `None` for the provider's own environment variable, such
  *   as `ANTHROPIC_API_KEY`, read when a service is made.
  *
  * @param baseUrl
  *   The origin to send requests to, or `None` for the provider's own.
  *
  * @param timeout
  *   The longest a request is waited for: a whole response from sending it to
  *   reading it, and each next part of a streamed one.
  *
  * @param maxIterations
  *   The most times a request is sent, the first included, where the provider
  *   refuses or fails it for now: `1` sends each once.
  *
  * @param backoff
  *   The wait before sending a request again where the provider asks for none,
  *   doubling each time, less up to half at random, and else added up to once
  *   at random to the wait it asks for.
  *
  * @param patience
  *   The longest a request is waited out in all, after which its last refusal
  *   is given.
  */
final case class ApiConfig
  (
    key: Option[String] = None,
    baseUrl: Option[String] = None,
    timeout: FiniteDuration = ApiConfig.defaultTimeout,
    maxIterations: Int = 4,
    backoff: FiniteDuration = 1.second,
    patience: FiniteDuration = 1.minute,
  ):

  /**
    * The wait before sending again a request refused as `error`, tried that
    * often and waited that long, at a random draw in `[0, 1)`, or `None` where
    * it is not to be sent again.
    */
  private[iris] def delay
    (
      error: Throwable,
      tried: Int,
      waited: FiniteDuration,
      draw: Double,
    )
    : Option[FiniteDuration] = ApiConfig
    .asked(error)
    .filter(_ => tried < maxIterations)
    .map(_.fold(backedOff(tried, draw))(after(_, draw)))
    .filter(_ <= patience - waited)

  private def backedOff(tried: Int, draw: Double): FiniteDuration = Refusal
    .nanos(backoff.toNanos * math.pow(2, tried - 1) * (1 - draw / 2))

  private def after(asked: FiniteDuration, draw: Double): FiniteDuration =
    Refusal.nanos(asked.toNanos + backoff.toNanos * draw)

  /** The configuration, its key redacted, so that hosts may log it. */
  override def toString: String = s"ApiConfig(${ key.map(_ =>
      "<redacted>",
    ) }, $baseUrl, $timeout, $maxIterations, $backoff, $patience)"

object ApiConfig:

  /** The longest a request is waited for, unless configured otherwise. */
  val defaultTimeout: FiniteDuration = 5.minutes

  /**
    * Reads a configuration from the environment: `LLM_BASE_URL` and
    * `LLM_TIMEOUT` (in whole seconds), the key left to the provider's own
    * variable.
    *
    * @return
    *   The configuration, or `None` where `LLM_TIMEOUT` is set but is no
    *   positive number.
    */
  def fromEnv(): Option[ApiConfig] = from(Environment.variable)

  /**
    * Reads a configuration from variables, as [[fromEnv]] does.
    *
    * @param variable
    *   Looks up a variable by name.
    *
    * @return
    *   The configuration, or `None` where `LLM_TIMEOUT` is set but is no
    *   positive number.
    */
  def from(variable: String => Option[String]): Option[ApiConfig] =
    variable("LLM_TIMEOUT")
      .fold(Some(defaultTimeout))(Environment.positive(_).map(_.seconds))
      .map(timeout =>
        ApiConfig(
          baseUrl = variable("LLM_BASE_URL"),
          timeout = timeout,
        ),
      )

  /** The wait a refusal for now asks for, where it is one. */
  private def asked(error: Throwable): Option[Option[FiniteDuration]] =
    error match
      case ApiError.RateLimited(_, retryAfter, _)  => Some(retryAfter)
      case ApiError.Overloaded(_, retryAfter, _)   => Some(retryAfter)
      case ApiError.Transient(_, _, retryAfter, _) => Some(retryAfter)
      case _                                       => None

/**
  * An error exchanging JSON with a provider: a refusal, a failure, or a
  * response that could not be read.
  *
  * @param message
  *   The error's message.
  */
enum ApiError(message: String) extends Exception(message):

  /**
    * An unsuccessful HTTP status from the provider that sending the request
    * again would not change, and so never sent again: a refused key, a request
    * the provider cannot take, a spent quota or credit, or any the provider
    * marks not to retry (`x-should-retry: false`).
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param status
    *   The HTTP status.
    *
    * @param detail
    *   The response body.
    */
  case Unsuccessful
    (
      provider: String,
      status: StatusCode,
      detail: String,
    ) extends ApiError(s"$provider request failed ($status): $detail")

  /**
    * A request the provider refused for now, as over the rate limits of the
    * key's account (HTTP `429`), and went on refusing for as long as the
    * service's [[ApiConfig]] allowed, or asked to be waited for longer.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it refused it last.
    *
    * @param detail
    *   The response body, or the streamed event that refused it.
    */
  case RateLimited
    (
      provider: String,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ApiError(
      s"$provider is limiting requests${ Refusal.asked(retryAfter) }: $detail",
    )

  /**
    * A request the provider refused for now, as too busy to answer it (HTTP
    * `503`, or Anthropic's `529`), and went on refusing for as long as the
    * service's [[ApiConfig]] allowed, or asked to be waited for longer.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it refused it last.
    *
    * @param detail
    *   The response body, or the streamed event that refused it.
    */
  case Overloaded
    (
      provider: String,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ApiError(
      s"$provider is overloaded${ Refusal.asked(retryAfter) }: $detail",
    )

  /**
    * A request the provider failed for now, which may succeed if sent again: a
    * failure of its own (HTTP `500`, `502` or `504`), a request it timed out
    * (`408`) or found in conflict (`409`), or any other it marks worth retrying
    * (`x-should-retry: true`), as the providers' own SDKs retry them. It went
    * on failing for as long as the service's [[ApiConfig]] allowed, or asked to
    * be waited for longer.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param status
    *   The HTTP status.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it failed it last.
    *
    * @param detail
    *   The response body, or the streamed event that failed it.
    */
  case Transient
    (
      provider: String,
      status: StatusCode,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ApiError(s"$provider failed for now ($status)${ Refusal.asked(
        retryAfter,
      ) }: $detail")

  /**
    * A response body that could not be read.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of the failure to read it.
    */
  case Malformed(provider: String, detail: String)
    extends ApiError(s"Unexpected $provider response: $detail")

  /**
    * A configuration that cannot be used, such as a base URL that is no URL, or
    * no key. No request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what cannot be used.
    */
  case Misconfigured(provider: String, detail: String)
    extends ApiError(s"Cannot address $provider: $detail")

  /**
    * A request the provider did not answer within the configured timeout, such
    * as one whose response took too long to write or stalled partway.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param after
    *   How long the request was waited for.
    */
  case TimedOut(provider: String, after: FiniteDuration)
    extends ApiError(s"$provider did not answer within $after")

/**
  * An [[ApiService]] sending a request again where the provider refuses or
  * fails it for now, while nothing of its response has arrived, waiting as
  * [[ApiConfig.delay]] says, with `draw` giving each random draw.
  */
private[iris] final class Retrying[F[_] : Temporal]
  (
    service: ApiService[F],
    config: ApiConfig,
    draw: F[Double],
  )
  extends ApiService[F]:

  override def stream(request: ApiRequest): Stream[F, Json] =
    unbegun(request, 1, Duration.Zero)

  /** The request, sent again on a refusal for now until something arrives. */
  private def unbegun
    (
      request: ApiRequest,
      tried: Int,
      waited: FiniteDuration,
    )
    : Stream[F, Json] = Stream
    .eval(Ref.of[F, Boolean](false))
    .flatMap(begun =>
      service
        .stream(request)
        .chunks
        .evalTap(chunk => begun.set(true).whenA(chunk.nonEmpty))
        .unchunks
        .handleErrorWith(error =>
          Stream
            .eval(
              begun
                .get
                .ifM(
                  none[FiniteDuration].pure[F],
                  draw.map(config.delay(error, tried, waited, _)),
                ),
            )
            .flatMap(_.fold(Stream.raiseError[F](error))(wait =>
              Stream.sleep_[F](wait) ++
                unbegun(request, tried + 1, waited + wait),
            )),
        ),
    )
