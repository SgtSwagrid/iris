package com.alecdorrington.iris
package api

import com.alecdorrington.iris.prompt.*

/**
  * A piece of a response as a provider's reader reads it: a run of [[Text]] and
  * [[ToolCall]]s, followed by one [[End]], which a [[PromptService]] gathers
  * into [[PromptEvent]]s, pricing the response.
  */
private[iris] enum ProviderEvent:

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

/**
  * A tool call whose arguments arrive in fragments of JSON, as Anthropic and
  * OpenAI stream them.
  */
private[iris] final case class PartialToolCall
  (
    id: String,
    tool: String,
    arguments: String = "",
  ):

  def more(fragment: String): PartialToolCall =
    copy(arguments = arguments + fragment)

  /** The whole call, its arguments an empty object where none were given. */
  def whole: ProviderEvent = ProviderEvent.ToolCall(MessagePart.ToolCall(
    id,
    tool,
    if arguments.isEmpty then "{}" else arguments,
  ))
