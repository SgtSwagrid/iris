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
private[iris] object Question:

  /** The most replies an ask asks for, unless told otherwise. */
  val attempts: Int = 3

  /**
    * Asks the model through a client's `send` until its reply can be read or
    * asks for a tool, telling it what was wrong with each that could not, so
    * long as asking again could help. A chat no provider would answer is sent
    * uninstructed, so that the provider refuses it before any request.
    */
  def ask[F[_] : MonadThrow, A : Schema as A]
    (
      send: Chat => F[Reply],
      chat: Chat,
      attempts: Int,
    )
    : F[Answer[F, A]] =
    // Sends the chat, after the replies so far, and reads what comes back.
    def attempt(chat: Chat, replies: Vector[Reply]): F[Answer[F, A]] = send(
      chat,
    ).flatMap(reply =>
      verdict(A, chat, replies :+ reply, attempts) match
        case Verdict.Answered(value, whole) =>
          Answer(value, whole.pure[F], MonadThrow[F].unit).pure[F]
        case Verdict.Again(next)   => attempt(next, replies :+ reply)
        case Verdict.Failed(error) => error.raiseError,
    )
    counted(attempts).fold(
      _.raiseError,
      _ => attempt(opening(chat, A), Vector.empty),
    )

  /** What the model is told of the form its reply must take. */
  def instructions(schema: Schema[?]): String =
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
    * The chat to send first: instructed, unless no provider would answer it, so
    * that the provider refuses it before any request.
    */
  def opening(chat: Chat, schema: Schema[?]): Chat = chat
    .unanswerable
    .fold(instructed(chat, instructions(schema)))(_ => chat)

  /**
    * What becomes of an ask once its latest reply has come: its answer, none
    * where the model asked for tools, a failure where asking again could not
    * help, or another attempt, told what was wrong.
    */
  def verdict[A]
    (
      schema: Schema[A],
      chat: Chat,
      replies: Vector[Reply],
      attempts: Int,
    )
    : Verdict[A] =
    val reply = replies.last
    if calledTools(reply) then Verdict.Answered(None, summed(replies))
    else
      hopeless(reply) match
        case Some(fatal) => Verdict.Failed(unreadable(replies, fatal))
        case None        => read(schema, reply).leftMap(stopped(reply, _)) match
            case Right(value) => Verdict.Answered(Some(value), summed(replies))
            case Left(why) if replies.size < attempts =>
              Verdict.Again(corrected(chat, reply, why))
            case Left(why) => Verdict.Failed(unreadable(replies, why))

  /** What becomes of an ask once its latest reply has come. */
  enum Verdict[+A]:

    /** The value, where the model gave one, and the reply it was read from. */
    case Answered(value: Option[A], reply: Reply)

    /** Another attempt, at the chat told what was wrong. */
    case Again(chat: Chat)

    /** The failure, where asking again could not help. */
    case Failed(error: LlmError)

  /** Why the replies asked for could not be read, as the last could not. */
  def unreadable(replies: Vector[Reply], detail: String): LlmError = LlmError
    .Unreadable(replies.size, detail, replies.toList)

  /**
    * Whether a reply asks for tools, by its calls or why it stopped, as OpenAI
    * says it stopped as usual for a call it was made to make. Calls count only
    * where it stopped as it would for them: one cut off at the token limit may
    * be cut short, and one the provider stopped, as on a refusal, is no call.
    */
  def calledTools(reply: Reply): Boolean =
    reply.stopReason == StopReason.ToolCall ||
    reply.toolCalls.nonEmpty &&
    (reply.stopReason == StopReason.Completed ||
    reply.stopReason == StopReason.StopSequence)

  /**
    * The last reply, counting every attempt: its usage is theirs together, and
    * its cost too, where each has one.
    */
  def summed(replies: Vector[Reply]): Reply = replies
    .last
    .copy(
      usage = replies.map(_.usage).reduce(_ + _),
      cost = replies.traverse(_.cost).map(_.reduce(_ + _)),
      attempts = replies.size,
    )

  /** The answer a reply holds, or why it holds none. */
  private def read[A](schema: Schema[A], reply: Reply): Either[String, A] =
    parsed(reply.text).flatMap(decoded(schema, _))

  /**
    * Why no reading of a reply could be trusted, nor asking again help, where
    * none could: it was cut short, so that even what parses may be cut, as `4`
    * of `42`, stopped for a reason of the provider's, such as a refusal, or
    * said nothing. A reply ended by a stop sequence is read, and if it cannot
    * be, asked for again, told so, as only Anthropic reports one.
    */
  def hopeless(reply: Reply): Option[String] = reply.stopReason match
    case StopReason.MaxTokens =>
      Some("It was cut off at the token limit before it was complete.")
    case StopReason.Other(reason)     => Some(s"It stopped, as `$reason`.")
    case _ if reply.text.trim.isEmpty => Some("It said nothing.")
    case _                            => None

  /** Why a reply could not be read, said to have ended at a stop sequence. */
  private def stopped(reply: Reply, why: String): String =
    if reply.stopReason == StopReason.StopSequence then
      s"It stopped at a stop sequence. $why"
    else why

  /**
    * Closes the user's last message with the instructions, or else, after the
    * assistant's, adds them in a message of their own, so that the model
    * answers afresh. A cached prefix is sent as it was.
    */
  private def instructed(chat: Chat, instructions: String): Chat =
    chat.messages.lastOption.filter(_.role == Role.User) match
      case Some(last) => chat.copy(messages =
          chat.messages.init :+
            last.copy(content = last.content :+ Part.Text(instructions)),
        )
      case None => chat.user(instructions)

  /** The chat continued with a reply that said something, and why it failed. */
  private def corrected(chat: Chat, reply: Reply, why: String): Chat = chat
    .assistant(reply.text)
    .user(s"That reply could not be read:\n$why\n\nReply again as asked.")

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
