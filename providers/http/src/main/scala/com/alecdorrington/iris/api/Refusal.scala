package com.alecdorrington.iris
package api

import scala.concurrent.duration.*
import sttp.model.{Header, StatusCode}

/**
  * What an unsuccessful status means, telling the failures of a request that
  * waiting may lift from those it cannot, and how long a provider asks to wait,
  * read as it writes it: whole seconds, milliseconds, an RFC 3339 time, Go's
  * durations (`6m0s`) or protobuf's (`1.5s`).
  */
private[iris] object Refusal:

  /** Words saying how long the provider asked to wait, where it said. */
  def asked(retryAfter: Option[FiniteDuration]): String =
    retryAfter.fold("")(after => s" (asked to wait $after)")

  /**
    * The error an unsuccessful status is, with the wait the provider asked for:
    * [[ApiError.RateLimited]] for `429`, [[ApiError.Overloaded]] for `503` or
    * Anthropic's `529`, and [[ApiError.Transient]] for any other the providers'
    * SDKs retry ([[transient]]); but [[ApiError.Unsuccessful]] for any whose
    * body says waiting cannot lift it, and for any other status. The provider's
    * `x-should-retry` header outweighs both, as its SDKs heed it: `true` makes
    * any status one to send again, `false` none.
    *
    * @param shouldRetry
    *   What the provider's `x-should-retry` header says, if it says.
    */
  def of
    (
      api: Provider,
      status: StatusCode,
      detail: String,
      retryAfter: => Option[FiniteDuration],
      shouldRetry: Option[Boolean],
    )
    : ApiError =
    val provider = api.displayName
    val retried  = shouldRetry.getOrElse(
      !api.lasting(detail) && (limiting(status) || transient(status)),
    )
    status.code match
      case _ if !retried => ApiError.Unsuccessful(provider, status, detail)
      case 429           => ApiError.RateLimited(provider, retryAfter, detail)
      case 503 | 529     => ApiError.Overloaded(provider, retryAfter, detail)
      case _ => ApiError.Transient(provider, status, retryAfter, detail)

  /** Whether a status refuses a request for now: `429`, `503` or `529`. */
  private def limiting(status: StatusCode): Boolean = Set(429, 503, 529)
    .contains(status.code)

  /**
    * Whether a status fails a request for now, as the providers' SDKs retry it:
    * a timeout (`408`), a conflict (`409`), or a failure of the provider's own
    * (`500`, `502`, `504`).
    */
  private def transient(status: StatusCode): Boolean =
    Set(408, 409, 500, 502, 504).contains(status.code)

  /** What an `x-should-retry` header says: `true` or `false`, if either. */
  def shouldRetry(value: String): Option[Boolean] = value.trim match
    case "true"  => Some(true)
    case "false" => Some(false)
    case _       => None

  /** The value of the header of a name, whatever case it was sent in. */
  def header(headers: Seq[Header], name: String): Option[String] = headers
    .find(_.is(name))
    .map(_.value.trim)

  /**
    * The longest wait any limit that is spent asks for, as a provider says it:
    * the reset of each whose remaining header is `0`.
    *
    * @param reset
    *   The name of the header saying when the limit of a remaining header
    *   resets, given that one's name in lower case, where it is one.
    *
    * @param read
    *   Reads how long until a reset.
    */
  def spent
    (
      headers: Seq[Header],
      reset: String => Option[String],
      read: String => Option[FiniteDuration],
    )
    : Option[FiniteDuration] = headers
    .filter(_.value.trim == "0")
    .flatMap(remaining => reset(remaining.name.map(_.toLower)))
    .flatMap(header(headers, _).flatMap(read))
    .maxOption

  /** A whole number of seconds, as `Retry-After` gives it; a date is not read. */
  def seconds(value: String): Option[FiniteDuration] = value
    .trim
    .toLongOption
    .filter(_ >= 0)
    .map(seconds => nanos(seconds * 1e9))

  /** A number of milliseconds, as OpenAI's `retry-after-ms` gives it. */
  def milliseconds(value: String): Option[FiniteDuration] = value
    .trim
    .toDoubleOption
    .filter(_ >= 0)
    .map(millis => nanos(millis * 1e6))

  private val instant =
    """(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?(?:[Zz]|([+-])(\d{2}):(\d{2}))"""
      .r

  /**
    * How long until a time in RFC 3339's form, as Anthropic gives its limits'
    * resets, from a moment since 1970, where it is later.
    */
  def until(now: FiniteDuration)(value: String): Option[FiniteDuration] =
    value match
      case instant(
          year,
          month,
          day,
          hour,
          minute,
          second,
          fraction,
          sign,
          offsetHours,
          offsetMinutes,
        ) =>
        val offset = Option(sign).fold(0L)(sign =>
          (if sign == "-" then -60 else 60) *
            (offsetHours.toLong * 60 + offsetMinutes.toLong),
        )
        val at = Provider.utc(year.toInt, month.toInt, day.toInt).toSeconds +
          hour.toLong * 3600 + minute.toLong * 60 + second.toLong - offset
        val later = (at + Option(fraction).fold(0.0)(_.toDouble)) * 1e9 -
          now.toNanos
        Option.when(later > 0)(nanos(later))
      case _ => None

  private val goUnits = Map(
    "h"  -> 3.6e12,
    "m"  -> 6e10,
    "s"  -> 1e9,
    "ms" -> 1e6,
    "us" -> 1e3,
    "µs" -> 1e3,
    "μs" -> 1e3,
    "ns" -> 1.0,
  )

  private val goPart = """(\d+(?:\.\d*)?|\.\d+)(h|ms|m|s|us|µs|μs|ns)""".r

  /**
    * A duration as Go writes it (`1s`, `6m0s`, `1.5ms`), as OpenAI gives its
    * limits' resets.
    */
  def go(value: String): Option[FiniteDuration] =
    val parts = goPart.findAllMatchIn(value).toList
    Option.when(parts.nonEmpty && parts.map(_.matched).mkString == value)(nanos(
      parts.map(part => part.group(1).toDouble * goUnits(part.group(2))).sum,
    ))

  private val protobufDuration = """(\d+(?:\.\d+)?)s""".r

  /**
    * A duration as protobuf writes it in JSON (`34s`, `1.5s`), as Gemini gives
    * `RetryInfo.retryDelay`.
    */
  def protobuf(value: String): Option[FiniteDuration] = value match
    case protobufDuration(seconds) => Some(nanos(seconds.toDouble * 1e9))
    case _                         => None

  /**
    * A number of nanoseconds as a duration, at most a quarter of the longest
    * there is, so that waits may be added up without overflowing.
    */
  def nanos(amount: Double): FiniteDuration = amount
    .max(0)
    .min((Long.MaxValue / 4).toDouble)
    .toLong
    .nanos
