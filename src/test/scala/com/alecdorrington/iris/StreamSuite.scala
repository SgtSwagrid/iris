package com.alecdorrington.iris

import cats.effect.SyncIO
import com.alecdorrington.iris.Fixtures.{config, json}
import fs2.{Pipe, Stream}
import io.circe.Json
import munit.FunSuite

class StreamSuite extends FunSuite:

  private def reply
    (body: String)
    (read: Pipe[SyncIO, Json, Delta])
    : List[Delta] = Stream
    .emits(body.getBytes("UTF-8"))
    .covary[SyncIO]
    .through(Sse.events)
    .through(read)
    .compile
    .toList
    .unsafeRunSync()

  test("a data line carries its payload, and no other line does"):
    List(
      "data: {\"a\":1}"     -> Some("{\"a\":1}"),
      "data:{\"a\":1}"      -> Some("{\"a\":1}"),
      "event: message_stop" -> None,
      ": a comment"         -> None,
      ""                    -> None,
    ).foreach((line, data) => assertEquals(Sse.data(line), data, line))

  test("events end at the end marker, whatever the body does after it"):
    val body = "data: {\"a\":1}\n\ndata: [DONE]\n\ndata: {\"b\":2}\n"
    assertEquals(
      (Stream.emits(body.getBytes("UTF-8")) ++
        Stream.raiseError[SyncIO](new Exception("never read")))
        .through(Sse.events)
        .compile
        .toList
        .unsafeRunSync(),
      List(Json.obj("a" -> Json.fromInt(1))),
    )

  test("each provider streams its text, how it ended, and what it cost"):
    val usage = Usage(uncachedTokens = 3, visibleTokens = 2)
    List(
      // Anthropic reports nothing of events which say nothing new, and counts
      // the request as it starts and the reply as it stops.
      AnthropicApi ->
        """event: message_start
data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":3,"output_tokens":1}}}

data: {"type":"content_block_start","index":0}

data: {"type":"ping"}

event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}

event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

event: message_stop
data: {"type":"message_stop"}
""",
      // OpenAI counts the reply in an event after the one saying it stopped.
      OpenAiApi ->
        """data: {"choices":[{"delta":{"content":"Hel"}}],"usage":null}

data: {"choices":[{"delta":{"content":"lo"}}],"usage":null}

data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":null}

data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}

data: [DONE]
""",
      // Gemini may speak and finish in the same breath.
      GeminiApi ->
        """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}
""",
    ).foreach: (api, body) =>
      assertEquals(
        reply(body)(api.reply),
        List(
          Delta.Text("Hel"),
          Delta.Text("lo"),
          Delta.End(StopReason.Completed, usage),
        ),
        api.provider,
      )
    // A reply cut off says so where it ended, counted as far as it was.
    assertEquals(
      reply(
        """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}
""",
      )(AnthropicApi.reply),
      List(Delta.End(StopReason.MaxTokens, Usage())),
    )
    // OpenAI's usage is read where it comes with the stop reason, too.
    assertEquals(
      reply("""data: {"choices":[{"delta":{},"finish_reason":"length"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}
""")(OpenAiApi.reply),
      List(Delta.End(StopReason.MaxTokens, usage)),
    )

  test("anthropic's stop counts what its start leaves out, and its thinking"):
    val body = """data: {"type":"message_start","message":{"usage":{"input_tokens":3,"cache_read_input_tokens":40,"output_tokens":1}}}

data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":5,"output_tokens":12,"output_tokens_details":{"thinking_tokens":10}}}
"""
    assertEquals(
      reply(body)(AnthropicApi.reply),
      List(Delta.End(
        StopReason.Completed,
        Usage(
          uncachedTokens = 5,
          cacheReadTokens = 40,
          visibleTokens = 2,
          thinkingTokens = 10,
        ),
      )),
    )

  test("openai's stream that stops without saying why has no end"):
    assertEquals(
      reply("""data: {"choices":[{"delta":{"content":"Hel"}}],"usage":null}
""")(OpenAiApi.reply),
      List(Delta.Text("Hel")),
    )

  test("gemini stops for a tool it asked for, however late it says it stopped"):
    val late = """data: {"candidates":[{"content":{"parts":[{"text":"Let me look."}]}}]}

data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"weather","args":{"city":"Zug"}}}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}
"""
    assertEquals(
      reply(late)(GeminiApi.reply),
      List(
        Delta.Text("Let me look."),
        Delta.ToolCall(weather),
        Delta.End(
          StopReason.ToolCall,
          Usage(uncachedTokens = 7, visibleTokens = 3),
        ),
      ),
    )
    val together =
      """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"weather","args":{"city":"Zug"}}}]},"finishReason":"STOP"}]}
"""
    assertEquals(
      reply(together)(GeminiApi.reply),
      List(
        Delta.ToolCall(weather),
        Delta.End(StopReason.ToolCall, Usage()),
      ),
    )

  test("gemini streams each call with its id and thought signature"):
    val together =
      """data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"a1","name":"weather","args":{"city":"Zug"}},"thoughtSignature":"c2lnbmVk"},{"functionCall":{"id":"b2","name":"weather","args":{"city":"Zug"}}}]},"finishReason":"STOP"}]}
"""
    assertEquals(
      reply(together)(GeminiApi.reply),
      List(
        Delta.ToolCall(weather.copy(id = "a1", signature = Some("c2lnbmVk"))),
        Delta.ToolCall(weather.copy(id = "b2")),
        Delta.End(StopReason.ToolCall, Usage()),
      ),
    )

  test("anthropic and openai stream a tool call whole, once its pieces end"):
    val anthropic = """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me look."}}

data: {"type":"content_block_stop","index":0}

data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"weather","name":"weather","input":{}}}

data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"ci"}}

data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"ty\":\"Zug\"}"}}

data: {"type":"content_block_stop","index":1}

data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
"""
    assertEquals(
      reply(anthropic)(AnthropicApi.reply),
      List(
        Delta.Text("Let me look."),
        Delta.ToolCall(weather),
        Delta.End(StopReason.ToolCall, Usage()),
      ),
    )
    val openAi = """data: {"choices":[{"delta":{"content":"Let me look."}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"weather","type":"function","function":{"name":"weather","arguments":""}}]}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Zug\"}"}}]}}]}

data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
"""
    assertEquals(
      reply(openAi)(OpenAiApi.reply),
      List(
        Delta.Text("Let me look."),
        Delta.ToolCall(weather),
        Delta.End(StopReason.ToolCall, Usage()),
      ),
    )

  test("a streamed tool call whose arguments are no JSON is malformed"):
    val cut =
      """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"weather","name":"weather","input":{}}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"ci"}}

data: {"type":"content_block_stop","index":0}
"""
    assertEquals(
      Stream
        .emits(cut.getBytes("UTF-8"))
        .covary[SyncIO]
        .through(Sse.events)
        .through(AnthropicApi.reply)
        .compile
        .toList
        .attempt
        .unsafeRunSync(),
      Left(LlmError.Malformed(
        "Anthropic",
        "the arguments of a call of `weather` are not JSON",
      )),
    )

  /** The call each provider is asked for, as it is read. */
  private val weather = Part.ToolCall(
    "weather",
    "weather",
    Json.obj("city" -> Json.fromString("Zug")),
  )

  test("a streaming request asks to be streamed, and openai for its usage"):
    val anthropic = json(AnthropicApi.body(
      config,
      Chat().user("Hi"),
      ReplyOptions(),
      true,
    ))
    assertEquals(
      anthropic.hcursor.get[Boolean]("stream").toOption,
      Some(true),
    )
    val openAi = json(OpenAiApi.body(
      config,
      Chat().user("Hi"),
      ReplyOptions(),
      true,
    ))
    assertEquals(
      openAi.hcursor.get[Boolean]("stream").toOption,
      Some(true),
    )
    // Usage is withheld from a stream unless asked for.
    assertEquals(
      openAi
        .hcursor
        .downField("stream_options")
        .get[Boolean]("include_usage")
        .toOption,
      Some(true),
    )
