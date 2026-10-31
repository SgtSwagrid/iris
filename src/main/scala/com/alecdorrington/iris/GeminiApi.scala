package com.alecdorrington.iris

import fs2.Stream
import io.circe.{Decoder, Json}
import io.circe.syntax.*
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

  override val answers: Decoder[Either[LlmError, Answer[Nothing]]] =
    Decoder[Response].map(_.toAnswer)

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
  override def reply[F[_]](events: Stream[F, Json]): Stream[F, Delta] = events
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
    val said = carried(event).flatMap(_.text).mkString
    List.concat(
      Option.when(said.nonEmpty)(Delta.Text(said)),
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
          "mime_type" -> mediaType.asJson,
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
    * Gemini leaves out every count of `0`.
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
      val prompt        = promptTokenCount.getOrElse(0)
      val cached        = cachedContentTokenCount.getOrElse(0).min(prompt)
      val cachedAudio   = audio(cacheTokensDetails).min(cached)
      val uncachedAudio = (audio(promptTokensDetails) - cachedAudio)
        .min(prompt - cached)
        .max(0)
      Usage(
        uncachedTokens = prompt - cached - uncachedAudio,
        cacheReadTokens = cached - cachedAudio,
        uncachedAudioTokens = uncachedAudio,
        cacheReadAudioTokens = cachedAudio,
        visibleTokens = candidatesTokenCount.getOrElse(0),
        thinkingTokens = thoughtsTokenCount.getOrElse(0),
      )

    private def audio(counts: Option[List[ModalityCount]]): Int = counts
      .getOrElse(List.empty)
      .filter(_.modality == "AUDIO")
      .flatMap(_.tokenCount)
      .sum

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
    def toAnswer: Either[LlmError, Answer[Nothing]] = candidates
      .flatMap(_.headOption)
      .toRight(LlmError.Malformed(
        LlmProvider.Gemini.displayName,
        unanswered,
      ))
      .map: candidate =>
        val calls = toolCalls(candidate)
        Answer(
          text = text(candidate),
          stopReason = stopped(calls.nonEmpty, candidate.finishReason),
          usage = usageMetadata.fold(Usage())(_.toUsage),
          toolCalls = calls,
        )

    private def unanswered: String = promptFeedback
      .flatMap(_.blockReason)
      .fold("no candidates")(reason => s"no candidates, blocked as $reason")
