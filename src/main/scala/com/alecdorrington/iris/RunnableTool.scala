package com.alecdorrington.iris

import cats.Applicative
import cats.syntax.all.*
import io.circe.Json

/**
  * A tool the model may ask to have run, with what running it does: given to
  * [[ModelClient.ask]], it is offered to the model and run wherever the model
  * asks for it, the model then told its output and asked to go on. What the
  * tool does, and whether it may, is the host's, as `run` decides.
  *
  * @tparam F
  *   The effect type.
  *
  * @param tool
  *   The tool, as the model is told of it.
  *
  * @param run
  *   Runs the tool on the arguments the model gave it, giving its output for
  *   the model to read.
  */
final case class RunnableTool[F[_]](tool: Tool, run: Json => F[String])

object RunnableTool:

  /**
    * Creates a tool whose arguments are a value of a type with a [[Schema]],
    * which describes them to the model and reads them back. Arguments that
    * cannot be read are answered with why, for the model to call it again.
    *
    * {{{
    * case class Lookup(word: String)
    *
    * RunnableTool.of[IO, Lookup]("dictionary", "Looks up a word.")(lookup =>
    *   IO(dictionary(lookup.word)),
    * )
    * }}}
    *
    * @tparam F
    *   The effect type.
    *
    * @tparam A
    *   The type of the arguments, an object of fields, as a case class is.
    *
    * @param name
    *   The name the model calls the tool by, unique among those offered.
    *
    * @param description
    *   The description of what the tool does, by which the model chooses it.
    *
    * @param run
    *   Runs the tool on the arguments read, giving its output for the model to
    *   read.
    *
    * @return
    *   The tool, with what running it does.
    */
  def of[F[_] : Applicative, A : Schema as A]
    (name: String, description: String)
    (run: A => F[String])
    : RunnableTool[F] = RunnableTool(
    Tool(name, description, A.json),
    arguments =>
      WholeAsker
        .decoded(A, arguments)
        .fold(
          why => s"The arguments could not be read:\n$why".pure[F],
          run,
        ),
  )

/** The tools an ask runs, where the model asks for those alone. */
private[iris] final case class Toolbox[F[_]](tools: List[RunnableTool[F]]):

  /** The options, offering these tools beside those offered already. */
  def offered(options: ModelRequest.Options): ModelRequest.Options = options
    .copy(tools = options.tools ++ tools.map(_.tool))

  /** Whether the calls are some, and every one of a tool in this box. */
  def runs(calls: List[MessagePart.ToolCall]): Boolean = calls.nonEmpty &&
    calls.forall(call => tools.exists(_.tool.name == call.tool))

  /** The result of each call, of a tool in this box, in order. */
  def ran
    (calls: List[MessagePart.ToolCall])
    (using Applicative[F])
    : F[List[MessagePart.ToolResult]] = calls.traverse(run)

  /** The result of a call of a tool in this box. */
  def run
    (call: MessagePart.ToolCall)
    (using Applicative[F])
    : F[MessagePart.ToolResult] = tools
    .find(_.tool.name == call.tool)
    .fold(s"No tool is named `${ call.tool }`.".pure[F])(_.run(call.arguments))
    .map(MessagePart.ToolResult(call.id, call.tool, _))
