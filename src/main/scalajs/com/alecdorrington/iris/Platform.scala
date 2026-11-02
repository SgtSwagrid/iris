package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import org.scalajs.dom.{AbortController, Event, Request, RequestInit}
import sttp.client4.impl.fs2.FetchFs2Backend

/** What differs between the platforms: in JavaScript, the Fetch API. */
private[iris] object Platform:

  /**
    * Backends of a client's own over the Fetch API: one sending requests whole,
    * and for each streamed reply, one whose request a signal of Iris's own
    * aborts, closing its connection.
    */
  def backends[F[_] : Async]: Resource[F, Backends[F]] = Resource
    .make(Async[F].delay(FetchFs2Backend[F]()))(_.close())
    .map(Backends(_, Async[F].delay(exchange[F])))

  /** Whether requests may come from a browser's page, which sends its origin. */
  val browser: Boolean = true

  /**
    * A backend for one streamed reply, whose request takes a signal of Iris's
    * own: sttp aborts its own only until the reply begins (on its timeout, or a
    * cancel), and never once a body is dropped.
    */
  private def exchange[F[_] : Async]: Exchange[F] =
    val controller = new AbortController()
    Exchange(
      FetchFs2Backend[F](customizeRequest = signalled(controller)),
      Async[F].delay(controller.abort()),
    )

  /** The request, taking the controller's signal, which its own still aborts. */
  private def signalled
    (controller: AbortController)
    (request: Request)
    : Request =
    request.signal.addEventListener("abort", (_: Event) => controller.abort())
    val init = new RequestInit {}
    init.signal = controller.signal
    new Request(request, init)
