package com.alecdorrington.iris

import cats.{Applicative, MonadThrow}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import fs2.{Compiler, Stream}
import scala.Conversion.into
import scala.compiletime.summonInline

/**
  * A request for the model's next message: one exchange of text with it. Its
  * settings override those of the [[PromptConfig]] the service was made with,
  * where they are set.
  *
  * @param conversation
  *   The conversation so far, whose next message the model writes.
  *
  * @param maxTokens
  *   The most tokens the model may write, its thinking included.
  *
  * @param temperature
  *   The sampling temperature; higher values give more varied messages. Not
  *   sent to Anthropic models that reject sampling.
  *
  * @param topP
  *   The nucleus sampling threshold. Not sent to Anthropic models that reject
  *   sampling.
  *
  * @param stopSequences
  *   The sequences at which the model stops writing.
  *
  * @param effort
  *   The effort a thinking model should give the request.
  *
  * @param tools
  *   The tools the model may ask to have run, which it asks for in the
  *   response's [[PromptResponse.toolCalls]].
  *
  * @param streamed
  *   Whether the response is to travel as it is written, so that
  *   [[PromptService.stream]] gives each part as it arrives, rather than all at
  *   once at the end. [[PromptService.send]] asks for it whole.
  *
  * @param cacheAll
  *   Whether a cache breakpoint closes the conversation as it is sent, never
  *   kept in it, so that a request going on from it reads it all from the
  *   cache, as an agent marks each round.
  */
final case class PromptRequest
  (
    conversation: Conversation,
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: Option[List[String]] = None,
    effort: Option[Effort] = None,
    tools: List[Tool] = List.empty,
    streamed: Boolean = true,
    cacheAll: Boolean = false,
  )

object PromptRequest:

  /**
    * A request of a conversation, with the service's settings.
    *
    * @return
    *   The conversion of a conversation to a request of it.
    */
  given fromConversation: Conversion[Conversation, PromptRequest] =
    PromptRequest(_)

  /**
    * A request of one message of the user's, saying a value as its [[Content]]
    * says it, as a single-turn prompt.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a value to a request of it.
    */
  inline given fromContent[A]: Conversion[A, PromptRequest] =
    said(summonInline[Content[A]])

  /** A request of one message of the user's, saying a value as it says it. */
  private[iris] def said[A](content: Content[A]): Conversion[A, PromptRequest] =
    value =>
      PromptRequest(Conversation(List(Message.User(List(content.text(value))))))

/**
  * The settings every [[PromptRequest]] to a service takes, unless it sets its
  * own.
  *
  * @param maxTokens
  *   The most tokens the model may write, its thinking included.
  *
  * @param temperature
  *   The sampling temperature, or `None` for the provider's own.
  *
  * @param topP
  *   The nucleus sampling threshold, or `None` for the provider's own.
  *
  * @param stopSequences
  *   The sequences at which the model stops writing.
  *
  * @param effort
  *   The effort a thinking model should give a request, or `None` for its own
  *   choice. A model that cannot take one refuses the request.
  *
  * @param rates
  *   The rates to price each response at, or `None` for those Iris lists for
  *   its model, of which a model Iris does not list has none.
  */
final case class PromptConfig
  (
    maxTokens: Int = PromptConfig.defaultMaxTokens,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: List[String] = List.empty,
    effort: Option[Effort] = None,
    rates: Option[Rates] = None,
  ):

  /** The settings of a request, its own where set, else these. */
  private[iris] def settings(request: PromptRequest): Settings = Settings(
    request.maxTokens.getOrElse(maxTokens),
    request.temperature.orElse(temperature),
    request.topP.orElse(topP),
    request.stopSequences.getOrElse(stopSequences),
    request.effort.orElse(effort),
    request.tools,
    request.streamed,
  )

object PromptConfig:

  /** The most tokens the model may write, unless configured otherwise. */
  val defaultMaxTokens: Int = 8192

  /**
    * Reads a configuration from the environment: `LLM_MAX_TOKENS`.
    *
    * @param defaultMaxTokens
    *   The most tokens where `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   The configuration, or `None` where `LLM_MAX_TOKENS` is set but is no
    *   positive number.
    */
  def fromEnv(defaultMaxTokens: Int = defaultMaxTokens): Option[PromptConfig] =
    from(Model.env, defaultMaxTokens)

  /**
    * Reads a configuration from variables, as [[fromEnv]] does.
    *
    * @param variable
    *   Looks up a variable by name.
    *
    * @param defaultMaxTokens
    *   The most tokens where `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   The configuration, or `None` where `LLM_MAX_TOKENS` is set but is no
    *   positive number.
    */
  def from
    (
      variable: String => Option[String],
      defaultMaxTokens: Int = defaultMaxTokens,
    )
    : Option[PromptConfig] = variable("LLM_MAX_TOKENS")
    .fold(Some(defaultMaxTokens))(Model.positive)
    .map(maxTokens => PromptConfig(maxTokens = maxTokens))

