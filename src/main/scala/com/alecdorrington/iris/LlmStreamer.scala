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
    * Asks the model for its next reply to a chat, as [[LlmClient.ask]] does,
    * but hands its answer over while the reply is still being written: as soon
    * as everything in it but its streams is read. Each `fs2.Stream` within it,
    * other than within a list or map, then delivers each of its elements as
    * soon as the model has written it whole, while the reply goes on being read
    * in a fiber of its own for as long as the resource is in use.
    *
    * The model is told the same JSON Schema as by [[LlmClient.ask]], a stream
    * being an array, so its reply is no different; only its reading is. A value
    * holding a stream is handed over once each of its fields is read or begun
    * as a stream, or left out, as one that may be is where a later field has
    * begun, the model writing its fields in order: a stream is delivered as it
    * is written only where every field after it is a stream too. One read only
    * once whole, as a stream within a list, holds its elements from the start.
    * A type holding no stream is handed over once the reply ends, as
    * [[LlmClient.ask]] gives it.
    *
    * {{{
    * case class Page(title: String, words: Stream[IO, Word])
    *
    * streamer.ask[Page](chat).use(answering =>
    *   answering.value.traverse_(_.words.evalMap(show).compile.drain),
    * )
    * }}}
    *
    * A reply that cannot be read before its answer is handed over is followed
    * by why, and the model asked again, as [[LlmClient.ask]] asks, as is a
    * reply that does not begin with its JSON, which is read only once whole.
    * Once handed over, an answer is never asked for again: a reply that cannot
    * be read to its end, as one cut off at the token limit, fails each of its
    * streams not yet ended, and [[Answering.answer]], with
    * [[LlmError.Unreadable]]. Tool calls are not streamed, so an answer asking
    * for a tool has neither a value nor any [[Answer.toolCalls]].
    *
    * @tparam A
    *   The type of the answer, whose streams are of this streamer's effect.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options, alike for every attempt; unset fields fall back
    *   to the configuration or the provider's defaults.
    *
    * @param attempts
    *   The most answers to ask for before one is handed over, three unless
    *   given, which must be at least one, as the resource otherwise fails with
    *   an `IllegalArgumentException`.
    *
    * @return
    *   A resource holding the answer as soon as it is handed over, which stops
    *   reading the reply once released. It fails as [[LlmClient.ask]] fails
    *   where no answer could be handed over.
    */
  final def ask[A : Schema]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Question.attempts,
    )
    (using Async[F])
    : Resource[F, Answering[F, A]] =
    Unfolding.ask(stream(_, options), chat, attempts)

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
