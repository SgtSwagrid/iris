package com.alecdorrington.iris

import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.config
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import sttp.client4.SttpClientException
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{Header, StatusCode}

class ClientSuite extends FunSuite:

  private val gemini = config.copy(model = LlmModel.Gemini2_5Flash)

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

  private def result(client: LlmClient[SyncIO]): Either[Throwable, Reply] =
    client.ask[Reply](prompt).attempt.unsafeRunSync()

  private def counted(client: LlmClient[SyncIO]): Either[Throwable, Int] =
    client.count(prompt).attempt.unsafeRunSync()

  test("a successful response becomes a reply"):
    val body = """{"content":[{"type":"text","text":"Hi"}],
         "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}"""
    assertEquals(
      result(client(body)),
      Right(Reply(
        "Hi",
        StopReason.Completed,
        Some(Usage(3, 1)),
      )),
    )

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

  test("an unsuccessful response is an error carrying its status"):
    val failure = result(client(
      "rate limited",
      StatusCode.TooManyRequests,
    ))
    assertEquals(
      failure,
      Left(LlmError.Unsuccessful(
        "Anthropic",
        StatusCode.TooManyRequests,
        None,
        "rate limited",
      )),
    )

  test("a provider's request to wait reaches the error it belongs to"):
    val failure = result(client(
      "slow down",
      StatusCode.TooManyRequests,
      Seq(Header("Retry-After", "30")),
    ))
    assertEquals(
      failure
        .left
        .toOption
        .collect:
          case LlmError.Unsuccessful(_, _, retryAfter, _) => retryAfter
      ,
      Some(Some(30.seconds)),
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

  test("a chat with nothing in it never reaches the provider"):
    val empty = client("{}").ask[Reply](Chat()).attempt.unsafeRunSync()
    assert(
      empty
        .left
        .exists:
          case LlmError.Unsendable("Anthropic", _) => true
          case _                                   => false,
    )

  test("anthropic counts a chat before it is sent"):
    assertEquals(
      counted(client("""{"input_tokens":42}""")),
      Right(42),
    )

  test("gemini counts a chat before it is sent"):
    assertEquals(
      counted(client(
        """{"totalTokens":17}""",
        config = gemini,
      )),
      Right(17),
    )

  test("openai says it cannot count rather than guessing"):
    val openAi = config.copy(model = LlmModel.Gpt5)
    assertEquals(
      counted(client("{}", config = openAi)),
      Left(LlmError.Unsupported("OpenAI", "counting tokens")),
    )

  test("each provider is sent its key where it looks for it"):
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

  test("each provider is given its own adapter"):
    val body = """{"candidates":[{"content":{"parts":[{"text":"Hei"}]},
         "finishReason":"STOP"}]}"""
    assertEquals(
      result(client(body, config = gemini)).map(_.text),
      Right("Hei"),
    )
