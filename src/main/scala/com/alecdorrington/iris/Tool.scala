package com.alecdorrington.iris

import io.circe.Json

/**
  * A tool the model may ask to have run, as it is told of it: offered through
  * [[ModelRequest.Options.tools]], for the host to run, where the model asks in
  * [[ModelResponse.toolCalls]] and the host answers with a
  * [[MessagePart.ToolResult]], or given to [[ModelClient.ask]] with what
  * running it does, as a [[RunnableTool]], for the ask to run.
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
