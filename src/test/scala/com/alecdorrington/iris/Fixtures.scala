package com.alecdorrington.iris

import cats.{Id, MonadThrow}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.{decode, parse}

object Fixtures:

  val config: ModelConfig = ModelConfig(Model.ClaudeHaiku4_5, "key", 512)

  def json(body: String): Json = parse(body).toOption.get

  /** What a provider makes of a response body that a test knows it can read. */
  def answer
    (api: ProviderApi, body: String)
    : Either[ModelError, ModelResponse] =
    decode[Either[ModelError, ModelResponse]](body)(using api.wholeResponse)
      .toOption
      .get

  /** The usage and cost of every response together, as an ask totals them. */
  def totals(responses: List[ModelResponse]): (Usage, Option[Usd]) =
    val summary = ValidatedResponse[Id, Nothing](None, responses, ())
    (summary.usage, summary.cost)

  extension [F[_] : MonadThrow](client: ModelClient[F])

    /**
      * Asks as [[ModelClient.ask]] asks for a type holding no stream, through
      * `send` alone, so that an effect that cannot run fibers, as `SyncIO`,
      * may: the value, and every response the ask took.
      */
    def askWhole[A : Schema]
      (
        chat: Chat,
        options: ModelRequest.Options = ModelRequest.Options(),
        attempts: Int = WholeAsker.attempts,
        tools: List[RunnableTool[F]] = Nil,
      )
      : F[(Option[A], List[ModelResponse])] = WholeAsker
      .ask[F, A](
        Sending(client).sent(_, Toolbox(tools).offered(options)),
        chat,
        attempts,
        Toolbox(tools),
      )
      .flatMap(answer => answer.responses.map(answer.value -> _))

  /**
    * A client sending as it is asked to, so that a test can have a reply as the
    * provider gave it, as only a wrapper may.
    */
  final class Sending[F[_]]
    (client: ModelClient[F])
    extends ModelClient.Forwarding(client):

    def sent
      (
        chat: Chat,
        options: ModelRequest.Options = ModelRequest.Options(),
      )
      : F[ModelResponse] = send(ModelRequest(chat, options))
