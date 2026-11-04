package com.alecdorrington.iris

import java.util.Base64
import scala.Conversion.into
import scala.compiletime.summonInline

/**
  * A part of a message. A message is a list of parts, so that text and media
  * travel together in reading order. Each owner of a message writes parts of
  * its own kinds: [[UserPart]]s, [[ModelPart]]s and [[ToolPart]]s.
  */
sealed trait MessagePart

/** A part of a message of the user's: text, media or a cache breakpoint. */
sealed trait UserPart extends MessagePart

object UserPart:

  /**
    * Says a value as a part wherever a user's message takes one, as its
    * [[Content]] says it, so that values of different kinds mix in one message.
    * A value whose type has no content is refused where it is given.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a value to the text saying it.
    */
  inline given fromContent[A]: Conversion[A, UserPart] =
    MessagePart.converted(summonInline[Content[A]])

/** A part of a message of the model's: text, a tool call or a breakpoint. */
sealed trait ModelPart extends MessagePart

object ModelPart:

  /**
    * Says a value as a part wherever a model's message takes one, as its
    * [[Content]] says it, as of a value an ask gave, to go on from.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a value to the text saying it.
    */
  inline given fromContent[A]: Conversion[A, ModelPart] =
    MessagePart.converted(summonInline[Content[A]])

/** A part of a message of the tools': a tool's result or a breakpoint. */
sealed trait ToolPart extends MessagePart

object MessagePart:

  /**
    * A part of written text. Two in a row, with nothing but breakpoints between
    * them, are sent as paragraphs of their own, a blank line apart, since every
    * provider would otherwise run them together.
    *
    * @param text
    *   The text.
    */
  final case class Text(text: String) extends UserPart, ModelPart

  /**
    * Creates the text saying a value, as its [[Content]] says it: text as text,
    * and a value with a schema or an encoder as JSON. A value given to a
    * conversation is said so already; this makes parts of a list of values to
    * give it, as `conversation.userSays(values.map(MessagePart.of)*)`.
    *
    * @tparam A
    *   The type of the value.
    *
    * @param value
    *   The value.
    *
    * @return
    *   The text saying the value.
    */
  def of[A : Content as A](value: A): Text = A.text(value)

  /** Converts by a content, so that no function is inlined where one is used. */
  private[iris] def converted[A, P >: Text]
    (content: Content[A])
    : Conversion[A, P] = content.text(_)

  /**
    * A picture, document or other media for the model to look at. A provider
    * refuses media it cannot take with [[PromptError.Unsupported]], before any
    * request: Anthropic takes every [[MediaType]] Iris lists, and OpenAI its
    * pictures alone. Gemini is sent any media, and judges it itself.
    *
    * @param mediaType
    *   The kind of media.
    *
    * @param data
    *   The content, Base64 encoded.
    */
  final case class Media(mediaType: MediaType, data: String) extends UserPart

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
    * message, and is sent back with the conversation as the response gave it.
    *
    * @param id
    *   The provider's identifier of the call, by which its result is matched.
    *   Where Gemini gives none, the tool's name stands in, and is not sent.
    *
    * @param tool
    *   The name of the [[Tool]] to run.
    *
    * @param arguments
    *   The arguments, as the model wrote them: JSON described by
    *   [[Tool.parameters]], unless the model wrote something else, which the
    *   tool is then told.
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
      arguments: String,
      signature: Option[String] = None,
    )
    extends ModelPart

  /**
    * The output of a tool, answering a [[ToolCall]] in the tools' next message.
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
    extends ToolPart

  /**
    * The end of a prefix for the provider to cache: everything before it, the
    * system message included, is shared by many requests. It says nothing to
    * the model.
    *
    * Anthropic caches only at breakpoints, and refuses a conversation whose
    * breakpoints mark more than four blocks with [[PromptError.Unsendable]],
    * but for any closing the conversation, which mark its last block only where
    * the others leave room; OpenAI and Gemini cache long prefixes by
    * themselves. A prefix too short for the provider is not cached. Requests
    * sent at once cannot read one another's writes, so call
    * [[PromptService.warmCache]] before a batch. An ask running tools closes
    * each request after a round of them with one of its own, so that the rounds
    * before it are read from the cache.
    */
  case object CacheBreakpoint extends UserPart, ModelPart, ToolPart

/**
  * A single message in a [[Conversation]], one case for each of its owners,
  * holding only the parts that owner writes.
  */
enum Message:

  /**
    * A message of the person or application conversing with the model.
    *
    * @param parts
    *   The parts of the message, in reading order.
    */
  case User(parts: List[UserPart])

  /**
    * A message of the model's: what it wrote, and the tools it called.
    *
    * @param parts
    *   The parts of the message, in reading order.
    */
  case Model(parts: List[ModelPart])

  /**
    * A message of the tools the model called: their results.
    *
    * @param parts
    *   The parts of the message, in the order of the calls.
    */
  case Tool(parts: List[ToolPart])

  /** The parts of this message, in reading order. */
  def parts: List[MessagePart]

  /** The text of this message, a paragraph per text part, media left out. */
  def text: String = parts
    .collect:
      case MessagePart.Text(text) if text.nonEmpty => text
    .mkString(Message.paragraphBreak)

  /** This message without its cache breakpoints. */
  def withoutBreakpoints: Message =
    edited([P] => (parts: List[P], mark: P) => parts.filterNot(_ == mark))

  /** This message ending with a cache breakpoint. */
  private[iris] def closed: Message =
    edited([P] => (parts: List[P], mark: P) => parts :+ mark)

  /** This message without the cache breakpoints ending it. */
  private[iris] def unclosed: Message = edited([P] =>
    (parts: List[P], mark: P) => parts.reverse.dropWhile(_ == mark).reverse,
  )

  /** This message's first parts, as many as given. */
  private[iris] def taken(count: Int): Message =
    edited([P] => (parts: List[P], _: P) => parts.take(count))

  /**
    * This message, its parts edited alike whoever owns it, given the cache
    * breakpoint, the one part every owner writes.
    */
  private def edited(edit: [P] => (List[P], P) => List[P]): Message = this match
    case User(parts)  => User(edit(parts, MessagePart.CacheBreakpoint))
    case Model(parts) => Model(edit(parts, MessagePart.CacheBreakpoint))
    case Tool(parts)  => Tool(edit(parts, MessagePart.CacheBreakpoint))

  /** Whether this message holds only breakpoints, which no provider accepts. */
  private[iris] def silent: Boolean =
    parts.forall(_ == MessagePart.CacheBreakpoint)

