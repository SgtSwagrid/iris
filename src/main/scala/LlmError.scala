package com.alecdorrington.iris

import scala.concurrent.duration.FiniteDuration
import sttp.model.StatusCode

/**
  * An error communicating with an LLM provider.
  *
  * @param message
  *   The error's message.
  */
enum LlmError(message: String) extends Exception(message):

  /**
    * An unsuccessful HTTP status from the provider.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param status
    *   The HTTP status.
    *
    * @param retryAfter
    *   The time the provider asked to wait before retrying, where it said, as
    *   on a rate limit.
    *
    * @param detail
    *   The response body.
    */
  case Unsuccessful
    (
      provider: String,
      status: StatusCode,
      retryAfter: Option[FiniteDuration],
      detail: String,
    ) extends LlmError(s"$provider request failed ($status): $detail")

  /**
    * A response body that could not be decoded.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of the decoding failure.
    */
  case Malformed(provider: String, detail: String)
    extends LlmError(s"Unexpected $provider response: $detail")

  /**
    * A configuration that cannot be used, such as a base URL that is no URL. No
    * request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what cannot be used.
    */
  case Misconfigured(provider: String, detail: String)
    extends LlmError(s"Cannot address $provider: $detail")

  /**
    * A chat the provider would refuse. No request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what the provider would refuse.
    */
  case Unsendable(provider: String, detail: String)
    extends LlmError(s"$provider will not answer this chat: $detail")

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
    extends LlmError(s"$provider cannot do this: $detail")

  /**
    * A request the provider did not answer within the configured timeout, such
    * as one whose reply took too long to write.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param after
    *   How long the request was waited for.
    */
  case TimedOut(provider: String, after: FiniteDuration)
    extends LlmError(s"$provider did not answer within $after")

  /**
    * A reply that could not be read as the answer [[LlmClient.ask]] asked for:
    * after the model was told why as often as allowed, or at once where asking
    * again could not help, as for a reply cut off at the token limit.
    *
    * @param attempts
    *   The number of replies asked for.
    *
    * @param detail
    *   The description of why the last reply could not be read, as the model
    *   was told it, which may repeat what the reply said, as a host's own
    *   refusal of a value may or a map's keys do, and so is kept out of the
    *   message.
    *
    * @param replies
    *   The replies asked for, oldest first, with their usage, also kept out of
    *   the message.
    */
  case Unreadable
    (
      attempts: Int,
      detail: String,
      replies: List[Reply[Nothing]],
    )
    extends LlmError(
      s"The model's reply could not be read as asked (attempts: $attempts)",
    )
