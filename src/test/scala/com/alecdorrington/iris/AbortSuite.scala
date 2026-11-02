package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.config
import fs2.Stream
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
  * A local server's view of the one request it answers.
  *
  * @param address
  *   The server's address, as a base URL.
  *
  * @param received
  *   Completes once the request has arrived whole.
  *
  * @param closed
  *   Completes once the client has closed the request's connection.
  */
final case class Served
  (
    address: String,
    received: Future[Unit],
    closed: Future[Unit],
  )

/**
  * Streamed replies given up on, sent to a real server, which notes when the
  * client closes their connections: one left open is one the provider goes on
  * writing, and charging for, with nobody reading it.
  */
class AbortSuite extends FunSuite:

  /** The first part of a reply, opening an array, after which nothing comes. */
  private val begun =
    "data: {\"type\":\"content_block_delta\"," +
      "\"delta\":{\"type\":\"text_delta\",\"text\":\"[\\\"a\\\",\"}}\n\n"

  private val request = ModelRequest(Chat.userSays("Count."))

  /**
    * Runs `test` against a server answering with `reply` and then nothing, or
    * not at all, and a client of Iris's own, configured with `timeout`.
    */
  private def serving
    (reply: Option[String], timeout: FiniteDuration = 30.seconds)
    (test: (Served, ModelClient[IO]) => IO[Unit])
    : Future[Unit] = Server(reply)
    .flatMap(served =>
      ModelClient
        .resource[IO](config.copy(
          baseUrl = Some(served.address),
          timeout = timeout,
        ))
        .map(served -> _),
    )
    .use(test.tupled)
    .timeout(20.seconds)
    .unsafeToFuture()

  /** Waits for the client to close its connection, failing if it does not. */
  private def closed(served: Served): IO[Unit] = IO
    .fromFutureCancelable(IO((served.closed, IO.unit)))
    .timeoutTo(5.seconds, IO(fail("The client left its connection open.")))

  test("a stream dropped early closes its connection, though its reply goes on"):
    serving(Some(begun))((served, client) =>
      client
        .stream(request)
        .take(1)
        .compile
        .toList
        .map(assertEquals(_, List(Delta.Text("[\"a\","))))
        *> closed(served),
    )

  test("a cancelled answer closes its connection, though its reply goes on"):
    serving(Some(begun))((served, client) =>
      client
        .ask[Stream[IO, String]](Chat.userSays("Count."))
        .flatMap(_.cancel) *> closed(served),
    )

  test("a stream cancelled before its reply begins closes its connection"):
    serving(None)((served, client) =>
      client
        .stream(request)
        .compile
        .drain
        .start
        .flatMap(streaming =>
          IO.fromFutureCancelable(IO((served.received, IO.unit))) *>
            // Not waited for, as a backend may wait for the reply to begin.
            streaming.cancel.start,
        ) *> closed(served),
    )

  test("a stream that stalls closes its connection once it times out"):
    serving(Some(begun), timeout = 1.second)((served, client) =>
      client
        .stream(request)
        .attempt
        .compile
        .toList
        .map(assertEquals(
          _,
          List(
            Right(Delta.Text("[\"a\",")),
            Left(ModelError.TimedOut("Anthropic", 1.second)),
          ),
        ))
        *> closed(served),
    )
