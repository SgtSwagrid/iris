package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.DecodingFailure
import io.circe.parser.parse
import scala.util.NotGiven

/**
  * A form the model's answer may take, for [[LlmClient.ask]]: what the model is
  * told of it, and how its reply is read.
  *
  * A `String` is the reply's text as written, of which the model is told
  * nothing. Any other [[Structured]] type is JSON, and the model is told its
  * schema.
  *
  * @tparam A
  *   The type of the answer.
  */
trait Answer[A]:

  /** The instructions telling the model what form its reply must take. */
  def instructions: Option[String]

  /**
    * Reads the model's reply.
    *
    * @param reply
    *   The reply.
    *
    * @return
    *   The answer, or why the reply is none, which the model is told when it is
    *   asked again.
    */
  def read(reply: Reply): Either[String, A]

object Answer:

  /** The most replies [[LlmClient.ask]] asks for, unless told otherwise. */
  val attempts: Int = 3

  /** The reply's text, as written. */
  given text: Answer[String] = new Answer[String]:

    override val instructions: Option[String] = None

    override def read(reply: Reply): Either[String, String] = Right(reply.text)

  /**
    * JSON conforming to a structured type's schema, for every type but
    * `String`, which is [[text]].
    *
    * @tparam A
    *   The structured type.
    *
    * @return
    *   An answer read as JSON.
    */
  given json[A]
    (
      using structured: Structured[A],
      notText: NotGiven[A =:= String],
    )
    : Answer[A] = Conforming(structured)

  /** JSON conforming to a structured type's schema. */
  private final class Conforming[A]
    (structured: Structured[A])
    extends Answer[A]:

    override val instructions: Option[String] = Some(
      "Reply with JSON alone, with no other text and no code fence, " +
        s"conforming to this JSON Schema:\n${ structured.schema.noSpaces }",
    )

    override def read(reply: Reply): Either[String, A] =
      for
        _ <- Either.cond(
          reply.stopReason != StopReason.MaxTokens,
          (),
          "It was cut off at the token limit before it was complete.",
        )
        json <- parse(unfenced(reply.text)).leftMap(failure =>
          s"It is not JSON: ${ failure.message }",
        )
        value <- structured
          .decoder
          .decodeAccumulating(json.hcursor)
          .toEither
          .leftMap(_.map(described).toList.mkString("\n"))
      yield value

  /**
    * Asks the model through a client until its reply can be read, telling it
    * what was wrong with each that could not.
    */
  private[iris] def ask[F[_] : MonadThrow, A]
    (
      client: LlmClient[F],
      chat: Chat,
      options: ReplyOptions,
      attempts: Int,
    )
    (using answer: Answer[A])
    : F[A] =
    def attempt(chat: Chat, made: Int): F[A] = client
      .send(chat, options)
      .flatMap: reply =>
        answer.read(reply) match
          case Right(value)                 => value.pure[F]
          case Left(why) if made < attempts =>
            attempt(corrected(chat, reply, why), made + 1)
          case Left(why) =>
            LlmError.Unreadable(made, why, reply.text).raiseError[F, A]
    attempt(
      answer.instructions.fold(chat)(instructed(chat, _)),
      1,
    )

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

  /**
    * The chat continued with the reply and why it could not be read. A reply
    * that says nothing is asked for again as it was, as no provider takes an
    * empty message.
    */
  private def corrected(chat: Chat, reply: Reply, why: String): Chat =
    if reply.text.trim.isEmpty then chat
    else
      chat
        .assistant(reply.text)
        .user(s"That reply could not be read:\n$why\n\nReply again as asked.")

  /** The text within the code fence it is written in, if any. */
  private def unfenced(text: String): String =
    val trimmed = text.trim
    if trimmed.startsWith("```") then
      trimmed.dropWhile(_ != '\n').stripSuffix("```").trim
    else trimmed

  private def described(failure: DecodingFailure): String =
    s"At ${ failure.pathToRootString.getOrElse("the top") }: ${ failure
        .message }"
