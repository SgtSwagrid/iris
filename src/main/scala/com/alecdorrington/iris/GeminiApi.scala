package com.alecdorrington.iris

import fs2.{RaiseThrowable, Stream}
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.model.{Header, Uri}

/**
  * The [[ProviderApi]] of the [Google Gemini
  * API](https://ai.google.dev/api/generate-content).
  */
private[iris] object GeminiApi extends ProviderApi:

  override val provider: LlmProvider = LlmProvider.Gemini

  /** Gemini streams a reply from an endpoint of its own. */
  override def endpoint
    (
      config: LlmConfig,
      options: ReplyOptions,
      streamed: Boolean,
    )
    : Either[LlmError, Uri] =
    if streamed then streaming(config, config.model)
    else endpoint(config, config.model)

  override def credentials(config: LlmConfig): Seq[Header] =
    Seq(Header("x-goog-api-key", config.apiKey))

  override val replies: Decoder[Either[LlmError, Reply]] = Decoder[Response]
    .map(_.toReply)

  /**
    * As https://ai.google.dev/gemini-api/docs/pricing gives them. Google names
    * no time zone for its price changes, so they are taken to come at midnight
    * UTC, nor says whether cached tokens count towards Gemini 2.5 Pro's 200K,
    * so they are taken to, as [[Rates.Above]] says.
    */
  override def rates(model: LlmModel, at: FiniteDuration): Option[Rates] =
    model match
      case LlmModel.Gemini4Argon => Some(
          if at < ProviderApi.utc(2026, 12, 1) then
            priced(2.00, 0.10, 2.00, 0.10, 10.00)
          else priced(4.00, 0.20, 4.00, 0.20, 20.00),
        )
      case LlmModel.Gemini3_8Flash | LlmModel.Gemini3_6Flash => Some(
          if at < ProviderApi.utc(2027, 1, 1) then
            priced(0.75, 0.075, 0.75, 0.075, 3.75)
          else priced(1.50, 0.15, 1.50, 0.15, 7.50),
        )
      case LlmModel.Gemini3_5FlashLite =>
        Some(priced(0.30, 0.03, 0.30, 0.03, 2.50))
      case LlmModel.Gemini3_1FlashLite =>
        Some(priced(0.25, 0.025, 0.50, 0.05, 1.50))
      case LlmModel.Gemini2_5Pro => Some(
          priced(1.25, 0.125, 1.25, 0.125, 10.00).copy(above =
            Some(Rates.Above(
              200_000,
              priced(2.50, 0.25, 2.50, 0.25, 15.00),
            )),
          ),
        )
      case LlmModel.Gemini2_5Flash => Some(priced(0.30, 0.03, 1.00, 0.10, 2.50))
      case LlmModel.Gemini2_5FlashLite =>
        Some(priced(0.10, 0.01, 0.30, 0.03, 0.40))
      case _ => None

  /**
    * Rates by input, its cache reads, audio, its cache reads, and output, as
    * Gemini lists them. Writing its cache costs no more than input.
    */
  private def priced
    (
      input: Double,
      cached: Double,
      audio: Double,
      cachedAudio: Double,
      output: Double,
    )
    : Rates = Rates(
    uncached = ProviderApi.usd(input),
    cacheRead = ProviderApi.usd(cached),
    cacheWrite = ProviderApi.usd(input),
    uncachedAudio = ProviderApi.usd(audio),
    cacheReadAudio = ProviderApi.usd(cachedAudio),
    visible = ProviderApi.usd(output),
    thinking = ProviderApi.usd(output),
  )

  override def counting
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
    )
    : Either[LlmError, Counting] =
    for
      _        <- JsonHttp.answerable(provider, chat)
      endpoint <- calling(config, config.model, "countTokens")
    yield Counting(
      endpoint,
      countBody(chat),
      Decoder[CountResponse].map(_.totalTokens),
    )

  /** The URL of a model's method, encoding the model so it cannot add a path. */
  private def calling
    (
      config: LlmConfig,
      model: LlmModel,
      method: String,
    )
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1beta",
    "models",
    s"${ model.id }:$method",
  )

  def endpoint(config: LlmConfig, model: LlmModel): Either[LlmError, Uri] =
    calling(config, model, "generateContent")

  def streaming(config: LlmConfig, model: LlmModel): Either[LlmError, Uri] =
    calling(config, model, "streamGenerateContent").map(
      _.addParam("alt", "sse"),
    )

  /**
    * Serialises a chat to count. Counting takes no `system_instruction`, so a
    * system message is counted as a user message.
    */
  def countBody(chat: Chat): String = JsonHttp.body(
    "contents" ->
      (chat.system.map(Message(Role.User, _)).toList ++ chat.messages)
        .map(content)
        .asJson,
  )

  final case class CountResponse(totalTokens: Int) derives Decoder

  /** Serialises a chat alike whether streamed. */
  override def body
    (
      config: LlmConfig,
      chat: Chat,
      options: ReplyOptions,
      streamed: Boolean,
    )
    : String = JsonHttp.body(
    "system_instruction" ->
      chat.system.map(text => Json.obj("parts" -> Json.arr(part(text)))).asJson,
    "tools"            -> tools(options),
    "contents"         -> chat.messages.map(content).asJson,
    "generationConfig" -> JsonHttp.objectOf(
      "maxOutputTokens" -> config.limit(options).asJson,
      "temperature"     -> options.temperature.asJson,
      "topP"            -> options.topP.asJson,
      "stopSequences"   -> JsonHttp.unlessEmpty(options.stopSequences),
      "thinkingConfig"  -> options.effort.map(thinking).asJson,
    ),
  )

  private def thinking(effort: Effort): Json =
    Json.obj("thinkingBudget" -> budget(effort).asJson)

  /** The thinking budget for an effort, which every thinking model accepts. */
  private def budget(effort: Effort): Int = effort match
    case Effort.Low    => 1_024
    case Effort.Medium => 4_096
    case Effort.High   => 16_384
    case Effort.Max    => 24_576

  /** Serialises a message, dropping breakpoints, as Gemini caches by itself. */
  private def content(message: Message): Json = Json.obj(
    "role"  -> role(message.role).asJson,
    "parts" -> Message.separated(message.uncached.content).map(part).asJson,
  )

  private def part(text: String): Json = Json.obj("text" -> text.asJson)

  /**
    * The reply a stream's events make up. A reply that asked for a tool in any
    * event stopped for it, as Gemini gives no finish reason for that.
    */
  override def reply[F[_] : RaiseThrowable]
    (events: Stream[F, Json])
    : Stream[F, Delta] = events
    .zipWithScan1(false)(_ || asks(_))
    .flatMap((event, asked) => Stream.emits(deltas(event, asked)))

  private def asks(event: Json): Boolean =
    carried(event).exists(_.functionCall.nonEmpty)

  private def carried(event: Json): List[ReplyPart] = event
    .hcursor
    .downField("candidates")
    .downN(0)
    .downField("content")
    .get[List[ReplyPart]]("parts")
    .toOption
    .getOrElse(List.empty)

  private def deltas(event: Json, asked: Boolean): List[Delta] =
    val parts = carried(event)
    val said  = parts.flatMap(_.text).mkString
    List.concat(
      Option.when(said.nonEmpty)(Delta.Text(said)),
      parts.flatMap(_.functionCall).map(call => Delta.ToolCall(call.toPart)),
      event
        .hcursor
        .downField("candidates")
        .downN(0)
        .get[String]("finishReason")
        .toOption
        .map(reason =>
          Delta.End(
            stopped(asked, Some(reason)),
            event
              .hcursor
              .get[TokenCounts]("usageMetadata")
              .fold(_ => Usage(), _.toUsage),
          ),
        ),
    )

  /** Serialises the tools on offer, which Gemini takes under one entry. */
  private def tools(options: ReplyOptions): Json = Option
    .when(options.tools.nonEmpty)(List(Json.obj(
      "functionDeclarations" ->
        options.tools.map(JsonHttp.declared(_, "parameters")).asJson,
    )))
    .asJson

  private def part(content: Part): Json = content match
    case Part.Text(text)             => part(text)
    case Part.Media(mediaType, data) => Json.obj(
        "inline_data" -> Json.obj(
          "mime_type" -> mediaType.name.asJson,
          "data"      -> data.asJson,
        ),
      )
    case Part.ToolCall(_, tool, arguments) => Json.obj(
        "functionCall" -> Json.obj("name" -> tool.asJson, "args" -> arguments),
      )
    case Part.ToolResult(_, tool, output) => Json.obj(
        "functionResponse" -> Json.obj(
          "name"     -> tool.asJson,
          "response" -> Json.obj("result" -> output.asJson),
        ),
      )
    // Breakpoints are taken out of a message before its parts are serialised.
    case Part.CacheBreakpoint => Json.obj()

  private def role(role: Role): String = role match
    case Role.Assistant => "model"
    case other          => other.code

  private val stopReason: Option[String] => StopReason =
    StopReason.normalise("STOP", "MAX_TOKENS")

  /** Why a reply stopped; Gemini gives no finish reason for a tool call. */
  private def stopped(asked: Boolean, reason: Option[String]): StopReason =
    if asked then StopReason.ToolCall else stopReason(reason)

  /** A tool call, whose name also stands in for the id Gemini lacks. */
  final case class FunctionCall
    (name: String, args: Option[Json])
    derives Decoder:

    def toPart: Part.ToolCall =
      Part.ToolCall(name, name, args.getOrElse(Json.obj()))

  final case class ReplyPart
    (
      text: Option[String],
      functionCall: Option[FunctionCall],
    )
    derives Decoder

  final case class Content(parts: Option[List[ReplyPart]]) derives Decoder

  final case class Candidate
    (
      content: Option[Content],
      finishReason: Option[String],
    )
    derives Decoder

  final case class PromptFeedback(blockReason: Option[String]) derives Decoder

  private def parts(candidate: Candidate): List[ReplyPart] = candidate
    .content
    .flatMap(_.parts)
    .getOrElse(List.empty)

  private def text(candidate: Candidate): String = parts(candidate)
    .flatMap(_.text)
    .mkString

  private def toolCalls(candidate: Candidate): List[Part.ToolCall] =
    parts(candidate).flatMap(_.functionCall).map(_.toPart)

  /** The tokens of one kind of media, such as `AUDIO`. */
  final case class ModalityCount
    (
      modality: String,
      tokenCount: Option[Int],
    )
    derives Decoder

  /**
    * The token counts of a response, whose prompt tokens include cached ones,
    * each by kind of media too, and whose candidates' leave out thinking.
    * Gemini leaves out every count of `0`, and is not said always to break its
    * cache down by kind of media, so without that breakdown the cache is taken
    * to hold audio in the share the prompt does.
    */
  final case class TokenCounts
    (
      promptTokenCount: Option[Int],
      candidatesTokenCount: Option[Int],
      cachedContentTokenCount: Option[Int],
      thoughtsTokenCount: Option[Int],
      promptTokensDetails: Option[List[ModalityCount]],
      cacheTokensDetails: Option[List[ModalityCount]],
    )
    derives Decoder:

    def toUsage: Usage =
      val prompt      = promptTokenCount.getOrElse(0)
      val cached      = cachedContentTokenCount.getOrElse(0).min(prompt)
      val prompted    = promptTokensDetails.fold(0)(audio)
      val cachedAudio = cacheTokensDetails
        .fold(share(cached, prompted, prompt))(audio)
        .min(cached)
      val uncachedAudio = (prompted - cachedAudio).min(prompt - cached).max(0)
      Usage(
        uncachedTokens = prompt - cached - uncachedAudio,
        cacheReadTokens = cached - cachedAudio,
        uncachedAudioTokens = uncachedAudio,
        cacheReadAudioTokens = cachedAudio,
        visibleTokens = candidatesTokenCount.getOrElse(0),
        thinkingTokens = thoughtsTokenCount.getOrElse(0),
      )

    private def audio(counts: List[ModalityCount]): Int = counts
      .filter(_.modality == "AUDIO")
      .flatMap(_.tokenCount)
      .sum

    /** The part of `whole` that `part` is of `of`, rounded down. */
    private def share(whole: Int, part: Int, of: Int): Int =
      if of == 0 then 0 else (whole.toLong * part / of).toInt

  final case class Response
    (
      candidates: Option[List[Candidate]],
      promptFeedback: Option[PromptFeedback],
      usageMetadata: Option[TokenCounts],
    )
    derives Decoder:

    /**
      * This response as a reply, failing without a candidate: a blocked prompt
      * gets `200` and none, which would pass for an empty reply.
      */
    def toReply: Either[LlmError, Reply] = candidates
      .flatMap(_.headOption)
      .toRight(LlmError.Malformed(
        LlmProvider.Gemini.displayName,
        unanswered,
      ))
      .map: candidate =>
        val calls = toolCalls(candidate)
        Reply(
          text = text(candidate),
          stopReason = stopped(calls.nonEmpty, candidate.finishReason),
          usage = usageMetadata.fold(Usage())(_.toUsage),
          toolCalls = calls,
        )

    private def unanswered: String = promptFeedback
      .flatMap(_.blockReason)
      .fold("no candidates")(reason => s"no candidates, blocked as $reason")
