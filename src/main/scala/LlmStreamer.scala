package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import cats.syntax.traverse.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
  * A client that delivers a reply as it is written. It is apart from
  * [[LlmClient]] because it needs a backend that can stream.
  *
  * @tparam F
  *   The effect type.
  */
trait LlmStreamer[F[_]]:

  /**
    * Sends a chat to the model, streaming its reply as it arrives.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options; unset fields fall back to the configuration or
    *   the provider's defaults.
    *
    * @return
    *   A stream of the reply's text, ending with a [[Delta.End]]. Nothing is
    *   sent until the stream runs, and failures are raised in it as
    *   [[LlmError]]s.
    */
  def stream
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : Stream[F, Delta]

  /**
    * Sends a single-turn prompt, streaming the reply as it arrives.
    *
    * @param prompt
    *   The prompt to send.
    *
    * @return
    *   A stream of the reply's text, ending with a [[Delta.End]].
    */
  final def stream(prompt: Prompt[?]): Stream[F, Delta] = stream(prompt.toChat)

object LlmStreamer:

  /**
    * Creates a streaming client over an existing streaming backend.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   The model, key and defaults to use.
    *
    * @param backend
    *   The backend to stream requests through.
    *
    * @return
    *   A streaming client for the configured provider.
    */
  def apply[F[_] : Async]
    (
      config: LlmConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : LlmStreamer[F] = SseClient(
    ProviderApi.of(config.provider),
    config,
    backend,
  )

  /**
    * Creates a streaming client with a streaming backend of its own.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   The model, key and defaults to use.
    *
    * @return
    *   A resource holding the client and its backend.
    */
  def resource[F[_] : Async](config: LlmConfig): Resource[F, LlmStreamer[F]] =
    Platform.streamBackend[F].map(apply(config, _))

  /**
    * Creates a streaming client configured from the environment by
    * [[LlmConfig.fromEnv]].
    *
    * @tparam F
    *   The effect type.
    *
    * @return
    *   A resource holding the client, or `None` when no provider is configured.
    */
  def fromEnv[F[_] : Async]: Resource[F, Option[LlmStreamer[F]]] = LlmConfig
    .fromEnv()
    .traverse(resource[F](_))
