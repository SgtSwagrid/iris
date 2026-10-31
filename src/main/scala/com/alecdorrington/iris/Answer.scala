package com.alecdorrington.iris

/**
  * The model's answer to a [[Chat]], with its value read from what the model
  * wrote: everything [[LlmClient.ask]] gives, alike for every type. Its text,
  * stop reason and tool calls are those of the last reply asked for. A reply as
  * the provider gave it, before any value is read, as `send` and `warm` give it
  * and [[LlmError.Unreadable]] keeps it, is an `Answer[Nothing]`, whose usage
  * is its own, with no value and one attempt.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param text
  *   The text of the last reply, as the model wrote it.
  *
  * @param stopReason
  *   The reason the model stopped the last reply.
  *
  * @param usage
  *   The token counts of every reply asked for, together.
  *
  * @param cost
  *   What every reply asked for cost, together, in US dollars, each priced as
  *   it came at the [[Rates]] of the configuration or else of its model, or
  *   `None` where neither has any, as for a model Iris does not list.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  *
  * @param value
  *   The value read from the last reply, or `None` where the model asked for
  *   tools instead. A reply from which no value can be read fails the ask.
  *
  * @param attempts
  *   The number of replies asked for, this one included.
  */
final case class Answer[+A]
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Usage = Usage(),
    cost: Option[BigDecimal] = None,
    toolCalls: List[Part.ToolCall] = List.empty,
    value: Option[A] = None,
    attempts: Int = 1,
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
