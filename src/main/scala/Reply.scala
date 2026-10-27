package com.alecdorrington.iris

/**
  * The model's reply to a [[Chat]], with the answer read from it: everything
  * [[LlmClient.ask]] gives, alike for every type of answer. A reply as the
  * provider gave it, before any answer is read, is a `Reply[Nothing]`.
  *
  * @tparam A
  *   The type of the answer.
  *
  * @param text
  *   The text of the reply, as the model wrote it.
  *
  * @param stopReason
  *   The reason the model stopped.
  *
  * @param usage
  *   The token counts, summed over every attempt, where the provider reports
  *   them.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  *
  * @param answer
  *   The answer read from the reply, or `None` where the model asked for tools
  *   instead, or none was read.
  *
  * @param attempts
  *   The number of replies asked for before this one could be read.
  */
final case class Reply[+A]
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Option[Usage] = None,
    toolCalls: List[Part.ToolCall] = List.empty,
    answer: Option[A] = None,
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
