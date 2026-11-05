package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.{access, config, model}
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.query.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, Executors}
import munit.FunSuite
import scala.concurrent.duration.*

/** Replies sent by a real server to the JDK's client, some stalling. */
class StallSuite extends FunSuite:

  /**
    * How long each reply is waited for: long enough for a cold JVM's first
    * request while other modules' tests run beside it, which one second was
    * not.
    */
  private val deadline = 3.seconds

  /**
    * Runs `test` against a local server whose every reply is `reply`, sent
    * whole, or where it `stalls`, followed by nothing more until the test is
    * over.
    */
  private def serving
    (reply: String, stalls: Boolean)
    (test: ApiConfig => Unit)
    : Unit =
    val over    = CountDownLatch(1)
    val threads = Executors.newCachedThreadPool()
    val server  = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange =>
        exchange.getRequestBody.readAllBytes()
        exchange.sendResponseHeaders(200, 0)
        exchange.getResponseBody.write(reply.getBytes(UTF_8))
        exchange.getResponseBody.flush()
        if stalls then over.await()
        exchange.close(),
    )
    server.setExecutor(threads)
    server.start()
    try
      test(access.copy(
        baseUrl = Some(s"http://127.0.0.1:${ server.getAddress.getPort }"),
        timeout = deadline,
      ))
    finally
      over.countDown()
      server.stop(0)
      threads.shutdownNow()
      ()

  private def asked(configured: ApiConfig) = model
    .prompt[IO](configured, config)
    .use(_.query.send[String](Conversation.userSays("Hello")).attempt)
    .unsafeRunTimed(30.seconds)

  private val timedOut = ApiError.TimedOut("Anthropic", deadline)

  test("a reply that arrives whole is read as before"):
    serving(
      """{"content":[{"type":"text","text":"\"Hi\""}],"stop_reason":"end_turn",""" +
        """"usage":{"input_tokens":1,"output_tokens":1}}""",
      stalls = false,
    )(configured =>
      assertEquals(
        asked(configured).flatMap(_.toOption).flatMap(_.value),
        Some("Hi"),
      ),
    )

  test("a streamed reply ends with what it cost when it came"):
    serving(
      """data: {"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}
        |
        |data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}
        |
        |""".stripMargin,
      stalls = false,
    )(configured =>
      assertEquals(
        model
          .prompt[IO](configured, config)
          .use(_.stream(Conversation.userSays("Hello")).compile.toList)
          .unsafeRunTimed(30.seconds),
        // Claude Haiku 4.5's $1 and $5 per million tokens.
        Some(List(PromptEvent.Ended(PromptResponse(
          "",
          StopReason.Completed,
          Usage(uncachedTokens = 3, visibleTokens = 2),
          Some(Usd(BigDecimal("0.000013"))),
        )))),
      ),
    )

  test("a reply that stalls once begun times out as configured"):
    serving(
      """{"id":"msg_1","content":[""",
      stalls = true,
    )(configured =>
      assertEquals(
        asked(configured),
        Some(Left(timedOut)),
      ),
    )

  test("a stream that stalls between its parts times out as configured"):
    serving(
      "data: {\"type\":\"content_block_delta\"," +
        "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n",
      stalls = true,
    )(configured =>
      assertEquals(
        model
          .prompt[IO](configured, config)
          .use(_.stream(Conversation.userSays("Hello")).attempt.compile.toList)
          .unsafeRunTimed(30.seconds),
        Some(List(
          Right(PromptEvent.Written(PromptEvent.Part.Text, "Hel")),
          Left(timedOut),
        )),
      ),
    )
