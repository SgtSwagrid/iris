package com.alecdorrington.iris
package anthropic

import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.model.{Header, StatusCode}

/**
  * The provider of the [Anthropic Messages
  * API](https://docs.anthropic.com/en/api/messages).
  */
object Anthropic extends Provider:

  override val displayName: String = "Anthropic"

  override val origin: String = "https://api.anthropic.com"

  override def defaultModel: AnthropicModel = AnthropicModel.ClaudeSonnet5_5

  override def model(id: String): Option[AnthropicModel] = AnthropicModel.of(id)

  override def listed: List[AnthropicModel] = AnthropicModel.listed

  override val keyVariables: List[String] = List("ANTHROPIC_API_KEY")

  override private[iris] def path(model: ProviderModel): List[String] =
    List("v1", "messages")

  override private[iris] def credentials(key: String): Seq[Header] = Seq(
    Header("x-api-key", key),
    Header("anthropic-version", "2023-06-01"),
  ) ++ Option.when(Platform.browser)(
    // Anthropic refuses a request that carries a page's origin unless it says
    // that it means to send it from there.
    Header(
      "anthropic-dangerous-direct-browser-access",
      "true",
    ),
  )

  /**
    * The reply a stream's events make up. Its first event counts the request,
    * and the stop reason's the reply, so the counts of the first are kept until
    * then, as is each tool call until its block ends.
    */
  override private[iris] def deltas[F[_] : RaiseThrowable]
    (events: Stream[F, Json])
    : Stream[F, ProviderEvent] = events
    .mapAccumulate(Streamed())(_.read(_))
    .flatMap((_, deltas) => Stream.emits(deltas))
    .rethrow

  /** Anthropic writes the cache without replying when allowed no tokens. */
  override private[iris] val leastMaxTokens: Int = 0

  /**
    * As `retry-after` says, else as late as the reset of each limit spent (its
    * `anthropic-ratelimit-*-remaining` header `0`), which its `*-reset` header
    * gives as an RFC 3339 time, as
    * https://platform.claude.com/docs/en/api/rate-limits says.
    */
  override private[iris] def retryAfter
    (
      headers: Seq[Header],
      detail: String,
      now: FiniteDuration,
    )
    : Option[FiniteDuration] = super
    .retryAfter(headers, detail, now)
    .orElse(Refusal.spent(headers, resetOf, Refusal.until(now)))

  /** The header saying when the limit of a remaining header resets. */
  private def resetOf(remaining: String): Option[String] = Option.when(
    remaining.startsWith("anthropic-") && remaining.endsWith("-remaining"),
  )(remaining.stripSuffix("-remaining") + "-reset")

  /**
    * A tier's monthly spend cap reached, a `429` that waiting does not lift
    * until the month ends, which Anthropic tells by its `error_code`.
    */
  override private[iris] def lasting(detail: String): Boolean = parse(detail)
    .toOption
    .flatMap(
      _.hcursor
        .downField("error")
        .downField("details")
        .get[String]("error_code")
        .toOption,
    )
    .contains("enforced_spend_limit_reached")

  override private[iris] def opening(event: Json): Boolean = event
    .hcursor
    .get[String]("type")
    .exists(Set("message_start", "ping").contains)

  override private[iris] def failure(event: Json): Option[ApiError] = Option
    .when(event.hcursor.get[String]("type").contains("error"))(streamedError(
      event,
    ))

  /**
    * The refusal an `error` event streams once the reply has begun, as an
    * `overloaded_error` for what would otherwise be a `529`, read as a refusal
    * of the status its type stands for.
    */
  private def streamedError(event: Json): ApiError = Refusal.of(
    this,
    StatusCode(
      event
        .hcursor
        .downField("error")
        .get[String]("type")
        .toOption
        .flatMap(errorStatuses.get)
        .getOrElse(500),
    ),
    event.noSpaces,
    None,
    None,
  )

  /**
    * The status of each error type, as
    * https://platform.claude.com/docs/en/api/errors lists them.
    */
  private val errorStatuses: Map[String, Int] = Map(
    "invalid_request_error" -> 400,
    "authentication_error"  -> 401,
    "billing_error"         -> 402,
    "permission_error"      -> 403,
    "not_found_error"       -> 404,
    "request_too_large"     -> 413,
    "rate_limit_error"      -> 429,
    "api_error"             -> 500,
    "timeout_error"         -> 504,
    "overloaded_error"      -> 529,
  )

  /** As https://platform.claude.com/docs/en/about-claude/pricing gives them. */
  override private[iris] def rates
    (model: ProviderModel, at: FiniteDuration)
    : Option[Rates] = model match
    case AnthropicModel.ClaudeFable5_2 | AnthropicModel.ClaudeMythos5_2 =>
      Some(priced(8, 0.20, 40))
    case AnthropicModel.ClaudeFable5_1 | AnthropicModel.ClaudeMythos5_1 =>
      Some(priced(10, 0.25, 50))
    case AnthropicModel.ClaudeFable5 | AnthropicModel.ClaudeMythos5 =>
      Some(priced(10, 1, 50))
    case AnthropicModel.ClaudeOpus5_5 => Some(priced(4, 0.20, 20))
    case AnthropicModel.ClaudeOpus5 | AnthropicModel.ClaudeOpus4_8 |
      AnthropicModel.ClaudeOpus4_7 | AnthropicModel.ClaudeOpus4_6 =>
      Some(priced(5, 0.50, 25))
    case AnthropicModel.ClaudeSonnet5_5 => Some(priced(2, 0.10, 10))
    case AnthropicModel.ClaudeSonnet5   => Some(priced(2, 0.20, 10))
    case AnthropicModel.ClaudeSonnet4_6 => Some(priced(3, 0.30, 15))
    case AnthropicModel.ClaudeHaiku5_5  =>
      Some(priced(0.10, 0.01, 0.50).copy(above =
        Some(Rates.Above(100_000, priced(0.50, 0.05, 2.50))),
      ))
    case AnthropicModel.ClaudeHaiku4_5 => Some(priced(1, 0.10, 5))
    case _                             => None

  /**
    * Rates by input, cache hits and output, writing the cache costing a quarter
    * more than input, for the five minutes Iris caches for.
    */
  private def priced(input: Double, hit: Double, output: Double): Rates = Rates(
    uncached = Provider.usd(input),
    cacheRead = Provider.usd(hit),
    cacheWrite = Provider.usd(input) * BigDecimal(1.25),
    uncachedAudio = Provider.usd(input),
    cacheReadAudio = Provider.usd(hit),
    visible = Provider.usd(output),
    thinking = Provider.usd(output),
  )

  override private[iris] def counting
    (
      model: ProviderModel,
      conversation: Conversation,
    )
    : Option[Either[PromptError, Counting]] = Some(
    JsonHttp
      .refuseUnanswerable(this, conversation)
      .map(_ =>
        Counting(
          List("v1", "messages", "count_tokens"),
          countBody(model, conversation),
          Decoder[CountResponse].map(_.inputTokens),
        ),
      ),
  )

  /** Refuses an empty conversation, a refused prefill, or too many cache marks. */
  override private[iris] def refuseUnsendable
    (
      model: ProviderModel,
      conversation: Conversation,
      settings: Settings,
    )
    : Either[PromptError, Unit] =
    for
      _ <- JsonHttp.refuseUnanswerable(this, conversation)
      _ <- refusePrefill(conversation, model)
      _ <- refuseExcessBreakpoints(conversation)
      _ <- JsonHttp.refuseUnsupportedMedia(this, conversation, media)
      _ <- JsonHttp.refuseUnreadableArguments(this, conversation)
    yield ()

  /**
    * The models that still accept `temperature` and `top_p`. Anthropic's newer
    * models reject a request carrying them, and an unlisted model is taken to
    * be as new.
    */
  private val sampling: Set[ProviderModel] = Set(
    AnthropicModel.ClaudeOpus4_6,
    AnthropicModel.ClaudeSonnet4_6,
    AnthropicModel.ClaudeHaiku4_5,
  )

  /**
    * The models that still continue a conversation ending with the model's
    * message. The others, unlisted ones included, refuse it.
    */
  private val prefilling: Set[ProviderModel] =
    Set(AnthropicModel.ClaudeHaiku4_5)

  private[iris] def refusePrefill
    (
      conversation: Conversation,
      model: ProviderModel,
    )
    : Either[PromptError, Unit] = Either.cond(
    prefilling.contains(model) ||
    !conversation.messages.lastOption.exists(_.isInstanceOf[Message.Model]),
    (),
    PromptError.Unsendable(
      displayName,
      s"${ model.id } will not continue a message of its own",
    ),
  )

  /** The media Anthropic takes inline: the pictures Iris lists, and PDFs. */
  private[iris] val media: Set[MediaType] = Set(
    MediaType.Png,
    MediaType.Jpeg,
    MediaType.Gif,
    MediaType.Webp,
    MediaType.Pdf,
  )

  private[iris] val maxBreakpoints: Int = 4

  /**
    * Refuses a conversation whose breakpoints mark more blocks than Anthropic
    * keeps, but for those closing it, which mark a block only where there is
    * room.
    */
  private[iris] def refuseExcessBreakpoints
    (conversation: Conversation)
    : Either[PromptError, Unit] =
    val count = marks(
      conversation,
      laid(unclosed(conversation)),
    )
    Either.cond(
      count <= maxBreakpoints,
      (),
      PromptError.Unsendable(
        displayName,
        s"its cache breakpoints mark $count blocks, and at most $maxBreakpoints are kept",
      ),
    )

  /**
    * The blocks a conversation's parts, as placed, mark; those in a row mark
    * one.
    */
  private def marks
    (
      conversation: Conversation,
      placed: Placed,
    )
    : Int =
    val (cached, blocks) = placed
    val onSystem = if cached && conversation.instructions.nonEmpty then 1 else 0
    onSystem + blocks.flatten.count(_._2)

  /** Serialises a conversation to count, which takes no `max_tokens`. */
  private[iris] def countBody
    (
      model: ProviderModel,
      conversation: Conversation,
    )
    : Json =
    val (system, messages) = serialised(conversation)
    JsonHttp.objectOf(
      "model"    -> model.id.asJson,
      "system"   -> system,
      "messages" -> messages,
    )

  private[iris] final case class CountResponse(inputTokens: Int)

  private[iris] object CountResponse:

    given Decoder[CountResponse] =
      Decoder.forProduct1("input_tokens")(CountResponse.apply)

  override private[iris] def body
    (
      model: ProviderModel,
      conversation: Conversation,
      settings: Settings,
    )
    : Json =
    val samples            = sampling.contains(model)
    val (system, messages) = serialised(conversation)
    JsonHttp.objectOf(
      "model"          -> model.id.asJson,
      "max_tokens"     -> settings.maxTokens.asJson,
      "temperature"    -> settings.temperature.filter(_ => samples).asJson,
      "top_p"          -> settings.topP.filter(_ => samples).asJson,
      "stop_sequences" -> JsonHttp.unlessEmpty(settings.stopSequences),
      "tools"          -> JsonHttp.unlessEmpty(
        settings.tools.map(JsonHttp.declared(_, "input_schema")),
      ),
      "output_config" -> settings.effort.map(output).asJson,
      "stream"        -> true.asJson,
      "system"        -> system,
      "messages"      -> messages,
    )

  private def output(effort: Effort): Json =
    Json.obj("effort" -> effort.toString.toLowerCase.asJson)

  /** A part, and whether a breakpoint marks it as the end of a prefix. */
  private type Marked = (MessagePart, Boolean)

  /** Whether a breakpoint marks the system message, and the messages' blocks. */
  private type Placed = (Boolean, Vector[Vector[Marked]])

  private def serialised(conversation: Conversation): (Json, Json) =
    val (cached, blocks) = placed(conversation)
    (
      conversation.instructions.map(system(_, cached)).asJson,
      turns(conversation).map(_._1).zip(blocks).map(message).asJson,
    )

  /** The conversation's turns, the tools' results the user's. */
  private def turns(conversation: Conversation): List[JsonHttp.Turn] = JsonHttp
    .turns(conversation.messages.map(message => role(message) -> message.parts))

  /**
    * Places a conversation's parts. Breakpoints closing it, as an ask puts
    * after each round of tools, mark its last block only where the others leave
    * room, so that they never crowd out the host's own.
    */
  private def placed(conversation: Conversation): Placed =
    val open = laid(unclosed(conversation))
    if closing(conversation) && marks(conversation, open) < maxBreakpoints then
      place(open, MessagePart.CacheBreakpoint)
    else open

  private def laid(conversation: Conversation): Placed = turns(conversation)
    .foldLeft((false, Vector.empty[Vector[Marked]])):
      case ((cached, blocks), (_, parts)) => Message
          .separated(parts)
          .foldLeft((cached, blocks :+ Vector.empty[Marked]))(place)

  /** Whether breakpoints end the conversation's last message. */
  private def closing(conversation: Conversation): Boolean = conversation
    .messages
    .lastOption
    .exists(_.parts.lastOption.contains(MessagePart.CacheBreakpoint))

  /** The conversation without the breakpoints ending it. */
  private def unclosed(conversation: Conversation): Conversation = conversation
    .copy(messages =
      conversation
        .messages
        .lastOption
        .fold(Nil)(last => conversation.messages.init :+ last.unclosed),
    )

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

  /**
    * The role of a message's owner in Anthropic's API, where the tools' results
    * are the user's, Anthropic running a user's message on from them.
    */
  private def role(message: Message): String = message match
    case _: Message.Model                  => "assistant"
    case _: Message.User | _: Message.Tool => "user"

  private def message(role: String, blocks: Vector[Marked]): Json = Json.obj(
    "role"    -> role.asJson,
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
        "input" -> parse(arguments).getOrElse(Json.obj()),
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
  private[iris] final case class Streamed
    (
      start: TokenCounts = TokenCounts.unreported,
      calls: Map[Int, PartialToolCall] = Map.empty,
    ):

    /** This, after an event, and the deltas the event makes, or why not. */
    def read(event: Json): (Streamed, List[Either[ApiError, ProviderEvent]]) =
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
                  .map(text => Right(ProviderEvent.Text(text)))
                  .toList
        case Some("content_block_stop") => index.fold(this -> Nil)(at =>
            copy(calls = calls - at) ->
              calls.get(at).map(call => Right(call.whole)).toList,
          )
        case Some("error")         => this -> List(Left(streamedError(event)))
        case Some("message_delta") => this -> List(Right(ProviderEvent.End(
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

  private[iris] final case class Block
    (
      kind: String,
      id: Option[String],
      name: Option[String],
    ):

    /** The call a block begins, whose arguments are still to come. */
    def calling: Option[PartialToolCall] =
      for
        _    <- Option.when(kind == "tool_use")(())
        id   <- id
        name <- name
      yield PartialToolCall(id, name)

  private[iris] object Block:

    given Decoder[Block] =
      Decoder.forProduct3("type", "id", "name")(Block.apply)

  private[iris] final case class OutputBreakdown(thinkingTokens: Option[Int])

  private[iris] object OutputBreakdown:

    given Decoder[OutputBreakdown] =
      Decoder.forProduct1("thinking_tokens")(OutputBreakdown.apply)

  /**
    * The token counts of a response. Anthropic counts cache writes and reads
    * apart from the input, as [[Usage]] does, but its thinking within the
    * output.
    */
  private[iris] final case class TokenCounts
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

  private[iris] object TokenCounts:

    val unreported: TokenCounts = TokenCounts(None, None, None, None, None)

    given Decoder[TokenCounts] = Decoder.forProduct5(
      "input_tokens",
      "output_tokens",
      "cache_creation_input_tokens",
      "cache_read_input_tokens",
      "output_tokens_details",
    )(TokenCounts.apply)
