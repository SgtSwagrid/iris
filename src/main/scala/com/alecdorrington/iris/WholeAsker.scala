package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import scala.util.matching.Regex

/**
  * The asking of an answer, one way for every type: the model is told the
  * type's JSON Schema, and its reply read as JSON conforming to it, or answered
  * with why it could not be and asked again.
  */
private[iris] object WholeAsker:

  /** The most replies an ask asks for, unless told otherwise. */
  val attempts: Int = 3

  /**
    * Asks the model through a client's `send` until its reply can be read or
    * asks for a tool, telling it what was wrong with each that could not, so
    * long as asking again could help, and running the tools in the box, where
    * it asks for those alone, before asking it to go on, each request after
    * them [[cached]]. A conversation no provider would answer is sent
    * uninstructed, so that the provider refuses it before any request.
    */
  def ask[F[_] : MonadThrow, A : Schema as A]
    (
      send: Conversation => F[ModelResponse],
      conversation: Conversation,
      attempts: Int,
      tools: Toolbox[F] = Toolbox[F](Nil),
    )
    : F[ValidatedResponse[F, A]] =
    val start = opening(conversation, schemaPrompt(A))
    // Sends the conversation, after the replies so far, `tried` of them read.
    def attempt
      (
        conversation: Conversation,
        replies: Vector[ModelResponse],
        tried: Int,
      )
      : F[ValidatedResponse[F, A]] =
      send(cached(start, conversation)).flatMap(reply =>
        verdict(
          A,
          conversation,
          replies :+ reply,
          attempts,
          tried + 1,
          tools,
        ) match
          case Verdict.Answered(value, responses) => ValidatedResponse(
              value,
              responses.pure[F],
              MonadThrow[F].unit,
            ).pure[F]
          case Verdict.Run(calls) => tools
              .ran(calls)
              .flatMap(results =>
                attempt(
                  onwards(conversation, reply, results),
                  replies :+ reply,
                  tried,
                ),
              )
          case Verdict.Again(next) => attempt(next, replies :+ reply, tried + 1)
          case Verdict.Failed(error) => error.raiseError,
      )
    counted(attempts).fold(
      _.raiseError,
      _ => attempt(start, Vector.empty, 0),
    )

  /** The conversation after a reply asking for tools, and what they gave. */
  def onwards
    (
      conversation: Conversation,
      reply: ModelResponse,
      results: List[MessagePart.ToolResult],
    )
    : Conversation = conversation.modelSays(reply).toolSays(results*)

  /**
    * The conversation to send, its end closed by a breakpoint once tools have
    * been run since the `opening`, so that each request after a round reads the
    * rounds before it from the cache, and writes only its own. The first after
    * the opening writes all past the host's last breakpoint, the whole prompt
    * where there is none. Only the newest end is marked: Anthropic, which
    * caches only where told, finds the last write by itself, looking up to
    * twenty blocks back (a run of tool calls, or of their results, counting as
    * one), and marks at most four blocks, the host's first. The conversation an
    * ask goes on with is never marked, so that the mark moves on with each
    * request.
    */
  def cached
    (
      opening: Conversation,
      conversation: Conversation,
    )
    : Conversation =
    if conversation
        .messages
        .drop(opening.messages.size)
        .exists(_.isInstanceOf[Message.Tool])
    then
      conversation.copy(messages =
        conversation.messages.init :+ conversation.messages.last.closed,
      )
    else conversation

  /** What the model is told of the form its reply must take. */
  def schemaPrompt(schema: Schema[?]): String =
    "Reply with JSON alone, with no other text and no code fence, " +
      s"conforming to this JSON Schema:\n${ schema.json.noSpaces }"

  /** Refuses fewer than one attempt. */
  def counted(attempts: Int): Either[IllegalArgumentException, Unit] = Either
    .cond(
      attempts >= 1,
      (),
      IllegalArgumentException(
        s"An ask needs at least one attempt, not $attempts.",
      ),
    )

  /**
    * The conversation to send first: instructed, unless no provider would
    * answer it, so that the provider refuses it before any request.
    */
  def opening
    (
      conversation: Conversation,
      prompt: String,
    )
    : Conversation = conversation
    .unanswerable
    .fold(instructed(conversation, prompt))(_ => conversation)

  /**
    * What becomes of an ask once its latest reply has come, the `tried`th read
    * as an answer: the tools it asks for run, where it asks for those in the
    * box alone, its answer, none where it asked for others, a failure where
    * asking again could not help, or another attempt, told what was wrong.
    */
  def verdict[A]
    (
      schema: Schema[A],
      conversation: Conversation,
      replies: Vector[ModelResponse],
      attempts: Int,
      tried: Int,
      tools: Toolbox[?],
    )
    : Verdict[A] =
    val reply = replies.last
    if calledTools(reply) then
      if tools.runs(reply.toolCalls) then Verdict.Run(reply.toolCalls)
      else Verdict.Answered(None, replies.toList)
    else
      hopeless(reply) match
        case Some(fatal) => Verdict.Failed(unreadable(replies, fatal))
        case None        => read(schema, reply).leftMap(stopped(reply, _)) match
            case Right(value) => Verdict.Answered(Some(value), replies.toList)
            case Left(why) if tried < attempts =>
              Verdict.Again(corrected(conversation, reply, why))
            case Left(why) => Verdict.Failed(unreadable(replies, why))

  /** What becomes of an ask once its latest reply has come. */
  enum Verdict[+A]:

    /** The value, where the model gave one, and every response asked for. */
    case Answered
      (
        value: Option[A],
        responses: List[ModelResponse],
      )

    /** The tools asked for, to run before asking the model to go on. */
    case Run(calls: List[MessagePart.ToolCall])

    /** Another attempt, at the conversation told what was wrong. */
    case Again(conversation: Conversation)

    /** The failure, where asking again could not help. */
    case Failed(error: ModelError)

  /** Why the replies asked for could not be read, as the last could not. */
  def unreadable
    (
      replies: Vector[ModelResponse],
      detail: String,
    )
    : ModelError = ModelError.UnreadableResponse(detail, replies.toList)

  /**
    * Whether a reply asks for tools, by its calls or why it stopped, as OpenAI
    * says it stopped as usual for a call it was made to make. Calls count only
    * where it stopped as it would for them: one cut off at the token limit may
    * be cut short, and one the provider stopped, as on a refusal, is no call.
    */
  def calledTools(reply: ModelResponse): Boolean =
    reply.stopReason == StopReason.ToolCall ||
    reply.toolCalls.nonEmpty &&
    (reply.stopReason == StopReason.Completed ||
    reply.stopReason == StopReason.StopSequence)

  /** The answer a reply holds, or why it holds none. */
  def read[A](schema: Schema[A], reply: ModelResponse): Either[String, A] =
    parsed(reply.text).flatMap(decoded(schema, _))

  /**
    * Why no reading of a reply could be trusted, nor asking again help, where
    * none could: it was cut short, so that even what parses may be cut, as `4`
    * of `42`, stopped for a reason of the provider's, such as a refusal, or
    * said nothing. A reply ended by a stop sequence is read, and if it cannot
    * be, asked for again, told so, as only Anthropic reports one.
    */
  def hopeless(reply: ModelResponse): Option[String] = reply.stopReason match
    case StopReason.MaxTokens =>
      Some("It was cut off at the token limit before it was complete.")
    case StopReason.Other(reason)     => Some(s"It stopped, as `$reason`.")
    case _ if reply.text.trim.isEmpty => Some("It said nothing.")
    case _                            => None

  /** Why a reply could not be read, said to have ended at a stop sequence. */
  private def stopped(reply: ModelResponse, why: String): String =
    if reply.stopReason == StopReason.StopSequence then
      s"It stopped at a stop sequence. $why"
    else why

  /**
    * Closes the user's last message with the prompt, or else, after the
    * model's, adds it in a message of its own, so that the model answers
    * afresh. A cached prefix is sent as it was.
    */
  private def instructed
    (
      conversation: Conversation,
      prompt: String,
    )
    : Conversation = conversation.messages.lastOption match
    case Some(Message.User(parts)) => conversation.copy(messages =
        conversation.messages.init :+
          Message.User(parts :+ MessagePart.Text(prompt)),
      )
    case _ => conversation.userSays(prompt)

  /**
    * The conversation continued with a reply that said something, and why it
    * failed.
    */
  private def corrected
    (
      conversation: Conversation,
      reply: ModelResponse,
      why: String,
    )
    : Conversation = conversation
    .modelSays(reply.text)
    .userSays(s"That reply could not be read:\n$why\n\nReply again as asked.")

  /**
    * The JSON of a reply: the whole reply, else what its last code fence holds,
    * as of a draft followed by an answer. A fence within a JSON string is part
    * of it.
    */
  private def parsed(text: String): Either[String, Json] =
    (text.trim :: fenced(text).toList)
      .map(parse)
      .collectFirst { case Right(json) => json }
      .toRight("It is not JSON.")

  /** What the last code fence holds, its language named or not. */
  private def fenced(text: String): Option[String] = fence
    .findAllMatchIn(text)
    .toList
    .lastOption
    .map(_.group(1))

  private val fence: Regex = """(?s)```(?:[\w-]+\s)?\s*(.*?)\s*```""".r

  /** Every failure to decode the JSON, as circe says it. */
  def decoded[A](schema: Schema[A], json: Json): Either[String, A] = schema
    .decoder
    .decodeAccumulating(json.hcursor)
    .toEither
    .leftMap(_.map(_.getMessage).toList.mkString("\n"))
