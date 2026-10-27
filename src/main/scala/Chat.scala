package com.alecdorrington.iris

import io.circe.Json

/** The author of a [[Message]] in a [[Chat]]. */
enum Role:

  /** The person or application conversing with the model. */
  case User

  /** The model itself. */
  case Assistant

  /** The role's name on the wire, where a provider uses this one. */
  def code: String = this match
    case User      => "user"
    case Assistant => "assistant"

/**
  * A part of a message. A message is a list of parts, so that text and media
  * travel together in reading order.
  */
sealed trait Part

object Part:

  /**
    * A part of written text. Two in a row, with nothing but breakpoints between
    * them, are sent as paragraphs of their own, a blank line apart, since every
    * provider would otherwise run them together.
    *
    * @param text
    *   The text.
    */
  final case class Text(text: String) extends Part

  /**
    * Creates a part saying a value, as its [[Content]] says it: text as text,
    * and a structured or encodable value as JSON. Parts in a row make a message
    * of several, as of an instruction and the value it is about.
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
  def of[A : Content as A](value: A): Part = A.part(value)

  /**
    * A picture, document or other media for the model to look at.
    *
    * @param mediaType
    *   The IANA media type of the data, such as `image/png`.
    *
    * @param data
    *   The content, Base64 encoded.
    */
  final case class Media(mediaType: String, data: String) extends Part

  /**
    * A call by the model for a tool to be run. It is part of the model's
    * message, and is sent back with the conversation.
    *
    * @param id
    *   The provider's identifier of the call, by which its result is matched.
    *   Gemini has none, so the tool's name stands in.
    *
    * @param tool
    *   The name of the [[Tool]] to run.
    *
    * @param arguments
    *   The arguments, as described by [[Tool.parameters]].
    */
  final case class ToolCall(id: String, tool: String, arguments: Json)
    extends Part

  /**
    * The output of a tool, answering a [[ToolCall]] in the user's next message.
    *
    * @param callId
    *   The [[ToolCall.id]] this answers.
    *
    * @param tool
    *   The name of the tool run, which Gemini matches by.
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
    extends Part

  /**
    * The end of a prefix for the provider to cache: everything before it, the
    * system message included, is shared by many requests. It says nothing to
    * the model.
    *
    * Anthropic caches only at breakpoints, and refuses a chat whose breakpoints
    * mark more than four blocks with [[LlmError.Unsendable]]; OpenAI and Gemini
    * cache long prefixes by themselves. A prefix too short for the provider is
    * not cached. Requests sent at once cannot read one another's writes, so
    * call [[LlmClient.warm]] before a batch.
    */
  case object CacheBreakpoint extends Part

/**
  * A single message in a [[Chat]].
  *
  * @param role
  *   The author of the message.
  *
  * @param content
  *   The parts of the message, in reading order.
  */
final case class Message(role: Role, content: List[Part]):

  /** The text of this message, a paragraph per text part, media left out. */
  def text: String = content
    .collect:
      case Part.Text(text) if text.nonEmpty => text
    .mkString(Message.paragraphBreak)

  /** This message without its cache breakpoints. */
  def uncached: Message =
    copy(content = content.filterNot(_ == Part.CacheBreakpoint))

  /** Whether this message holds only breakpoints, which no provider accepts. */
  private[iris] def silent: Boolean = content.forall(_ == Part.CacheBreakpoint)

  /** The tool results this message carries. */
  def toolResults: List[Part.ToolResult] = content.collect:
    case result: Part.ToolResult => result

