package com.alecdorrington.iris

/**
  * The model's reply to a [[Chat]], as [[LlmClient.ask]] gives it whole.
  *
  * @param text
  *   The text of the reply.
  *
  * @param stopReason
  *   The reason the model stopped.
  *
  * @param usage
  *   The token counts for the request, where the provider reports them.
  *
  * @param toolCalls
  *   The tools the model asks to have run before it goes on, if any.
  */
final case class Reply
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Option[Usage] = None,
    toolCalls: List[Part.ToolCall] = List.empty,
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
