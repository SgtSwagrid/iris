package com.alecdorrington.iris

import cats.{~>, MonadThrow}
import cats.effect.{Async, Resource}
import cats.syntax.traverse.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}

/**
  * A provider-agnostic client for large language models, constructed with
  * [[LlmClient.resource]] or [[LlmClient.fromEnv]].
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
    * none to read, alike for every type.
    *
    * Every type is asked for alike, `String` as much as a case class of one's
    * own: the model is told the type's JSON Schema, closing the chat's last
    * user message, and its reply read as JSON conforming to it. A reply that
    * cannot be read is followed by why, and the model asked again, until one
    * can be or the attempts run out, but not where asking again could not help:
    * a reply cut off at the token limit, which is never read, stopped for a
    * reason of the provider's, such as a refusal, or saying nothing. A reply
    * asking for a tool is given with no value. A chat in which the user says
    * nothing fails with [[LlmError.Unsendable]] before any request.
    *
    * {{{
    * case class Person(name: String, age: Int)
    *
    * client.ask[Person](Chat.of("Invent a person.")).map(_.value)
    * }}}
    *
    * The answer is given as soon as everything in its value but its streams is
    * read. Each `fs2.Stream` within the type, other than within a list or map,
    * which are read whole, then delivers each of its elements as soon as the
    * model has written it whole, while the reply goes on being read to its end
    * in a fiber of its own; [[Answer.reply]] gives the reply once it ends. Only
    * streams are ever partial: every other value is read whole first. A type
    * holding no stream is asked with one request, through [[send]], and given
    * once its reply ends; any other, through [[stream]], each attempt going
    * through the one or the other, so a wrapper overriding both sees each.
    *
    * {{{
    * case class Page(title: String, words: Stream[IO, Word])
    *
    * client.ask[Page](chat).flatMap(answer =>
    *   answer.value.traverse_(_.words.evalMap(show).compile.drain),
    * )
    * }}}
    *
    * The model is told the same JSON Schema whether its reply streams or not, a
    * stream being an array, so its reply is no different; only its reading is.
    * The model writes a value's fields in order, so a value holding a stream is
    * given once each field it has begun is read or begun as a stream, each it
    * has passed over is one that may be left out, and each it has yet to begin
    * is a stream it must write, given to begin when it does: so a value's
    * streams are delivered as they are written wherever every field after them
    * is a stream, and any other is whole by the time the field after it begins.
    * A field it writes after its value was given, or a stream it never begins,
    * fails the reply. Every stream may be read as often as wanted, each time
    * from its first element. A streamed reply's JSON is read from its start, or
    * from within a code fence it opens with, and reading ends with it: what
    * follows, which a reply read whole would be refused for, is not read; one
    * that does not begin with its JSON is read only once whole. Once an answer
    * is given, it is never asked for again: whatever fails afterwards fails
    * each of its streams not yet ended, after the elements they have delivered,
    * and [[Answer.reply]], with [[LlmError.Unreadable]] where the reply cannot
    * be read to its end, as one cut off at the token limit, and as the request
    * fails where that fails partway, as one timing out. A streamed reply is
    * read to its end even where nothing reads its streams.
    *
    * @tparam A
    *   The type of the answer, whose streams are of this client's effect.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options, alike for every attempt; unset fields fall back
    *   to the configuration or the provider's defaults.
    *
    * @param attempts
    *   The most replies to ask for before an answer is given, three unless
    *   given, which must be at least one, as the effect otherwise fails with an
    *   `IllegalArgumentException`.
    *
    * @return
    *   An effect producing the answer, as soon as it can be given. It fails
    *   with [[LlmError.Unreadable]] where the last reply could not be read
    *   either.
    */
  final def ask[A : Schema]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Question.attempts,
    )
    (using Async[F])
    : F[Answer[F, A]] =
    if Schema[A].gradual then Unfolding.ask(stream, chat, options, attempts)
    else Question.ask(send(_, options), chat, attempts)

  /**
    * Sends a chat to the model for its next reply, whole: the request every ask
    * for a type holding no stream is made through, which a client implements
    * and a wrapper overrides, passing it on with `super.send`. Callers ask
    * through [[ask]].
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
  protected def send(chat: Chat, options: ReplyOptions): F[Reply]

  /**
    * Sends a chat to the model, streaming its reply as it arrives: the request
    * every ask for a type holding a stream is made through. Unless a client
    * says otherwise, its reply is sent through [[send]] and arrives at once,
    * whole, as a client's over a backend that cannot stream does.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options; unset fields fall back to the configuration or
    *   the provider's defaults.
    *
    * @return
    *   A stream of the reply's text and tool calls, ending with a
    *   [[Delta.End]]. Nothing is sent until the stream runs, and failures are
    *   raised in it as [[LlmError]]s.
    */
  def stream
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : Stream[F, Delta] = Stream
    .eval(send(chat, options))
    .flatMap(reply =>
      Stream.emits(List.concat(
        Option.when(reply.text.nonEmpty)(Delta.Text(reply.text)),
        reply.toolCalls.map(Delta.ToolCall(_)),
        List(Delta.End(reply.stopReason, reply.usage)),
      )),
    )

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
    : F[Reply] = send(
    chat.cacheable,
    options.copy(maxTokens = Some(1)),
  )

  /**
    * Runs every request of this client, [[warm]] included, through a
    * transformation, and each step of a [[stream]] alike: one that holds a
    * stream as a whole, such as a limit on requests in flight, belongs in an
    * override of `stream` in [[LlmClient.Forwarding]] instead.
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
      override protected def send(chat: Chat, options: ReplyOptions): G[Reply] =
        transform(client.send(chat, options))

      override def count(chat: Chat, options: ReplyOptions): G[Int] =
        transform(client.count(chat, options))

      override def warm(chat: Chat, options: ReplyOptions): G[Reply] =
        transform(client.warm(chat, options))

      override def stream(chat: Chat, options: ReplyOptions): Stream[G, Delta] =
        client.stream(chat, options).translate(transform)

object LlmClient:

  /**
    * A client passing every request on to another, for a wrapper to override
    * only what it changes. It passes [[LlmClient.warm]] on too, which the
    * default would send through [[LlmClient.send]] instead. A wrapper seeing
    * every request overrides all four: `send` and [[LlmClient.stream]], which
    * every attempt of an `ask` goes through, one or the other by the type asked
    * for, and [[LlmClient.count]] and [[LlmClient.warm]], which go their own
    * ways, each passing it on through `super`.
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

    override protected def send(chat: Chat, options: ReplyOptions): F[Reply] =
      underlying.send(chat, options)

    override def count(chat: Chat, options: ReplyOptions): F[Int] = underlying
      .count(chat, options)

    override def warm(chat: Chat, options: ReplyOptions): F[Reply] = underlying
      .warm(chat, options)

    override def stream(chat: Chat, options: ReplyOptions): Stream[F, Delta] =
      underlying.stream(chat, options)

  /**
    * Creates a client over an existing backend, which cannot stream: a reply
    * that would stream arrives whole instead, once it ends.
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
