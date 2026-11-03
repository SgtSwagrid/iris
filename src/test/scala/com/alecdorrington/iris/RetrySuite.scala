package com.alecdorrington.iris

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.{config, Sending}
import fs2.Stream
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.Response
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{ResponseStub, StreamBackendStub, StubBody}
import sttp.model.{Header, StatusCode}

/**
  * Requests a provider refuses for now, waited out in virtual time, so that a
  * wait of a minute takes none.
  */
class RetrySuite extends FunSuite:

  private val chat = Chat.userSays("Hello")

  private val anthropic = config.copy(retries = Retries(patience = 10.minutes))

  private val replies = Map(
    ModelProvider.Anthropic ->
      """{"content":[{"type":"text","text":"Hi"}],"stop_reason":"end_turn"}""",
    ModelProvider.OpenAi ->
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
    ModelProvider.Gemini ->
      """{"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""",
  )

  private def ok(body: String): Response[StubBody] = ResponseStub.adjust(body)

  private def refused
    (
      status: Int,
      headers: Seq[Header] = Nil,
      body: String = "slow down",
    )
    : Response[StubBody] =
    ResponseStub.adjust(body, StatusCode(status), headers)

  /** A reply streamed as server-sent events. */
  private def streamed(events: String): Response[StubBody] = ResponseStub
    .adjust(Stream.emits(events.getBytes("UTF-8")).covary[IO])

  private val overloaded =
    """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""

  private val hi =
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

"""

  /**
    * Runs a test in virtual time, against a client of the configuration
    * answering each request with the next of the responses, the last again once
    * they run out, drawing `draw` for each wait, or at random as any client
    * does where none is given; the test is given when each request was sent.
    */
  private def answering
    (
      configured: ModelConfig,
      responses: Response[StubBody]*,
    )
    (draw: Option[Double] = Some(0))
    (
      test: (
        ModelClient[IO],
        IO[List[FiniteDuration]],
      ) => IO[Unit],
    )
    : Future[Unit] = TestControl
    .executeEmbed(
      Ref
        .of[IO, List[FiniteDuration]](Nil)
        .flatMap: sent =>
          val backend = StreamBackendStub[IO, Fs2Streams[IO]](
            CatsMonadAsyncError[IO](),
          ).whenAnyRequest
            .thenRespondF(_ =>
              IO.monotonic
                .flatMap(now =>
                  sent.modify(times =>
                    (
                      times :+ now,
                      responses(times.size.min(responses.size - 1)),
                    ),
                  ),
                ),
            )
          val client = draw.fold(ModelClient[IO](configured, backend))(draw =>
            Retrying(
              ModelClient[IO](
                configured.copy(retries = Retries.none),
                backend,
              ),
              configured.retries,
              IO.pure(draw),
            ),
          )
          test(client, sent.get),
    )
    .unsafeToFuture()

  private def sent(client: ModelClient[IO]): IO[Either[Throwable, String]] =
    Sending(client).sent(chat).map(_.text).attempt

  private def streamedText
    (client: ModelClient[IO])
    : IO[List[Either[Throwable, Delta]]] = client
    .stream(ModelRequest(chat))
    .attempt
    .compile
    .toList

  test("a request refused for now is sent again once the asked wait is over"):
    answering(
      anthropic,
      refused(429, Seq(Header("retry-after", "30"))),
      ok(replies(ModelProvider.Anthropic)),
    )()((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assertEquals(times, List(0.seconds, 30.seconds)),
    )

  /** Each provider's refusal, and the wait it asks for. */
  private val asked = List(
    (
      "anthropic's retry-after",
      ModelProvider.Anthropic,
      refused(429, Seq(Header("retry-after", "7"))),
      7.seconds,
    ),
    // Only the limit that is spent counts, until it is whole again.
    (
      "anthropic's reset of the limit spent",
      ModelProvider.Anthropic,
      refused(
        429,
        Seq(
          Header(
            "anthropic-ratelimit-requests-remaining",
            "0",
          ),
          Header(
            "anthropic-ratelimit-requests-reset",
            "1970-01-01T00:00:20Z",
          ),
          Header(
            "anthropic-ratelimit-tokens-remaining",
            "5000",
          ),
          Header(
            "anthropic-ratelimit-tokens-reset",
            "1970-01-01T00:00:50Z",
          ),
        ),
      ),
      20.seconds,
    ),
    (
      "anthropic's reset given in another time zone",
      ModelProvider.Anthropic,
      refused(
        429,
        Seq(
          Header(
            "Anthropic-RateLimit-Output-Tokens-Remaining",
            "0",
          ),
          Header(
            "anthropic-ratelimit-output-tokens-reset",
            "1970-01-01T01:00:12.5+01:00",
          ),
        ),
      ),
      12.5.seconds,
    ),
    (
      "anthropic's overloading, with no wait asked for",
      ModelProvider.Anthropic,
      refused(529, body = overloaded),
      1.second,
    ),
    // Milliseconds first, as OpenAI's own SDKs read them.
    (
      "openai's retry-after-ms",
      ModelProvider.OpenAi,
      refused(
        429,
        Seq(
          Header("retry-after-ms", "1500"),
          Header("retry-after", "2"),
        ),
      ),
      1.5.seconds,
    ),
    (
      "openai's retry-after",
      ModelProvider.OpenAi,
      refused(429, Seq(Header("retry-after", "3"))),
      3.seconds,
    ),
    (
      "openai's reset of the limit spent",
      ModelProvider.OpenAi,
      refused(
        429,
        Seq(
          Header("x-ratelimit-remaining-requests", "59"),
          Header("x-ratelimit-reset-requests", "1s"),
          Header("x-ratelimit-remaining-tokens", "0"),
          Header("x-ratelimit-reset-tokens", "1m30s"),
        ),
      ),
      90.seconds,
    ),
    (
      "openai's overloading",
      ModelProvider.OpenAi,
      refused(
        503,
        body =
          """{"error":{"type":"service_unavailable_error","code":"server_is_overloaded"}}""",
      ),
      1.second,
    ),
    (
      "gemini's retry delay",
      ModelProvider.Gemini,
      refused(
        429,
        body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
          {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[]},
          {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}""",
      ),
      34.seconds,
    ),
    (
      "gemini's retry delay of a fraction of a second",
      ModelProvider.Gemini,
      refused(
        429,
        body = """{"error":{"code":429,"details":[
          {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"0.5s"}]}}""",
      ),
      0.5.seconds,
    ),
    (
      "gemini's overloading",
      ModelProvider.Gemini,
      refused(
        503,
        body = """{"error":{"code":503,"status":"UNAVAILABLE"}}""",
      ),
      1.second,
    ),
  )

  asked.foreach: (row, provider, refusal, wait) =>
    test(s"a refusal is waited out as long as asked: $row"):
      answering(
        anthropic.copy(model = provider.defaultModel),
        refusal,
        ok(replies(provider)),
      )()((client, times) =>
        for
          result <- sent(client)
          times  <- times
        yield
          assertEquals(result, Right("Hi"))
          assertEquals(times, List(Duration.Zero, wait)),
      )

  test(
    "with no wait asked for, each is twice the last, until the attempts end",
  ):
    answering(config, refused(429))()((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        assertEquals(
          result,
          Left(ModelError.RateLimited("Anthropic", None, "slow down")),
        )
        assertEquals(times, List(0, 1, 3, 7).map(_.seconds)),
    )

  test("a wait is drawn a little longer than asked, or shorter than doubled"):
    val limited = ModelError.RateLimited("Anthropic", Some(30.seconds), "")
    val unasked = ModelError.Overloaded("Anthropic", None, "")
    val retries = Retries()
    val waited  = Duration.Zero
    val drawn   = (error: Throwable, tried: Int, draw: Double) =>
      retries.delay(error, tried, waited, draw)
    assertEquals(drawn(limited, 1, 0), Some(30.seconds))
    assertEquals(
      drawn(limited, 1, 0.5),
      Some(30.5.seconds),
    )
    assertEquals(drawn(unasked, 1, 0), Some(1.second))
    assertEquals(drawn(unasked, 1, 1), Some(0.5.seconds))
    assertEquals(drawn(unasked, 3, 0.5), Some(3.seconds))

  test("a client over a streaming backend waits out refusals, drawing waits"):
    answering(
      config,
      refused(429, Seq(Header("retry-after", "2"))),
      ok(replies(ModelProvider.Anthropic)),
    )(draw = None)((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assert(
          times.last >= 2.seconds && times.last <= 3.seconds,
          times,
        ),
    )

  test("a client told not to retry gives each refusal at once"):
    answering(
      config.copy(retries = Retries.none),
      refused(429, Seq(Header("retry-after", "2"))),
    )(draw = None)((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        assertEquals(
          result,
          Left(ModelError.RateLimited(
            "Anthropic",
            Some(2.seconds),
            "slow down",
          )),
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("a wait asked for longer than the patience is not waited"):
    answering(
      config,
      refused(429, Seq(Header("retry-after", "90"))),
    )()((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        // The wait asked for, which a host may tell its user.
        assertEquals(
          result,
          Left(ModelError.RateLimited(
            "Anthropic",
            Some(90.seconds),
            "slow down",
          )),
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("waiting stops where the next wait would pass the patience"):
    answering(
      config.copy(retries = Retries(attempts = 10)),
      refused(429, Seq(Header("retry-after", "25"))),
    )()((client, times) =>
      for
        result <- sent(client)
        times  <- times
      yield
        assert(result.isLeft, result)
        // A third wait would end at 75 s, past the minute.
        assertEquals(times, List(0, 25, 50).map(_.seconds)),
    )

  /** Refusals that waiting does not lift, given at once. */
  private val lasting = List(
    ("a refused key", ModelProvider.Anthropic, refused(401)),
    ("a forbidden key", ModelProvider.OpenAi, refused(403)),
    ("a malformed request", ModelProvider.Gemini, refused(400)),
    ("a failure of the provider's", ModelProvider.Anthropic, refused(500)),
    (
      "anthropic's spend cap",
      ModelProvider.Anthropic,
      refused(
        429,
        body =
          """{"type":"error","error":{"type":"rate_limit_error","message":"You have reached your API usage limits",
            "details":{"error_code":"enforced_spend_limit_reached"}}}""",
      ),
    ),
    (
      "openai's spent quota",
      ModelProvider.OpenAi,
      refused(
        429,
        Seq(Header("retry-after", "1")),
        """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
      ),
    ),
    (
      "openai's spent credit",
      ModelProvider.OpenAi,
      refused(
        429,
        body =
          """{"error":{"type":"invalid_request_error","code":"credit_balance_exhausted"}}""",
      ),
    ),
    (
      "a refusal the provider says not to retry",
      ModelProvider.Anthropic,
      refused(
        529,
        Seq(Header("x-should-retry", "false")),
      ),
    ),
  )

  lasting.foreach: (row, provider, refusal) =>
    test(s"a refusal that waiting cannot lift is given at once: $row"):
      answering(
        anthropic.copy(model = provider.defaultModel),
        refusal,
      )()((client, times) =>
        for
          result <- sent(client)
          times  <- times
        yield
          assert(
            result.left.exists(_.isInstanceOf[ModelError.Unsuccessful]),
            result,
          )
          assertEquals(times, List(Duration.Zero)),
      )

  test("a reply cut off at the token limit is never asked for again"):
    answering(
      config,
      ok(
        """{"content":[{"type":"text","text":"[1, 2"}],"stop_reason":"max_tokens"}""",
      ),
    )()((client, times) =>
      for
        result <- client.ask[List[Int]](chat).attempt
        times  <- times
      yield
        assert(
          result.left.exists(_.isInstanceOf[ModelError.UnreadableResponse]),
          result,
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("counting is sent again as asking is"):
    answering(
      anthropic,
      refused(429, Seq(Header("retry-after", "5"))),
      ok("""{"input_tokens":42}"""),
    )()((client, times) =>
      for
        counted <- client.countTokens(ModelRequest(chat))
        times   <- times
      yield
        assertEquals(counted, 42)
        assertEquals(times, List(0, 5).map(_.seconds)),
    )

  test("warming is sent again as asking is"):
    answering(
      anthropic,
      refused(529),
      ok(replies(ModelProvider.Anthropic)),
    )()((client, times) =>
      for
        warmed <- client.warmCache(ModelRequest(
          Chat.userSays("Hello", MessagePart.CacheBreakpoint),
        ))
        times <- times
      yield
        assertEquals(warmed.text, "Hi")
        assertEquals(times, List(0, 1).map(_.seconds)),
    )

  test("a request waiting out a refusal is cancelled at once, sent no more"):
    answering(
      anthropic,
      refused(429, Seq(Header("retry-after", "30"))),
    )()((client, times) =>
      for
        sending   <- sent(client).start
        streaming <- streamedText(client).start
        _         <- IO.sleep(1.second)
        _         <- sending.cancel
        _         <- streaming.cancel
        sentOut   <- sending.join
        streamOut <- streaming.join
        now       <- IO.monotonic
        times     <- times
      yield
        assert(sentOut.isCanceled && streamOut.isCanceled)
        assertEquals(now, 1.second)
        assertEquals(
          times,
          List(Duration.Zero, Duration.Zero),
        ),
    )

  test("a stream refused before its reply begins is streamed again"):
    answering(
      anthropic,
      refused(429, Seq(Header("retry-after", "4"))),
      streamed(
        s"data: {\"type\":\"message_start\",\"message\":{}}\n\ndata: $overloaded\n\n",
      ),
      streamed(hi),
    )()((client, times) =>
      for
        deltas <- streamedText(client)
        times  <- times
      yield
        assertEquals(
          deltas.map(_.map {
            case end: Delta.End => end.stopReason
            case delta          => delta
          }),
          List(
            Right(Delta.Text("Hi")),
            Right(StopReason.Completed),
          ),
        )
        // Refused by its status, then by an event before any of its text,
        // after which the wait named none, so is backed off, the second time.
        assertEquals(times, List(0, 4, 6).map(_.seconds)),
    )

  test("a stream refused once its reply has begun fails, never streamed again"):
    val begun =
      """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

"""
    answering(
      anthropic,
      streamed(s"${ begun }data: $overloaded\n\n"),
    )()((client, times) =>
      for
        deltas <- streamedText(client)
        times  <- times
      yield
        assertEquals(
          deltas,
          List(
            Right(Delta.Text("Hel")),
            Left(ModelError.Overloaded("Anthropic", None, overloaded)),
          ),
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("a provider's wait is read as it writes it"):
    val now = 1_000.seconds
    assertEquals(
      Refusal.seconds(" 30 "),
      Some(30.seconds),
    )
    assertEquals(
      Refusal.seconds("Wed, 21 Oct 2026 07:28:00 GMT"),
      None,
    )
    assertEquals(Refusal.seconds("-1"), None)
    assertEquals(
      Refusal.milliseconds("1500"),
      Some(1.5.seconds),
    )
    assertEquals(
      Refusal.until(now)("1970-01-01T00:16:50Z"),
      Some(10.seconds),
    )
    assertEquals(
      Refusal.until(now)("1969-12-31T23:16:50.25-01:00"),
      Some(10.25.seconds),
    )
    assertEquals(
      Refusal.until(now)("1970-01-01T00:16:30Z"),
      None,
    )
    assertEquals(Refusal.until(now)("soon"), None)
    assertEquals(Refusal.go("6m0s"), Some(6.minutes))
    assertEquals(
      Refusal.go("1h2m3.5s"),
      Some(3_723.5.seconds),
    )
    assertEquals(Refusal.go("20ms"), Some(20.millis))
    assertEquals(Refusal.go("6 minutes"), None)
    assertEquals(
      Refusal.protobuf("34s"),
      Some(34.seconds),
    )
    assertEquals(
      Refusal.protobuf("1.5s"),
      Some(1.5.seconds),
    )
    assertEquals(Refusal.protobuf("34"), None)
