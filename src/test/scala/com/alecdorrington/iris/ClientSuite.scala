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

  private val prompt = Chat.userSays("Hello")

  private def client
    (
      body: String,
      status: StatusCode = StatusCode.Ok,
      headers: Seq[Header] = Seq.empty,
      config: ModelConfig = config,
    )
    : ModelClient[SyncIO] = ModelClient[SyncIO](
    config,
    BackendStub(CatsMonadError[SyncIO]())
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust(body, status, headers)),
  )

  private def result
    (client: ModelClient[SyncIO])
    : Either[Throwable, ModelResponse] = Sending(client)
    .sent(prompt)
    .attempt
    .unsafeRunSync()

  test("a request no answer comes to in time is an error saying so"):
    val slow = ModelClient[SyncIO](
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
      Left(ModelError.TimedOut("Anthropic", config.timeout)),
    )

  test("a client over a backend that cannot stream streams its reply whole"):
    assertEquals(
      client("""{"content":[{"type":"text","text":"Hi"},{"type":"tool_use",
          "id":"call_1","name":"look","input":{}}],"stop_reason":"tool_use"}""")
        .stream(ModelRequest(prompt))
        .compile
        .toList
        .unsafeRunSync(),
      List(
        Delta.Text("Hi"),
        Delta.ToolCall(
          MessagePart.ToolCall("call_1", "look", io.circe.Json.obj()),
        ),
        // Priced as it came, at Claude Haiku 4.5's rates, though it reports none.
        Delta.End(
          StopReason.ToolCall,
          Usage(),
          Some(Usd(BigDecimal(0))),
        ),
      ),
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
        Left(ModelError.Unsuccessful(
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
          case ModelError.Malformed("Anthropic", _) => true
          case _                                    => false,
    )

  test("a base url which is not a url fails the effect, never the call"):
    val broken = config.copy(baseUrl = Some("https://proxy.test/%"))
    assert(
      result(client("{}", config = broken))
        .left
        .exists:
          case ModelError.Misconfigured("Anthropic", _) => true
          case _                                        => false,
    )

  test("a chat is counted before it is sent, where its provider can count"):
    List(
      (Model.ClaudeHaiku4_5, """{"input_tokens":42}""", Right(42)),
      (Model.Gemini2_5Flash, """{"totalTokens":17}""", Right(17)),
      // OpenAI says it cannot count rather than guessing.
      (
        Model.Gpt5,
        "{}",
        Left(ModelError.Unsupported("OpenAI", "counting tokens")),
      ),
    ).foreach: (model, body, count) =>
      assertEquals(
        client(body, config = config.copy(model = model))
          .countTokens(ModelRequest(prompt))
          .attempt
          .unsafeRunSync(),
        count,
        model,
      )

  test("each provider serves its default model, and is sent its key"):
    ModelProvider
      .values
      .foreach(provider =>
        assertEquals(
          provider.defaultModel.provider,
          provider,
        ),
      )
    List(
      (
        ModelProvider.Anthropic,
        "x-api-key",
        "key",
        """{"content":[],"stop_reason":"end_turn"}""",
      ),
      (
        ModelProvider.OpenAi,
        "Authorization",
        "Bearer key",
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
      ),
      (
        ModelProvider.Gemini,
        "x-goog-api-key",
        "key",
        """{"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""",
      ),
    ).foreach: (provider, name, key, body) =>
      val keyed = ModelClient[SyncIO](
        config.copy(model = provider.defaultModel),
        BackendStub(CatsMonadError[SyncIO]())
          .whenRequestMatches(_.header(name).contains(key))
          .thenRespond(ResponseStub.adjust(body, StatusCode.Ok))
          .whenAnyRequest
          .thenRespond(ResponseStub.adjust("unkeyed", StatusCode.Unauthorized)),
      )
      assert(result(keyed).isRight, provider)
