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
    * Sends a chat to the model for its next reply.
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
  def send
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
    )
    : F[Reply]

  /**
    * Sends a single-turn prompt to the model.
    *
    * @param prompt
    *   The prompt to send.
    *
    * @return
    *   An effect producing the model's reply.
    */
  final def send(prompt: Prompt): F[Reply] = send(prompt.toChat)

  /**
    * Sends a single-turn prompt to the model with options.
    *
    * @param prompt
    *   The prompt to send.
    *
    * @param options
    *   The per-request options.
    *
    * @return
    *   An effect producing the model's reply.
    */
  final def send(prompt: Prompt, options: ReplyOptions): F[Reply] =
    send(prompt.toChat, options)

  /**
    * Asks the model for an answer of a type, telling it what form the answer
    * must take and reading its reply as one. A reply that cannot be read is
    * followed by why, and the model asked again, until one can be or the
    * attempts run out. Each attempt goes through [[send]], so a wrapper around
    * the client sees every one.
    *
    * A `String` is the reply's text as written, as [[send]] gives it. Any
    * [[Structured]] type is JSON conforming to its schema, which closes the
    * chat's last user message:
    *
    * {{{
    * case class Person(name: String, age: Int) derives Structured
    *
    * client.ask[Person](Prompt("Invent a person."))
    * }}}
    *
    * @tparam A
    *   The type of the answer.
    *
    * @param chat
    *   The conversation so far, including any system message.
    *
    * @param options
    *   The per-request options, alike for every attempt.
    *
    * @param attempts
    *   The most replies to ask for, at least one.
    *
    * @return
    *   An effect producing the answer, which fails with [[LlmError.Unreadable]]
    *   where the last reply could not be read either.
    */
  final def ask[A]
    (
      chat: Chat,
      options: ReplyOptions = ReplyOptions(),
      attempts: Int = Answer.attempts,
    )
    (using Answer[A], MonadThrow[F])
    : F[A] = Answer.ask(this, chat, options, attempts)

  /**
    * Asks the model for an answer of a type to a single-turn prompt.
    *
    * @tparam A
    *   The type of the answer.
    *
    * @param prompt
    *   The prompt to send.
    *
    * @return
    *   An effect producing the answer.
    */
  final def ask[A](prompt: Prompt)(using Answer[A], MonadThrow[F]): F[A] =
    ask[A](prompt.toChat)

  /**
    * Asks the model for an answer of a type to a single-turn prompt with
    * options.
    *
    * @tparam A
    *   The type of the answer.
    *
    * @param prompt
    *   The prompt to send.
    *
    * @param options
    *   The per-request options.
    *
    * @return
    *   An effect producing the answer.
    */
  final def ask[A]
    (prompt: Prompt, options: ReplyOptions)
    (using Answer[A], MonadThrow[F])
    : F[A] = ask[A](prompt.toChat, options)

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
    * Counts the input tokens a single-turn prompt would cost.
    *
    * @param prompt
    *   The prompt to count.
    *
    * @return
    *   An effect producing the number of input tokens.
    */
  final def count(prompt: Prompt): F[Int] = count(prompt.toChat)

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
      override def send(chat: Chat, options: ReplyOptions): G[Reply] =
        transform(client.send(chat, options))

      override def count(chat: Chat, options: ReplyOptions): G[Int] =
        transform(client.count(chat, options))

      override def warm(chat: Chat, options: ReplyOptions): G[Reply] =
        transform(client.warm(chat, options))

object LlmClient:

  /**
    * A client passing every request on to another, for a wrapper to override
    * only what it changes. It passes [[LlmClient.warm]] on too, which the
    * default would send through [[LlmClient.send]] instead.
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

    override def send(chat: Chat, options: ReplyOptions): F[Reply] = underlying
      .send(chat, options)

    override def count(chat: Chat, options: ReplyOptions): F[Int] = underlying
      .count(chat, options)

    override def warm(chat: Chat, options: ReplyOptions): F[Reply] = underlying
      .warm(chat, options)

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