object Message:

  /**
    * Creates a message of one part, saying a value as its [[Content]] says it.
    *
    * @tparam A
    *   The type of the value.
    *
    * @param role
    *   The author of the message.
    *
    * @param value
    *   The value: text, a part, or a value said as JSON.
    *
    * @return
    *   A message of one part.
    */
  def apply[A : Content as A](role: Role, value: A): Message =
    Message(role, List(A.part(value)))

  /** What is sent between two text parts in a row. */
  private[iris] val paragraphBreak: String = "\n\n"

  /**
    * The parts as sent: each text part saying something opens with a
    * [[paragraphBreak]] where one said something before it with nothing but
    * breakpoints between. A part is sent as the parts before it decide, never
    * those after it, so that a cached prefix is sent alike whatever follows.
    */
  private[iris] def separated(parts: List[Part]): List[Part] = parts
    .foldLeft((Vector.empty[Part], false)):
      case ((sent, afterText), Part.Text(text)) if text.nonEmpty =>
        val opened = if afterText then paragraphBreak + text else text
        (sent :+ Part.Text(opened), true)
      case ((sent, afterText), part @ (Part.Text(_) | Part.CacheBreakpoint)) =>
        (sent :+ part, afterText)
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
  *   first  <- client.ask[String](chat)
  *   next    = chat.assistant(first).user("Tell me more.")
  *   second <- client.ask[String](next)
  * yield second.answer
  * }}}
  *
  * A chat should end with the user's message: one ending with the assistant's
  * asks the model to continue its own reply. An empty chat, a message of
  * breakpoints alone, or a reply that a newer Anthropic model would have to
  * continue fails with [[LlmError.Unsendable]] before any request is made.
  *
  * @param messages
  *   The messages exchanged so far, oldest first.
  *
  * @param system
  *   The system message, if any, setting the model's general behaviour.
  */
final case class Chat
  (
    messages: List[Message] = List.empty,
    system: Option[String] = None,
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
    * and any other value as JSON. Values of different kinds are given as parts,
    * by [[Part.of]].
    *
    * @tparam A
    *   The type of the values.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this chat ending with the message.
    */
  def user[A : Content](content: A*): Chat =
    appended(Chat.said(Role.User, content))

  /**
    * Appends an assistant message saying each value as its [[Content]] says it,
    * as of an answer [[LlmClient.ask]] gave, to continue from.
    *
    * @tparam A
    *   The type of the values.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this chat ending with the message.
    */
  def assistant[A : Content](content: A*): Chat =
    appended(Chat.said(Role.Assistant, content))

  /**
    * Appends the model's reply, tool calls included.
    *
    * @param reply
    *   The reply to append.
    *
    * @return
    *   A copy of this chat ending with the reply.
    */
  def assistant(reply: Reply[?]): Chat = appended(Message(
    Role.Assistant,
    Part.Text(reply.text) +: reply.toolCalls,
  ))

  /**
    * Sets the system message.
    *
    * @param system
    *   The system message.
    *
    * @return
    *   A copy of this chat with the system message.
    */
  def withSystem(system: String): Chat = copy(system = Some(system))

  /**
    * The prefix of this chat to cache: everything up to and including its last
    * [[Part.CacheBreakpoint]], followed where needed by a minimal user message
    * so that it can be sent. Without a breakpoint, it holds no messages and so
    * cannot be sent.
    */
  def cacheable: Chat =
    val last = messages.lastIndexWhere(_.content.contains(Part.CacheBreakpoint))
    copy(messages =
      if last < 0 then List.empty
      else
        val message = messages(last)
        asked(
          messages.take(last) :+ message.copy(content =
            message
              .content
              .take(message.content.lastIndexOf(Part.CacheBreakpoint) + 1),
          ),
        ),
    )

  /**
    * Ends the messages with the user's. Breakpoints that a last message holds
    * alone move onto the user's message before it, or else into a minimal user
    * message.
    */
  private def asked(prefix: List[Message]): List[Message] =
    val (said, breakpoints) = prefix.lastOption.filter(_.silent) match
      case Some(empty) => (prefix.init, empty.content)
      case None        => (prefix, List.empty)
    said.lastOption.filter(_.role == Role.User) match
      case Some(last) => said.init :+
          last.copy(content = last.content ++ breakpoints)
      case None => said :+ Message(Role.User, breakpoints :+ Part.Text("."))

object Chat:

  /**
    * Creates a chat of one user message, as a single-turn prompt, saying a
    * value as its [[Content]] says it. A system message is set with
    * [[Chat.withSystem]].
    *
    * @tparam A
    *   The type of the value.
    *
    * @param user
    *   What the user says: text, a part, or a value said as JSON.
    *
    * @return
    *   A chat of one user message.
    */
  def of[A : Content](user: A): Chat = Chat().user(user)

  /** A message saying each value as its content says it. */
  private def said[A : Content as A](role: Role, values: Seq[A]): Message =
    Message(role, values.map(A.part).toList)
