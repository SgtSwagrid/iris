package com.alecdorrington.iris

import cats.Id
import cats.effect.Sync
import cats.syntax.all.*
import com.alecdorrington.iris.agent.*
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.query.*
import io.circe.Json
import io.circe.parser.parse
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

  /** What a provider makes of a response body that a test knows it can read. */
  def answer(api: Provider, body: String): Either[ApiError, PromptResponse] =
    json(body).as(using api.wholeResponse).toOption.get

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
