package com.alecdorrington.iris
package api

import com.alecdorrington.iris.prompt.*

/**
  * A piece of a response as a provider's reader reads it: a run of [[Text]] and
  * [[ToolCall]]s, followed by one [[End]], which a [[PromptService]] gathers
  * into [[PromptEvent]]s, pricing the response.
  */
private[iris] enum Delta:

  /**
    * More of the reply's text, to append to what came before.
    *
    * @param text
    *   The new text.
    */
  case Text(text: String)

  /**
    * A tool the model asks to have run, once its call is whole.
    *
    * @param call
    *   The call.
    */
  case ToolCall(call: MessagePart.ToolCall)

  /**
    * The end of the reply.
    *
    * @param stopReason
    *   The reason the model stopped.
    *
    * @param usage
    *   The token counts of the whole reply.
    */
  case End(stopReason: StopReason, usage: Usage)

private[iris] object Delta:

  /** The deltas of a whole response, as it would have arrived. */
  def of(response: PromptResponse): List[Delta] = List.concat(
    Option.when(response.text.nonEmpty)(Text(response.text)),
    response.toolCalls.map(ToolCall(_)),
    List(End(response.stopReason, response.usage)),
  )

/**
  * A tool call whose arguments arrive in fragments of JSON, as Anthropic and
  * OpenAI stream them.
  */
private[iris] final case class Calling
  (
    id: String,
    tool: String,
    arguments: String = "",
  ):

  def more(fragment: String): Calling = copy(arguments = arguments + fragment)

  /** The whole call, its arguments an empty object where none were given. */
  def whole: Delta = Delta.ToolCall(MessagePart.ToolCall(
    id,
    tool,
    if arguments.isEmpty then "{}" else arguments,
  ))
