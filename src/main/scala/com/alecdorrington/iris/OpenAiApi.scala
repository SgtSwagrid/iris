package com.alecdorrington.iris

import cats.syntax.all.*
import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.model.Header

/**
  * The [[ProviderApi]] of the [OpenAI Chat Completions
  * API](https://platform.openai.com/docs/api-reference/chat).
  */
private[iris] object OpenAiApi extends ProviderApi:

  override val provider: ModelProvider = ModelProvider.OpenAi

  override def path(model: Model, streamed: Boolean): List[String] =
    List("v1", "chat", "completions")

  override def credentials(key: String): Seq[Header] =
    Seq(Header.authorization("Bearer", key))

  override val wholeResponse: Decoder[Either[ApiError, PromptResponse]] =
    Decoder[Response].map(_.toReply)

  /**
    * As `retry-after-ms` says, as OpenAI's own SDKs read it first, else
    * `retry-after`, else as late as the reset of each limit spent (its
    * `x-ratelimit-remaining-*` header `0`), which its `x-ratelimit-reset-*`
    * header gives as a Go duration (`6m0s`), as
    * https://developers.openai.com/api/docs/guides/rate-limits says.
    */
  override def retryAfter
    (
      headers: Seq[Header],
      detail: String,
      now: FiniteDuration,
    )
    : Option[FiniteDuration] = Refusal
    .header(headers, "retry-after-ms")
    .flatMap(Refusal.milliseconds)
    .orElse(super.retryAfter(headers, detail, now))
    .orElse(Refusal.spent(headers, resetOf, Refusal.go))

  /** The header saying when the limit of a remaining header resets. */
  private def resetOf(remaining: String): Option[String] = Option.when(
    remaining.startsWith(remainingPrefix),
  )("x-ratelimit-reset-" + remaining.stripPrefix(remainingPrefix))

  private val remainingPrefix = "x-ratelimit-remaining-"

  /**
    * A `429` that waiting does not lift, as credit, a spend limit or a usage
    * limit is spent, as
    * https://developers.openai.com/api/docs/guides/error-codes lists them, by
    * the error's code or its type.
    */
  override def lasting(detail: String): Boolean =
    val error = parse(detail).toOption.map(_.hcursor.downField("error"))
    List("type", "code")
      .flatMap(field => error.flatMap(_.get[String](field).toOption))
      .exists(exhausted.contains)

  private val exhausted = Set(
    "insufficient_quota",
    "credit_balance_exhausted",
    "organization_spend_limit_exceeded",
    "project_spend_limit_exceeded",
    "organization_usage_limit_exceeded",
  )

  /**
    * As https://developers.openai.com/api/docs/pricing gives them. GPT-5.6
    * Sol's are promotional, until November 21, 2026 at least, and OpenAI has
    * not said what they are after.
    */
  override def rates(model: Model, at: FiniteDuration): Option[Rates] =
    model match
      case Model.Gpt6Astra   => Some(writing(10, 1, 50))
      case Model.Gpt6_1Sol   => Some(writing(2, 0.10, 10))
      case Model.Gpt6Sol     => Some(writing(2, 0.20, 10))
      case Model.Gpt5_6Sol   => Some(writing(4, 0.40, 20))
      case Model.Gpt6_1Terra => Some(writing(1, 0.05, 5))
      case Model.Gpt5_6Terra => Some(writing(2, 0.20, 12))
      case Model.Gpt6Luna    => Some(writing(0.10, 0.01, 0.50))
      case Model.Gpt5_6Luna  => Some(writing(0.20, 0.02, 1.20))
      case Model.Gpt5        => Some(priced(1.25, 0.125, 10))
      case Model.Gpt5Mini    => Some(priced(0.25, 0.025, 2))
      case _                 => None

  /**
    * The rates of GPT-5.6 and later, which charge a quarter more than input for
    * writing the cache, for the thirty minutes it keeps unless told otherwise,
    * and for a prompt longer than 272K tokens twice as much for input and half
    * as much again for output. OpenAI does not say whether cached tokens count
    * towards the 272K, so they are taken to, as [[Rates.Above]] says.
    */
  private def writing
    (
      input: Double,
      cached: Double,
      output: Double,
    )
    : Rates =
    val (in, read, out) =
      (ProviderApi.usd(input), ProviderApi.usd(cached), ProviderApi.usd(output))
    val (twice, half) = (BigDecimal(2), BigDecimal(1.5))
    rated(in, read, out, in * BigDecimal(1.25)).copy(above =
      Some(Rates.Above(
        272_000,
        rated(
          in * twice,
          read * twice,
          out * half,
          in * BigDecimal(2.5),
        ),
      )),
    )

  /** The rates of earlier models, which charge no more for writing the cache. */
  private def priced
    (
      input: Double,
      cached: Double,
      output: Double,
    )
    : Rates =
    val in = ProviderApi.usd(input)
    rated(
      in,
      ProviderApi.usd(cached),
      ProviderApi.usd(output),
      in,
    )

  private def rated
    (
      input: Usd,
      cached: Usd,
      output: Usd,
      written: Usd,
    )
    : Rates = Rates(
    uncached = input,
    cacheRead = cached,
    cacheWrite = written,
    uncachedAudio = input,
    cacheReadAudio = cached,
    visible = output,
    thinking = output,
  )

  /**
    * The reply a stream's events make up, which ends with the stream, as its
    * usage comes in an event of its own after the one saying why it stopped.
    */
  override def deltas[F[_] : RaiseThrowable]
    (events: Stream[F, Json])
    : Stream[F, Delta] = events
    .noneTerminate
    .zipWithScan1(Ending())((ending, event) => event.fold(ending)(ending.after))
    .flatMap:
      case (Some(event), _) => Stream.emits(textDelta(event).toList)
      case (None, ending)   => Stream.emits(ending.end)

  /** Refuses an empty conversation, and one carrying media other than pictures. */
  override def refuseUnsendable
    (
      model: Model,
      conversation: Conversation,
      settings: Settings,
    )
    : Either[PromptError, Unit] = JsonHttp
    .refuseUnanswerable(provider, conversation)
    .flatMap(_ =>
      JsonHttp.refuseUnsupportedMedia(provider, conversation, media),
    )

  /**
    * The models whose reasoning effort goes no higher than `high`. OpenAI's
    * newer models also take `xhigh` and `max`, and an unlisted model is taken
    * to be as new.
    */
  private val cappedAtHigh: Set[Model] = Set(Model.Gpt5, Model.Gpt5Mini)

  private def reasoning(model: Model, effort: Effort): String = effort match
    case Effort.Low    => "low"
    case Effort.Medium => "medium"
    case Effort.High   => "high"
    case Effort.Max    => if cappedAtHigh.contains(model) then "high" else "max"

  override def body
    (
      model: Model,
      conversation: Conversation,
      settings: Settings,
    )
    : Json =
    val messages = conversation.instructions.map(text("system", _)).toList ++
      conversation.messages.flatMap(message)
    JsonHttp.objectOf(
      "model"                 -> model.id.asJson,
      "max_completion_tokens" -> settings.maxTokens.asJson,
      "temperature"           -> settings.temperature.asJson,
      "top_p"                 -> settings.topP.asJson,
      "stop"                  -> JsonHttp.unlessEmpty(settings.stopSequences),
      "tools"                 -> JsonHttp.unlessEmpty(settings.tools.map(tool)),
      "reasoning_effort" -> settings.effort.map(reasoning(model, _)).asJson,
      "stream"           -> Option.when(settings.streamed)(true).asJson,
      // Usage is withheld from a stream unless asked for.
      "stream_options" ->
        Option
          .when(settings.streamed)(Json.obj("include_usage" -> true.asJson))
          .asJson,
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

  /**
    * Serialises a message, each tool result as a message of its own. OpenAI
    * caches long prefixes by itself, so breakpoints are dropped.
    */
  private def message(message: Message): List[Json] =
    message.withoutBreakpoints match
      case Message.User(parts)  => List(written("user", parts))
      case Message.Model(parts) => List(written("assistant", parts))
      case Message.Tool(parts)  => parts.collect:
          case result: MessagePart.ToolResult => this.result(result)

  private def result(result: MessagePart.ToolResult): Json = Json.obj(
    "role"         -> "tool".asJson,
    "tool_call_id" -> result.callId.asJson,
    "content"      -> result.output.asJson,
  )

  /** Serialises a message of the parts, its tool calls apart. */
  private def written(role: String, parts: List[MessagePart]): Json =
    val calls = parts.collect:
      case call: MessagePart.ToolCall => call
    JsonHttp.objectOf(
      "role"    -> role.asJson,
      "content" ->
        content(parts.filterNot(_.isInstanceOf[MessagePart.ToolCall])),
      "tool_calls" -> Option.when(calls.nonEmpty)(calls.map(call)).asJson,
    )

  /** Serialises what a message says, separated once its tools are out. */
  private def content(parts: List[MessagePart]): Json =
    val sent = Message.separated(parts)
    if sent.isEmpty then Json.Null
    else JsonHttp.plain(sent).fold(sent.flatMap(part).asJson)(_.asJson)

  /** Serialises a tool call, whose arguments OpenAI takes as a string. */
  private def call(call: MessagePart.ToolCall): Json = Json.obj(
    "id"       -> call.id.asJson,
    "type"     -> "function".asJson,
    "function" -> Json.obj(
      "name"      -> call.tool.asJson,
      "arguments" -> call.arguments.asJson,
    ),
  )

  private def tool(tool: Tool): Json = Json.obj(
    "type"     -> "function".asJson,
    "function" -> JsonHttp.declared(tool, "parameters"),
  )

  private def part(part: MessagePart): Option[Json] = part match
    case MessagePart.Text(text) => Some(Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      ))
    case MessagePart.Media(mediaType, data) => Some(Json.obj(
        "type"      -> "image_url".asJson,
        "image_url" ->
          Json.obj("url" -> s"data:${ mediaType.name };base64,$data".asJson),
      ))
    case _: MessagePart.ToolCall | _: MessagePart.ToolResult | MessagePart
        .CacheBreakpoint => None

  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "stop",
    "length",
    toolCall = Some("tool_calls"),
  )

  private def textDelta(event: Json): Option[Delta] = event
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

  final case class FunctionCall(name: String, arguments: Option[String])
    derives Decoder

  final case class ToolCall(id: String, function: FunctionCall) derives Decoder:

    def toPart: MessagePart.ToolCall = MessagePart.ToolCall(
      id,
      function.name,
      function.arguments.filter(_.nonEmpty).getOrElse("{}"),
    )

  final case class ResponseMessage
    (
      content: Option[String],
      toolCalls: Option[List[ToolCall]],
    )

  object ResponseMessage:

    given Decoder[ResponseMessage] =
      Decoder.forProduct2("content", "tool_calls")(ResponseMessage.apply)

  final case class Choice
    (
      message: ResponseMessage,
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

    def toReply: Either[ApiError, PromptResponse] = choices
      .headOption
      .toRight(ApiError.Malformed(
        ModelProvider.OpenAi.displayName,
        "no choices",
      ))
      .map: choice =>
        PromptResponse(
          text = choice.message.content.getOrElse(""),
          stopReason = stopReason(choice.finishReason),
          usage = usage.fold(Usage())(_.toUsage),
          toolCalls =
            choice.message.toolCalls.getOrElse(List.empty).map(_.toPart),
        )
