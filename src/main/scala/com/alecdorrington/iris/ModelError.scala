package com.alecdorrington.iris

import scala.concurrent.duration.FiniteDuration
import sttp.model.StatusCode

/**
  * An error communicating with an LLM provider.
  *
  * @param message
  *   The error's message.
  */
enum ModelError(message: String) extends Exception(message):

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
    ) extends ModelError(s"$provider request failed ($status): $detail")

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
    extends ModelError(s"Unexpected $provider response: $detail")

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
    extends ModelError(s"Cannot address $provider: $detail")

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
    extends ModelError(s"$provider will not answer this chat: $detail")

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
    extends ModelError(s"$provider cannot do this: $detail")

  /**
    * A request the provider did not answer within the configured timeout, such
    * as one whose reply took too long to write or stalled partway.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param after
    *   How long the request was waited for.
    */
  case TimedOut(provider: String, after: FiniteDuration)
    extends ModelError(s"$provider did not answer within $after")

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
    extends ModelError(
      s"Over budget: US$$${ spent.amount } of US$$${ limit.amount } spent",
    )

  /**
    * A reply a [[Budget]] could not charge, as it has no cost: from a model
    * Iris does not list, whose configuration gives no rates. The provider has
    * charged for the request by then, and its reply is lost.
    */
  case Unpriced
    extends ModelError(
      "A budget cannot charge a reply with no cost: give the configuration rates for a model Iris does not list",
    )

  /**
    * A reply that could not be read as the answer [[ModelClient.ask]] asked
    * for: after the model was told why as often as allowed, or at once where
    * asking again could not help, as for a reply cut off at the token limit.
    *
    * @param detail
    *   The description of why the last reply could not be read, as the model
    *   was told it, which may repeat what the reply said, as a host's own
    *   refusal of a value may or a map's keys do, and so is kept out of the
    *   message.
    *
    * @param responses
    *   Every response asked for, oldest first, with their usage, also kept out
    *   of the message: as many as the ask made attempts.
    */
  case UnreadableResponse
    (
      detail: String,
      responses: List[ModelResponse],
    )
    extends ModelError(
      s"The model's reply could not be read as asked (attempts: ${ responses
          .size })",
    )
