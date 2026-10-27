package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.{DecodingFailure, Json, ParsingFailure}
import io.circe.DecodingFailure.Reason
import io.circe.parser.parse
import scala.annotation.implicitNotFound
import scala.util.NotGiven
import scala.util.matching.Regex

/**
  * A form the model's answer may take, for [[LlmClient.ask]]: what the model is
  * told of it, and how its reply is read.
  *
  * A [[Reply]] is the reply whole, and a `String` its text as written, of
  * neither of which the model is told anything. Any other [[Structured]] type
  * is JSON, and the model is told its schema.
  *
  * @tparam A
  *   The type of the answer.
  */
@implicitNotFound(
  "${A} cannot be asked for: ask takes a String, or a type that is Structured, as with `derives Structured`.",
)
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
    *   asked again. It should not repeat the reply, which the model has.
    */
  def read(reply: Reply): Either[String, A]

object Answer:

  /** The most replies [[LlmClient.ask]] asks for, unless told otherwise. */
  val attempts: Int = 3

  /**
    * The reply whole: its text, why it stopped, its usage and the tools it
    * asked for, of which the model is told nothing, and which is never refused.
    */
  given reply: Answer[Reply] = new Answer[Reply]:

    override val instructions: Option[String] = None

    override def read(reply: Reply): Either[String, Reply] = Right(reply)

  /**
    * The reply's text, as written, cut off or not, but no reply asking for a
    * tool, whose call a text cannot hold, and which [[reply]] keeps.
    */
  given text: Answer[String] = new Answer[String]:

    override val instructions: Option[String] = None

    override def read(reply: Reply): Either[String, String] = Either.cond(
      reply.stopReason != StopReason.ToolCall,
      reply.text,
      calledTool,
    )

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

    override def read(reply: Reply): Either[String, A] = parsed(reply.text)
      .flatMap(decoded(structured, _))

  /**
    * Asks the model through a client's `send` until its reply can be read,
    * telling it what was wrong with each that could not, so long as asking
    * again could help.
    */
  private[iris] def ask[F[_] : MonadThrow, A : Answer as A]
    (
      send: Chat => F[Reply],
      chat: Chat,
      attempts: Int,
    )
    : F[A] =
    if attempts < 1 then
      MonadThrow[F].raiseError(IllegalArgumentException(
        s"An ask needs at least one attempt, not $attempts.",
      ))
    else
      Asking(send, attempts).attempt(
        A.instructions.fold(chat)(instructed(chat, _)),
        Vector.empty,
      )

  /** The asking of one answer, made of attempts. */
  private final class Asking[F[_] : MonadThrow, A : Answer as A]
    (send: Chat => F[Reply], attempts: Int):

    def attempt(chat: Chat, replies: Vector[Reply]): F[A] =
      send(chat).flatMap(reply => answered(chat, replies :+ reply))

    private def answered(chat: Chat, replies: Vector[Reply]): F[A] =
      val reply = replies.last
      A.read(reply).leftMap(stopped(reply, _)) match
        case Right(value) => value.pure[F]
        case Left(why)    => hopeless(reply) match
            case Some(fatal)                     => unreadable(replies, fatal)
            case None if replies.size < attempts =>
              attempt(corrected(chat, reply, why), replies)
            case None => unreadable(replies, why)

    private def unreadable(replies: Vector[Reply], detail: String): F[A] =
      LlmError.Unreadable(replies.size, detail, replies.toList).raiseError

  /**
    * Why asking again could not help a reply that could not be read, where it
    * could not: it was cut short, asked for a tool, stopped for a reason of the
    * provider's, such as a refusal, or said nothing. A reply ended by a stop
    * sequence is asked for again, told so, as only Anthropic reports one.
    */
  private def hopeless(reply: Reply): Option[String] = reply.stopReason match
    case StopReason.MaxTokens =>
      Some("It was cut off at the token limit before it was complete.")
    case StopReason.ToolCall          => Some(calledTool)
    case StopReason.Other(reason)     => Some(s"It stopped, as `$reason`.")
    case _ if reply.text.trim.isEmpty => Some("It said nothing.")
    case _                            => None

  private val calledTool: String = "It asked for a tool instead of answering."

  /** Why a reply could not be read, said to have ended at a stop sequence. */
  private def stopped(reply: Reply, why: String): String =
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
  private def corrected(chat: Chat, reply: Reply, why: String): Chat = chat
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
