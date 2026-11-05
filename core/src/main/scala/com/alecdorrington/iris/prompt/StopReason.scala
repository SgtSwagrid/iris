package com.alecdorrington.iris
package prompt

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
