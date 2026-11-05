package com.alecdorrington.iris

import cats.Id
import cats.effect.Sync
import cats.syntax.all.*
import com.alecdorrington.iris.agent.*
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.openai.OpenAi
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.query.*
import fs2.{Fallible, Stream}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.client4.Backend

object Fixtures:

  /** The model tests prompt. */
  val model: ProviderModel = AnthropicModel.ClaudeHaiku4_5

  /** The settings tests prompt with. */
  val config: PromptConfig = PromptConfig(maxTokens = 512)

  /** How tests reach a provider's API. */
  val access: ApiConfig = ApiConfig(key = Some("key"))

  /** A request's settings, as its provider is given them. */
  def settings
    (request: PromptRequest = PromptRequest(Conversation()))
    : Settings = config.settings(request)

  def json(body: String): Json = parse(body).toOption.get

  /**
    * What a provider makes of a response, given whole as its API would give it
    * unstreamed, read from the events it would have streamed.
    */
  def answer(api: Provider, body: String): Either[ApiError, PromptResponse] =
    Stream
      .emits(events(api, body))
      .through(api.deltas[Fallible])
      .compile
      .toList
      .leftMap { case error: ApiError => error }
      .flatMap(deltas =>
        Either.cond(
          deltas.exists(_.isInstanceOf[ProviderEvent.End]),
          deltas.foldLeft(PromptResponse(""))((so, delta) =>
            delta match
              case ProviderEvent.Text(text) => so.copy(text = so.text + text)
              case ProviderEvent.ToolCall(call) =>
                so.copy(toolCalls = so.toolCalls :+ call)
              case ProviderEvent.End(stopReason, usage) =>
                so.copy(stopReason = stopReason, usage = usage),
          ),
          ApiError.Malformed(api.displayName, unended),
        ),
      )

  /** Why a provider's response that never ends cannot be read. */
  val unended: String = "the stream ended before it said the response had"

  /**
    * A provider's response, given whole as its API would give it unstreamed, as
    * the server-sent events of the stream it would have been.
    */
  def streamed(api: Provider, body: String): String = parse(body).fold(
    _ => body,
    _ =>
      events(api, body).map(event => s"data: ${ event.noSpaces }\n\n").mkString,
  )

  /** The events a response would have streamed, given whole. */
  private def events(api: Provider, body: String): List[Json] =
    val whole = json(body)
    api match
      case Anthropic => anthropic(whole)
      case OpenAi    => openAi(whole)
      case _         => List(whole)

  /** Anthropic's events of a message: its start, its blocks, its end. */
  private def anthropic(whole: Json): List[Json] =
    val cursor = whole.hcursor
    val blocks = cursor
      .get[List[Json]]("content")
      .getOrElse(Nil)
      .zipWithIndex
      .flatMap((block, index) =>
        val at    = "index" -> index.asJson
        val delta = block.hcursor.get[String]("type").toOption match
          case Some("text") => Some(Json.obj(
              "type" -> "text_delta".asJson,
              "text" -> block.hcursor.get[String]("text").getOrElse("").asJson,
            ))
          case Some("tool_use") => Some(Json.obj(
              "type"         -> "input_json_delta".asJson,
              "partial_json" ->
                block
                  .hcursor
                  .downField("input")
                  .focus
                  .getOrElse(Json.obj())
                  .noSpaces
                  .asJson,
            ))
          case _ => None
        List(
          Some(Json.obj(
            "type" -> "content_block_start".asJson,
            at,
            "content_block" -> block.mapObject(_.remove("text").remove("input")),
          )),
          delta.map(delta =>
            Json.obj(
              "type" -> "content_block_delta".asJson,
              at,
              "delta" -> delta,
            ),
          ),
          Some(Json.obj(
            "type" -> "content_block_stop".asJson,
            at,
          )),
        ).flatten,
      )
    val start = Json.obj(
      "type"    -> "message_start".asJson,
      "message" -> Json.obj(
        "usage" -> cursor.downField("usage").focus.getOrElse(Json.Null),
      ),
    )
    val end = Json.obj(
      "type"  -> "message_delta".asJson,
      "delta" -> Json.obj(
        "stop_reason" ->
          cursor.downField("stop_reason").focus.getOrElse(Json.Null),
      ),
    )
    (start :: blocks) :+ end

  /**
    * OpenAI's chunk of a whole completion: its message as one delta, why it
    * stopped and its usage, each tool call numbered.
    */
  private def openAi(whole: Json): List[Json] =
    val cursor = whole.hcursor
    val choice = cursor.downField("choices").downN(0)
    val calls  = choice
      .downField("message")
      .get[List[Json]]("tool_calls")
      .getOrElse(Nil)
      .zipWithIndex
      .map((call, index) => call.mapObject(_.add("index", index.asJson)))
    List(Json.obj(
      "choices" ->
        choice
          .focus
          .map(focus =>
            Json.arr(Json.obj(
              "index" -> 0.asJson,
              "delta" -> Json.obj(
                "content" ->
                  choice
                    .downField("message")
                    .downField("content")
                    .focus
                    .getOrElse(Json.Null),
                "tool_calls" -> calls.asJson,
              ),
              "finish_reason" ->
                focus
                  .hcursor
                  .downField("finish_reason")
                  .focus
                  .getOrElse(Json.Null),
            )),
          )
          .getOrElse(Json.arr()),
      "usage" -> cursor.downField("usage").focus.getOrElse(Json.Null),
    ))

  /** The usage and cost of every response together, as a query totals them. */
  def totals(responses: List[PromptResponse]): (Usage, Option[Usd]) =
    val summary =
      QueryResponse[Id, Nothing](None, responses, (), Conversation())
    (summary.usage, summary.cost)

  /**
    * The prompt service of a model over a host's backend, which sends each
    * request once and answers a streamed one whole.
    */
  def prompting[F[_] : Sync]
    (
      backend: Backend[F],
      model: ProviderModel = model,
      config: PromptConfig = config,
      access: ApiConfig = access,
    )
    : PromptService[F] = model.prompt(
    model.provider.api(access, backend),
    config,
  )

  extension [F[_] : Sync](prompt: PromptService[F])

    /**
      * Asks as a query or an agent asks for a type holding no stream, through
      * `send` alone, so that an effect that cannot run fibers, as `SyncIO`,
      * may: the value, and every response the ask took.
      */
    def askWhole[A : Schema]
      (
        conversation: Conversation,
        offered: List[Tool] = Nil,
        attempts: Int = QueryConfig().maxAttempts,
        tools: List[RunnableTool[F]] = Nil,
        rounds: Option[Int] = None,
      )
      : F[(Option[A], List[PromptResponse])] = QueryAgentService
      .rounds[F, A](
        request =>
          ValueReader.counted(attempts).liftTo[F] *> ValueReader.whole(
            prompt.send(_),
            Schema[A],
            attempts,
            request
              .prompt
              .copy(conversation =
                ValueReader.opening(
                  request.prompt.conversation,
                  ValueReader.schemaPrompt(Schema[A]),
                ),
              ),
          ),
        Toolbox(tools),
        QueryRequest(PromptRequest(conversation, tools = offered)),
        rounds,
      )
      .flatMap(answer => answer.responses.map(answer.value -> _))
