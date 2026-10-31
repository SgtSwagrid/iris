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
    * it asks for those alone, before asking it to go on. A chat no provider
    * would answer is sent uninstructed, so that the provider refuses it before
    * any request.
    */
  def ask[F[_] : MonadThrow, A : Schema as A]
    (
      send: Chat => F[ModelResponse],
      chat: Chat,
      attempts: Int,
      tools: Toolbox[F] = Toolbox[F](Nil),
    )
    : F[ValidatedResponse[F, A]] =
    // Sends the chat, after the replies so far, `tried` of them read.
    def attempt
      (
        chat: Chat,
        replies: Vector[ModelResponse],
        tried: Int,
      )
      : F[ValidatedResponse[F, A]] = send(chat).flatMap(reply =>
      verdict(
        A,
        chat,
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
                onwards(chat, reply, results),
                replies :+ reply,
                tried,
              ),
            )
        case Verdict.Again(next)   => attempt(next, replies :+ reply, tried + 1)
        case Verdict.Failed(error) => error.raiseError,
    )
    counted(attempts).fold(
      _.raiseError,
      _ =>
        attempt(
          opening(chat, schemaPrompt(A)),
          Vector.empty,
          0,
        ),
    )

  /** The chat after a reply asking for tools, and what they gave. */
  def onwards
    (
      chat: Chat,
      reply: ModelResponse,
      results: List[MessagePart.ToolResult],
    )
    : Chat = chat.modelSays(reply).userSays(results*)

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
    * The chat to send first: instructed, unless no provider would answer it, so
    * that the provider refuses it before any request.
    */
  def opening(chat: Chat, prompt: String): Chat = chat
    .unanswerable
    .fold(instructed(chat, prompt))(_ => chat)

  /**
    * What becomes of an ask once its latest reply has come, the `tried`th read
    * as an answer: the tools it asks for run, where it asks for those in the
    * box alone, its answer, none where it asked for others, a failure where
    * asking again could not help, or another attempt, told what was wrong.
    */
  def verdict[A]
    (
      schema: Schema[A],
      chat: Chat,
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
              Verdict.Again(corrected(chat, reply, why))
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

    /** Another attempt, at the chat told what was wrong. */
    case Again(chat: Chat)

    /** The failure, where asking again could not help. */
    case Failed(error: ModelError)

  /** Why the replies asked for could not be read, as the last could not. */
  def unreadable
    (
      replies: Vector[ModelResponse],
      detail: String,
    )
    : ModelError =
    ModelError.UnreadableResponse(replies.size, detail, replies.toList)

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
  private def read[A]
    (schema: Schema[A], reply: ModelResponse)
    : Either[String, A] = parsed(reply.text).flatMap(decoded(schema, _))

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
  private def instructed(chat: Chat, prompt: String): Chat =
    chat.messages.lastOption.filter(_.role == Role.User) match
      case Some(last) => chat.copy(messages =
          chat.messages.init :+
            last.copy(parts = last.parts :+ MessagePart.Text(prompt)),
        )
      case None => chat.userSays(prompt)

  /** The chat continued with a reply that said something, and why it failed. */
  private def corrected
    (
      chat: Chat,
      reply: ModelResponse,
      why: String,
    )
    : Chat = chat
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
