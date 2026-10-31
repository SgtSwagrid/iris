package com.alecdorrington.iris

import cats.{~>, Monad, MonadThrow}
import cats.effect.{Async, Clock, Resource}
import cats.syntax.flatMap.*
import cats.syntax.traverse.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}

/**
  * A provider-agnostic client for large language models, constructed with
  * [[ModelClient.resource]] or [[ModelClient.fromEnv]].
  *
  * Clients are stateless: the whole conversation travels with each [[Chat]], so
  * a conversation continues by appending to the chat and sending it again.
  *
  * @tparam F
  *   The effect type.
  */
trait ModelClient[F[_]]:

  /**
    * Asks the model for its next response to a chat, with a value of a type
    * read from it and validated against the type's schema: the one way a caller
    * has a response, as [[countTokens]] and [[warmCache]] have none to read,
    * alike for every type.
    *
    * Every type is asked for alike, `String` as much as a case class of one's
    * own: the model is told the type's JSON Schema, closing the chat's last
    * user message, and its reply read as JSON conforming to it. A reply that
    * cannot be read is followed by why, and the model asked again, until one
    * can be or the attempts run out, but not where asking again could not help:
    * a reply cut off at the token limit, which is never read, stopped for a
    * reason of the provider's, such as a refusal, or saying nothing. A reply
    * asking for a tool is given with no value. A chat in which the user says
    * nothing fails with [[ModelError.Unsendable]] before any request.
    *
    * {{{
    * case class Person(name: String, age: Int)
    *
    * client.ask[Person](Chat.userSays("Invent a person.")).map(_.value)
    * }}}
    *
    * The answer is given as soon as everything in its value but its streams is
    * read. Each `fs2.Stream` within the type, other than within a list or map,
    * which are read whole, then delivers each of its elements as soon as the
    * model has written it whole, while the reply goes on being read to its end
    * in a fiber of its own; [[ValidatedResponse.responses]] gives every
    * response once the last ends. Only streams are ever partial: every other
    * value is read whole first. A type holding no stream is asked with one
    * request, through [[send]], and given once its reply ends; any other,
    * through [[stream]], each attempt going through the one or the other, so a
    * wrapper overriding both sees each.
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
    * and [[ValidatedResponse.responses]], with
    * [[ModelError.UnreadableResponse]] where the reply cannot be read to its
    * end, as one cut off at the token limit, stopped, or asking for a tool, as
    * a whole reply would, and as the request fails where that fails partway, as
    * one timing out. A streamed reply is read to its end even where nothing
    * reads its streams, unless [[ValidatedResponse.cancel]] stops it, and its
    * failure reaches only its streams and [[ValidatedResponse.responses]]. A
    * value is given early only while one of its streams is seen open, so how a
    * reply's parts arrive may decide whether it is.
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
    *   `IllegalArgumentException`; a reply asking for tools that are run does
    *   not count. Given through `emit`, the most times in a row an element may
    *   be emitted unreadable.
    *
    * @param tools
    *   The tools to offer the model and run wherever it asks for them, beside
    *   any [[ModelRequest.Options.tools]] offered for the host to run: a reply
    *   asking for these alone is followed by their outputs, and the model asked
    *   to go on, as often as it asks. Given tools, an answer that is itself a
    *   `fs2.Stream` is given element by element through a tool of its own,
    *   `emit`, so that the model may run the others between elements, and ends
    *   with the reply in which the model calls another, `done`. No tool may
    *   then be offered for the host to run, nor one named `emit` or `done`, as
    *   the effect otherwise fails with an `IllegalArgumentException`. A tool
    *   whose `run` fails fails the ask, or the stream and
    *   [[ValidatedResponse.responses]] where its answer was given, the model
    *   not told. Tools are run for as long as the model asks for them, each
    *   round a request whose cost is the host's to bound, as for any request's:
    *   unbounded, a model that never stops asking for them is asked again
    *   without end.
    *
    * @return
    *   An effect producing the answer, as soon as it can be given. It fails
    *   with [[ModelError.UnreadableResponse]] where the last reply could not be
    *   read either.
    */
  final def ask[A : Schema]
    (
      chat: Chat,
      options: ModelRequest.Options = ModelRequest.Options(),
      attempts: Int = WholeAsker.attempts,
      tools: List[RunnableTool[F]] = Nil,
    )
    (using Async[F])
    : F[ValidatedResponse[F, A]] =
    val toolbox = Toolbox(tools)
    if Schema[A].gradual then
      StreamingAsker.ask(
        (chat, options) => stream(ModelRequest(chat, options)),
        chat,
        options,
        attempts,
        toolbox,
      )
    else
      WholeAsker.ask(
        chat => send(ModelRequest(chat, toolbox.offered(options))),
        chat,
        attempts,
        toolbox,
      )

  /**
    * Sends a request to the model for its next response, whole: the request
    * every ask for a type holding no stream is made through, which a client
    * implements and a wrapper overrides, passing it on with `super.send`.
    * Callers ask through [[ask]].
    *
    * @param request
    *   The chat, and the options to send it with.
    *
    * @return
    *   An effect producing the model's response.
    */
  protected def send(request: ModelRequest): F[ModelResponse]

  /**
    * Sends a request to the model, streaming its response as it arrives: the
    * request every ask for a type holding a stream is made through. Unless a
    * client says otherwise, it is sent through [[send]], and its response
    * arrives at once, whole, as a client's over a backend that cannot stream
    * does.
    *
    * @param request
    *   The chat, and the options to send it with.
    *
    * @return
    *   A stream of the response's text and tool calls, ending with a
    *   [[Delta.End]]. Nothing is sent until the stream runs, and failures are
    *   raised in it as [[ModelError]]s.
    */
  def stream(request: ModelRequest): Stream[F, Delta] = Stream
    .eval(send(request))
    .flatMap(response =>
      Stream.emits(List.concat(
        Option.when(response.text.nonEmpty)(Delta.Text(response.text)),
        response.toolCalls.map(Delta.ToolCall(_)),
        List(Delta.End(
          response.stopReason,
          response.usage,
          response.cost,
        )),
      )),
    )

  /**
    * Counts the input tokens a request would cost, as the provider counts them.
    * Fails with [[ModelError.Unsupported]] where the provider cannot count
    * (OpenAI).
    *
    * @param request
    *   The chat to count, and the options it would be sent with, which no count
    *   depends on.
    *
    * @return
    *   An effect producing the number of input tokens.
    */
  def countTokens(request: ModelRequest): F[Int]

  /**
    * Writes the [[Chat.cachePrefix]] of a request's chat into the provider's
    * cache, with as short a response as the provider allows. Requests sharing
    * the prefix and sent at once afterwards then all read it, where unwarmed
    * each would write its own. Fails with [[ModelError.Unsendable]] when the
    * chat has no [[MessagePart.CacheBreakpoint]].
    *
    * @param request
    *   The chat whose prefix to cache, and the options the requests will be
    *   sent with, through a client of the same model, as a cache is kept per
    *   model.
    *
    * @return
    *   An effect producing the provider's empty response, whose usage says how
    *   much of the prefix was cached.
    */
  def warmCache(request: ModelRequest): F[ModelResponse] = send(ModelRequest(
    request.chat.cachePrefix,
    request.options.copy(maxTokens = Some(1)),
  ))

  /**
    * Runs every request of this client, [[warmCache]] included, through a
    * transformation, and each step of a [[stream]] alike: one that holds a
    * stream as a whole, such as a limit on requests in flight, belongs in an
    * override of `stream` in [[ModelClient.Forwarding]] instead.
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
  final def mapK[G[_]](transform: F ~> G): ModelClient[G] =
    val client = this
    new ModelClient[G]:
      override protected def send(request: ModelRequest): G[ModelResponse] =
        transform(client.send(request))

      override def countTokens(request: ModelRequest): G[Int] =
        transform(client.countTokens(request))

      override def warmCache(request: ModelRequest): G[ModelResponse] =
        transform(client.warmCache(request))

      override def stream(request: ModelRequest): Stream[G, Delta] = client
        .stream(request)
        .translate(transform)

  /**
    * Runs a function on what each request this client makes spent, as its reply
    * comes: once for every attempt of an [[ask]], on that attempt's reply
    * before it is read, so an ask that fails after three attempts is metered
    * three times, once for [[warmCache]], and once for each [[stream]], at its
    * [[Delta.End]]. [[countTokens]] costs nothing, so is never metered, nor is
    * a request that fails with no reply, nor a stream stopped before its end,
    * which reports no usage. The meter runs before the reply is handed on, so a
    * meter that fails fails the request, and the meters of requests sent at
    * once may run at once.
    *
    * {{{
    * client.metered((_, cost) => cost.traverse_(spent => total.update(_ + spent)))
    * }}}
    *
    * @param meter
    *   The function, given the request's token counts and what it cost, or
    *   `None` where Iris knows no rates for the model.
    *
    * @return
    *   A client sending through this one.
    */
  final def metered
    (meter: (Usage, Option[Usd]) => F[Unit])
    (using Monad[F])
    : ModelClient[F] = new ModelClient.Forwarding(this):
    override protected def send(request: ModelRequest): F[ModelResponse] = super
      .send(request)
      .flatTap(response => meter(response.usage, response.cost))

    override def warmCache(request: ModelRequest): F[ModelResponse] = super
      .warmCache(request)
      .flatTap(response => meter(response.usage, response.cost))

    override def stream(request: ModelRequest): Stream[F, Delta] = super
      .stream(request)
      .evalTap:
        case Delta.End(_, usage, cost) => meter(usage, cost)
        case _                         => Monad[F].unit

