package com.alecdorrington.iris

import cats.{~>, MonadThrow}
import cats.effect.{Async, Resource}
import cats.syntax.traverse.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}

/**
  * A provider-agnostic client for large language models, constructed with
  * [[LlmClient.resource]] or [[LlmClient.fromEnv]]. It gives a reply whole,
  * through [[ask]], or as it is written, through [[askStreamed]] and
  * [[stream]].
  *
  * Clients are stateless: the whole conversation travels with each [[Chat]], so
  * a conversation continues by appending to the chat and sending it again.
  *
  * @tparam F
  *   The effect type.
  */
trait LlmClient[F[_]]:

  /**
    * Asks the model for its next reply to a chat, with an answer of a type read
    * from it: the one way a caller has a reply, as [[count]] and [[warm]] have
    * none to read. It needs `F` to raise errors, as every client's effect can.
    *
    * Every type is asked for alike, `String` as much as a case class of one's
    * own: the model is told the type's JSON Schema, closing the chat's last
    * user message, and its reply read as JSON conforming to it. A reply that
    * cannot be read is followed by why, and the model asked again, until one
    * can be or the attempts run out, but not where asking again could not help:
    * a reply cut off at the token limit, which is never read, stopped for a
    * reason of the provider's, such as a refusal, or saying nothing. A reply
    * asking for a tool is given with no answer. A chat in which the user says
    * nothing fails with [[LlmError.Unsendable]] before any request. Every
    * attempt goes through [[send]], so a wrapper overriding it sees each.
    *
    * {{{
    * case class Person(name: String, age: Int)
    *
    * client.ask[Person](Chat.of("Invent a person.")).map(_.value)
    * }}}
    *
    * @tparam A
    *   The type of the answer.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options, alike for every attempt; unset fields fall back
    *   to the configuration or the provider's defaults.
    *
    * @param attempts
    *   The most answers to ask for, three unless given, which must be at least
    *   one, as the effect otherwise fails with an `IllegalArgumentException`.
    *
    * @return
    *   An effect producing the last reply with its answer, alike for every
    *   type: its text, why the model stopped, the usage of every attempt and
    *   the tools it asked for. It fails with [[LlmError.Unreadable]] where the
    *   last reply could not be read either.
    */
  final def ask[A : Schema]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Question.attempts,
    )
    (using MonadThrow[F])
    : F[Answer[A]] = Question.ask(send(_, options), chat, attempts)

  /**
    * Asks the model for its next reply to a chat, as [[ask]] does, but hands
    * its answer over while the reply is still being written, through
    * [[stream]]: as soon as everything in it but its streams is read. Each
    * `fs2.Stream` within it, other than within a list or map, then delivers
    * each of its elements as soon as the model has written it whole, while the
    * reply goes on being read in a fiber of its own for as long as the resource
    * is in use.
    *
    * The model is told the same JSON Schema as by [[ask]], a stream being an
    * array, so its reply is no different; only its reading is. The model writes
    * a value's fields in order, so a value holding a stream is handed over once
    * each field it has begun is read or begun as a stream, each it has passed
    * over is one that may be left out, and each it has yet to begin is a stream
    * it must write, handed over to begin when it does: so a value's streams are
    * delivered as they are written wherever every field after them is a stream,
    * and any other is whole by the time the field after it begins. A field it
    * writes after its value was handed over, or a stream it never begins, fails
    * the answer. One read only once whole, as a stream within a list, holds its
    * elements from the start. A type holding no stream is handed over once the
    * reply ends, as [[ask]] gives it. Every stream may be read as often as
    * wanted, each time from its first element. Its JSON is read from the
    * reply's start, or from within a code fence it opens with, and reading ends
    * with it: what follows, which [[ask]] would refuse, is not read.
    *
    * {{{
    * case class Page(title: String, words: Stream[IO, Word])
    *
    * client.askStreamed[Page](chat).use(answering =>
    *   answering.value.traverse_(_.words.evalMap(show).compile.drain),
    * )
    * }}}
    *
    * A reply that cannot be read before its answer is handed over is followed
    * by why, and the model asked again, as [[ask]] asks, as is a reply that
    * does not begin with its JSON, which is read only once whole. Once handed
    * over, an answer is never asked for again: whatever fails afterwards fails
    * each of its streams not yet ended, after the elements they have delivered,
    * and [[Answering.answer]]. A reply that cannot be read to its end, as one
    * cut off at the token limit, fails with [[LlmError.Unreadable]]; a request
    * failing partway, as one timing out, fails as it does; and a release of the
    * resource before the reply ends fails with an `IllegalStateException`. Tool
    * calls are not streamed, so no tool may be offered. A client over a backend
    * that cannot stream fails with [[LlmError.Unsupported]].
    *
    * @tparam A
    *   The type of the answer, whose streams are of this client's effect.
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
    *   until the next part of the reply arrives. It fails as [[ask]] fails
    *   where no answer could be handed over.
    */
  final def askStreamed[A : Schema]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Question.attempts,
    )
    (using Async[F])
    : Resource[F, Answering[F, A]] =
    Unfolding.ask(stream, chat, options, attempts)

  /**
    * Sends a chat to the model for its next reply: the one request every other
    * is made through, which a client implements and a wrapper overrides,
    * passing it on with `super.send`. Callers ask through [[ask]].
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options; unset fields fall back to the configuration or
    *   the provider's defaults.
    *
    * @return
    *   An effect producing the model's reply.
    */
  protected def send(chat: Chat, options: ReplyOptions): F[Answer[Nothing]]

  /**
    * Sends a chat to the model, streaming its reply's text as it arrives: the
    * request every [[askStreamed]] is made through.
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
    *   [[LlmError]]s, as [[LlmError.Unsupported]] where the client's backend
    *   cannot stream.
    */
  def stream
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : Stream[F, Delta]

  /**
    * Counts the input tokens a chat would cost, as the provider counts them.
    * Fails with [[LlmError.Unsupported]] where the provider cannot count
    * (OpenAI).
    *
    * @param chat
    *   The conversation to count, including any system message.
    *
    * @param options
    *   The options the chat would be sent with, which no count depends on.
    *
    * @return
    *   An effect producing the number of input tokens.
    */
  def count
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : F[Int]

  /**
    * Writes the [[Chat.cacheable]] prefix of a chat into the provider's cache,
    * with as short a reply as the provider allows. Requests sharing the prefix
    * and sent at once afterwards then all read it, where unwarmed each would
    * write its own. Fails with [[LlmError.Unsendable]] when the chat has no
    * [[Part.CacheBreakpoint]].
    *
    * @param chat
    *   The chat whose prefix to cache.
    *
    * @param options
    *   The options the requests will be sent with, through a client of the same
    *   model, as a cache is kept per model.
    *
    * @return
    *   An effect producing the provider's empty reply, whose usage says how
    *   much of the prefix was cached.
    */
  def warm
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : F[Answer[Nothing]] = send(
    chat.cacheable,
    options.copy(maxTokens = Some(1)),
  )

  /**
    * Runs every request of this client, [[warm]] included, through a
    * transformation, such as a limit on requests in flight, and each step of a
    * [[stream]] alike.
    *
    * @tparam G
    *   The effect the transformed client runs in.
    *
    * @param transform
    *   The transformation applied to each request.
    *
    * @return
    *   A client sending through this one.
    */
  final def mapK[G[_]](transform: F ~> G): LlmClient[G] =
    val client = this
    new LlmClient[G]:
      override protected def send
        (chat: Chat, options: ReplyOptions)
        : G[Answer[Nothing]] = transform(client.send(chat, options))

      override def count(chat: Chat, options: ReplyOptions): G[Int] =
        transform(client.count(chat, options))

      override def warm(chat: Chat, options: ReplyOptions): G[Answer[Nothing]] =
        transform(client.warm(chat, options))

      override def stream(chat: Chat, options: ReplyOptions): Stream[G, Delta] =
        client.stream(chat, options).translate(transform)

