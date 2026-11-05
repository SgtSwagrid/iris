package com.alecdorrington.iris
package api

import cats.effect.{Async, Resource, Sync}
import com.alecdorrington.iris.prompt.*
import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import scala.annotation.unused
import scala.concurrent.duration.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}
import sttp.model.{Header, Uri}

/**
  * A provider of models over HTTP, as `Anthropic`, each in an artifact of its
  * own: how its [[ApiService]] reaches it, and how a [[PromptService]] of one
  * of its models speaks to it, where a request goes, how it is written, which
  * conversations it refuses, and how its responses read.
  */
trait Provider:

  /** The provider's name for display and in errors. */
  def displayName: String

  /** The origin of the provider's API, used unless one is configured. */
  def origin: String

  /** The provider's name before a model's, such as `openai`. */
  def id: String = displayName.map(_.toLower)

  /** The model prompted when none is configured. */
  def defaultModel: ProviderModel

  /**
    * Names a model this provider serves, as its listed case when Iris lists it.
    *
    * @param id
    *   The model's name in the provider's API, in any case for a listed model.
    *
    * @return
    *   The model, or `None` where the name is blank.
    */
  def model(id: String): Option[ProviderModel]

  /** Every model of this provider's that Iris lists. */
  def listed: List[ProviderModel]

  /** The variables the provider's key may be in, in the order read. */
  def keyVariables: List[String]

  /**
    * Makes the provider's API service, with an HTTP client of its own, which
    * gives each whole response at most the configured timeout, from sending the
    * request to reading the response whole, and each next part of a streamed
    * one as long, and sends a request again where the provider refuses or fails
    * it for now, as the configuration says.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @return
    *   A resource holding the service and its HTTP client.
    */
  def api[F[_] : Async]
    (config: ApiConfig = ApiConfig())
    : Resource[F, ApiService[F]] = Platform
    .backends[F]
    .map(backends =>
      HttpApiService(
        this,
        config,
        key(config),
        DeadlineBackend(backends.sending),
        Some(EventStreams(this, config, backends.streaming)),
      ).retrying(config),
    )

  /**
    * Makes the provider's API service over a host's backend that cannot stream,
    * which times requests out as it does itself, and sends each once. A
    * streamed request is answered whole, its events all at once.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @param backend
    *   The backend to send requests through.
    *
    * @return
    *   The service.
    */
  def api[F[_] : Sync](config: ApiConfig, backend: Backend[F]): ApiService[F] =
    HttpApiService(this, config, key(config), backend, None)

  /**
    * Makes the provider's API service over a host's backend that can stream,
    * which times requests out as it does itself, but for each next part of a
    * streamed response, given the configured timeout. It sends a request again
    * where the provider refuses or fails it for now, as the configuration says,
    * but aborts nothing of the backend's when a stream is given up on.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   How the API is reached.
    *
    * @param backend
    *   The backend to send requests through.
    *
    * @return
    *   The service.
    */
  def api[F[_] : Async]
    (
      config: ApiConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : ApiService[F] = HttpApiService(
    this,
    config,
    key(config),
    backend,
    Some(EventStreams(
      this,
      config,
      Async[F].pure(Exchange(backend, Async[F].unit)),
    )),
  ).retrying(config)

  /** The configured key, else the one in the provider's own variable. */
  private def key(config: ApiConfig): Option[String] = config
    .key
    .orElse(key(Environment.variable))

  /** The key in the provider's own variable, read by a lookup. */
  private[iris] def key(variable: String => Option[String]): Option[String] =
    keyVariables.flatMap(variable).headOption

  /** The headers authenticating a request with a key. */
  private[iris] def credentials(key: String): Seq[Header]

  /** Where a streamed request goes, given where a whole one would. */
  private[iris] def streaming(uri: Uri): Uri = uri

  /**
    * How long the provider asks to wait before a request it refused is sent
    * again, where it says: by the standard `Retry-After` header, in whole
    * seconds, unless the provider says more.
    *
    * @param headers
    *   The refusal's headers.
    *
    * @param detail
    *   The refusal's body.
    *
    * @param now
    *   The moment the refusal came, since 1970, from which the times it names
    *   are read.
    */
  private[iris] def retryAfter
    (
      headers: Seq[Header],
      @unused
      detail: String,
      @unused
      now: FiniteDuration,
    )
    : Option[FiniteDuration] = Refusal
    .header(headers, "retry-after")
    .flatMap(Refusal.seconds)

  /**
    * Whether a refusal its status calls one for now (`429`, `503` or `529`) is
    * one waiting cannot lift, by its body, as a spent quota or credit is.
    */
  private[iris] def lasting
    (
      @unused
      detail: String,
    )
    : Boolean = false

  /** The path of a model's endpoint, for a whole or streamed response. */
  private[iris] def path(model: ProviderModel, streamed: Boolean): List[String]

  /** A request's body. */
  private[iris] def body
    (
      model: ProviderModel,
      conversation: Conversation,
      settings: Settings,
    )
    : Json

  /** One whole response, or why it is none. */
  private[iris] def wholeResponse: Decoder[Either[ApiError, PromptResponse]]

  /**
    * Whether a streamed event only opens a stream, before anything the model
    * writes, as Anthropic's `message_start` and `ping` do, so that a refusal
    * after it still fails the stream before anything is given.
    */
  private[iris] def opening
    (
      @unused
      event: Json,
    )
    : Boolean = false

  /**
    * The refusal or failure a streamed event reports, where it reports one, as
    * Anthropic's `error` event does, which fails the stream at the API, so that
    * one failing before anything else arrives is sent again.
    */
  private[iris] def failure
    (
      @unused
      event: Json,
    )
    : Option[ApiError] = None

  /** The response a stream's events make up, read as they arrive. */
  private[iris] def deltas[F[_] : RaiseThrowable](events: Stream[F, Json])
    : Stream[F, ProviderEvent]

  /** Why this provider would refuse the conversation, where it would. */
  private[iris] def refuseUnsendable
    (
      @unused
      model: ProviderModel,
      conversation: Conversation,
      @unused
      settings: Settings,
    )
    : Either[PromptError, Unit] =
    JsonHttp.refuseUnanswerable(this, conversation)

  /** The fewest tokens a response may be allowed, which warming asks for. */
  private[iris] def leastMaxTokens: Int = 1

  /**
    * The rates of a model this provider serves at a moment since 1970, as its
    * pricing page gives them, with any change it has announced from its day, or
    * `None` for a model Iris does not list. A response is priced at the rates
    * of the moment it arrives.
    */
  private[iris] def rates(model: ProviderModel, at: FiniteDuration)
    : Option[Rates]

  /** The request counting a conversation's tokens, or `None` where none can. */
  private[iris] def counting
    (
      @unused
      model: ProviderModel,
      @unused
      conversation: Conversation,
    )
    : Option[Either[PromptError, Counting]] = None

private[iris] object Provider:

  /**
    * The moment a day begins in UTC, since 1970, from which new rates apply:
    * Iris's assumption, as a provider may name no time zone (Google names
    * none). By Howard Hinnant's `days_from_civil`, as JavaScript has no
    * `java.time`.
    */
  def utc(year: Int, month: Int, day: Int): FiniteDuration =
    val shifted = if month <= 2 then year - 1 else year
    val era     = shifted / 400
    val ofEra   = shifted - era * 400
    val ofYear  = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
    val days    = ofEra * 365 + ofEra / 4 - ofEra / 100 + ofYear
    (era * 146_097L + days - 719_468).days

  /** A rate from its decimal figure, as the provider's page gives it. */
  def usd(figure: Double): Usd = Usd(BigDecimal(figure))

/** A request counting a conversation's tokens, and how to read the count back. */
private[iris] final case class Counting
  (
    path: List[String],
    body: Json,
    tokens: Decoder[Int],
  )
