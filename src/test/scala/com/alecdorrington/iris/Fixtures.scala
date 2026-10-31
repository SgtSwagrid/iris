package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.{decode, parse}

object Fixtures:

  val config: LlmConfig = LlmConfig(LlmModel.ClaudeHaiku4_5, "key", 512)

  def json(body: String): Json = parse(body).toOption.get

  /** What a provider makes of a response body that a test knows it can read. */
  def answer(api: ProviderApi, body: String): Either[LlmError, Reply] =
    decode[Either[LlmError, Reply]](body)(using api.replies).toOption.get

  extension [F[_] : MonadThrow](client: LlmClient[F])

    /**
      * Asks as [[LlmClient.ask]] asks for a type holding no stream, through
      * `send` alone, so that an effect that cannot run fibers, as `SyncIO`,
      * may: the value, and the reply it was read from.
      */
    def askWhole[A : Schema]
      (
        chat: Chat,
        options: ReplyOptions = ReplyOptions(),
        attempts: Int = Question.attempts,
      )
      : F[(Option[A], Reply)] = Question
      .ask[F, A](
        Sending(client).sent(_, options),
        chat,
        attempts,
      )
      .flatMap(answer => answer.reply.map(answer.value -> _))

  /**
    * A client sending as it is asked to, so that a test can have a reply as the
    * provider gave it, as only a wrapper may.
    */
  final class Sending[F[_]]
    (client: LlmClient[F])
    extends LlmClient.Forwarding(client):

    def sent
      (
        chat: Chat,
        options: ReplyOptions = ReplyOptions(),
      )
      : F[Reply] = send(chat, options)
