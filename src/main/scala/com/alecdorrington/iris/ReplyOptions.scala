package com.alecdorrington.iris

/**
  * Per-request options. Unset fields fall back to [[LlmConfig]] or to the
  * provider's defaults.
  *
  * @param maxTokens
  *   The reply's token limit, overriding the configured one.
  *
  * @param temperature
  *   The sampling temperature; higher values give more varied answers. Not sent
  *   to Anthropic models that reject sampling.
  *
  * @param topP
  *   The nucleus sampling threshold. Not sent to Anthropic models that reject
  *   sampling.
  *
  * @param stopSequences
  *   The sequences at which the model stops generating, in the text it writes,
  *   which for [[LlmClient.ask]] is JSON, in whose strings a newline is never
  *   written as itself.
  *
  * @param tools
  *   The tools the model may ask to have run.
  *
  * @param effort
  *   The effort a thinking model should give the request, or `None` for its own
  *   choice.
  */
final case class ReplyOptions
  (
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: List[String] = List.empty,
    tools: List[Tool] = List.empty,
    effort: Option[Effort] = None,
  )
