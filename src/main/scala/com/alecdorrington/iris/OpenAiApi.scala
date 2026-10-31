package com.alecdorrington.iris

import cats.syntax.all.*
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

  /**
    * The reply a stream's events make up, which ends with the stream, as its
    * usage comes in an event of its own after the one saying why it stopped.
    */
  override def reply[F[_]](events: Stream[F, Json]): Stream[F, Delta] = events
    .noneTerminate
    .zipWithScan1(Ending())((ending, event) => event.fold(ending)(ending.after))
    .flatMap:
      case (Some(event), _) => Stream.emits(said(event).toList)
      case (None, ending)   => Stream.emits(ending.end)

  /** Refuses an empty chat, and one carrying media other than pictures. */
  override def acceptable
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
    )
    : Either[LlmError, Unit] = JsonHttp
    .answerable(provider, chat)
    .flatMap(_ => JsonHttp.viewable(provider, chat, media))

  /**
    * The models whose reasoning effort goes no higher than `high`. OpenAI's
    * newer models also take `xhigh` and `max`, and an unlisted model is taken
    * to be as new.
    */
  private val cappedAtHigh: Set[LlmModel] =
    Set(LlmModel.Gpt5, LlmModel.Gpt5Mini)

  private def reasoning(model: LlmModel, effort: Effort): String = effort match
    case Effort.Low    => "low"
    case Effort.Medium => "medium"
    case Effort.High   => "high"
    case Effort.Max    => if cappedAtHigh.contains(model) then "high" else "max"

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
      "reasoning_effort"      ->
        options.effort.map(reasoning(config.model, _)).asJson,
      "stream" -> Option.when(streamed)(true).asJson,
      // Usage is withheld from a stream unless asked for.
      "stream_options" ->
        Option.when(streamed)(Json.obj("include_usage" -> true.asJson)).asJson,
      "messages" -> messages.asJson,
    )

  /**
    * The media chat completions are sent inline: pictures alone, as Iris sends
    * no PDF as a file part.
    */
  val media: Set[MediaType] = Set(
    MediaType.Png,
    MediaType.Jpeg,
    MediaType.Gif,
    MediaType.Webp,
  )

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
        "image_url" ->
          Json.obj("url" -> s"data:${ mediaType.name };base64,$data".asJson),
      ))
    case _: Part.ToolCall | _: Part.ToolResult | Part.CacheBreakpoint => None

  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "stop",
    "length",
    toolCall = Some("tool_calls"),
  )

  private def said(event: Json): Option[Delta] = event
    .hcursor
    .downField("choices")
    .downN(0)
    .downField("delta")
    .get[String]("content")
    .toOption
    .filter(_.nonEmpty)
    .map(Delta.Text.apply)

  /**
    * What a stream has said so far of how it ended: why it stopped, its token
    * counts, which come after, and its tool calls, whose arguments arrive in
    * fragments, by their indices.
    */
  final case class Ending
    (
      reason: Option[String] = None,
      counts: Option[TokenCounts] = None,
      calls: Map[Int, Calling] = Map.empty,
    ):

    def after(event: Json): Ending =
      val choice = event.hcursor.downField("choices").downN(0)
      Ending(
        choice
          .get[String]("finishReason")
          .toOption
          .orElse(choice.get[String]("finish_reason").toOption)
          .orElse(reason),
        event.hcursor.get[TokenCounts]("usage").toOption.orElse(counts),
        choice
          .downField("delta")
          .get[List[Json]]("tool_calls")
          .getOrElse(Nil)
          .foldLeft(calls)(called),
      )

    /** The calls, with a fragment of one. */
    private def called(calls: Map[Int, Calling], fragment: Json) =
      val cursor   = fragment.hcursor
      val function = cursor.downField("function")
      cursor
        .get[Int]("index")
        .toOption
        .fold(calls)(index =>
          calls.updatedWith(index)(call =>
            call
              .orElse(
                (
                  cursor.get[String]("id").toOption,
                  function.get[String]("name").toOption,
                ).mapN(Calling(_, _)),
              )
              .map(_.more(function.get[String]("arguments").getOrElse(""))),
          ),
        )

    /** The tool calls and end of the reply, where it said why it stopped. */
    def end: List[Delta] = reason
      .toList
      .flatMap(reason =>
        calls.toList.sortBy(_._1).map(_._2.whole) :+ Delta.End(
          stopReason(Some(reason)),
          counts.fold(Usage())(_.toUsage),
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

  final case class PromptBreakdown
    (
      cachedTokens: Option[Int],
      cacheWriteTokens: Option[Int],
    )

  object PromptBreakdown:

    given Decoder[PromptBreakdown] =
      Decoder.forProduct2("cached_tokens", "cache_write_tokens")(
        PromptBreakdown.apply,
      )

  final case class CompletionBreakdown(reasoningTokens: Option[Int])

  object CompletionBreakdown:

    given Decoder[CompletionBreakdown] =
      Decoder.forProduct1("reasoning_tokens")(CompletionBreakdown.apply)

  /**
    * The token counts of a response, whose prompt tokens include those read
    * from and written to the cache, and completion tokens reasoning.
    */
  final case class TokenCounts
    (
      promptTokens: Option[Int],
      completionTokens: Option[Int],
      promptBreakdown: Option[PromptBreakdown],
      completionBreakdown: Option[CompletionBreakdown],
    ):

    def toUsage: Usage =
      val prompt = promptTokens.getOrElse(0)
      val cached = promptBreakdown
        .flatMap(_.cachedTokens)
        .getOrElse(0)
        .min(prompt)
      val written = promptBreakdown
        .flatMap(_.cacheWriteTokens)
        .getOrElse(0)
        .min(prompt - cached)
      val completion = completionTokens.getOrElse(0)
      val reasoning  = completionBreakdown
        .flatMap(_.reasoningTokens)
        .getOrElse(0)
        .min(completion)
      Usage(
        uncachedTokens = prompt - cached - written,
        cacheReadTokens = cached,
        cacheWriteTokens = written,
        visibleTokens = completion - reasoning,
        thinkingTokens = reasoning,
      )

  object TokenCounts:

    given Decoder[TokenCounts] = Decoder.forProduct4(
      "prompt_tokens",
      "completion_tokens",
      "prompt_tokens_details",
      "completion_tokens_details",
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
          usage = usage.fold(Usage())(_.toUsage),
          toolCalls =
            choice.message.toolCalls.getOrElse(List.empty).map(_.toPart),
        )
