package com.alecdorrington.iris

import io.circe.Json
import java.util.Base64
import scala.Conversion.into
import scala.compiletime.summonInline

/** The author of a [[Message]] in a [[Chat]]. */
enum Role:

  /** The person or application conversing with the model. */
  case User

  /** The model itself. */
  case Model

/**
  * A part of a message. A message is a list of parts, so that text and media
  * travel together in reading order.
  */
sealed trait MessagePart

object MessagePart:

  /**
    * A part of written text. Two in a row, with nothing but breakpoints between
    * them, are sent as paragraphs of their own, a blank line apart, since every
    * provider would otherwise run them together.
    *
    * @param text
    *   The text.
    */
  final case class Text(text: String) extends MessagePart

  /**
    * Creates a part saying a value, as its [[Content]] says it: text as text,
    * and a value with a schema or an encoder as JSON. A value given to a chat
    * or message is said so already; this makes parts of a list of values to
    * give it, as `chat.userSays(values.map(MessagePart.of)*)`.
    *
    * @tparam A
    *   The type of the value.
    *
    * @param value
    *   The value.
    *
    * @return
    *   A part saying the value.
    */
  def of[A : Content as A](value: A): MessagePart = A.part(value)

  /**
    * Says a value as a part wherever a chat or message takes one, as its
    * [[Content]] says it, so that values of different kinds mix in one message.
    * A value whose type has no content is refused where it is given, saying
    * what can be said.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a value to the part saying it.
    */
  inline given fromContent[A]: Conversion[A, MessagePart] =
    converted(summonInline[Content[A]])

  /** Converts by a content, so that no function is inlined where one is used. */
  private def converted[A](content: Content[A]): Conversion[A, MessagePart] =
    content.part(_)

  /**
    * A picture, document or other media for the model to look at. A provider
    * refuses media it cannot take with [[ModelError.Unsupported]], before any
    * request: Anthropic takes every [[MediaType]] Iris lists, and OpenAI its
    * pictures alone. Gemini is sent any media, and judges it itself.
    *
    * @param mediaType
    *   The kind of media.
    *
    * @param data
    *   The content, Base64 encoded.
    */
  final case class Media(mediaType: MediaType, data: String) extends MessagePart

  object Media:

    /**
      * Creates media of its content as bytes, which are Base64 encoded.
      *
      * @param mediaType
      *   The kind of media.
      *
      * @param bytes
      *   The content.
      *
      * @return
      *   Media of the content.
      */
    def apply(mediaType: MediaType, bytes: Array[Byte]): Media = Media(
      mediaType,
      Base64.getEncoder.encodeToString(bytes),
    )

  /**
    * A call by the model for a tool to be run. It is part of the model's
    * message, and is sent back with the conversation as the reply gave it.
    *
    * @param id
    *   The provider's identifier of the call, by which its result is matched.
    *   Where Gemini gives none, the tool's name stands in, and is not sent.
    *
    * @param tool
    *   The name of the [[Tool]] to run.
    *
    * @param arguments
    *   The arguments, as described by [[Tool.parameters]].
    *
    * @param signature
    *   An opaque token the provider gave with the call, sent back with it
    *   unchanged: Gemini's thought signature, which its thinking models need to
    *   go on with their reasoning. No other provider gives one.
    */
  final case class ToolCall
    (
      id: String,
      tool: String,
      arguments: Json,
      signature: Option[String] = None,
    )
    extends MessagePart

  /**
    * The output of a tool, answering a [[ToolCall]] in the user's next message.
    *
    * @param callId
    *   The [[ToolCall.id]] this answers.
    *
    * @param tool
    *   The name of the tool run, which Gemini matches by where the call has no
    *   id of its own.
    *
    * @param output
    *   The tool's output, for the model to read.
    */
  final case class ToolResult
    (
      callId: String,
      tool: String,
      output: String,
    )
    extends MessagePart

  /**
    * The end of a prefix for the provider to cache: everything before it, the
    * system message included, is shared by many requests. It says nothing to
    * the model.
    *
    * Anthropic caches only at breakpoints, and refuses a chat whose breakpoints
    * mark more than four blocks with [[ModelError.Unsendable]]; OpenAI and
    * Gemini cache long prefixes by themselves. A prefix too short for the
    * provider is not cached. Requests sent at once cannot read one another's
    * writes, so call [[ModelClient.warmCache]] before a batch.
    */
  case object CacheBreakpoint extends MessagePart

/**
  * A single message in a [[Chat]].
  *
  * @param role
  *   The author of the message.
  *
  * @param parts
  *   The parts of the message, in reading order.
  */
final case class Message(role: Role, parts: List[MessagePart]):

  /** The text of this message, a paragraph per text part, media left out. */
  def text: String = parts
    .collect:
      case MessagePart.Text(text) if text.nonEmpty => text
    .mkString(Message.paragraphBreak)

  /** This message without its cache breakpoints. */
  def withoutBreakpoints: Message =
    copy(parts = parts.filterNot(_ == MessagePart.CacheBreakpoint))

  /** Whether this message holds only breakpoints, which no provider accepts. */
  private[iris] def silent: Boolean =
    parts.forall(_ == MessagePart.CacheBreakpoint)

  /** The tool results this message carries. */
  def toolResults: List[MessagePart.ToolResult] = parts.collect:
    case result: MessagePart.ToolResult => result

