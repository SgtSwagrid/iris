package com.alecdorrington.iris

import cats.Functor
import cats.syntax.functor.*
import cats.syntax.traverse.*

/**
  * The model's response to a [[Chat]] as [[ModelClient.ask]] gives it, its
  * value read and validated against the type's schema, alike for every type:
  * the value, as soon as everything in it but its streams is read, and every
  * response the ask took, once the last ends. An ask may take several, each a
  * request of its own: asking again after a response that cannot be read, and
  * going on after tools are run.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the last response, or `None` where the model asked for
  *   tools instead. Each `fs2.Stream` within it delivers its elements as the
  *   model writes them, each time it is read, from the first.
  *
  * @param responses
  *   Every response the ask took, oldest first, once the last ends: the last is
  *   the one the value was read from. Where the last cannot be read to its end
  *   after the value was given, it fails as the value's streams not yet ended
  *   do: with [[ModelError.UnreadableResponse]] where what the model wrote
  *   cannot be read, and as the request fails where that fails partway.
  *
  * @param cancel
  *   Stops reading the last response, where it is still being read once the
  *   value was given, as it is for a type holding a stream: its streams not yet
  *   ended, and its responses, then fail with an `IllegalStateException`.
  *   Otherwise it does nothing, as does a cancel once the response has ended.
  */
final case class ValidatedResponse[F[_], +A]
  (
    value: Option[A],
    responses: F[List[ModelResponse]],
    cancel: F[Unit],
  ):

  /**
    * The token counts of every response the ask took, together.
    *
    * @return
    *   An effect producing the counts, once the last response ends.
    */
  def usage(using Functor[F]): F[Usage] =
    responses.map(_.foldLeft(Usage())(_ + _.usage))

  /**
    * What every response the ask took cost, together.
    *
    * @return
    *   An effect producing the cost, once the last response ends, or `None`
    *   where any response has none, as from a model Iris does not list.
    */
  def cost(using Functor[F]): F[Option[Usd]] =
    responses.map(_.traverse(_.cost).map(_.foldLeft(Usd.zero)(_ + _)))

/**
  * One response of the model's, as the provider gave it, before any value is
  * read from it: what a client's `send` and [[ModelClient.warmCache]] give,
  * and each of the responses a [[ValidatedResponse]] or
  * [[ModelError.UnreadableResponse]] keeps.
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
  *   configuration or else of its model, or `None` where there are none, as for
  *   a model Iris does not list.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  */
final case class ModelResponse
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Usage = Usage(),
    cost: Option[Usd] = None,
    toolCalls: List[MessagePart.ToolCall] = List.empty,
  )

/** The reason a model stopped generating, normalised across providers. */
enum StopReason:

  /** The model finished its reply naturally. */
  case Completed

  /** The reply was cut off at the token limit. */
  case MaxTokens

  /** The reply reached one of the stop sequences. */
  case StopSequence

  /** The model stopped to await the result of a tool it called. */
  case ToolCall

  /**
    * A provider-specific reason not covered by the other cases.
    *
    * @param reason
    *   The provider's name for the reason.
    */
  case Other(reason: String)

  /** The provider did not say why the model stopped. */
  case Unknown

object StopReason:

  /** Normalises a provider's stop reason by its labels for each case. */
  private[iris] def normalise
    (
      completed: String,
      maxTokens: String,
      stopSequence: Option[String] = None,
      toolCall: Option[String] = None,
    )
    (reason: Option[String])
    : StopReason = reason match
    case Some(`completed`)                           => Completed
    case Some(`maxTokens`)                           => MaxTokens
    case Some(other) if stopSequence.contains(other) => StopSequence
    case Some(other) if toolCall.contains(other)     => ToolCall
    case Some(other)                                 => Other(other)
    case None                                        => Unknown

/**
  * The token counts of one request or more, as the provider reported them,
  * alike for every provider. No count includes another, so that each may be
  * priced alone. A count the provider did not report is `0`, as is one it never
  * charges for apart: Gemini charges nothing for writing its cache, nor OpenAI
  * before GPT-5.6, and only Gemini reports audio apart.
  *
  * @param uncachedTokens
  *   The number of tokens of the request read afresh, other than audio.
  *
  * @param cacheReadTokens
  *   The number of tokens of the request read from the provider's cache, other
  *   than audio.
  *
  * @param cacheWriteTokens
  *   The number of tokens of the request written to the provider's cache, where
  *   the provider charges for writing them.
  *
  * @param uncachedAudioTokens
  *   The number of tokens of audio in the request read afresh.
  *
  * @param cacheReadAudioTokens
  *   The number of tokens of audio in the request read from the provider's
  *   cache.
  *
  * @param visibleTokens
  *   The number of tokens the model replied with: its text and tool calls.
  *
  * @param thinkingTokens
  *   The number of tokens the model thought in before it replied, whether or
  *   not the provider shows what it thought.
  */
final case class Usage
  (
    uncachedTokens: Int = 0,
    cacheReadTokens: Int = 0,
    cacheWriteTokens: Int = 0,
    uncachedAudioTokens: Int = 0,
    cacheReadAudioTokens: Int = 0,
    visibleTokens: Int = 0,
    thinkingTokens: Int = 0,
  ):

  /** The number of tokens in the request, the whole chat included. */
  def inputTokens: Int = uncachedTokens + cacheReadTokens + cacheWriteTokens +
    uncachedAudioTokens + cacheReadAudioTokens

  /** The number of the [[inputTokens]] read from the provider's cache. */
  def cachedTokens: Int = cacheReadTokens + cacheReadAudioTokens

  /** The number of tokens in the reply, its thinking included. */
  def outputTokens: Int = visibleTokens + thinkingTokens

  /**
    * Adds the token counts of another request to these.
    *
    * @param other
    *   The other request's counts.
    *
    * @return
    *   The counts of both requests together.
    */
  def + (other: Usage): Usage = Usage(
    uncachedTokens + other.uncachedTokens,
    cacheReadTokens + other.cacheReadTokens,
    cacheWriteTokens + other.cacheWriteTokens,
    uncachedAudioTokens + other.uncachedAudioTokens,
    cacheReadAudioTokens + other.cacheReadAudioTokens,
    visibleTokens + other.visibleTokens,
    thinkingTokens + other.thinkingTokens,
  )