/** A request's settings, resolved against its service's configuration. */
private[iris] final case class Settings
  (
    maxTokens: Int,
    temperature: Option[Double],
    topP: Option[Double],
    stopSequences: List[String],
    effort: Option[Effort],
    tools: List[Tool],
    streamed: Boolean,
  )

/**
  * One response of the model's, as the provider gave it: its message, why it
  * stopped, and what it took.
  *
  * @param text
  *   The text of the response, as the model wrote it.
  *
  * @param stopReason
  *   The reason the model stopped.
  *
  * @param usage
  *   The token counts of the response.
  *
  * @param cost
  *   What the response cost, priced as it came at the [[Rates]] of the
  *   service's configuration or else of its model, or `None` where there are
  *   none, as for a model Iris does not list.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  */
final case class PromptResponse
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Usage = Usage(),
    cost: Option[Usd] = None,
    toolCalls: List[MessagePart.ToolCall] = List.empty,
  ):

  /** The model's message, its text and tool calls. */
  def message: Message = Message.Model(
    Option.when(text.nonEmpty)(MessagePart.Text(text)).toList ++ toolCalls,
  )

/**
  * A part of a response as it arrives through [[PromptService.stream]]: more of
  * what the model wrote, then its end.
  */
enum PromptEvent:

  /**
    * More characters of a part of the model's message, to append to those
    * before.
    *
    * @param part
    *   The part they belong to: the text, or a tool call's arguments.
    *
    * @param fragment
    *   The characters.
    */
  case Written(part: PromptEvent.Part, fragment: String)

  /**
    * The end of the response, the provider's report on it, not something the
    * model wrote.
    *
    * @param response
    *   The whole response.
    */
  case Ended(response: PromptResponse)

object PromptEvent:

  /** A part of the model's message, as characters of it arrive. */
  enum Part:

    /** The model's text. */
    case Text

    /**
      * The arguments of a tool call, which arrive whole.
      *
      * @param id
      *   The call's identifier.
      *
      * @param tool
      *   The name of the tool called.
      *
      * @param signature
      *   The provider's opaque token for the call, if any.
      */
    case Call
      (
        id: String,
        tool: String,
        signature: Option[String],
      )

/**
  * An error preparing an exchange of text: a request the provider would refuse,
  * or a budget's refusal. No request is made where one is.
  *
  * @param message
  *   The error's message.
  */
enum PromptError(message: String) extends Exception(message):

  /**
    * A conversation the provider would refuse. No request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what the provider would refuse.
    */
  case Unsendable(provider: String, detail: String)
    extends PromptError(s"$provider will not answer this conversation: $detail")

  /**
    * An operation the provider's API does not offer, which fails every time. No
    * request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what the provider cannot do.
    */
  case Unsupported(provider: String, detail: String)
    extends PromptError(s"$provider cannot do this: $detail")

  /**
    * A request a [[Budget]] refused, as as much as its limit was spent. No
    * request is made.
    *
    * @param limit
    *   The most the budget's requests may spend together.
    *
    * @param spent
    *   What they had spent when this one was refused.
    */
  case OverBudget(limit: Usd, spent: Usd)
    extends PromptError(
      s"Over budget: US$$${ spent.amount } of US$$${ limit.amount } spent",
    )

  /**
    * A response a [[Budget]] could not charge, as it has no cost: from a model
    * Iris does not list, whose configuration gives no rates. The provider has
    * charged for the request by then, and its response is lost.
    */
  case Unpriced
    extends PromptError(
      "A budget cannot charge a response with no cost: give the configuration rates for a model Iris does not list",
    )

/**
  * One exchange of text with the model: a conversation in, the model's next
  * message out. Its one method, [[stream]], is what an implementation or a
  * middleware writes, which [[send]] gathers. A service is made by its model,
  * as `Model.ClaudeSonnet5_5.prompt[IO]()`.
  *
  * {{{
  * Model.ClaudeHaiku5_5.prompt[IO]().use(_.send("Hello!")).map(_.text)
  * }}}
  *
  * @tparam F
  *   The effect type.
  */