object ModelClient:

  /**
    * A client passing every request on to another, for a wrapper to override
    * only what it changes. It passes [[ModelClient.warmCache]] on too, which
    * the default would send through [[ModelClient.send]] instead. A wrapper
    * seeing every request overrides all four: `send` and
    * [[ModelClient.stream]], which every attempt of an `ask` goes through, one
    * or the other by the type asked for, and [[ModelClient.countTokens]] and
    * [[ModelClient.warmCache]], which go their own ways, each passing it on
    * through `super`.
    *
    * @tparam F
    *   The effect type.
    *
    * @param underlying
    *   The client requests are passed on to.
    */
  abstract class Forwarding[F[_]]
    (protected val underlying: ModelClient[F])
    extends ModelClient[F]:

    override protected def send(request: ModelRequest): F[ModelResponse] =
      underlying.send(request)

    override def countTokens(request: ModelRequest): F[Int] = underlying
      .countTokens(request)

    override def warmCache(request: ModelRequest): F[ModelResponse] = underlying
      .warmCache(request)

    override def stream(request: ModelRequest): Stream[F, Delta] = underlying
      .stream(request)

  /**
    * Creates a client over an existing backend, which cannot stream: a reply
    * that would stream arrives whole instead, once it ends.
    *
    * @tparam F
    *   The effect type, whose clock prices each reply as it comes.
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
  def apply[F[_] : MonadThrow : Clock]
    (config: ModelConfig, backend: Backend[F])
    : ModelClient[F] = JsonClient(
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
      config: ModelConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : ModelClient[F] = streaming(config, backend, backend)

  /** A client sending through one backend, and streaming through another. */
  private def streaming[F[_] : Async]
    (
      config: ModelConfig,
      sending: Backend[F],
      streaming: StreamBackend[F, Fs2Streams[F]],
    )
    : ModelClient[F] =
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
  def resource[F[_] : Async](config: ModelConfig): Resource[F, ModelClient[F]] =
    Platform
      .backend[F]
      .map(backend => streaming(config, Deadlined(backend), backend))

  /**
    * Creates a client configured from the environment by
    * [[ModelConfig.fromEnv]].
    *
    * @tparam F
    *   The effect type.
    *
    * @return
    *   A resource holding the client, or `None` when no provider is configured.
    */
  def fromEnv[F[_] : Async]: Resource[F, Option[ModelClient[F]]] = ModelConfig
    .fromEnv()
    .traverse(resource[F](_))
