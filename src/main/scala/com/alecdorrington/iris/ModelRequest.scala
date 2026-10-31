package com.alecdorrington.iris

/**
  * A request for the model's next response to a chat, as a [[ModelClient]]
  * sends it, streams it, counts its tokens or warms the cache with it.
  *
  * @param chat
  *   The conversation so far, including any system message.
  *
  * @param options
  *   The per-request options; unset fields fall back to the configuration or
  *   the provider's defaults.
  */
final case class ModelRequest
  (
    chat: Chat,
    options: ModelRequest.Options = ModelRequest.Options(),
  )

object ModelRequest:

  /**
    * The options of a request. Unset fields fall back to [[ModelConfig]] or to
    * the provider's defaults.
    *
    * @param maxTokens
    *   The response's token limit, overriding the configured one.
    *
    * @param temperature
    *   The sampling temperature; higher values give more varied answers. Not
    *   sent to Anthropic models that reject sampling.
    *
    * @param topP
    *   The nucleus sampling threshold. Not sent to Anthropic models that reject
    *   sampling.
    *
    * @param stopSequences
    *   The sequences at which the model stops generating, in the text it
    *   writes, which for [[ModelClient.ask]] is JSON, in whose strings a
    *   newline is never written as itself.
    *
    * @param tools
    *   The tools the model may ask to have run.
    *
    * @param effort
    *   The effort a thinking model should give the request, or `None` for its
    *   own choice.
    */
  final case class Options
    (
      maxTokens: Option[Int] = None,
      temperature: Option[Double] = None,
      topP: Option[Double] = None,
      stopSequences: List[String] = List.empty,
      tools: List[Tool] = List.empty,
      effort: Option[Effort] = None,
    )
