package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.parse

/**
  * A piece of a reply arriving through [[ModelClient.stream]]: a run of
  * [[Text]] and [[ToolCall]]s, followed by one [[End]].
  */
enum Delta:

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
    *
    * @param cost
    *   What the reply cost, as [[ModelResponse.cost]] says.
    */
  case End
    (
      stopReason: StopReason,
      usage: Usage,
      cost: Option[Usd] = None,
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

  /**
    * The whole call, its arguments an empty object where none were given, or
    * why it is malformed, where they are no JSON, as a call cut short is not.
    */
  def whole(provider: ModelProvider): Either[ModelError, Delta] =
    (if arguments.isEmpty then Right(Json.obj()) else parse(arguments))
      .left
      .map(_ =>
        ModelError.Malformed(
          provider.displayName,
          s"the arguments of a call of `$tool` are not JSON",
        ),
      )
      .map(json => Delta.ToolCall(MessagePart.ToolCall(id, tool, json)))
