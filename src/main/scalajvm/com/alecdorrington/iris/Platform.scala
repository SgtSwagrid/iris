package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{Backend, StreamBackend}
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

/** What differs between the platforms: on the JVM, the JDK's HTTP client. */
private[iris] object Platform:

  /** A backend of its own, over the JDK's HTTP client. */
  def backend[F[_] : Async]: Resource[F, Backend[F]] = HttpClientCatsBackend
    .resource[F]()

  /** A streaming backend of its own, over the JDK's HTTP client. */
  def streamBackend[F[_] : Async]
    : Resource[F, StreamBackend[F, Fs2Streams[F]]] = HttpClientFs2Backend
    .resource[F]()

  /** Whether requests may come from a browser's page, which sends its origin. */
  val browser: Boolean = false