object Message:

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
  * request. To go on with a conversation, append the model's response and the
  * user's next message, then send the conversation again:
  *
  * {{{
  * for
  *   first  <- query.send[String](conversation)
  *   asked  <- first.iterations
  *   next    = conversation.modelSays(asked.last).userSays("Tell me more.")
  *   second <- query.send[String](next)
  * yield second.value
  * }}}
  *
  * Asked of a conversation ending with the model's message, the model is told
  * what to answer in a message of the user's after it, and so answers afresh
  * rather than going on with its own response. A conversation with no messages,
  * a message of breakpoints alone, or no message of the user's fails with
  * [[PromptError.Unsendable]] before any request is made.
  *
  * @param messages
  *   The messages exchanged so far, oldest first.
  *
  * @param instructions
  *   The instructions, if any, setting the model's general behaviour, sent as
  *   the provider's system message.
  */
final case class Conversation
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
    *   A copy of this conversation ending with the message.
    */
  def appended(message: Message): Conversation =
    copy(messages = messages :+ message)

  /**
    * Appends a message of the user's saying each value as its [[Content]] says
    * it: text as text, a part as itself, and any other value as JSON, of any
    * kinds together. A list of parts is given as `parts*`, and one of other
    * values as `values.map(MessagePart.of)*`.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this conversation ending with the message.
    */
  def userSays(content: into[UserPart]*): Conversation =
    appended(Message.User(content.toList))

  /**
    * Appends a message of the model's saying each value as its [[Content]] says
    * it, as of a value [[QueryService.send]] gave, to go on from.
    *
    * @param content
    *   The values, in reading order.
    *
    * @return
    *   A copy of this conversation ending with the message.
    */
  def modelSays(content: into[ModelPart]*): Conversation =
    appended(Message.Model(content.toList))

  /**
    * Appends the model's response, tool calls included.
    *
    * @param response
    *   The response to append.
    *
    * @return
    *   A copy of this conversation ending with the response.
    */
  def modelSays(response: PromptResponse): Conversation =
    appended(response.message)

  /**
    * Appends a message of the tools' results, answering the calls the model's
    * last message made.
    *
    * @param parts
    *   The results, in the order of the calls, and any breakpoints.
    *
    * @return
    *   A copy of this conversation ending with the message.
    */
  def toolSays(parts: ToolPart*): Conversation =
    appended(Message.Tool(parts.toList))

  /**
    * Sets the system message.
    *
    * @param instructions
    *   The system message.
    *
    * @return
    *   A copy of this conversation with the system message.
    */
  def withInstructions(instructions: String): Conversation =
    copy(instructions = Some(instructions))

  /** Why no provider would answer this conversation, where none would. */
  private[iris] def unanswerable: Option[String] =
    if messages.isEmpty then Some("it has no messages")
    else if messages.exists(_.silent) then
      Some("one of its messages says nothing")
    else if !messages.exists(_.isInstanceOf[Message.User]) then
      Some("the user says nothing in it")
    else None

  /**
    * The prefix of this conversation to cache: everything up to and including
    * its last [[MessagePart.CacheBreakpoint]], followed where needed by a
    * minimal user message so that it can be sent. Without a breakpoint, it
    * holds no messages and so cannot be sent.
    */
  def cachePrefix: Conversation =
    val last =
      messages.lastIndexWhere(_.parts.contains(MessagePart.CacheBreakpoint))
    copy(messages =
      if last < 0 then List.empty
      else
        val message = messages(last)
        answerable(
          messages.take(last) :+ message.taken(
            message.parts.lastIndexOf(MessagePart.CacheBreakpoint) + 1,
          ),
        ),
    )

  /**
    * Ends the messages with the user's or the tools'. Breakpoints that a last
    * message holds alone move onto the message before it, or else into a
    * minimal user message.
    */
  private def answerable(prefix: List[Message]): List[Message] =
    val (leading, breakpoints) = prefix.lastOption.filter(_.silent) match
      case Some(empty) => (prefix.init, empty.parts.size)
      case None        => (prefix, 0)
    leading.lastOption.filterNot(_.isInstanceOf[Message.Model]) match
      case Some(last) => leading.init :+
          Iterator.iterate(last)(_.closed).drop(breakpoints).next()
      case None => leading :+ Message.User(
          List.fill(breakpoints)(MessagePart.CacheBreakpoint) :+
            MessagePart.Text("."),
        )

object Conversation:

  /**
    * Creates a conversation of one user message, as a single-turn prompt,
    * saying each value as its [[Content]] says it, as [[Conversation.userSays]]
    * does. A system message is set with [[Conversation.withInstructions]].
    *
    * @param content
    *   What the user says, in reading order: text, parts, or values said as
    *   JSON, of any kinds together.
    *
    * @return
    *   A conversation of one user message.
    */
  def userSays(content: into[UserPart]*): Conversation = Conversation()
    .userSays(content*)