object Message:

  /**
    * Creates a message saying each value as its [[Content]] says it.
    *
    * @param role
    *   The author of the message.
    *
    * @param content
    *   The values, in reading order: text, parts, or values said as JSON, of
    *   any kinds together.
    *
    * @return
    *   A message of a part per value.
    */
  def apply(role: Role, content: into[MessagePart]*): Message =
    new Message(role, content.toList)

  /** What is sent between two text parts in a row. */
  private[iris] val paragraphBreak: String = "\n\n"

  /**
    * The parts as sent: each text part saying something opens with a
    * [[paragraphBreak]] where one said something before it with nothing but
    * breakpoints between. A part is sent as the parts before it decide, never
    * those after it, so that a cached prefix is sent alike whatever follows.
    */
  private[iris] def separated(parts: List[MessagePart]): List[MessagePart] =
    parts
      .foldLeft((Vector.empty[MessagePart], false)):
        case ((sent, afterText), MessagePart.Text(text)) if text.nonEmpty =>
          val opened = if afterText then paragraphBreak + text else text
          (sent :+ MessagePart.Text(opened), true)
        case (
            (sent, afterText),
            part @ (MessagePart.Text(_) | MessagePart.CacheBreakpoint),
          ) => (sent :+ part, afterText)
        case ((sent, _), part) => (sent :+ part, false)
      ._1
      .toList

/**
  * The full history of a conversation with a model, sent whole with every
  * request. To continue a conversation, append the model's reply and the next
  * user message, then send the chat again:
  *
  * {{{
  * for
  *   first     <- client.ask[String](chat)
  *   responses <- first.responses
  *   next       = chat.modelSays(responses.last).userSays("Tell me more.")
  *   second    <- client.ask[String](next)
  * yield second.value
  * }}}
  *
  * Asked of a chat ending with the model's message, the model is told what to
  * answer in a message of the user's after it, and so answers afresh rather
  * than continuing its own reply. A chat with no messages, a message of
  * breakpoints alone, or no message of the user's fails with
  * [[ModelError.Unsendable]] before any request is made.
  *
  * @param messages
  *   The messages exchanged so far, oldest first.
  *
  * @param instructions
  *   The system message, if any, setting the model's general behaviour.
  */
final case class Chat
  (
    messages: List[Message] = List.empty,
    instructions: Option[String] = None,
  ):

  /**
    * Appends a message.
    *
    * @param message
    *   The message to append.
    *
    * @return
    *   A copy of this chat ending with the message.
    */
  def appended(message: Message): Chat = copy(messages = messages :+ message)

  /**
    * Appends a user message saying each value as its [[Content]] says it: text
    * as text, a part as itself, such as the result of a tool the model called,
    * and any other value as JSON, of any kinds together. A list of parts is
    * given as `parts*`, and one of other values as
    * `values.map(MessagePart.of)*`.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this chat ending with the message.
    */
  def userSays(content: into[MessagePart]*): Chat =
    appended(Message(Role.User, content*))

  /**
    * Appends a message of the model's saying each value as its [[Content]] says
    * it, as of a value [[ModelClient.ask]] gave, to continue from.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this chat ending with the message.
    */
  def modelSays(content: into[MessagePart]*): Chat =
    appended(Message(Role.Model, content*))

  /**
    * Appends the model's response, tool calls included.
    *
    * @param response
    *   The response to append.
    *
    * @return
    *   A copy of this chat ending with the response.
    */
  def modelSays(response: ModelResponse): Chat = appended(Message(
    Role.Model,
    Option
      .when(response.text.nonEmpty)(MessagePart.Text(response.text))
      .toList ++ response.toolCalls,
  ))

  /**
    * Sets the system message.
    *
    * @param instructions
    *   The system message.
    *
    * @return
    *   A copy of this chat with the system message.
    */
  def withInstructions(instructions: String): Chat =
    copy(instructions = Some(instructions))

  /** Why no provider would answer this chat, where none would. */
  private[iris] def unanswerable: Option[String] =
    if messages.isEmpty then Some("it has no messages")
    else if messages.exists(_.silent) then
      Some("one of its messages says nothing")
    else if !messages.exists(_.role == Role.User) then
      Some("the user says nothing in it")
    else None

  /**
    * The prefix of this chat to cache: everything up to and including its last
    * [[MessagePart.CacheBreakpoint]], followed where needed by a minimal user
    * message so that it can be sent. Without a breakpoint, it holds no messages
    * and so cannot be sent.
    */
  def cachePrefix: Chat =
    val last =
      messages.lastIndexWhere(_.parts.contains(MessagePart.CacheBreakpoint))
    copy(messages =
      if last < 0 then List.empty
      else
        val message = messages(last)
        asked(
          messages.take(last) :+ message.copy(parts =
            message
              .parts
              .take(message.parts.lastIndexOf(MessagePart.CacheBreakpoint) + 1),
          ),
        ),
    )

  /**
    * Ends the messages with the user's. Breakpoints that a last message holds
    * alone move onto the user's message before it, or else into a minimal user
    * message.
    */
  private def asked(prefix: List[Message]): List[Message] =
    val (leading, breakpoints) = prefix.lastOption.filter(_.silent) match
      case Some(empty) => (prefix.init, empty.parts)
      case None        => (prefix, List.empty)
    leading.lastOption.filter(_.role == Role.User) match
      case Some(last) => leading.init :+
          last.copy(parts = last.parts ++ breakpoints)
      case None => leading :+ Message(
          Role.User,
          breakpoints :+ MessagePart.Text("."),
        )

object Chat:

  /**
    * Creates a chat of one user message, as a single-turn prompt, saying each
    * value as its [[Content]] says it, as [[Chat.userSays]] does. A system
    * message is set with [[Chat.withInstructions]].
    *
    * @param content
    *   What the user says, in reading order: text, parts, or values said as
    *   JSON, of any kinds together.
    *
    * @return
    *   A chat of one user message.
    */
  def userSays(content: into[MessagePart]*): Chat = Chat().userSays(content*)
