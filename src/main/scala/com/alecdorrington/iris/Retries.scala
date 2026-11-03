package com.alecdorrington.iris

import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*

/**
  * How a client waits out a provider's refusals of a request for now, as over
  * its rate limits ([[ModelError.RateLimited]]) or overloaded
  * ([[ModelError.Overloaded]]), and its failures for now
  * ([[ModelError.Transient]]), sending the request again until it is answered
  * or the retries run out, when the last refusal is given. It waits as long as
  * the provider asks, where it says, and up to [[backoff]] more at random, so
  * that requests refused together do not all come back at once; else
  * [[backoff]] after the first refusal, twice as long after each next, less up
  * to half at random. Any other failure is given at once, as waiting would not
  * change it: a refused key, a request the provider cannot take, a spent quota
  * or credit, a day's quota used up, or a reply cut off at the token limit. A
  * stream is sent again only while none of its reply has come, as its reader
  * would otherwise have read part of a reply twice; a stream refused after its
  * reply began fails.
  *
  * @param attempts
  *   The most times to send one request, the first among them, so that `1`
  *   never sends one again.
  *
  * @param backoff
  *   The wait after a first refusal where the provider asks for none, and the
  *   most added at random to a wait it asks for.
  *
  * @param patience
  *   The most to wait for one request in all: a refusal asking for longer than
  *   is left of this, or after which the next wait would take longer, is given
  *   at once, with the wait asked for, which a host may then tell its user.
  */
final case class Retries
  (
    attempts: Int = 4,
    backoff: FiniteDuration = 1.second,
    patience: FiniteDuration = 1.minute,
  ):

  /**
    * How long to wait before sending a request again after its `tried`th
    * failure, having waited `waited` already, given a draw between `0` and `1`,
    * or `None` to give the failure.
    */
  private[iris] def delay
    (
      error: Throwable,
      tried: Int,
      waited: FiniteDuration,
      draw: Double,
    )
    : Option[FiniteDuration] = Retries
    .asked(error)
    .filter(_ => tried < attempts)
    .map(_.fold(backedOff(tried, draw))(after(_, draw)))
    .filter(_ <= patience - waited)

  /** The wait after the `tried`th refusal naming none. */
  private def backedOff(tried: Int, draw: Double): FiniteDuration = Refusal
    .nanos(backoff.toNanos * math.pow(2, tried - 1) * (1 - draw / 2))

  /** A wait the provider asked for, and a little more at random. */
  private def after(asked: FiniteDuration, draw: Double): FiniteDuration =
    Refusal.nanos(asked.toNanos + backoff.toNanos * draw)

object Retries:

  /** Retries that never send a request again, giving each refusal at once. */
  val none: Retries = Retries(attempts = 1)

  /**
    * The wait a refusal that waiting may lift asks for, if any, or `None` for
    * any other failure.
    */
  private def asked(error: Throwable): Option[Option[FiniteDuration]] =
    error match
      case ModelError.RateLimited(_, retryAfter, _)  => Some(retryAfter)
      case ModelError.Overloaded(_, retryAfter, _)   => Some(retryAfter)
      case ModelError.Transient(_, _, retryAfter, _) => Some(retryAfter)
      case _                                         => None

/**
  * A client sending each request again as [[Retries]] say, for as long as the
  * provider refuses it for now: every request but a stream whose reply has
  * begun. Waiting is cancelled with the request.
  *
  * @param draw
  *   Draws a number between `0` and `1` for each wait, at random but in tests.
  */
private[iris] final class Retrying[F[_] : Temporal]
  (
    client: ModelClient[F],
    retries: Retries,
    draw: F[Double],
  )
  extends ModelClient.Forwarding(client):

  override protected def send(request: ModelRequest): F[ModelResponse] =
    retried(super.send(request))

  override def countTokens(request: ModelRequest): F[Int] =
    retried(super.countTokens(request))

  override def warmCache(request: ModelRequest): F[ModelResponse] =
    retried(super.warmCache(request))

  override def stream(request: ModelRequest): Stream[F, Delta] =
    unbegun(super.stream(request))

  /** The request, sent again after each refusal, as the retries allow. */
  private def retried[A]
    (
      request: F[A],
      tried: Int = 1,
      waited: FiniteDuration = Duration.Zero,
    )
    : F[A] = request.handleErrorWith(error =>
    delay(error, tried, waited).flatMap(_.fold(error.raiseError[F, A])(wait =>
      Temporal[F].sleep(wait) >> retried(request, tried + 1, waited + wait),
    )),
  )

  /**
    * The stream, streamed again after each refusal that comes before any of its
    * reply, as the retries allow.
    */
  private def unbegun
    (
      deltas: Stream[F, Delta],
      tried: Int = 1,
      waited: FiniteDuration = Duration.Zero,
    )
    : Stream[F, Delta] = Stream
    .eval(Ref.of[F, Boolean](false))
    .flatMap(begun =>
      deltas
        .chunks
        .evalTap(chunk => begun.set(true).whenA(chunk.nonEmpty))
        .unchunks
        .handleErrorWith(error =>
          Stream
            .eval(begun.get.ifM(none.pure, delay(error, tried, waited)))
            .flatMap(_.fold(Stream.raiseError[F](error))(wait =>
              Stream.sleep_[F](wait) ++
                unbegun(deltas, tried + 1, waited + wait),
            )),
        ),
    )

  private def delay
    (
      error: Throwable,
      tried: Int,
      waited: FiniteDuration,
    )
    : F[Option[FiniteDuration]] =
    draw.map(retries.delay(error, tried, waited, _))
