package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}
import sttp.client4.impl.cats.FetchCatsBackend
import sttp.client4.impl.fs2.FetchFs2Backend

/** What differs between the platforms: in JavaScript, the Fetch API. */
private[iris] object Platform:

  /** A backend of its own, over the Fetch API. */
  def backend[F[_] : Async]: Resource[F, Backend[F]] =
    Resource.make(Async[F].delay(FetchCatsBackend[F]()))(_.close())

  /** A streaming backend of its own, over the Fetch API. */
  def streamBackend[F[_] : Async]
    : Resource[F, StreamBackend[F, Fs2Streams[F]]] =
    Resource.make(Async[F].delay(FetchFs2Backend[F]()))(_.close())

  /** Whether requests may come from a browser's page, which sends its origin. */
  val browser: Boolean = true
