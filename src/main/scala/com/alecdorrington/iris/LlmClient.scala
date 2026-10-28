package com.alecdorrington.iris

import cats.{~>, MonadThrow}
import cats.effect.{Async, Resource}
import cats.syntax.traverse.*
import sttp.client4.Backend

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
    * transformation, such as a limit on requests in flight.
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

object LlmClient:

  /**
    * A client passing every request on to another, for a wrapper to override
    * only what it changes. It passes [[LlmClient.warm]] on too, which the
    * default would send through [[LlmClient.send]] instead. A wrapper seeing
    * every request overrides all three: `send`, which every attempt of an `ask`
    * goes through, and [[LlmClient.count]] and [[LlmClient.warm]], which go
    * their own ways, each passing it on through `super`.
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

  /**
    * Creates a client over an existing backend.
    *
    * @tparam F
    *   The effect type.
    *
    * @param config
    *   The model, key and defaults to use.
    *
    * @param backend
    *   The backend to send requests through.
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
  )

  /**
    * Creates a client with an HTTP backend of its own.
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
    Platform.backend[F].map(apply(config, _))

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
