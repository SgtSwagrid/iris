package com.alecdorrington.iris

import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.model.{Header, Uri}

/**
  * The [[ProviderApi]] of the [Anthropic Messages
  * API](https://docs.anthropic.com/en/api/messages).
  */
private[iris] object AnthropicApi extends ProviderApi:

  override val provider: ModelProvider = ModelProvider.Anthropic

  override def endpoint
    (
      config: ModelConfig,
      options: ModelRequest.Options,
      streamed: Boolean,
    )
    : Either[ModelError, Uri] = messagesEndpoint(config)

  override def credentials(config: ModelConfig): Seq[Header] = Seq(
    Header("x-api-key", config.apiKey),
    Header("anthropic-version", "2023-06-01"),
  ) ++ Option.when(Platform.browser)(
    // Anthropic refuses a request that carries a page's origin unless it says
    // that it means to send it from there.
    Header(
      "anthropic-dangerous-direct-browser-access",
      "true",
    ),
  )

  override val wholeResponse: Decoder[Either[ModelError, ModelResponse]] =
    Decoder[Response].map(_.toReply)

  /**
    * The reply a stream's events make up. Its first event counts the request,
    * and the stop reason's the reply, so the counts of the first are kept until
    * then, as is each tool call until its block ends.
    */
  override def deltas[F[_] : RaiseThrowable]
    (events: Stream[F, Json])
    : Stream[F, Delta] = events
    .mapAccumulate(Streamed())(_.read(_))
    .flatMap((_, deltas) => Stream.emits(deltas))
    .rethrow

  /** Anthropic writes the cache without replying when allowed no tokens. */
  override val leastMaxTokens: Int = 0

  /** As https://platform.claude.com/docs/en/about-claude/pricing gives them. */
  override def rates(model: Model, at: FiniteDuration): Option[Rates] =
    model match
      case Model.ClaudeFable5_1 | Model.ClaudeMythos5_1 =>
        Some(priced(10, 0.25, 50))
      case Model.ClaudeFable5 | Model.ClaudeMythos5 => Some(priced(10, 1, 50))
      case Model.ClaudeOpus5_5                      => Some(priced(4, 0.20, 20))
      case Model.ClaudeOpus5 | Model.ClaudeOpus4_8 | Model.ClaudeOpus4_7 | Model
          .ClaudeOpus4_6 => Some(priced(5, 0.50, 25))
      case Model.ClaudeSonnet5_5 => Some(priced(2, 0.10, 10))
      case Model.ClaudeSonnet5   => Some(priced(2, 0.20, 10))
      case Model.ClaudeSonnet4_6 => Some(priced(3, 0.30, 15))
      case Model.ClaudeHaiku5_5  => Some(priced(0.10, 0.01, 0.50).copy(above =
          Some(Rates.Above(100_000, priced(0.50, 0.05, 2.50))),
        ))
      case Model.ClaudeHaiku4_5 => Some(priced(1, 0.10, 5))
      case _                    => None

  /**
    * Rates by input, cache hits and output, writing the cache costing a quarter
    * more than input, for the five minutes Iris caches for.
    */
  private def priced(input: Double, hit: Double, output: Double): Rates = Rates(
    withoutBreakpoints = ProviderApi.usd(input),
    cacheRead = ProviderApi.usd(hit),
    cacheWrite = ProviderApi.usd(input) * BigDecimal(1.25),
    uncachedAudio = ProviderApi.usd(input),
    cacheReadAudio = ProviderApi.usd(hit),
    visible = ProviderApi.usd(output),
    thinking = ProviderApi.usd(output),
  )

  override def counting
    (
      config: ModelConfig,
      chat: Chat,
      options: ModelRequest.Options,
    )
    : Either[ModelError, Counting] =
    for
      _        <- JsonHttp.refuseUnanswerable(provider, chat)
      endpoint <- messagesEndpoint(config, "count_tokens")
    yield Counting(
      endpoint,
      countBody(config, chat),
      Decoder[CountResponse].map(_.inputTokens),
    )

  /** The Messages API's endpoint, or one beneath it. */
  private def messagesEndpoint
    (config: ModelConfig, path: String*)
    : Either[ModelError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    ("v1" +: "messages" +: path)*,
  )

  /** Refuses an empty chat, a refused prefill, or too many cache marks. */
  override def refuseUnsendable
    (
      config: ModelConfig,
      chat: Chat,
      options: ModelRequest.Options,
    )
    : Either[ModelError, Unit] =
    for
      _ <- JsonHttp.refuseUnanswerable(provider, chat)
      _ <- refusePrefill(chat, config.model)
      _ <- refuseExcessBreakpoints(chat)
      _ <- JsonHttp.refuseUnsupportedMedia(provider, chat, media)
    yield ()

  /**
    * The models that still accept `temperature` and `top_p`. Anthropic's newer
    * models reject a request carrying them, and an unlisted model is taken to
    * be as new.
    */
  private val sampling: Set[Model] = Set(
    Model.ClaudeOpus4_6,
    Model.ClaudeSonnet4_6,
    Model.ClaudeHaiku4_5,
  )

  /**
    * The models that still continue a chat ending with the model's message. The
    * others, unlisted ones included, refuse it.
    */
  private val prefilling: Set[Model] = Set(Model.ClaudeHaiku4_5)

  def refusePrefill(chat: Chat, model: Model): Either[ModelError, Unit] = Either
    .cond(
      prefilling.contains(model) ||
      !chat.messages.lastOption.exists(_.role == Role.Model),
      (),
      ModelError.Unsendable(
        provider.displayName,
        s"${ model.id } will not continue a message of its own",
      ),
    )

  /** The media Anthropic takes inline: the pictures Iris lists, and PDFs. */
  val media: Set[MediaType] = Set(
    MediaType.Png,
    MediaType.Jpeg,
    MediaType.Gif,
    MediaType.Webp,
    MediaType.Pdf,
  )

  val maxBreakpoints: Int = 4

  def refuseExcessBreakpoints(chat: Chat): Either[ModelError, Unit] =
    val count = marks(chat)
    Either.cond(
      count <= maxBreakpoints,
      (),
      ModelError.Unsendable(
        provider.displayName,
        s"its cache breakpoints mark $count blocks, and at most $maxBreakpoints are kept",
      ),
    )

  /** The blocks a chat's breakpoints mark; breakpoints in a row mark one. */
  private def marks(chat: Chat): Int =
    val (cached, blocks) = placed(chat)
    val onSystem         = if cached && chat.instructions.nonEmpty then 1 else 0
    onSystem + blocks.flatten.count(_._2)

  /** Serialises a chat to count, which takes no `max_tokens`. */
  def countBody(config: ModelConfig, chat: Chat): String =
    val (system, messages) = conversation(chat)
    JsonHttp.body(
      "model"    -> config.model.id.asJson,
      "system"   -> system,
      "messages" -> messages,
    )

  final case class CountResponse(inputTokens: Int)

  object CountResponse:

    given Decoder[CountResponse] =
      Decoder.forProduct1("input_tokens")(CountResponse.apply)

  override def body
    (
      config: ModelConfig,
      chat: Chat,
      options: ModelRequest.Options,
      streamed: Boolean,
    )
    : String =
    val samples            = sampling.contains(config.model)
    val (system, messages) = conversation(chat)
    JsonHttp.body(
      "model"          -> config.model.id.asJson,
      "max_tokens"     -> config.limit(options).asJson,
      "temperature"    -> options.temperature.filter(_ => samples).asJson,
      "top_p"          -> options.topP.filter(_ => samples).asJson,
      "stop_sequences" -> JsonHttp.unlessEmpty(options.stopSequences),
      "tools"          -> JsonHttp.unlessEmpty(
        options.tools.map(JsonHttp.declared(_, "input_schema")),
      ),
      "output_config" -> options.effort.map(output).asJson,
      "stream"        -> Option.when(streamed)(true).asJson,
      "system"        -> system,
      "messages"      -> messages,
    )

  private def output(effort: Effort): Json =
    Json.obj("effort" -> effort.toString.toLowerCase.asJson)

  /** A part, and whether a breakpoint marks it as the end of a prefix. */
  private type Marked = (MessagePart, Boolean)

  /** Whether a breakpoint marks the system message, and the messages' blocks. */
  private type Placed = (Boolean, Vector[Vector[Marked]])

  private def conversation(chat: Chat): (Json, Json) =
    val (cached, blocks) = placed(chat)
    (
      chat.instructions.map(system(_, cached)).asJson,
      chat
        .messages
        .zip(blocks)
        .map((message, blocks) => this.message(message.role, blocks))
        .asJson,
    )

  private def placed(chat: Chat): Placed = chat
    .messages
    .foldLeft((false, Vector.empty[Vector[Marked]])):
      case ((cached, blocks), message) => Message
          .separated(message.parts)
          .foldLeft((cached, blocks :+ Vector.empty[Marked]))(place)

  /**
    * Places one more part. A breakpoint marks the last block before it, in any
    * earlier message, or else the system message.
    */
  private def place(placed: Placed, part: MessagePart): Placed =
    val (cached, blocks) = placed
    part match
      case MessagePart.CacheBreakpoint =>
        blocks.lastIndexWhere(_.nonEmpty) match
          case -1 => (true, blocks)
          case at => (cached, blocks.updated(at, lastMarked(blocks(at))))
      case _ => (cached, blocks.init :+ (blocks.last :+ (part -> false)))

  private def lastMarked(blocks: Vector[Marked]): Vector[Marked] =
    blocks.init :+ (blocks.last._1 -> true)

  private def system(text: String, cached: Boolean): Json =
    if cached then Json.arr(marked(MessagePart.Text(text) -> true))
    else text.asJson

  /** A role's name in Anthropic's API. */
  private def named(role: Role): String = role match
    case Role.User  => "user"
    case Role.Model => "assistant"

  private def message(role: Role, blocks: Vector[Marked]): Json = Json.obj(
    "role"    -> named(role).asJson,
    "content" ->
      Option
        .unless(blocks.exists(_._2))(blocks.map(_._1))
        .flatMap(JsonHttp.plain)
        .fold(blocks.map(marked).asJson)(_.asJson),
  )

  private def marked(block: Marked): Json =
    val (content, cached) = block
    if cached then
      part(content).deepMerge(Json.obj(
        "cache_control" -> Json.obj("type" -> "ephemeral".asJson),
      ))
    else part(content)

  private def part(part: MessagePart): Json = part match
    case MessagePart.Text(text) => Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      )
    case MessagePart.Media(mediaType, data) => Json.obj(
        "type" -> (if mediaType == MediaType.Pdf then "document" else "image")
          .asJson,
        "source" -> Json.obj(
          "type"       -> "base64".asJson,
          "media_type" -> mediaType.name.asJson,
          "data"       -> data.asJson,
        ),
      )
    case MessagePart.ToolCall(id, tool, arguments, _) => Json.obj(
        "type"  -> "tool_use".asJson,
        "id"    -> id.asJson,
        "name"  -> tool.asJson,
        "input" -> arguments,
      )
    case MessagePart.ToolResult(callId, _, output) => Json.obj(
        "type"        -> "tool_result".asJson,
        "tool_use_id" -> callId.asJson,
        "content"     -> output.asJson,
      )
    // Breakpoints are marks on the block before them, removed by now.
    case MessagePart.CacheBreakpoint => Json.obj()

  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "end_turn",
    "max_tokens",
    Some("stop_sequence"),
    Some("tool_use"),
  )

  /**
    * What one streamed event says, given the counts the stream started with,
    * which the stop reason's event may leave out.
    */
  /**
    * What a stream has said so far that its later events need: the counts it
    * started with, and the tool calls not yet ended, by their blocks' indices.
    */
  final case class Streamed
    (
      start: TokenCounts = TokenCounts.unreported,
      calls: Map[Int, Calling] = Map.empty,
    ):

    /** This, after an event, and the deltas the event makes, or why not. */
    def read(event: Json): (Streamed, List[Either[ModelError, Delta]]) =
      val cursor = event.hcursor
      val index  = cursor.get[Int]("index").toOption
      cursor.get[String]("type").toOption match
        case Some("message_start") => copy(start =
            cursor
              .downField("message")
              .get[TokenCounts]("usage")
              .getOrElse(TokenCounts.unreported),
          ) -> Nil
        case Some("content_block_start") => cursor
            .downField("content_block")
            .as[Block]
            .toOption
            .flatMap(_.calling)
            .zip(index)
            .fold(this)((call, at) => copy(calls = calls + (at -> call))) -> Nil
        case Some("content_block_delta") =>
          val delta = cursor.downField("delta")
          delta.get[String]("partial_json").toOption.zip(index) match
            case Some((fragment, at)) =>
              copy(calls = calls.updatedWith(at)(_.map(_.more(fragment)))) ->
                Nil
            case None => this ->
                delta
                  .get[String]("text")
                  .toOption
                  .map(text => Right(Delta.Text(text)))
                  .toList
        case Some("content_block_stop") => index.fold(this -> Nil)(at =>
            copy(calls = calls - at) ->
              calls.get(at).map(_.whole(provider)).toList,
          )
        case Some("message_delta") => this -> List(Right(Delta.End(
            stopReason(
              cursor.downField("delta").get[String]("stop_reason").toOption,
            ),
            cursor
              .get[TokenCounts]("usage")
              .getOrElse(TokenCounts.unreported)
              .over(start)
              .toUsage,
          )))
        case _ => this -> Nil

  final case class Block
    (
      kind: String,
      text: Option[String],
      id: Option[String],
      name: Option[String],
      input: Option[Json],
    ):

    def visibleText: Option[String] = text.filter(_ => kind == "text")

    def toolCall: Option[MessagePart.ToolCall] =
      for
        _    <- Option.when(kind == "tool_use")(())
        id   <- id
        name <- name
      yield MessagePart.ToolCall(id, name, input.getOrElse(Json.obj()))

    /** The call a block begins, whose arguments are still to come. */
    def calling: Option[Calling] =
      for
        _    <- Option.when(kind == "tool_use")(())
        id   <- id
        name <- name
      yield Calling(id, name)

  object Block:

    given Decoder[Block] =
      Decoder.forProduct5("type", "text", "id", "name", "input")(Block.apply)

  final case class OutputBreakdown(thinkingTokens: Option[Int])

  object OutputBreakdown:

    given Decoder[OutputBreakdown] =
      Decoder.forProduct1("thinking_tokens")(OutputBreakdown.apply)

  /**
    * The token counts of a response. Anthropic counts cache writes and reads
    * apart from the input, as [[Usage]] does, but its thinking within the
    * output.
    */
  final case class TokenCounts
    (
      inputTokens: Option[Int],
      outputTokens: Option[Int],
      cacheWrites: Option[Int],
      cacheReads: Option[Int],
      outputBreakdown: Option[OutputBreakdown],
    ):

    def toUsage: Usage =
      val output   = outputTokens.getOrElse(0)
      val thinking = outputBreakdown
        .flatMap(_.thinkingTokens)
        .getOrElse(0)
        .min(output)
      Usage(
        uncachedTokens = inputTokens.getOrElse(0),
        cacheReadTokens = cacheReads.getOrElse(0),
        cacheWriteTokens = cacheWrites.getOrElse(0),
        visibleTokens = output - thinking,
        thinkingTokens = thinking,
      )

    /** These counts, with each they leave out taken from earlier ones. */
    def over(earlier: TokenCounts): TokenCounts = TokenCounts(
      inputTokens.orElse(earlier.inputTokens),
      outputTokens.orElse(earlier.outputTokens),
      cacheWrites.orElse(earlier.cacheWrites),
      cacheReads.orElse(earlier.cacheReads),
      outputBreakdown.orElse(earlier.outputBreakdown),
    )

  object TokenCounts:

    val unreported: TokenCounts = TokenCounts(None, None, None, None, None)

    given Decoder[TokenCounts] = Decoder.forProduct5(
      "input_tokens",
      "output_tokens",
      "cache_creation_input_tokens",
      "cache_read_input_tokens",
      "output_tokens_details",
    )(TokenCounts.apply)

  final case class Response
    (
      content: List[Block],
      stopReason: Option[String],
      usage: Option[TokenCounts],
    ):

    /** This response as a reply, which may be empty, as on a refusal. */
    def toReply: Either[ModelError, ModelResponse] = Right(ModelResponse(
      text = content.flatMap(_.visibleText).mkString,
      stopReason = AnthropicApi.stopReason(stopReason),
      usage = usage.fold(Usage())(_.toUsage),
      toolCalls = content.flatMap(_.toolCall),
    ))

  object Response:

    given Decoder[Response] =
      Decoder.forProduct3("content", "stop_reason", "usage")(Response.apply)
