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

  /** The most answers an ask asks for, unless told otherwise. */
  val attempts: Int = 3

  /**
    * Asks the model through a client's `send` until its reply can be read or
    * asks for a tool, telling it what was wrong with each that could not, so
    * long as asking again could help. A chat no provider would answer is sent
    * uninstructed, so that the provider refuses it before any request.
    */
  def ask[F[_] : MonadThrow, A : Schema as A]
    (
      send: Chat => F[Answer[Nothing]],
      chat: Chat,
      attempts: Int,
    )
    : F[Answer[A]] =
    if attempts < 1 then
      MonadThrow[F].raiseError(IllegalArgumentException(
        s"An ask needs at least one attempt, not $attempts.",
      ))
    else
      Attempts(send, A, attempts).attempt(
        chat.unanswerable.fold(instructed(chat, instructions(A)))(_ => chat),
        Vector.empty,
      )

  /** What the model is told of the form its reply must take. */
  def instructions(schema: Schema[?]): String =
    "Reply with JSON alone, with no other text and no code fence, " +
      s"conforming to this JSON Schema:\n${ schema.json.noSpaces }"

  /** The attempts at one answer. */
  private final class Attempts[F[_] : MonadThrow, A]
    (
      send: Chat => F[Answer[Nothing]],
      schema: Schema[A],
      attempts: Int,
    ):

    /** Sends the chat, after the answers so far, and reads what comes back. */
    def attempt
      (
        chat: Chat,
        answers: Vector[Answer[Nothing]],
      )
      : F[Answer[A]] =
      send(chat).flatMap(reply => answered(chat, answers :+ reply))

    /**
      * The answer, none where the model asked for tools, a failure where asking
      * again could not help, or another attempt.
      */
    private def answered
      (
        chat: Chat,
        answers: Vector[Answer[Nothing]],
      )
      : F[Answer[A]] =
      val reply = answers.last
      if calledTools(reply) then summed(answers, None).pure[F]
      else
        hopeless(reply) match
          case Some(fatal) => unreadable(answers, fatal)
          case None => read(schema, reply).leftMap(stopped(reply, _)) match
              case Right(value) => summed(answers, Some(value)).pure[F]
              case Left(why) if answers.size < attempts =>
                attempt(corrected(chat, reply, why), answers)
              case Left(why) => unreadable(answers, why)

    private def unreadable
      (
        answers: Vector[Answer[Nothing]],
        detail: String,
      )
      : F[Answer[A]] = LlmError
      .Unreadable(answers.size, detail, answers.toList)
      .raiseError

  /**
    * Whether a reply asks for tools, by its calls or why it stopped, as OpenAI
    * says it stopped as usual for a call it was made to make. A call cut off at
    * the token limit may be cut short, so is no call, but cut off.
    */
  private def calledTools(reply: Answer[Nothing]): Boolean =
    reply.stopReason == StopReason.ToolCall ||
    reply.toolCalls.nonEmpty && reply.stopReason != StopReason.MaxTokens

  /**
    * The last reply with the answer read from it, counting every attempt: its
    * usage is theirs together, or none where one of them reports none.
    */
  private def summed[A]
    (
      answers: Vector[Answer[Nothing]],
      answer: Option[A],
    )
    : Answer[A] = answers
    .last
    .copy(
      usage = answers.toList.traverse(_.usage).flatMap(_.reduceOption(_ + _)),
      value = answer,
      attempts = answers.size,
    )

  /** The answer a reply holds, or why it holds none. */
  private def read[A]
    (
      schema: Schema[A],
      reply: Answer[Nothing],
    )
    : Either[String, A] = parsed(reply.text).flatMap(decoded(schema, _))

  /**
    * Why no reading of a reply could be trusted, nor asking again help, where
    * none could: it was cut short, so that even what parses may be cut, as `4`
    * of `42`, stopped for a reason of the provider's, such as a refusal, or
    * said nothing. A reply ended by a stop sequence is read, and if it cannot
    * be, asked for again, told so, as only Anthropic reports one.
    */
  private def hopeless(reply: Answer[Nothing]): Option[String] =
    reply.stopReason match
      case StopReason.MaxTokens =>
        Some("It was cut off at the token limit before it was complete.")
      case StopReason.Other(reason)     => Some(s"It stopped, as `$reason`.")
      case _ if reply.text.trim.isEmpty => Some("It said nothing.")
      case _                            => None

  /** Why a reply could not be read, said to have ended at a stop sequence. */
  private def stopped(reply: Answer[Nothing], why: String): String =
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
  private def corrected
    (
      chat: Chat,
      reply: Answer[Nothing],
      why: String,
    )
    : Chat = chat
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
  private def decoded[A](schema: Schema[A], json: Json): Either[String, A] =
    schema
      .decoder
      .decodeAccumulating(json.hcursor)
      .toEither
      .leftMap(_.map(_.getMessage).toList.mkString("\n"))
