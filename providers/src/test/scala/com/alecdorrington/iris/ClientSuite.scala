package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import cats.syntax.all.*
import com.alecdorrington.iris.Fixtures.{access, json, prompting, streamed}
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.providers.*
import io.circe.Json
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import sttp.client4.{Backend, StringBody, SttpClientException}
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{Header, StatusCode}

class ClientSuite extends FunSuite:

  private val prompt = Conversation.userSays("Hello")

  /** A backend answering every request with the body. */
  private def answering
    (
      body: String,
      status: StatusCode = StatusCode.Ok,
      headers: Seq[Header] = Seq.empty,
    )
    : Backend[SyncIO] = BackendStub(CatsMonadError[SyncIO]())
    .whenAnyRequest
    .thenRespond(ResponseStub.adjust(body, status, headers))

  private def service
    (
      body: String,
      status: StatusCode = StatusCode.Ok,
      headers: Seq[Header] = Seq.empty,
      access: ApiConfig = access,
    )
    : PromptService[SyncIO] = prompting(
    answering(
      if status.isSuccess then streamed(Anthropic, body) else body,
      status,
      headers,
    ),
    access = access,
  )

  private def result
    (service: PromptService[SyncIO])
    : Either[Throwable, PromptResponse] = service
    .send(prompt)
    .attempt
    .unsafeRunSync()

  test("a request no answer comes to in time is an error saying so"):
    val slow = prompting(
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
      Left(ApiError.TimedOut("Anthropic", access.timeout)),
    )

  /** Anthropic's response of text and a call, as the events of a stream. */
  private val events = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"look","input":{}}}

data: {"type":"content_block_stop","index":1}

data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

