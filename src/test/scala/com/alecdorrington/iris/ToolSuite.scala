package com.alecdorrington.iris

import com.alecdorrington.iris.Fixtures.{answer, config, json}
import io.circe.{ACursor, Json}
import munit.FunSuite

class ToolSuite extends FunSuite:

  /** A tool whose schema holds nulls, which are values there. */
  private val weather = Tool(
    "weather",
    "Looks up the weather somewhere, or where it can.",
    Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" -> Json.obj(
        "city" -> Json.obj(
          "type" -> Json.arr(
            Json.fromString("string"),
            Json.fromString("null"),
          ),
          "default" -> Json.Null,
          "enum"    -> Json.arr(Json.fromString("Zug"), Json.Null),
        ),
      ),
    ),
  )

  private val offering = ModelRequest.Options(tools = List(weather))

  private val asked = MessagePart.ToolCall(
    "call_1",
    "weather",
    Json.obj("city" -> Json.fromString("Zug")),
  )

  /** A call whose arguments hold a null, which is a value there. */
  private val unplaced = asked.copy(arguments = Json.obj("city" -> Json.Null))

  private val answered = MessagePart.ToolResult("call_1", "weather", "Snowing.")

  private val exchange = Chat()
    .userSays("What is the weather?")
    .modelSays(unplaced)
    .userSays(answered)

  test("each provider is offered a tool where it looks, its schema as it is"):
    List[(ProviderApi, ACursor => ACursor, String)](
      (AnthropicApi, _.downField("tools").downN(0), "input_schema"),
      (
        OpenAiApi,
        _.downField("tools").downN(0).downField("function"),
        "parameters",
      ),
      (
        GeminiApi,
        _.downField("tools")
          .downN(0)
          .downField("functionDeclarations")
          .downN(0),
        "parameters",
      ),
    ).foreach: (api, declared, schema) =>
      val tool = declared(
        json(api.body(config, Chat().userSays("Hi"), offering)).hcursor,
      )
      assertEquals(
        tool.get[String]("name").toOption,
        Some("weather"),
        api.provider,
      )
      assertEquals(
        tool.downField(schema).focus,
        Some(weather.parameters),
        api.provider,
      )
    assertEquals(
      json(OpenAiApi.body(config, Chat().userSays("Hi"), offering))
        .hcursor
        .downField("tools")
        .downN(0)
        .get[String]("type")
        .toOption,
      Some("function"),
    )

  test("gemini is offered a tool taking nothing with no schema, others with"):
    val bare = Tool(
      "ping",
      "Pings.",
      Json.obj(
        "type"       -> Json.fromString("object"),
        "properties" -> Json.obj(),
      ),
    )
    // An object of no named properties that takes any others.
    val tally = Tool(
      "tally",
      "Tallies.",
      Schema[Map[String, Int]].json,
    )
    val offered  = ModelRequest.Options(tools = List(bare, weather, tally))
    val declared = json(GeminiApi.body(config, Chat().userSays("Hi"), offered))
      .hcursor
      .downField("tools")
      .downN(0)
      .downField("functionDeclarations")
    assertEquals(
      declared.downN(0).get[String]("name").toOption,
      Some("ping"),
    )
    assert(declared.downN(0).downField("parameters").failed)
    assertEquals(
      declared.downN(1).downField("parameters").focus,
      Some(weather.parameters),
    )
    assertEquals(
      declared.downN(2).downField("parameters").focus,
      Some(tally.parameters),
    )
    assertEquals(
      json(AnthropicApi.body(config, Chat().userSays("Hi"), offered))
        .hcursor
        .downField("tools")
        .downN(0)
        .downField("input_schema")
        .focus,
      Some(bare.parameters),
    )

  test(
    "anthropic sends a call and its result as blocks, the call's nulls kept",
  ):
    val body     = json(AnthropicApi.body(config, exchange, offering))
    val messages = body.hcursor.downField("messages")
    val call     = messages.downN(1).downField("content").downN(0)
    val result   = messages.downN(2).downField("content").downN(0)
    assertEquals(
      call.get[String]("type").toOption,
      Some("tool_use"),
    )
    assertEquals(
      call.get[String]("id").toOption,
      Some("call_1"),
    )
    assertEquals(
      call.downField("input").focus,
      Some(unplaced.arguments),
    )
    assertEquals(
      result.get[String]("type").toOption,
      Some("tool_result"),
    )
    assertEquals(
      result.get[String]("tool_use_id").toOption,
      Some("call_1"),
    )

  test("openai sends a call's nulls as they are, and a result as a message"):
    val body     = json(OpenAiApi.body(config, exchange, offering))
    val messages = body.hcursor.downField("messages")
    val call     = messages.downN(1)
    assertEquals(
      call.get[String]("role").toOption,
      Some("assistant"),
    )
    assertEquals(
      call
        .downField("tool_calls")
        .downN(0)
        .downField("function")
        .get[String]("arguments")
        .toOption,
      Some("""{"city":null}"""),
    )
    assert(call.downField("content").failed)
    assertEquals(
      messages.downN(2).get[String]("role").toOption,
      Some("tool"),
    )
    assertEquals(
      messages.downN(2).get[String]("tool_call_id").toOption,
      Some("call_1"),
    )

  test("gemini sends a call and its result as parts, the call's nulls kept"):
    val body     = json(GeminiApi.body(config, exchange, offering))
    val contents = body.hcursor.downField("contents")
    val call     = contents
      .downN(1)
      .downField("parts")
      .downN(0)
      .downField("functionCall")
    assertEquals(
      call.get[String]("name").toOption,
      Some("weather"),
    )
    assertEquals(
      call.downField("args").focus,
      Some(unplaced.arguments),
    )
    assertEquals(
      contents
        .downN(2)
        .downField("parts")
        .downN(0)
        .downField("functionResponse")
        .get[String]("name")
        .toOption,
      Some("weather"),
    )

  test("each provider reports the tool it asked for"):
    List(
      (
        AnthropicApi,
        """{"content":[{"type":"text","text":"Let me look."},
         {"type":"tool_use","id":"call_1","name":"weather",
          "input":{"city":"Zug"}}],"stop_reason":"tool_use"}""",
        "Let me look.",
        asked,
      ),
      // OpenAI's arguments arrive as a string of JSON, to be parsed.
      (
        OpenAiApi,
        """{"choices":[{"message":{"content":null,"tool_calls":[
         {"id":"call_1","type":"function",
          "function":{"name":"weather","arguments":"{\"city\":\"Zug\"}"}}]},
         "finish_reason":"tool_calls"}]}""",
        "",
        asked,
      ),
      // Gemini's call is named for want of an id, and stops as any reply does.
      (
        GeminiApi,
        """{"candidates":[{"content":{"parts":[
         {"functionCall":{"name":"weather","args":{"city":"Zug"}}}]},
         "finishReason":"STOP"}]}""",
        "",
        asked.copy(id = "weather"),
      ),
    ).foreach: (api, body, text, call) =>
      assertEquals(
        answer(api, body).map(reply =>
          (reply.text, reply.stopReason, reply.toolCalls),
        ),
        Right((text, StopReason.ToolCall, List(call))),
        api.provider,
      )

  test("gemini's calls keep their ids, by which two of one tool differ"):
    assertEquals(
      answer(
        GeminiApi,
        """{"candidates":[{"content":{"parts":[
         {"functionCall":{"id":"a1","name":"weather","args":{"city":"Zug"}},
          "thoughtSignature":"c2lnbmVk"},
         {"functionCall":{"id":"b2","name":"weather","args":{"city":"Bern"}}}]},
         "finishReason":"STOP"}]}""",
      ).map(_.toolCalls),
      // Only the first of calls made together carries a thought signature.
      Right(List(
        asked.copy(id = "a1", signature = Some("c2lnbmVk")),
        asked.copy(
          id = "b2",
          arguments = Json.obj("city" -> Json.fromString("Bern")),
        ),
      )),
    )

  test("gemini is sent each call as it gave it, and each result by its id"):
    val signed = asked.copy(signature = Some("c2lnbmVk"))
    val named  = asked.copy(id = "weather")
    val sent   = json(GeminiApi.body(
      config,
      Chat()
        .userSays("What is the weather?")
        .modelSays(signed, named)
        .userSays(
          answered,
          MessagePart.ToolResult("weather", "weather", "Sunny."),
        ),
      offering,
    )).hcursor.downField("contents")
    // A call named for want of an id is sent with none, as is its result.
    assertEquals(
      sent.downN(1).get[List[Json]]("parts").toOption,
      Some(List(
        Json.obj(
          "functionCall" -> Json.obj(
            "id"   -> Json.fromString("call_1"),
            "name" -> Json.fromString("weather"),
            "args" -> asked.arguments,
          ),
          "thoughtSignature" -> Json.fromString("c2lnbmVk"),
        ),
        Json.obj(
          "functionCall" -> Json.obj(
            "name" -> Json.fromString("weather"),
            "args" -> asked.arguments,
          ),
        ),
      )),
    )
    assertEquals(
      sent
        .downN(2)
        .get[List[Json]]("parts")
        .map(_.map(
          _.hcursor.downField("functionResponse").get[String]("id").toOption,
        )),
      Right(List(Some("call_1"), None)),
    )

  test(
    "gemini counts the contents it would send, its system message the user's",
  ):
    val briefed  = exchange.withInstructions("Be brief.")
    val contents =
      (body: String) => json(body).hcursor.get[List[Json]]("contents")
    val system = Json.obj(
      "role"  -> Json.fromString("user"),
      "parts" -> Json.arr(Json.obj("text" -> Json.fromString("Be brief."))),
    )
    assertEquals(
      contents(GeminiApi.countBody(briefed)),
      contents(GeminiApi.body(config, briefed, offering)).map(system :: _),
    )

  test("a model's reply can be appended and answered"):
    val reply = ModelResponse(
      "Let me look.",
      StopReason.ToolCall,
      toolCalls = List(asked),
    )
    val next = Chat().userSays("Weather?").modelSays(reply).userSays(answered)
    assertEquals(next.messages.size, 3)
    assertEquals(next.messages(1).role, Role.Model)
    assertEquals(next.messages(1).parts.last, asked)
    assertEquals(next.messages(2).parts, List(answered))

  test("a model's reply saying nothing but its calls is appended as those"):
    val reply = ModelResponse(
      "",
      StopReason.ToolCall,
      toolCalls = List(asked),
    )
    assertEquals(
      Chat().userSays("Weather?").modelSays(reply).messages.last.parts,
      List(asked),
    )
