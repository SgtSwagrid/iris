package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import org.scalajs.dom.{AbortController, Event, Request, RequestInit}
import scala.scalajs.js
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
    * cancel), and never once a body is dropped. It keeps every request it makes
    * for as long as it lives, as Node's `fetch` (undici) has a request follow
    * the signal it was made with only through a weak reference to the request's
    * own controller: once nothing holds a request, a collection while its reply
    * is read leaves aborting the signal aborting nothing, and the connection
    * open, as `AbortSuite` saw now and then on a busy machine.
    */
  private def exchange[F[_] : Async]: Exchange[F] =
    val controller = new AbortController()
    val kept       = js.Array[Request]()
    Exchange(
      FetchFs2Backend[F](customizeRequest = request =>
        val sent = signalled(controller)(request)
        kept.push(request, sent)
        sent),
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
