package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.{decode, parse}

object Fixtures:

  val config: LlmConfig = LlmConfig(LlmModel.ClaudeHaiku4_5, "key", 512)

  def json(body: String): Json = parse(body).toOption.get

  /** What a provider makes of a response body that a test knows it can read. */
  def answer
    (api: ProviderApi, body: String)
    : Either[LlmError, Answer[Nothing]] =
    decode[Either[LlmError, Answer[Nothing]]](body)(using api.answers)
      .toOption
      .get

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
      : F[Answer[Nothing]] = send(chat, options)
