package com.alecdorrington.iris

import io.circe.Json

/**
  * A tool the model may ask to have run, offered through
  * [[ReplyOptions.tools]]. The model asks in [[Reply.toolCalls]], and the host
  * answers with a [[Part.ToolResult]]. Iris never runs a tool itself.
  *
  * @param name
  *   The name the model calls the tool by, unique among those offered.
  *
  * @param description
  *   The description of what the tool does, by which the model chooses it.
  *
  * @param parameters
  *   The [JSON Schema](https://json-schema.org) object describing the tool's
  *   arguments.
  */
final case class Tool
  (
    name: String,
    description: String,
    parameters: Json,
  )
