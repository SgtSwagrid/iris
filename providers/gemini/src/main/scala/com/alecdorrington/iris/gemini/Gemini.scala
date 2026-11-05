package com.alecdorrington.iris
package gemini

import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
import fs2.{RaiseThrowable, Stream}
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import scala.concurrent.duration.FiniteDuration
import sttp.model.{Header, Uri}

/**
  * The provider of the [Google Gemini
  * API](https://ai.google.dev/api/generate-content).
  */
object Gemini extends Provider:

  override val displayName: String = "Gemini"

  override val origin: String = "https://generativelanguage.googleapis.com"

  override def defaultModel: GeminiModel = GeminiModel.Gemini3_8Flash

  override def model(id: String): Option[GeminiModel] = GeminiModel.of(id)

  override def listed: List[GeminiModel] = GeminiModel.listed

  override val keyVariables: List[String] =
    List("GEMINI_API_KEY", "GOOGLE_API_KEY")

  /** Gemini streams a response from a method of its own. */
  override private[iris] def path
    (model: ProviderModel, streamed: Boolean)
    : List[String] = calling(
    model,
    if streamed then "streamGenerateContent" else "generateContent",
  )

  /** Gemini streams server-sent events only where asked to. */
  override private[iris] def streaming(uri: Uri): Uri =
    uri.addParam("alt", "sse")

  override private[iris] def credentials(key: String): Seq[Header] =
    Seq(Header("x-goog-api-key", key))

  override private[iris] val wholeResponse
    : Decoder[Either[ApiError, PromptResponse]] =
    Decoder[Response].map(_.toReply)

  /**
    * As `retry-after` says, else as the `retryDelay` of the error's
    * `google.rpc.RetryInfo` detail, a protobuf duration (`34s`), as
    * https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto
    * describes it.
    */
  override private[iris] def retryAfter
    (
      headers: Seq[Header],
      detail: String,
      now: FiniteDuration,
    )
    : Option[FiniteDuration] = super
    .retryAfter(headers, detail, now)
    .orElse(
      details(detail)
        .find(_.get[String]("@type").toOption.contains(retryInfo))
        .flatMap(_.get[String]("retryDelay").toOption)
        .flatMap(Refusal.protobuf),
    )

  private val retryInfo = "type.googleapis.com/google.rpc.RetryInfo"

  /**
    * A day's quota used up, as of the free tier's requests a day, which waiting
    * does not lift until the day ends: a `429` whose `google.rpc.QuotaFailure`
    * detail names a quota of a day (`quotaId`, as
    * `GenerateRequestsPerDayPerProjectPerModel-FreeTier`).
    */
  override private[iris] def lasting(detail: String): Boolean = details(detail)
    .filter(_.get[String]("@type").toOption.contains(quotaFailure))
    .flatMap(_.downField("violations").values.toList.flatten)
    .flatMap(_.hcursor.get[String]("quotaId").toOption)
    .exists(_.contains("PerDay"))

  private val quotaFailure = "type.googleapis.com/google.rpc.QuotaFailure"

  /** The details of an error's body, each a cursor. */
  private def details(detail: String): List[ACursor] = parse(detail)
    .toOption
    .flatMap(_.hcursor.downField("error").get[List[Json]]("details").toOption)
    .toList
    .flatten
    .map(_.hcursor)

  /**
    * As https://ai.google.dev/gemini-api/docs/pricing gives them. Google names
    * no time zone for its price changes, so they are taken to come at midnight
    * UTC, nor says whether cached tokens count towards Gemini 2.5 Pro's 200K,
    * so they are taken to, as [[Rates.Above]] says. From December 1, 2026,
    * Google answers Gemini 2.5 Pro with Gemini 3.8 Flash and Gemini 2.5 Flash
    * with Gemini 3.5 Flash-Lite, billing each at its replacement's rates.
    */
  override private[iris] def rates
    (model: ProviderModel, at: FiniteDuration)
    : Option[Rates] = model match
    case GeminiModel.Gemini4Argon => Some(
        if at < Provider.utc(2026, 12, 1) then
          priced(2.00, 0.10, 2.00, 0.10, 10.00)
        else priced(4.00, 0.20, 4.00, 0.20, 20.00),
      )
    case GeminiModel.Gemini3_8Flash | GeminiModel.Gemini3_6Flash => Some(
        if at < Provider.utc(2027, 1, 1) then
          priced(0.75, 0.075, 0.75, 0.075, 3.75)
        else priced(1.50, 0.15, 1.50, 0.15, 7.50),
      )
    case GeminiModel.Gemini3_5FlashLite =>
      Some(priced(0.30, 0.03, 0.30, 0.03, 2.50))
    case GeminiModel.Gemini3_1FlashLite =>
      Some(priced(0.25, 0.025, 0.50, 0.05, 1.50))
    case GeminiModel.Gemini2_5Pro =>
      if at < Provider.utc(2026, 12, 1) then
        Some(priced(1.25, 0.125, 1.25, 0.125, 10.00).copy(above =
          Some(Rates.Above(
            200_000,
            priced(2.50, 0.25, 2.50, 0.25, 15.00),
          )),
        ))
      else rates(GeminiModel.Gemini3_8Flash, at)
    case GeminiModel.Gemini2_5Flash =>
      if at < Provider.utc(2026, 12, 1) then
        Some(priced(0.30, 0.03, 1.00, 0.10, 2.50))
      else rates(GeminiModel.Gemini3_5FlashLite, at)
    case GeminiModel.Gemini2_5FlashLite =>
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
    uncached = Provider.usd(input),
    cacheRead = Provider.usd(cached),
    cacheWrite = Provider.usd(input),
    uncachedAudio = Provider.usd(audio),
    cacheReadAudio = Provider.usd(cachedAudio),
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
          calling(model, "countTokens"),
          countBody(conversation),
          Decoder[CountResponse].map(_.totalTokens),
        ),
      ),
  )

  /** The path of a model's method, the model one segment, so it adds none. */
  private def calling(model: ProviderModel, method: String): List[String] =
    List(
      "v1beta",
      "models",
      s"${ model.id }:$method",
    )

  /**
    * Serialises a conversation to count. Counting takes no
    * `system_instruction`, so a system message is counted as a user message of
    * its own.
    */
  private[iris] def countBody(conversation: Conversation): Json = JsonHttp
    .objectOf(
      "contents" ->
        (conversation
          .instructions
          .map(text => content("user", List(MessagePart.Text(text))))
          .toList ++ contents(conversation.messages)).asJson,
    )

  /** Refuses an empty conversation, and a call whose arguments are no object. */
  override private[iris] def refuseUnsendable
    (
      model: ProviderModel,
      conversation: Conversation,
      settings: Settings,
    )
    : Either[PromptError, Unit] = JsonHttp
    .refuseUnanswerable(this, conversation)
    .flatMap(_ => JsonHttp.refuseUnreadableArguments(this, conversation))

  private[iris] final case class CountResponse(totalTokens: Int) derives Decoder

  /** Serialises a conversation alike whether streamed. */
  override private[iris] def body
    (
      model: ProviderModel,
      conversation: Conversation,
      settings: Settings,
    )
    : Json = JsonHttp.objectOf(
    "system_instruction" ->
      conversation
        .instructions
        .map(text => Json.obj("parts" -> Json.arr(part(text))))
        .asJson,
    "tools"            -> tools(settings.tools),
    "contents"         -> contents(conversation.messages).asJson,
    "generationConfig" -> JsonHttp.objectOf(
      "maxOutputTokens" -> settings.maxTokens.asJson,
      "temperature"     -> settings.temperature.asJson,
      "topP"            -> settings.topP.asJson,
      "stopSequences"   -> JsonHttp.unlessEmpty(settings.stopSequences),
      "thinkingConfig"  -> settings.effort.map(thinking).asJson,
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

  /**
    * Serialises messages, dropping breakpoints, as Gemini caches by itself, and
    * running together those in a row of one role, as the tools' results and the
    * user's next words both are the user's.
    */
  private def contents(messages: List[Message]): List[Json] = JsonHttp
    .turns(
      messages.map(message => role(message) -> message.withoutBreakpoints.parts),
    )
    .map(content)

  private def content(role: String, parts: List[MessagePart]): Json = Json.obj(
    "role"  -> role.asJson,
    "parts" -> Message.separated(parts).map(part).asJson,
  )

  private def part(text: String): Json = Json.obj("text" -> text.asJson)

  /**
    * The reply a stream's events make up. A reply that asked for a tool in any
    * event stopped for it, as Gemini gives no finish reason for that.
    */
  override private[iris] def deltas[F[_] : RaiseThrowable]
    (events: Stream[F, Json])
    : Stream[F, ProviderEvent] = events
    .zipWithScan1(false)(_ || asks(_))
    .flatMap((event, asked) => Stream.emits(deltas(event, asked)))

  private def asks(event: Json): Boolean =
    partsOf(event).exists(_.functionCall.nonEmpty)

  private def partsOf(event: Json): List[ResponsePart] = event
    .hcursor
    .downField("candidates")
    .downN(0)
    .downField("content")
    .get[List[ResponsePart]]("parts")
    .toOption
    .getOrElse(List.empty)

  private def deltas(event: Json, asked: Boolean): List[ProviderEvent] =
    val parts = partsOf(event)
    val text  = parts.flatMap(_.text).mkString
    List.concat(
      Option.when(text.nonEmpty)(ProviderEvent.Text(text)),
      parts.flatMap(_.toolCall).map(ProviderEvent.ToolCall(_)),
      event
        .hcursor
        .downField("candidates")
        .downN(0)
        .get[String]("finishReason")
        .toOption
        .map(reason =>
          ProviderEvent.End(
            stopped(asked, Some(reason)),
            event
              .hcursor
              .get[TokenCounts]("usageMetadata")
              .fold(_ => Usage(), _.toUsage),
          ),
        ),
    )

  /** Serialises the tools on offer, which Gemini takes under one entry. */
  private def tools(offered: List[Tool]): Json = Option
    .when(offered.nonEmpty)(List(
      Json.obj("functionDeclarations" -> offered.map(declared).asJson),
    ))
    .asJson

  /**
    * A tool's declaration, with no schema where it takes no arguments, as
    * Gemini refuses an object of no properties.
    */
  private def declared(tool: Tool): Json = JsonHttp
    .declared(tool, "parameters")
    .mapObject(declaration =>
      if empty(tool.parameters) then declaration.remove("parameters")
      else declaration,
    )

  /**
    * Whether a schema is of an object with no properties, taking no others, as
    * a map's would.
    */
  private def empty(schema: Json): Boolean =
    val cursor = schema.hcursor
    cursor
      .downField("properties")
      .focus
      .flatMap(_.asObject)
      .exists(_.isEmpty) &&
    cursor.downField("additionalProperties").focus.forall(_ == Json.False)

  private def part(content: MessagePart): Json = content match
    case MessagePart.Text(text)             => part(text)
    case MessagePart.Media(mediaType, data) => Json.obj(
        "inline_data" -> Json.obj(
          "mime_type" -> mediaType.name.asJson,
          "data"      -> data.asJson,
        ),
      )
    case MessagePart.ToolCall(id, tool, arguments, signature) => JsonHttp
        .objectOf(
          "functionCall" -> JsonHttp.objectOf(
            "id"   -> issued(id, tool).asJson,
            "name" -> tool.asJson,
            "args" -> parse(arguments).getOrElse(Json.obj()),
          ),
          "thoughtSignature" -> signature.asJson,
        )
    case MessagePart.ToolResult(callId, tool, output) => Json.obj(
        "functionResponse" -> JsonHttp.objectOf(
          "id"       -> issued(callId, tool).asJson,
          "name"     -> tool.asJson,
          "response" -> Json.obj("result" -> output.asJson),
        ),
      )
    // Breakpoints are taken out of a message before its parts are serialised.
    case MessagePart.CacheBreakpoint => Json.obj()

  /**
    * The id Gemini gave a call, where it gave one: the tool's name stands in
    * for one it did not, so is never sent as an id.
    */
  private def issued(id: String, tool: String): Option[String] =
    Option.when(id != tool)(id)

  /** The role of a message's owner, the tools' results being the user's. */
  private def role(message: Message): String = message match
    case _: Message.Model                  => "model"
    case _: Message.User | _: Message.Tool => "user"

  private val stopReason: Option[String] => StopReason =
    StopReason.normalise("STOP", "MAX_TOKENS")

  /** Why a reply stopped; Gemini gives no finish reason for a tool call. */
  private def stopped(asked: Boolean, reason: Option[String]): StopReason =
    if asked then StopReason.ToolCall else stopReason(reason)

  /** A tool call, which Gemini may give no id. */
  private[iris] final case class FunctionCall
    (
      id: Option[String],
      name: String,
      args: Option[Json],
    )
    derives Decoder

  /**
    * A part of a reply, whose thought signature belongs to the call it makes,
    * if any. A signature on text is not kept, as a reply's text parts are
    * joined, and Gemini requires one back only with a call.
    */
  private[iris] final case class ResponsePart
    (
      text: Option[String],
      functionCall: Option[FunctionCall],
      thoughtSignature: Option[String],
    )
    derives Decoder:

    /** The call this part makes, its tool's name the id where it has none. */
    def toolCall: Option[MessagePart.ToolCall] = functionCall.map: call =>
      MessagePart.ToolCall(
        call.id.getOrElse(call.name),
        call.name,
        call.args.getOrElse(Json.obj()).noSpaces,
        thoughtSignature,
      )

  private[iris] final case class Content(parts: Option[List[ResponsePart]])
    derives Decoder

  private[iris] final case class Candidate
    (
      content: Option[Content],
      finishReason: Option[String],
    )
    derives Decoder

  private[iris] final case class PromptFeedback(blockReason: Option[String])
    derives Decoder

  private def parts(candidate: Candidate): List[ResponsePart] = candidate
    .content
    .flatMap(_.parts)
    .getOrElse(List.empty)

  private def text(candidate: Candidate): String = parts(candidate)
    .flatMap(_.text)
    .mkString

  private def toolCalls(candidate: Candidate): List[MessagePart.ToolCall] =
    parts(candidate).flatMap(_.toolCall)

  /** The tokens of one kind of media, such as `AUDIO`. */
  private[iris] final case class ModalityCount
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
  private[iris] final case class TokenCounts
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

  private[iris] final case class Response
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
    def toReply: Either[ApiError, PromptResponse] = candidates
      .flatMap(_.headOption)
      .toRight(ApiError.Malformed(displayName, unanswered))
      .map: candidate =>
        val calls = toolCalls(candidate)
        PromptResponse(
          text = text(candidate),
          stopReason = stopped(calls.nonEmpty, candidate.finishReason),
          usage = usageMetadata.fold(Usage())(_.toUsage),
          toolCalls = calls,
        )

    private def unanswered: String = promptFeedback
      .flatMap(_.blockReason)
      .fold("no candidates")(reason => s"no candidates, blocked as $reason")