"""

  test(
    "over a backend that cannot stream, a streamed response is read at once",
  ):
    // Every request asks for its response as it would arrive, or is refused.
    val backend = BackendStub(CatsMonadError[SyncIO]())
      .whenAnyRequest
      .thenRespondF(request =>
        request.body match
          case StringBody(sent, _, _) =>
            if json(sent).hcursor.get[Boolean]("stream").contains(true) then
              SyncIO(ResponseStub.adjust(events))
            else SyncIO.raiseError(Exception("It was not streamed."))
          case _ => SyncIO.raiseError(Exception("No JSON was sent.")),
      )
    val looked   = MessagePart.ToolCall("call_1", "look", "{}")
    val response = PromptResponse(
      "Hi",
      StopReason.ToolCall,
      Usage(),
      // Priced as it came, at Claude Haiku 4.5's rates, though it reports none.
      Some(Usd(BigDecimal(0))),
      List(looked),
    )
    val written = List(
      PromptEvent.Written(PromptEvent.Part.Text, "Hi"),
      PromptEvent.Written(
        PromptEvent.Part.Call("call_1", "look", None),
        "{}",
      ),
      PromptEvent.Ended(response),
    )
    val reading = prompting(backend)
    assertEquals(
      reading
        .stream(PromptRequest(prompt))
        .compile
        .toList
        .attempt
        .unsafeRunSync(),
      Right(written),
    )
    assertEquals(
      reading.send(prompt).attempt.unsafeRunSync(),
      Right(response),
    )
    assertEquals(
      response.message,
      Message.Model(List(MessagePart.Text("Hi"), looked)),
    )

  test("a prompt is what the user says, a conversation, or a request of one"):
    val heard =
      for
        bodies <- Ref.of[SyncIO, List[Json]](Nil)
        said = prompting(
          BackendStub(CatsMonadError[SyncIO]())
            .whenAnyRequest
            .thenRespondF(request =>
              request.body match
                case StringBody(sent, _, _) => bodies
                    .update(_ :+ json(sent))
                    .as(ResponseStub.adjust(streamed(
                      Anthropic,
                      """{"content":[{"type":"text","text":"Hi"}],
                        "stop_reason":"end_turn"}""",
                    )))
                case _ => SyncIO.raiseError(Exception("No JSON was sent.")),
            ),
        )
        texts <- List(
          said.send("Hello"),
          said.send(prompt),
          said.send(PromptRequest(prompt)),
        ).sequence
        sent <- bodies.get
      yield (texts.map(_.text), sent.map(_.hcursor.downField("messages").focus))
    assertEquals(
      heard.unsafeRunSync(),
      (
        List.fill(3)("Hi"),
        List.fill(3)(Some(Json.arr(Json.obj(
          "role"    -> Json.fromString("user"),
          "content" -> Json.fromString("Hello"),
        )))),
      ),
    )

  test("a refusal for now carries the wait asked for, given at once here"):
    // A service over a host's backend sends each request once.
    List(
      Seq(Header("Retry-After", "30")) -> Some(30.seconds),
      Seq.empty                        -> None,
    ).foreach: (headers, delay) =>
      assertEquals(
        result(service(
          "slow down",
          StatusCode.TooManyRequests,
          headers,
        )),
        Left(ApiError.RateLimited(
          "Anthropic",
          StatusCode.TooManyRequests,
          delay,
          "slow down",
        )),
        headers,
      )
    assertEquals(
      result(service("busy", StatusCode(529))),
      Left(ApiError.Overloaded(
        "Anthropic",
        StatusCode(529),
        None,
        "busy",
      )),
    )

  test("a failure for now carries its status, given at once here"):
    assertEquals(
      result(service("down", StatusCode.InternalServerError)),
      Left(ApiError.Transient(
        "Anthropic",
        StatusCode.InternalServerError,
        None,
        "down",
      )),
    )

  test("any other unsuccessful response is an error carrying its status"):
    assertEquals(
      result(service("no such key", StatusCode.Unauthorized)),
      Left(ApiError.Unsuccessful(
        "Anthropic",
        StatusCode.Unauthorized,
        "no such key",
      )),
    )

  test("a response which is not json at all is malformed"):
    assert(
      result(service("<html>down for maintenance</html>"))
        .left
        .exists:
          case ApiError.Malformed("Anthropic", _) => true
          case _                                  => false,
    )

  test("a base url which is not a url fails the effect, never the call"):
    val broken = access.copy(baseUrl = Some("https://proxy.test/%"))
    assert(
      result(service("{}", access = broken))
        .left
        .exists:
          case ApiError.Misconfigured("Anthropic", _) => true
          case _                                      => false,
    )

  test("a key given nowhere fails the effect, before any request"):
    val unkeyed = Providers.all.filter(_.key(Environment.variable).isEmpty)
    assume(
      unkeyed.nonEmpty,
      "Every provider's key is set in the environment.",
    )
    val never = BackendStub(CatsMonadError[SyncIO]())
      .whenAnyRequest
      .thenRespondF(_ =>
        SyncIO.raiseError(AssertionError("A request was made.")),
      )
    unkeyed.foreach: provider =>
      val keyless = prompting(
        never,
        provider.defaultModel,
        access = ApiConfig(),
      )
      assert(
        result(keyless)
          .left
          .exists:
            case ApiError.Misconfigured(name, detail) =>
              name == provider.displayName &&
              detail.contains(provider.keyVariables.head)
            case _ => false,
        provider,
      )

  test(
    "a conversation is counted before it is sent, where its provider can count",
  ):
    List(
      (
        AnthropicModel.ClaudeHaiku4_5,
        """{"input_tokens":42}""",
        Some(Right(42)),
      ),
      (GeminiModel.Gemini2_5Flash, """{"totalTokens":17}""", Some(Right(17))),
      // OpenAI has no counter, rather than one guessing.
      (OpenAiModel.Gpt5, "{}", None),
    ).foreach: (model, body, count) =>
      assertEquals(
        model
          .counter(model.provider.api(access, answering(body)))
          .map(_.countTokens(prompt).attempt.unsafeRunSync()),
        count,
        model,
      )

  test("each provider serves its default model, and is sent its key"):
    Providers
      .all
      .foreach(provider =>
        assertEquals(
          provider.defaultModel.provider,
          provider,
        ),
      )
    List(
      (
        Anthropic,
        "x-api-key",
        "key",
        """{"content":[],"stop_reason":"end_turn"}""",
      ),
      (
        OpenAi,
        "Authorization",
        "Bearer key",
        """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}""",
      ),
      (
        Gemini,
        "x-goog-api-key",
        "key",
        """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}]}""",
      ),
    ).foreach: (provider, name, key, body) =>
      val keyed = prompting(
        BackendStub(CatsMonadError[SyncIO]())
          .whenRequestMatches(_.header(name).contains(key))
          .thenRespond(ResponseStub.adjust(
            streamed(provider, body),
            StatusCode.Ok,
          ))
          .whenAnyRequest
          .thenRespond(ResponseStub.adjust("unkeyed", StatusCode.Unauthorized)),
        provider.defaultModel,
      )
      assert(result(keyed).isRight, provider)

  test("a service answering whole is read alike, streamed or sent"):
    val looked   = MessagePart.ToolCall("call_1", "look", "{}")
    val response = PromptResponse(
      "Hi",
      StopReason.ToolCall,
      toolCalls = List(looked),
    )
    val answering = PromptService.of[SyncIO](_ => SyncIO.pure(response))
    assertEquals(
      response.events,
      List(
        PromptEvent.Written(PromptEvent.Part.Text, "Hi"),
        PromptEvent.Written(
          PromptEvent.Part.Call("call_1", "look", None),
          "{}",
        ),
        PromptEvent.Ended(response),
      ),
    )
    assertEquals(
      answering.stream(prompt).compile.toList.unsafeRunSync(),
      response.events,
    )
    assertEquals(
      answering.send(prompt).unsafeRunSync(),
      response,
    )

  test("a request warming the cache says so, and no other does"):
    val asked =
      for
        sent <- Ref.of[SyncIO, List[PromptRequest]](Nil)
        recording = PromptService.of[SyncIO](request =>
          sent.update(_ :+ request).as(PromptResponse("")),
        )
        cached = Conversation.userSays(
          "A long document.",
          MessagePart.CacheBreakpoint,
          "A question of it.",
        )
        _     <- recording.warmCache(cached)
        _     <- recording.send(cached)
        sends <- sent.get
      yield sends.map(_.warming)
    assertEquals(asked.unsafeRunSync(), List(true, false))