trait PromptService[F[_]]:

  /**
    * Sends a request, giving its response as it arrives: what the model writes,
    * then the response whole. It travels as it is written where the request is
    * [[PromptRequest.streamed]], and arrives all at once at the end otherwise.
    *
    * @param request
    *   The request: a conversation, or what the user says, for the service's
    *   settings.
    *
    * @return
    *   A stream of the response's events, ending with [[PromptEvent.Ended]].
    */
  def stream(request: into[PromptRequest]): Stream[F, PromptEvent]

  /**
    * Sends a request, giving its response whole.
    *
    * @param request
    *   The request: a conversation, or what the user says, for the service's
    *   settings. It is sent unstreamed.
    *
    * @return
    *   An effect producing the response.
    */
  final def send
    (request: into[PromptRequest])
    (using Compiler[F, F], MonadThrow[F])
    : F[PromptResponse] = stream(request.copy(streamed = false))
    .collect { case PromptEvent.Ended(response) => response }
    .compile
    .lastOrError

  /**
    * Writes the [[Conversation.cachePrefix]] of a request's conversation into
    * the provider's cache, with as short a response as the provider allows.
    * Requests sharing the prefix and sent at once afterwards then all read it,
    * where unwarmed each would write its own. Fails with
    * [[PromptError.Unsendable]] where the conversation has no
    * [[MessagePart.CacheBreakpoint]].
    *
    * @param request
    *   The request whose prefix to cache, with the settings the requests will
    *   be sent with, as a cache is kept per model and settings.
    *
    * @return
    *   An effect producing the provider's empty response, whose usage says how
    *   much of the prefix was cached.
    */
  final def warmCache
    (request: into[PromptRequest])
    (using Compiler[F, F], MonadThrow[F])
    : F[PromptResponse] = send(request.copy(
    conversation = request.conversation.cachePrefix,
    maxTokens = Some(0),
  ))

  /**
    * This service, running a function on each response's usage and cost as it
    * ends: once for each request that spends, as an ask's every attempt and
    * round does, and warming the cache. A stream stopped before its end reports
    * nothing.
    *
    * @param meter
    *   Runs on each response's usage and cost.
    *
    * @return
    *   A service metering each response.
    */
  final def metered
    (meter: (Usage, Option[Usd]) => F[Unit])
    (using Applicative[F])
    : PromptService[F] = request =>
    stream(request).evalTap:
      case PromptEvent.Ended(response) => meter(response.usage, response.cost)
      case _                           => Applicative[F].unit

  /**
    * This service, refusing requests once a budget is spent, and charging it
    * each response's cost, as [[Budget.limits]] does.
    *
    * @param budget
    *   The budget to charge.
    *
    * @return
    *   A service spending from the budget.
    */
  final def limitedBy(budget: Budget[F]): PromptService[F] = budget.limits(this)

object PromptService:

  /**
    * Creates a service configured from the environment: its model by
    * [[Model.fromEnv]], its API by [[ApiConfig.fromEnv]] and its settings by
    * [[PromptConfig.fromEnv]].
    *
    * @tparam F
    *   The effect type.
    *
    * @param defaultMaxTokens
    *   The most tokens a response may take where `LLM_MAX_TOKENS` is unset.
    *
    * @return
    *   A resource holding the service, or `None` where the environment
    *   configures none.
    */
  def fromEnv[F[_] : Async]
    (defaultMaxTokens: Int = PromptConfig.defaultMaxTokens)
    : Resource[F, Option[PromptService[F]]] = (
    Model.fromEnv(),
    ApiConfig.fromEnv(),
    PromptConfig.fromEnv(defaultMaxTokens),
  ).tupled.traverse((model, api, config) => model.prompt[F](api, config))

/**
  * Counts the tokens of a conversation as its model's provider would, where it
  * can: the one exchange with a provider that is no prompt, so made by a model
  * apart from its [[PromptService]], as `model.counter(api)`.
  *
  * @tparam F
  *   The effect type.
  */
trait TokenCounter[F[_]]:

  /**
    * Counts the input tokens a request would cost.
    *
    * @param request
    *   The request to count, of which the conversation alone is counted.
    *
    * @return
    *   An effect producing the number of input tokens.
    */
  def countTokens(request: into[PromptRequest]): F[Int]
