package com.alecdorrington.iris

import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import scala.annotation.unused
import scala.concurrent.duration.*
import sttp.model.{Header, Uri}

/**
  * One provider's API: how its [[ApiService]] reaches it, and how its
  * [[PromptService]] speaks to it, where a request goes, how it is written,
  * which conversations the provider refuses, and how its responses read.
  */
private[iris] trait ProviderApi:

  def provider: ModelProvider

  /** The headers authenticating a request with a key. */
  def credentials(key: String): Seq[Header]

  /** Where a streamed request goes, given where a whole one would. */
  def streaming(uri: Uri): Uri = uri

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
  def retryAfter
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
  def lasting
    (
      @unused
      detail: String,
    )
    : Boolean = false

  /** The path of a model's endpoint, for a whole or streamed response. */
  def path(model: Model, streamed: Boolean): List[String]

  /** A request's body. */
  def body
    (
      model: Model,
      conversation: Conversation,
      settings: Settings,
    )
    : Json

  /** One whole response, or why it is none. */
  def wholeResponse: Decoder[Either[ApiError, PromptResponse]]

  /**
    * Whether a streamed event only opens a stream, before anything the model
    * writes, as Anthropic's `message_start` and `ping` do, so that a refusal
    * after it still fails the stream before anything is given.
    */
  def opening
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
  def failure
    (
      @unused
      event: Json,
    )
    : Option[ApiError] = None

  /** The response a stream's events make up, read as they arrive. */
  def deltas[F[_] : RaiseThrowable](events: Stream[F, Json]): Stream[F, Delta]

  /** Why this provider would refuse the conversation, where it would. */
  def refuseUnsendable
    (
      @unused
      model: Model,
      conversation: Conversation,
      @unused
      settings: Settings,
    )
    : Either[PromptError, Unit] =
    JsonHttp.refuseUnanswerable(provider, conversation)

  /** The fewest tokens a response may be allowed, which warming asks for. */
  def leastMaxTokens: Int = 1

  /**
    * The rates of a model this provider serves at a moment since 1970, as its
    * pricing page gives them, with any change it has announced from its day, or
    * `None` for a model Iris does not list. A response is priced at the rates
    * of the moment it arrives.
    */
  def rates(model: Model, at: FiniteDuration): Option[Rates]

  /** The request counting a conversation's tokens, or `None` where none can. */
  def counting
    (
      @unused
      model: Model,
      @unused
      conversation: Conversation,
    )
    : Option[Either[PromptError, Counting]] = None

private[iris] object ProviderApi:

  def of(provider: ModelProvider): ProviderApi = provider match
    case ModelProvider.Anthropic => AnthropicApi
    case ModelProvider.OpenAi    => OpenAiApi
    case ModelProvider.Gemini    => GeminiApi

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
