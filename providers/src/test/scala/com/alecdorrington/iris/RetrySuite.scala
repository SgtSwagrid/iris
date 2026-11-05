package com.alecdorrington.iris

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.{access, config}
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.query.*
import fs2.Stream
import io.circe.Json
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

  private val conversation = Conversation.userSays("Hello")

  private val patient = access.copy(patience = 10.minutes)

  private val replies = Map(
    Anthropic ->
      """{"content":[{"type":"text","text":"Hi"}],"stop_reason":"end_turn"}""",
    OpenAi ->
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
    Gemini ->
      """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}]}""",
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

  /**
    * Gemini's refusal of a quota spent, as its body gives it, naming the quota
    * and how long to wait.
    */
  private def gemini429(quota: String): String =
    s"""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
      {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
        {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
         "quotaId":"$quota","quotaValue":"50"}]},
      {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}"""

  /** A provider's reply, given whole, streamed as it would have been. */
  private def replied(provider: Provider, body: String): Response[StubBody] =
    streamed(Fixtures.streamed(provider, body))

  /** A reply streamed as server-sent events. */
  private def streamed(events: String): Response[StubBody] = ResponseStub
    .adjust(Stream.emits(events.getBytes("UTF-8")).covary[IO])

  private val overloaded =
    """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""

  private val hi =
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

"""

  /** A model's API, and its prompt service over it. */
  private final case class Services
    (
      model: ProviderModel,
      api: ApiService[IO],
    ):

    val prompt: PromptService[IO] = model.prompt(api, config)

  /**
    * Runs a test in virtual time, against a model's API, of the configuration,
    * answering each request with the next of the responses, the last again once
    * they run out, drawing `draw` for each wait, or at random as any service
    * does where none is given; the test is given when each request was sent.
    */
  private def answering
    (
      configured: ApiConfig,
      responses: Response[StubBody]*,
    )
    (
      draw: Option[Double] = Some(0),
      model: ProviderModel = Fixtures.model,
    )
    (test: (Services, IO[List[FiniteDuration]]) => IO[Unit])
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
          val api = draw.fold(model.provider.api(configured, backend))(draw =>
            RetryingApiService(
              model.provider.api(configured.copy(maxRetries = 0), backend),
              configured,
              IO.pure(draw),
            ),
          )
          test(Services(model, api), sent.get),
    )
    .unsafeToFuture()

  private def sent(services: Services): IO[Either[Throwable, String]] = services
    .prompt
    .send(conversation)
    .map(_.text)
    .attempt

  private def streamedText
    (services: Services)
    : IO[List[Either[Throwable, PromptEvent]]] = services
    .prompt
    .stream(conversation)
    .attempt
    .compile
    .toList

  test("a request refused for now is sent again once the asked wait is over"):
    answering(
      patient,
      refused(429, Seq(Header("retry-after", "30"))),
      replied(Anthropic, replies(Anthropic)),
    )()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assertEquals(times, List(0.seconds, 30.seconds)),
    )

  /** Each provider's refusal, and the wait it asks for. */
  private val asked = List(
    (
      "anthropic's retry-after",
      Anthropic,
      refused(429, Seq(Header("retry-after", "7"))),
      7.seconds,
    ),
    // Only the limit that is spent counts, until it is whole again.
    (
      "anthropic's reset of the limit spent",
      Anthropic,
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
      Anthropic,
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
      Anthropic,
      refused(529, body = overloaded),
      1.second,
    ),
    // Milliseconds first, as OpenAI's own SDKs read them.
    (
      "openai's retry-after-ms",
      OpenAi,
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
      OpenAi,
      refused(429, Seq(Header("retry-after", "3"))),
      3.seconds,
    ),
    (
      "openai's reset of the limit spent",
      OpenAi,
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
      OpenAi,
      refused(
        503,
        body =
          """{"error":{"type":"service_unavailable_error","code":"server_is_overloaded"}}""",
      ),
      1.second,
    ),
    // A minute's quota spent is waited out, as Gemini asks.
    (
      "gemini's retry delay",
      Gemini,
      refused(
        429,
        body = gemini429("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"),
      ),
      34.seconds,
    ),
    (
      "gemini's retry delay of a fraction of a second",
      Gemini,
      refused(
        429,
        body = """{"error":{"code":429,"details":[
          {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"0.5s"}]}}""",
      ),
      0.5.seconds,
    ),
    (
      "gemini's overloading",
      Gemini,
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
        patient,
        refusal,
        replied(provider, replies(provider)),
      )(model = provider.defaultModel)((services, times) =>
        for
          result <- sent(services)
          times  <- times
        yield
          assertEquals(result, Right("Hi"))
          assertEquals(times, List(Duration.Zero, wait)),
      )

  test(
    "with no wait asked for, each is twice the last, until the attempts end",
  ):
    answering(access, refused(429))()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(
          result,
          Left(ApiError.RateLimited("Anthropic", None, "slow down")),
        )
        assertEquals(times, List(0, 1, 3, 7).map(_.seconds)),
    )

  test("a wait is drawn a little longer than asked, or shorter than doubled"):
    val limited = ApiError.RateLimited("Anthropic", Some(30.seconds), "")
    val unasked = ApiError.Overloaded("Anthropic", None, "")
    val retries = ApiConfig()
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

  test("a service over a streaming backend waits out refusals, drawing waits"):
    answering(
      access,
      refused(429, Seq(Header("retry-after", "2"))),
      replied(Anthropic, replies(Anthropic)),
    )(draw = None)((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assert(
          times.last >= 2.seconds && times.last <= 3.seconds,
          times,
        ),
    )

  test("a service told not to retry gives each refusal at once"):
    answering(
      access.copy(maxRetries = 0),
      refused(429, Seq(Header("retry-after", "2"))),
    )(draw = None)((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(
          result,
          Left(ApiError.RateLimited(
            "Anthropic",
            Some(2.seconds),
            "slow down",
          )),
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("a wait asked for longer than the patience is not waited"):
    answering(
      access,
      refused(429, Seq(Header("retry-after", "90"))),
    )()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        // The wait asked for, which a host may tell its user.
        assertEquals(
          result,
          Left(ApiError.RateLimited(
            "Anthropic",
            Some(90.seconds),
            "slow down",
          )),
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("waiting stops where the next wait would pass the patience"):
    answering(
      access.copy(maxRetries = 9),
      refused(429, Seq(Header("retry-after", "25"))),
    )()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assert(result.isLeft, result)
        // A third wait would end at 75 s, past the minute.
        assertEquals(times, List(0, 25, 50).map(_.seconds)),
    )

  /** Refusals that waiting does not lift, given at once. */
  private val lasting = List(
    ("a refused key", Anthropic, refused(401)),
    ("a forbidden key", OpenAi, refused(403)),
    ("a malformed request", Gemini, refused(400)),
    ("a request too large", Anthropic, refused(413)),
    (
      "gemini's day's quota spent",
      Gemini,
      refused(
        429,
        body = gemini429("GenerateRequestsPerDayPerProjectPerModel-FreeTier"),
      ),
    ),
    (
      "a failure the provider says not to retry",
      OpenAi,
      refused(
        500,
        Seq(Header("x-should-retry", "false")),
      ),
    ),
    (
      "anthropic's spend cap",
      Anthropic,
      refused(
        429,
        body =
          """{"type":"error","error":{"type":"rate_limit_error","message":"You have reached your API usage limits",
            "details":{"error_code":"enforced_spend_limit_reached"}}}""",
      ),
    ),
    (
      "openai's spent quota",
      OpenAi,
      refused(
        429,
        Seq(Header("retry-after", "1")),
        """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
      ),
    ),
    (
      "openai's spent credit",
      OpenAi,
      refused(
        429,
        body =
          """{"error":{"type":"invalid_request_error","code":"credit_balance_exhausted"}}""",
      ),
    ),
    (
      "a refusal the provider says not to retry",
      Anthropic,
      refused(
        529,
        Seq(Header("x-should-retry", "false")),
      ),
    ),
  )

  /** Failures for now that the providers' SDKs retry, and each's error. */
  private val transient = List(
    (408, Anthropic, None),
    (409, Anthropic, None),
    (500, OpenAi, None),
    (502, Gemini, None),
    (504, Anthropic, Some(Header("retry-after", "3"))),
  )

  transient.foreach: (status, provider, header) =>
    test(s"a failure for now is sent again, as the SDKs send it: $status"):
      answering(
        patient,
        refused(status, header.toList),
        replied(provider, replies(provider)),
      )(model = provider.defaultModel)((services, times) =>
        for
          result <- sent(services)
          times  <- times
        yield
          assertEquals(result, Right("Hi"))
          assertEquals(
            times,
            List(
              Duration.Zero,
              header.fold(1.second)(_ => 3.seconds),
            ),
          ),
      )

  test("a failure for now that goes on is given once the attempts end"):
    answering(access, refused(502))()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(
          result,
          Left(ApiError.Transient(
            "Anthropic",
            StatusCode.BadGateway,
            None,
            "slow down",
          )),
        )
        assertEquals(times, List(0, 1, 3, 7).map(_.seconds)),
    )

  test("any failure the provider says to retry is sent again"):
    answering(
      patient,
      refused(
        400,
        Seq(Header("x-should-retry", "true")),
        "try again",
      ),
      replied(Anthropic, replies(Anthropic)),
    )()((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assertEquals(times, List(0, 1).map(_.seconds)),
    )

  test("the provider's word to retry outweighs what its body says"):
    // A day's quota spent, though the provider says to retry it.
    answering(
      patient,
      refused(
        429,
        Seq(Header("x-should-retry", "true")),
        gemini429("GenerateRequestsPerDayPerProjectPerModel-FreeTier"),
      ),
      replied(Gemini, replies(Gemini)),
    )(model = Gemini.defaultModel)((services, times) =>
      for
        result <- sent(services)
        times  <- times
      yield
        assertEquals(result, Right("Hi"))
        assertEquals(times, List(0, 34).map(_.seconds)),
    )

  test("a failure streamed before the reply begins is streamed again"):
    val failed =
      """{"type":"error","error":{"type":"api_error","message":"Internal"}}"""
    answering(
      patient,
      streamed(s"data: $failed\n\n"),
      streamed(hi),
    )()((services, times) =>
      for
        events <- streamedText(services)
        times  <- times
      yield
        assertEquals(
          events.headOption,
          Some(Right(PromptEvent.Written(PromptEvent.Part.Text, "Hi"))),
        )
        assertEquals(times, List(0, 1).map(_.seconds)),
    )

  lasting.foreach: (row, provider, refusal) =>
    test(s"a refusal that waiting cannot lift is given at once: $row"):
      answering(patient, refusal)(model = provider.defaultModel)(
        (services, times) =>
          for
            result <- sent(services)
            times  <- times
          yield
            assert(
              result.left.exists(_.isInstanceOf[ApiError.Unsuccessful]),
              result,
            )
            assertEquals(times, List(Duration.Zero)),
      )

  test("a reply cut off at the token limit is never asked for again"):
    answering(
      access,
      replied(
        Anthropic,
        """{"content":[{"type":"text","text":"[1, 2"}],"stop_reason":"max_tokens"}""",
      ),
    )()((services, times) =>
      for
        result <- services.prompt.query.send[List[Int]](conversation).attempt
        times  <- times
      yield
        assert(
          result.left.exists(_.isInstanceOf[QueryError.Unreadable]),
          result,
        )
        assertEquals(times, List(Duration.Zero)),
    )

  test("counting is sent again as asking is"):
    answering(
      patient,
      refused(429, Seq(Header("retry-after", "5"))),
      ok("""{"input_tokens":42}"""),
    )()((services, times) =>
      for
        counted <-
          services.model.counter(services.api).get.countTokens(conversation)
        times <- times
      yield
        assertEquals(counted, 42)
        assertEquals(times, List(0, 5).map(_.seconds)),
    )

  test("warming is sent again as asking is"):
    answering(
      patient,
      refused(529),
      replied(Anthropic, replies(Anthropic)),
    )()((services, times) =>
      for
        warmed <- services
          .prompt
          .warmCache(
            Conversation.userSays("Hello", MessagePart.CacheBreakpoint),
          )
        times <- times
      yield
        assertEquals(warmed.text, "Hi")
        assertEquals(times, List(0, 1).map(_.seconds)),
    )

  test("a request waiting out a refusal is cancelled at once, sent no more"):
    answering(
      patient,
      refused(429, Seq(Header("retry-after", "30"))),
    )()((services, times) =>
      for
        sending   <- sent(services).start
        streaming <- streamedText(services).start
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
      patient,
      refused(429, Seq(Header("retry-after", "4"))),
      streamed(
        s"data: {\"type\":\"message_start\",\"message\":{}}\n\ndata: $overloaded\n\n",
      ),
      streamed(hi),
    )()((services, times) =>
      for
        events <- streamedText(services)
        times  <- times
      yield
        assertEquals(
          events.map(_.map {
            case PromptEvent.Ended(response) => response.stopReason
            case event                       => event
          }),
          List(
            Right(PromptEvent.Written(PromptEvent.Part.Text, "Hi")),
            Right(StopReason.Completed),
          ),
        )
        // Refused by its status, then by an event before any of its text, after
        // which the wait named none, so is backed off, the second time.
        assertEquals(times, List(0, 4, 6).map(_.seconds)),
    )

  test(
    "a streamed refusal fails the api's stream, as a refusal by status does",
  ):
    answering(
      access.copy(maxRetries = 0),
      streamed(s"data: $overloaded\n\n"),
    )()((services, _) =>
      services
        .api
        .stream(ApiRequest(
          List("v1", "messages"),
          Json.obj(),
          streamed = true,
        ))
        .attempt
        .compile
        .toList
        .map(assertEquals(
          _,
          List(Left(ApiError.Overloaded("Anthropic", None, overloaded))),
        )),
    )

  test("a stream refused once its reply has begun fails, never streamed again"):
    val begun =
      """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

"""
    answering(
      patient,
      streamed(s"${ begun }data: $overloaded\n\n"),
    )()((services, times) =>
      for
        events <- streamedText(services)
        times  <- times
      yield
        assertEquals(
          events,
          List(
            Right(PromptEvent.Written(PromptEvent.Part.Text, "Hel")),
            Left(ApiError.Overloaded("Anthropic", None, overloaded)),
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
