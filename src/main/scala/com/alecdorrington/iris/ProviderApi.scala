package com.alecdorrington.iris

import fs2.Stream
import io.circe.{Decoder, Json}
import scala.annotation.unused
import scala.concurrent.duration.*
import sttp.client4.*
import sttp.model.{Header, Uri}

/**
  * One provider's API, which [[JsonClient]] and [[SseClient]] alike speak:
  * where a request goes, how it is written and authenticated, which chats the
  * provider refuses, and how its answers read.
  */
private[iris] trait ProviderApi:

  def provider: LlmProvider

  def endpoint
    (
      config: LlmConfig,
      options: ReplyOptions,
      streamed: Boolean,
    )
    : Either[LlmError, Uri]

  def body
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
      streamed: Boolean = false,
    )
    : String

  def credentials(config: LlmConfig): Seq[Header]

  /** One whole answer, as a reply or why it is none. */
  def answers: Decoder[Either[LlmError, Answer[Nothing]]]

  /** The reply a stream's events make up, read as they arrive. */
  def reply[F[_]](events: Stream[F, Json]): Stream[F, Delta]

  /** Why this provider would refuse the chat, where it would. */
  def acceptable
    (
      @unused
      config: LlmConfig,
      chat: Chat,
      @unused
      options: ReplyOptions,
    )
    : Either[LlmError, Unit] = JsonHttp.answerable(provider, chat)

  /** The fewest tokens a reply may be allowed, which warming asks for. */
  def leastReply: Int = 1

  /**
    * The rates of a model this provider serves at a moment since 1970, as its
    * pricing page gives them, with any change it has announced from its day, or
    * `None` for a model Iris does not list.
    */
  def rates(model: LlmModel, at: FiniteDuration): Option[Rates]

  /** What a reply cost at a moment: at the configuration's rates, else listed. */
  final def cost
    (
      config: LlmConfig,
      usage: Usage,
      at: FiniteDuration,
    )
    : Option[BigDecimal] = config
    .rates
    .orElse(rates(config.model, at))
    .map(_.cost(usage))

  /** The request counting a chat's tokens, refused where there is none. */
  def counting
    (
      @unused
      config: LlmConfig,
      @unused
      chat: Chat,
      @unused
      options: ReplyOptions,
    )
    : Either[LlmError, Counting] = Left(LlmError.Unsupported(
    provider.displayName,
    "counting tokens",
  ))

  /** Posts JSON with this provider's credentials and the configured timeout. */
  final def post
    (
      config: LlmConfig,
      endpoint: Uri,
      body: String,
    )
    : Request[Either[String, String]] = basicRequest
    .post(endpoint)
    .body(body)
    .contentType("application/json")
    .headers(credentials(config)*)
    .readTimeout(config.timeout)

private[iris] object ProviderApi:

  def of(provider: LlmProvider): ProviderApi = provider match
    case LlmProvider.Anthropic => AnthropicApi
    case LlmProvider.OpenAi    => OpenAiApi
    case LlmProvider.Gemini    => GeminiApi

  /**
    * The moment a day begins in UTC, since 1970, from which new rates apply. By
    * Howard Hinnant's `days_from_civil`, as JavaScript has no `java.time`.
    */
  def utc(year: Int, month: Int, day: Int): FiniteDuration =
    val shifted = if month <= 2 then year - 1 else year
    val era     = shifted / 400
    val ofEra   = shifted - era * 400
    val ofYear  = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
    val days    = ofEra * 365 + ofEra / 4 - ofEra / 100 + ofYear
    (era * 146_097L + days - 719_468).days

  /** A rate from its decimal figure, as the provider's page gives it. */
  def usd(figure: Double): BigDecimal = BigDecimal(figure)

/** A request counting a chat's tokens, and how to read the count back. */
private[iris] final case class Counting
  (
    endpoint: Uri,
    body: String,
    tokens: Decoder[Int],
  )
