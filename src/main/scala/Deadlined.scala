package com.alecdorrington.iris

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import sttp.capabilities.Effect
import sttp.client4.{Backend, GenericRequest, Response, SttpClientException}
import sttp.client4.wrappers.DelegateBackend

/**
  * A backend giving each exchange at most its request's read timeout, from
  * sending it to reading its body whole. The JDK's client times a request out
  * only until its reply begins, and then waits for the rest forever. An
  * exchange given up on is left to end by itself, as a blocked read cannot be
  * interrupted.
  */
private[iris] final class Deadlined[F[_] : Temporal](delegate: Backend[F])
  extends DelegateBackend[F, Any](delegate), Backend[F]:

  override def send[T]
    (request: GenericRequest[T, Any & Effect[F]])
    : F[Response[T]] = request.options.readTimeout match
    case limit: FiniteDuration =>
      delegate
        .send(request)
        .timeoutAndForget(limit)
        .adaptError { case cause: TimeoutException =>
          SttpClientException.TimeoutException(request, cause)
        }
    case _ => delegate.send(request)
