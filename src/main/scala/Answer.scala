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
  *   The token counts of every reply asked for, together, or `None` where the
  *   provider did not report those of one.
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
    usage: Option[Usage] = None,
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
  * The token counts for one request, as reported by the provider.
  *
  * @param inputTokens
  *   The number of tokens in the request, the whole chat included.
  *
  * @param outputTokens
  *   The number of tokens in the reply.
  *
  * @param cachedTokens
  *   The number of the [[inputTokens]] read from the provider's cache.
  */
final case class Usage
  (
    inputTokens: Int,
    outputTokens: Int,
    cachedTokens: Int = 0,
  ):

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
    inputTokens + other.inputTokens,
    outputTokens + other.outputTokens,
    cachedTokens + other.cachedTokens,
  )

object Usage:

  /**
    * The usage, where both counts are reported, as providers may omit them on a
    * failed reply. An unreported cache read counts as `0`.
    */
  private[iris] def of
    (
      input: Option[Int],
      output: Option[Int],
      cached: Option[Int] = None,
    )
    : Option[Usage] =
    for
      inputTokens  <- input
      outputTokens <- output
    yield Usage(
      inputTokens,
      outputTokens,
      cached.getOrElse(0),
    )
