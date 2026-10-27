package com.alecdorrington.iris

import fs2.Stream
import io.circe.{Decoder, Json}
import scala.annotation.unused
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
  def replies: Decoder[Either[LlmError, Reply[Nothing]]]

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

/** A request counting a chat's tokens, and how to read the count back. */
private[iris] final case class Counting
  (
    endpoint: Uri,
    body: String,
    tokens: Decoder[Int],
  )
