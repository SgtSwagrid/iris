package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.config
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, Executors}
import munit.FunSuite
import scala.concurrent.duration.*

/** Replies that stall once begun, sent by a real server to the JDK's client. */
class StallSuite extends FunSuite:

  /**
    * Runs `test` against a local server whose every reply begins with `begun`
    * and then sends nothing more until the test is over.
    */
  private def stalling(begun: String)(test: LlmConfig => Unit): Unit =
    val over    = CountDownLatch(1)
    val threads = Executors.newCachedThreadPool()
    val server  = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        exchange.getRequestBody.readAllBytes()
        exchange.sendResponseHeaders(200, 0)
        exchange.getResponseBody.write(begun.getBytes(UTF_8))
        exchange.getResponseBody.flush()
        over.await()
        exchange.close()
      },
    )
    server.setExecutor(threads)
    server.start()
    try
      test(config.copy(
        baseUrl = Some(s"http://127.0.0.1:${ server.getAddress.getPort }"),
        timeout = 1.second,
      ))
    finally
      over.countDown()
      server.stop(0)
      threads.shutdownNow()
      ()

  private val timedOut = LlmError.TimedOut("Anthropic", 1.second)

  test("a reply that stalls once begun times out as configured"):
    stalling("""{"id":"msg_1","content":[""")(configured =>
      assertEquals(
        LlmClient
          .resource[IO](configured)
          .use(_.ask[String](Chat.of("Hello")).attempt)
          .unsafeRunTimed(30.seconds),
        Some(Left(timedOut)),
      ),
    )

  test("a stream that stalls between its parts times out as configured"):
    stalling(
      "data: {\"type\":\"content_block_delta\"," +
        "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n",
    )(configured =>
      assertEquals(
        LlmStreamer
          .resource[IO](configured)
          .use(_.stream(Chat.of("Hello")).attempt.compile.toList)
          .unsafeRunTimed(30.seconds),
        Some(List(Right(Delta.Text("Hel")), Left(timedOut))),
      ),
    )
