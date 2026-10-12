package com.alecdorrington.iris

import fs2.Stream
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.model.{Header, Uri}

/**
  * The [[ProviderApi]] of the [OpenAI Chat Completions
  * API](https://platform.openai.com/docs/api-reference/chat).
  */
private[iris] object OpenAiApi extends ProviderApi:

  override val provider: LlmProvider = LlmProvider.OpenAi

  override def endpoint
    (
      config: LlmConfig,
      options: ReplyOptions,
      streamed: Boolean,
    )
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1",
    "chat",
    "completions",
  )

  override def credentials(config: LlmConfig): Seq[Header] =
    Seq(Header.authorization("Bearer", config.apiKey))

  override val replies: Decoder[Either[LlmError, Reply]] = Decoder[Response]
    .map(_.toReply)

  override def reply[F[_]](events: Stream[F, Json]): Stream[F, Delta] = Sse
    .separately(deltas)(events)

  /** Refuses an empty chat, and one carrying media other than pictures. */
  override def acceptable
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
    )
    : Either[LlmError, Unit] = JsonHttp
    .answerable(provider, chat)
    .flatMap(_ => pictorial(chat))

  private def reasoning(effort: Effort): String = effort match
    case Effort.Low               => "low"
    case Effort.Medium            => "medium"
    case Effort.High | Effort.Max => "high"

  override def body
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
      streamed: Boolean,
    )
    : String =
    val messages = chat.system.map(text("system", _)).toList ++
      chat.messages.flatMap(message)
    JsonHttp.body(
      "model"                 -> config.model.id.asJson,
      "max_completion_tokens" -> config.limit(options).asJson,
      "temperature"           -> options.temperature.asJson,
      "top_p"                 -> options.topP.asJson,
      "stop"                  -> JsonHttp.unlessEmpty(options.stopSequences),
      "tools"                 -> JsonHttp.unlessEmpty(options.tools.map(tool)),
      "reasoning_effort"      -> options.effort.map(reasoning).asJson,
      "stream"                -> Option.when(streamed)(true).asJson,
      // Usage is withheld from a stream unless asked for.
      "stream_options" ->
        Option.when(streamed)(Json.obj("include_usage" -> true.asJson)).asJson,
      "messages" -> messages.asJson,
    )

  /** Refuses media other than pictures, which chat completions cannot take. */
  def pictorial(chat: Chat): Either[LlmError, Unit] = chat
    .messages
    .flatMap(_.content)
    .collectFirst:
      case Part.Media(mediaType, _) if !mediaType.startsWith("image/") =>
        LlmError.Unsupported(
          provider.displayName,
          s"sending $mediaType in a chat",
        )
    .toLeft(())

  private def text(role: String, content: String): Json = Json.obj(
    "role"    -> role.asJson,
    "content" -> content.asJson,
  )

  /** Serialises a message, each tool result as a message of its own. */
  private def message(message: Message): List[Json] =
    // OpenAI caches long prefixes by itself, so breakpoints are dropped.
    val spoken = message
      .uncached
      .content
      .filterNot(_.isInstanceOf[Part.ToolResult])
    message.toolResults.map(result) ++
      Option.when(spoken.nonEmpty)(said(message.role, spoken))

  private def result(result: Part.ToolResult): Json = Json.obj(
    "role"         -> "tool".asJson,
    "tool_call_id" -> result.callId.asJson,
    "content"      -> result.output.asJson,
  )

  private def said(role: Role, spoken: List[Part]): Json =
    val calls = spoken.collect:
      case call: Part.ToolCall => call
    JsonHttp.objectOf(
      "role"       -> role.code.asJson,
      "content"    -> content(spoken.filterNot(_.isInstanceOf[Part.ToolCall])),
      "tool_calls" -> Option.when(calls.nonEmpty)(calls.map(call)).asJson,
    )

  /** Serialises what a message says, separated once its tools are out. */
  private def content(spoken: List[Part]): Json =
    val sent = Message.separated(spoken)
    if sent.isEmpty then Json.Null
    else JsonHttp.plain(sent).fold(sent.flatMap(part).asJson)(_.asJson)

  /** Serialises a tool call, whose arguments OpenAI takes as a string. */
  private def call(call: Part.ToolCall): Json = Json.obj(
    "id"       -> call.id.asJson,
    "type"     -> "function".asJson,
    "function" -> Json.obj(
      "name"      -> call.tool.asJson,
      "arguments" -> call.arguments.noSpaces.asJson,
    ),
  )

  private def tool(tool: Tool): Json = Json.obj(
    "type"     -> "function".asJson,
    "function" -> JsonHttp.declared(tool, "parameters"),
  )

  private def part(part: Part): Option[Json] = part match
    case Part.Text(text) => Some(Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      ))
    case Part.Media(mediaType, data) => Some(Json.obj(
        "type"      -> "image_url".asJson,
        "image_url" -> Json.obj("url" -> s"data:$mediaType;base64,$data".asJson),
      ))
    case _: Part.ToolCall | _: Part.ToolResult | Part.CacheBreakpoint => None

  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "stop",
    "length",
    toolCall = Some("tool_calls"),
  )

  def deltas(event: Json): List[Delta] =
    val choice = event.hcursor.downField("choices").downN(0)
    val said   = choice.downField("delta").get[String]("content").toOption
    val ended  = choice
      .get[String]("finishReason")
      .toOption
      .orElse(choice.get[String]("finish_reason").toOption)
    List.concat(
      said.filter(_.nonEmpty).map(Delta.Text.apply),
      ended.map(reason =>
        Delta.End(
          stopReason(Some(reason)),
          event.hcursor.get[TokenCounts]("usage").toOption.flatMap(_.toUsage),
        ),
      ),
    )

  final case class FunctionCall
    (name: String, arguments: Option[String])
    derives Decoder:

    /** The arguments, which arrive as a string of JSON. */
    def parsedArguments: Json = arguments
      .flatMap(parse(_).toOption)
      .getOrElse(Json.obj())

  final case class ToolCall(id: String, function: FunctionCall) derives Decoder:

    def toPart: Part.ToolCall = Part.ToolCall(
      id,
      function.name,
      function.parsedArguments,
    )

  final case class ReplyMessage
    (
      content: Option[String],
      toolCalls: Option[List[ToolCall]],
    )

  object ReplyMessage:

    given Decoder[ReplyMessage] =
      Decoder.forProduct2("content", "tool_calls")(ReplyMessage.apply)

  final case class Choice
    (
      message: ReplyMessage,
      finishReason: Option[String],
    )

  object Choice:

    given Decoder[Choice] =
      Decoder.forProduct2("message", "finish_reason")(Choice.apply)

  final case class PromptBreakdown(cachedTokens: Option[Int])

  object PromptBreakdown:

    given Decoder[PromptBreakdown] =
      Decoder.forProduct1("cached_tokens")(PromptBreakdown.apply)

  /** The token counts of a response, whose prompt tokens include cached ones. */
  final case class TokenCounts
    (
      promptTokens: Option[Int],
      completionTokens: Option[Int],
      promptBreakdown: Option[PromptBreakdown],
    ):

    def toUsage: Option[Usage] = Usage.of(
      promptTokens,
      completionTokens,
      promptBreakdown.flatMap(_.cachedTokens),
    )

  object TokenCounts:

    given Decoder[TokenCounts] = Decoder.forProduct3(
      "prompt_tokens",
      "completion_tokens",
      "prompt_tokens_details",
    )(TokenCounts.apply)

  final case class Response
    (
      choices: List[Choice],
      usage: Option[TokenCounts],
    )
    derives Decoder:

    def toReply: Either[LlmError, Reply] = choices
      .headOption
      .toRight(LlmError.Malformed(
        LlmProvider.OpenAi.displayName,
        "no choices",
      ))
      .map: choice =>
        Reply(
          text = choice.message.content.getOrElse(""),
          stopReason = stopReason(choice.finishReason),
          usage = usage.flatMap(_.toUsage),
          toolCalls =
            choice.message.toolCalls.getOrElse(List.empty).map(_.toPart),
        )