object LlmClient:

  /**
    * A client passing every request on to another, for a wrapper to override
    * only what it changes. It passes [[LlmClient.warm]] on too, which the
    * default would send through [[LlmClient.send]] instead. A wrapper seeing
    * every request overrides all four: `send`, which every attempt of an `ask`
    * goes through, [[LlmClient.stream]], which every attempt of an
    * `askStreamed` goes through, and [[LlmClient.count]] and
    * [[LlmClient.warm]], which go their own ways, each passing it on through
    * `super`.
    *
    * @tparam F
    *   The effect type.
    *
    * @param underlying
    *   The client requests are passed on to.
    */
  abstract class Forwarding[F[_]]
    (protected val underlying: LlmClient[F])
    extends LlmClient[F]:

    override protected def send
      (chat: Chat, options: ReplyOptions)
      : F[Answer[Nothing]] = underlying.send(chat, options)

    override def count(chat: Chat, options: ReplyOptions): F[Int] = underlying
      .count(chat, options)

    override def warm(chat: Chat, options: ReplyOptions): F[Answer[Nothing]] =
      underlying.warm(chat, options)

    override def stream(chat: Chat, options: ReplyOptions): Stream[F, Delta] =
      underlying.stream(chat, options)

  /**
    * Creates a client over an existing backend, which cannot stream: its
    * [[LlmClient.stream]] and [[LlmClient.askStreamed]] fail with
    * [[LlmError.Unsupported]].
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   The model, key and defaults to use.
    *
    * @param backend
    *   The backend to send requests through, which times them out as it does
    *   itself: the JDK's client, for one, only until a reply begins, where
    *   [[resource]] gives each reply the configured timeout whole.
    *
    * @return
    *   A client for the configured provider.
    */
  def apply[F[_] : MonadThrow]
    (config: LlmConfig, backend: Backend[F])
    : LlmClient[F] = JsonClient(
    ProviderApi.of(config.provider),
    config,
    backend,
    None,
  )

  /**
    * Creates a client over an existing backend which can stream.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   The model, key and defaults to use.
    *
    * @param backend
    *   The backend to send and stream requests through, which times them out as
    *   it does itself: the JDK's client, for one, only until a reply begins,
    *   where [[resource]] gives each reply the configured timeout whole, and
    *   each part of a streamed one.
    *
    * @return
    *   A client for the configured provider.
    */
  def apply[F[_] : Async]
    (
      config: LlmConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : LlmClient[F] = streaming(config, backend, backend)

  /** A client sending through one backend, and streaming through another. */
  private def streaming[F[_] : Async]
    (
      config: LlmConfig,
      sending: Backend[F],
      streaming: StreamBackend[F, Fs2Streams[F]],
    )
    : LlmClient[F] =
    val api = ProviderApi.of(config.provider)
    JsonClient(
      api,
      config,
      sending,
      Some(SseClient(api, config, streaming)),
    )

  /**
    * Creates a client with an HTTP backend of its own, which can stream, and
    * gives each reply at most the configured timeout, from sending the request
    * to reading the reply whole, and each part of a streamed reply as long.
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
  def resource[F[_] : Async](config: LlmConfig): Resource[F, LlmClient[F]] =
    Platform
      .backend[F]
      .map(backend => streaming(config, Deadlined(backend), backend))

  /**
    * Creates a client configured from the environment by [[LlmConfig.fromEnv]].
    *
    * @tparam F
    *   The effect type.
    *
    * @return
    *   A resource holding the client, or `None` when no provider is configured.
    */
  def fromEnv[F[_] : Async]: Resource[F, Option[LlmClient[F]]] = LlmConfig
    .fromEnv()
    .traverse(resource[F](_))
