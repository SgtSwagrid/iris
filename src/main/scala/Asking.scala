package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.{DecodingFailure, Json, ParsingFailure}
import io.circe.DecodingFailure.Reason
import io.circe.parser.parse
import scala.util.matching.Regex

/**
  * The asking of an answer, one way for every type: the model is told the
  * type's JSON Schema, and its reply read as JSON conforming to it, or answered
  * with why it could not be and asked again.
  */
private[iris] object Asking:

  /** The most replies an ask asks for, unless told otherwise. */
  val attempts: Int = 3

  /**
    * Asks the model through a client's `send` until its reply can be read or
    * asks for a tool, telling it what was wrong with each that could not, so
    * long as asking again could help.
    */
  def ask[F[_] : MonadThrow, A : Structured as A]
    (
      send: Chat => F[Reply[Nothing]],
      chat: Chat,
      attempts: Int,
    )
    : F[Reply[A]] =
    if attempts < 1 then
      MonadThrow[F].raiseError(IllegalArgumentException(
        s"An ask needs at least one attempt, not $attempts.",
      ))
    else
      Attempts(send, A, attempts).attempt(
        instructed(chat, instructions(A)),
        Vector.empty,
      )

  /** What the model is told of the form its reply must take. */
  def instructions(structured: Structured[?]): String =
    "Reply with JSON alone, with no other text and no code fence, " +
      s"conforming to this JSON Schema:\n${ structured.schema.noSpaces }"

  /** The attempts at one answer. */
  private final class Attempts[F[_] : MonadThrow, A]
    (
      send: Chat => F[Reply[Nothing]],
      structured: Structured[A],
      attempts: Int,
    ):

    def attempt
      (
        chat: Chat,
        replies: Vector[Reply[Nothing]],
      )
      : F[Reply[A]] =
      send(chat).flatMap(reply => answered(chat, replies :+ reply))

    /** The answer, none where the model asked for tools, or another attempt. */
    private def answered
      (
        chat: Chat,
        replies: Vector[Reply[Nothing]],
      )
      : F[Reply[A]] =
      val reply = replies.last
      if reply.stopReason == StopReason.ToolCall then
        summed(replies, None).pure[F]
      else
        read(structured, reply).leftMap(stopped(reply, _)) match
          case Right(value) => summed(replies, Some(value)).pure[F]
          case Left(why)    => hopeless(reply) match
              case Some(fatal)                     => unreadable(replies, fatal)
              case None if replies.size < attempts =>
                attempt(corrected(chat, reply, why), replies)
              case None => unreadable(replies, why)

    private def unreadable
      (
        replies: Vector[Reply[Nothing]],
        detail: String,
      )
      : F[Reply[A]] = LlmError
      .Unreadable(replies.size, detail, replies.toList)
      .raiseError

  /** The last reply with the answer read from it, counting every attempt. */
  private def summed[A]
    (
      replies: Vector[Reply[Nothing]],
      answer: Option[A],
    )
    : Reply[A] = replies
    .last
    .copy(
      usage = replies.flatMap(_.usage).reduceOption(_ + _),
      answer = answer,
      attempts = replies.size,
    )

  /** The answer a reply holds, or why it holds none. */
  private def read[A]
    (
      structured: Structured[A],
      reply: Reply[Nothing],
    )
    : Either[String, A] = parsed(reply.text).flatMap(decoded(structured, _))

  /**
    * Why asking again could not help a reply that could not be read, where it
    * could not: it was cut short, stopped for a reason of the provider's, such
    * as a refusal, or said nothing. A reply ended by a stop sequence is asked
    * for again, told so, as only Anthropic reports one.
    */
  private def hopeless(reply: Reply[Nothing]): Option[String] =
    reply.stopReason match
      case StopReason.MaxTokens =>
        Some("It was cut off at the token limit before it was complete.")
      case StopReason.Other(reason)     => Some(s"It stopped, as `$reason`.")
      case _ if reply.text.trim.isEmpty => Some("It said nothing.")
      case _                            => None

  /** Why a reply could not be read, said to have ended at a stop sequence. */
  private def stopped(reply: Reply[Nothing], why: String): String =
    if reply.stopReason == StopReason.StopSequence then
      s"It stopped at a stop sequence. $why"
    else why

  /**
    * Closes the user's last message with the instructions, or else adds them in
    * a message of their own, so that a cached prefix is sent as it was.
    */
  private def instructed(chat: Chat, instructions: String): Chat =
    chat.messages.lastOption.filter(_.role == Role.User) match
      case Some(last) => chat.copy(messages =
          chat.messages.init :+
            last.copy(content = last.content :+ Part.Text(instructions)),
        )
      case None => chat.user(instructions)

  /** The chat continued with a reply that said something, and why it failed. */
  private def corrected
    (
      chat: Chat,
      reply: Reply[Nothing],
      why: String,
    )
    : Chat = chat
    .assistant(reply.text)
    .user(s"That reply could not be read:\n$why\n\nReply again as asked.")

  /**
    * The JSON of a reply: the whole reply, else what its last code fence holds,
    * as of a draft followed by an answer, else its outermost object or array,
    * as prose may surround it. A fence within a JSON string is part of it.
    */
  private def parsed(text: String): Either[String, Json] =
    val fence = fenced(text)
    (text.trim :: fence.toList ++ outermost(text))
      .map(parse)
      .collectFirst { case Right(json) => json }
      .toRight(s"It is not JSON${ where(parse(fence.getOrElse(text.trim))) }.")

  /**
    * Where a parse failed, as its message says, without what it read there. A
    * parser in JavaScript says nowhere.
    */
  private def where(read: Either[ParsingFailure, Json]): String = read
    .left
    .toOption
    .flatMap(failure => position.findFirstIn(failure.message))
    .fold("")(" " + _)

  private val position: Regex = """\(line \d+, column \d+\)""".r

  /** What the last code fence holds, its language named or not. */
  private def fenced(text: String): Option[String] = fence
    .findAllMatchIn(text)
    .toList
    .lastOption
    .map(_.group(1))

  private val fence: Regex = """(?s)```(?:[\w-]+\s)?\s*(.*?)\s*```""".r

  /** The text from the first opening brace or bracket to the last closing one. */
  private def outermost(text: String): Option[String] =
    val start = text.indexWhere(c => c == '{' || c == '[')
    val end   = text.lastIndexWhere(c => c == '}' || c == ']')
    Option.when(start >= 0 && end > start)(text.substring(start, end + 1))

  private def decoded[A]
    (structured: Structured[A], json: Json)
    : Either[String, A] = structured
    .decoder
    .decodeAccumulating(json.hcursor)
    .toEither
    .leftMap(_.map(described).toList.mkString("\n"))

  /** A decoding failure, by where and why, without the values it met. */
  private def described(failure: DecodingFailure): String =
    s"At ${ place(failure) }: ${ reason(failure) }"

  /** Where a failure was met, which circe gives as empty at the top. */
  private def place(failure: DecodingFailure): String = failure
    .pathToRootString
    .filter(_.nonEmpty)
    .getOrElse("the top")

  private def reason(failure: DecodingFailure): String = failure.reason match
    case Reason.WrongTypeExpectation(expected, json) =>
      s"Expected ${ article(expected) }, not ${ kind(json) }"
    case Reason.MissingField          => "Missing required field"
    case Reason.CustomReason(message) => message

  /** A JSON type's name with its article, as `an integer`. */
  private def article(kind: String): String = kind match
    case "boolean"                           => "a Boolean"
    case vowel if "aeiou".contains(vowel(0)) => s"an $vowel"
    case other                               => s"a $other"

  private def kind(json: Json): String = json.fold(
    "null",
    _ => "a Boolean",
    _ => "a number",
    _ => "a string",
    _ => "an array",
    _ => "an object",
  )
