package com.alecdorrington.iris

import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.{config, Sending}
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import sttp.client4.SttpClientException
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{Header, StatusCode}

class ClientSuite extends FunSuite:

  private val prompt = Chat.of("Hello")

  private def client
    (
      body: String,
      status: StatusCode = StatusCode.Ok,
      headers: Seq[Header] = Seq.empty,
      config: LlmConfig = config,
    )
    : LlmClient[SyncIO] = LlmClient[SyncIO](
    config,
    BackendStub(CatsMonadError[SyncIO]())
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust(body, status, headers)),
  )

  private def result
    (client: LlmClient[SyncIO])
    : Either[Throwable, Answer[Nothing]] = Sending(client)
    .sent(prompt)
    .attempt
    .unsafeRunSync()

  test("a request no answer comes to in time is an error saying so"):
    val slow = LlmClient[SyncIO](
      config,
      BackendStub(CatsMonadError[SyncIO]())
        .whenAnyRequest
        .thenRespondF(request =>
          SyncIO.raiseError(SttpClientException.TimeoutException(
            request,
            Exception("read timed out"),
          )),
        ),
    )
    assertEquals(
      result(slow),
      Left(LlmError.TimedOut("Anthropic", config.timeout)),
    )

  test("a client over a backend that cannot stream says so when streaming"):
    assertEquals(
      client("{}").stream(prompt).compile.toList.attempt.unsafeRunSync(),
      Left(LlmError.Unsupported(
        "Anthropic",
        "streaming, through a backend that cannot stream",
      )),
    )

  test("an unsuccessful response is an error carrying its status and delay"):
    List(
      Seq(Header("Retry-After", "30")) -> Some(30.seconds),
      Seq.empty                        -> None,
    ).foreach: (headers, delay) =>
      assertEquals(
        result(client(
          "slow down",
          StatusCode.TooManyRequests,
          headers,
        )),
        Left(LlmError.Unsuccessful(
          "Anthropic",
          StatusCode.TooManyRequests,
          delay,
          "slow down",
        )),
        headers,
      )

  test("a response which is not json at all is malformed"):
    assert(
      result(client("<html>down for maintenance</html>"))
        .left
        .exists:
          case LlmError.Malformed("Anthropic", _) => true
          case _                                  => false,
    )

  test("a base url which is not a url fails the effect, never the call"):
    val broken = config.copy(baseUrl = Some("https://proxy.test/%"))
    assert(
      result(client("{}", config = broken))
        .left
        .exists:
          case LlmError.Misconfigured("Anthropic", _) => true
          case _                                      => false,
    )

  test("a chat is counted before it is sent, where its provider can count"):
    List(
      (LlmModel.ClaudeHaiku4_5, """{"input_tokens":42}""", Right(42)),
      (LlmModel.Gemini2_5Flash, """{"totalTokens":17}""", Right(17)),
      // OpenAI says it cannot count rather than guessing.
      (
        LlmModel.Gpt5,
        "{}",
        Left(LlmError.Unsupported("OpenAI", "counting tokens")),
      ),
    ).foreach: (model, body, count) =>
      assertEquals(
        client(body, config = config.copy(model = model))
          .count(prompt)
          .attempt
          .unsafeRunSync(),
        count,
        model,
      )

  test("each provider serves its default model, and is sent its key"):
    LlmProvider
      .values
      .foreach(provider =>
        assertEquals(
          provider.defaultModel.provider,
          provider,
        ),
      )
    List(
      (
        LlmProvider.Anthropic,
        "x-api-key",
        "key",
        """{"content":[],"stop_reason":"end_turn"}""",
      ),
      (
        LlmProvider.OpenAi,
        "Authorization",
        "Bearer key",
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
      ),
      (
        LlmProvider.Gemini,
        "x-goog-api-key",
        "key",
        """{"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""",
      ),
    ).foreach: (provider, name, key, body) =>
      val keyed = LlmClient[SyncIO](
        config.copy(model = provider.defaultModel),
        BackendStub(CatsMonadError[SyncIO]())
          .whenRequestMatches(_.header(name).contains(key))
          .thenRespond(ResponseStub.adjust(body, StatusCode.Ok))
          .whenAnyRequest
          .thenRespond(ResponseStub.adjust("unkeyed", StatusCode.Unauthorized)),
      )
      assert(result(keyed).isRight, provider)
