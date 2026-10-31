package com.alecdorrington.iris

/**
  * The model's answer to a [[Chat]], as [[LlmClient.ask]] gives it, alike for
  * every type: its value, as soon as everything in it but its streams is read,
  * and the reply it was read from, once that reply ends.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the last reply, or `None` where the model asked for
  *   tools instead. Each `fs2.Stream` within it delivers its elements as the
  *   model writes them, each time it is read, from the first.
  *
  * @param reply
  *   The last reply, once it ends, with the usage of every reply asked for.
  *   Where the reply cannot be read to its end after its value was given, it
  *   fails as the value's streams not yet ended do: with
  *   [[LlmError.Unreadable]] where what the model wrote cannot be read, and as
  *   the request fails where that fails partway.
  *
  * @param cancel
  *   Stops reading the reply, where it is still being read once its value was
  *   given, as it is for a type holding a stream: its streams not yet ended,
  *   and its reply, then fail with an `IllegalStateException`. Otherwise it
  *   does nothing, as does a cancel once the reply has ended.
  */
final case class Answer[F[_], +A]
  (
    value: Option[A],
    reply: F[Reply],
    cancel: F[Unit],
  )

/**
  * A reply of the model's as the provider gave it, before any value is read
  * from it: what [[LlmClient.ask]] gives of the reply its answer was read from,
  * what `send` and `warm` give, and what [[LlmError.Unreadable]] keeps.
  *
  * @param text
  *   The text of the reply, as the model wrote it.
  *
  * @param stopReason
  *   The reason the model stopped.
  *
  * @param usage
  *   The token counts of the reply, or of every reply an answer was asked for
  *   in, together.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  *
  * @param attempts
  *   The number of replies an answer was asked for in, this one included.
  */
final case class Reply
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Usage = Usage(),
    toolCalls: List[Part.ToolCall] = List.empty,
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
