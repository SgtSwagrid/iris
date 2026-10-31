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
    * being an array, so its reply is no different; only its reading is. The
    * model writes a value's fields in order, so a value holding a stream is
    * handed over once each field it has begun is read or begun as a stream,
    * each it has passed over is one that may be left out, and each it has yet
    * to begin is a stream it must write, handed over to begin when it does: so
    * a value's streams are delivered as they are written wherever every field
    * after them is a stream, and any other is whole by the time the field after
    * it begins. A field it writes after its value was handed over, or a stream
    * it never begins, fails the answer. One read only once whole, as a stream
    * within a list, holds its elements from the start. A type holding no stream
    * is handed over once the reply ends, as [[LlmClient.ask]] gives it. Every
    * stream may be read as often as wanted, each time from its first element.
    * Its JSON is read from the reply's start, or from within a code fence it
    * opens with, and reading ends with it: what follows, which
    * [[LlmClient.ask]] would refuse, is not read.
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
    * Once handed over, an answer is never asked for again: whatever fails
    * afterwards fails each of its streams not yet ended, after the elements
    * they have delivered, and [[Answering.answer]]. A reply that cannot be read
    * to its end, as one cut off at the token limit, fails with
    * [[LlmError.Unreadable]]; a request failing partway, as one timing out,
    * fails as it does; and a release of the resource before the reply ends
    * fails with an `IllegalStateException`. Tool calls are not streamed, so no
    * tool may be offered.
    *
    * @tparam A
    *   The type of the answer, whose streams are of this streamer's effect.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options, alike for every attempt; unset fields fall back
    *   to the configuration or the provider's defaults. They may offer no tool,
    *   as the resource otherwise fails with an `IllegalArgumentException`.
    *
    * @param attempts
    *   The most answers to ask for before one is handed over, three unless
    *   given, which must be at least one, as the resource otherwise fails with
    *   an `IllegalArgumentException`.
    *
    * @return
    *   A resource holding the answer as soon as it is handed over, which stops
    *   reading the reply once released, though the connection may stay open
    *   until the next part of the reply arrives. It fails as [[LlmClient.ask]]
    *   fails where no answer could be handed over.
    */
  final def ask[A : Schema]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Question.attempts,
    )
    (using Async[F])
    : Resource[F, Answering[F, A]] =
    Unfolding.ask(stream, chat, options, attempts)

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
