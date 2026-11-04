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
    * An unsuccessful HTTP status from the provider that sending the request
    * again would not change, and so never sent again: a refused key, a request
    * the provider cannot take, a spent quota or credit, or any the provider
    * marks not to retry (`x-should-retry: false`).
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param status
    *   The HTTP status.
    *
    * @param detail
    *   The response body.
    */
  case Unsuccessful
    (
      provider: String,
      status: StatusCode,
      detail: String,
    ) extends ModelError(s"$provider request failed ($status): $detail")

  /**
    * A request the provider refused for now, as over the rate limits of the
    * key's account (HTTP `429`), and went on refusing for as long as the
    * client's [[Retries]] allowed, or asked to be waited for longer; a client
    * that cannot wait gives it at once.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it refused it last.
    *
    * @param detail
    *   The response body, or the streamed event that refused it.
    */
  case RateLimited
    (
      provider: String,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ModelError(
      s"$provider is limiting requests${ Refusal.asked(retryAfter) }: $detail",
    )

  /**
    * A request the provider refused for now, as too busy to answer it (HTTP
    * `503`, or Anthropic's `529`), and went on refusing for as long as the
    * client's [[Retries]] allowed, or asked to be waited for longer; a client
    * that cannot wait gives it at once.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it refused it last.
    *
    * @param detail
    *   The response body, or the streamed event that refused it.
    */
  case Overloaded
    (
      provider: String,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ModelError(
      s"$provider is overloaded${ Refusal.asked(retryAfter) }: $detail",
    )

  /**
    * A request the provider failed for now, which may succeed if sent again: a
    * failure of its own (HTTP `500`, `502` or `504`), a request it timed out
    * (`408`) or found in conflict (`409`), or any other it marks worth retrying
    * (`x-should-retry: true`), as the providers' own SDKs retry them. It went
    * on failing for as long as the client's [[Retries]] allowed, or asked to be
    * waited for longer; a client that cannot wait gives it at once.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param status
    *   The HTTP status.
    *
    * @param retryAfter
    *   How long the provider asked to wait before sending it again, where it
    *   said, from when it failed it last.
    *
    * @param detail
    *   The response body, or the streamed event that failed it.
    */
  case Transient
    (
      provider: String,
      status: StatusCode,
      retryAfter: Option[FiniteDuration],
      detail: String,
    )
    extends ModelError(s"$provider failed for now ($status)${ Refusal.asked(
        retryAfter,
      ) }: $detail")

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
    * A conversation the provider would refuse. No request is made.
    *
    * @param provider
    *   The name of the provider, for display.
    *
    * @param detail
    *   The description of what the provider would refuse.
    */
  case Unsendable(provider: String, detail: String)
    extends ModelError(s"$provider will not answer this conversation: $detail")

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
