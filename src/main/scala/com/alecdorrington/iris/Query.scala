package com.alecdorrington.iris

import cats.{Functor, MonadThrow}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import scala.Conversion.into
import scala.compiletime.summonInline
import scala.util.matching.Regex

/**
  * A request for a value of a type, read from what the model writes: asked in
  * words for JSON of the type's [[Schema]], and asked again while what it
  * writes cannot be read.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param prompt
  *   The prompt each attempt sends.
  *
  * @param maxIterations
  *   The most prompts to send for the value, the first included, or `None` for
  *   the service's [[QueryConfig.maxIterations]].
  *
  * @param instructions
  *   The words telling the model what form its response must take, closing the
  *   user's last message, or `None` for those asking for JSON of the schema.
  */
final case class QueryRequest[A]
  (
    prompt: PromptRequest,
    maxIterations: Option[Int] = None,
    instructions: Option[String] = None,
  )

object QueryRequest:

  /**
    * A query of a prompt.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a prompt to a query of it.
    */
  given fromPrompt[A]: Conversion[PromptRequest, QueryRequest[A]] =
    QueryRequest(_)

  /**
    * A query of a conversation.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a conversation to a query of it.
    */
  given fromConversation[A]: Conversion[Conversation, QueryRequest[A]] =
    conversation => QueryRequest(PromptRequest(conversation))

  /**
    * A query of one message of the user's, saying a value as its [[Content]]
    * says it, as JSON where it has a schema.
    *
    * @tparam A
    *   The type of the value asked for.
    *
    * @tparam B
    *   The type of the value said.
    *
    * @return
    *   The conversion of a value to a query of it.
    */
  inline given fromContent[A, B]: Conversion[B, QueryRequest[A]] =
    said(summonInline[Content[B]])

  private def said[A, B](content: Content[B]): Conversion[B, QueryRequest[A]] =
    value => QueryRequest(PromptRequest.said(content)(value))

/**
  * The settings every [[QueryRequest]] to a service takes, unless it sets its
  * own.
  *
  * @param maxIterations
  *   The most prompts to send for a value, the first included: asking again
  *   after a response that cannot be read, told why.
  */
final case class QueryConfig(maxIterations: Int = 3)

/**
  * A value of a type as a [[QueryService]] gives it, read from what the model
  * wrote, alike for every type: the value, as soon as everything in it but its
  * streams is read, and every prompt the query took, once the last ends.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the last response, or `None` where the model asked for
  *   tools instead. Each `fs2.Stream` within it delivers its elements as the
  *   model writes them, each time it is read, from the first.
  *
  * @param iterations
  *   Every response the query took, oldest first, once the last ends: the last
  *   is the one the value was read from. Where the last cannot be read to its
  *   end after the value was given, it fails as the value's streams not yet
  *   ended do: with [[QueryError.Unreadable]] where what the model wrote cannot
  *   be read, and as the request fails where that fails partway.
  *
  * @param cancel
  *   Stops reading the last response, where it is still being read once the
  *   value was given, as it is for a type holding a stream: its streams not yet
  *   ended, and its iterations, then fail with an `IllegalStateException`.
  *   Otherwise it does nothing, as does a cancel once the response has ended.
  *
  * @param conversation
  *   The conversation as the model saw it last, with its last response, its
  *   instructions included, to go on from, once the last response ends.
  */
final case class QueryResponse[F[_], +A]
  (
    value: Option[A],
    iterations: F[List[PromptResponse]],
    cancel: F[Unit],
    conversation: F[Conversation],
  ):

  /**
    * The token counts of every response the query took, together.
    *
    * @return
    *   An effect producing the counts, once the last response ends.
    */
  def usage(using Functor[F]): F[Usage] =
    iterations.map(_.foldLeft(Usage())(_ + _.usage))

  /**
    * What every response the query took cost, together.
    *
    * @return
    *   An effect producing the cost, once the last response ends, or `None`
    *   where any response has none, as from a model Iris does not list.
    */
  def cost(using Functor[F]): F[Option[Usd]] =
    iterations.map(_.traverse(_.cost).map(_.foldLeft(Usd.zero)(_ + _)))

/**
  * An error reading a value from what the model wrote.
  *
  * @param message
  *   The error's message.
  */
enum QueryError(message: String) extends Exception(message):

  /**
    * A response that could not be read as the value asked for: after the model
    * was told why as often as allowed, or at once where asking again could not
    * help, as for a response cut off at the token limit.
    *
    * @param detail
    *   The description of why the last response could not be read, as the model
    *   was told it, which may repeat what the response said, as a host's own
    *   refusal of a value may or a map's keys do, and so is kept out of the
    *   message.
    *
    * @param responses
    *   Every response asked for, oldest first, with their usage, also kept out
    *   of the message.
    */
  case Unreadable
    (
      detail: String,
      responses: List[PromptResponse],
    )
    extends QueryError(
      s"The model's response could not be read as asked (iterations: ${ responses
          .size })",
    )

/**
  * A value of any type with a [[Schema]], `String` included, read from what the
  * model writes, alike for every type: the model is told the type's JSON
  * Schema, closing the conversation's last message of the user's, once, and
  * what it writes is read as JSON conforming to it. A response that cannot be
  * read is followed by why, and the model asked again, until one can be or the
  * iterations run out, but not where asking again could not help: a response
  * cut off at the token limit, stopped for a reason of the provider's, such as
  * a refusal, or saying nothing. A response asking for a tool is given with no
  * value. A service is made from the prompt service it sends through, as
  * `prompt.query`.
  *
  * {{{
  * case class Person(name: String, age: Int)
  *
  * prompt.query.send[Person]("Invent a person.").map(_.value)
  * }}}
  *
  * @tparam F
  *   The effect type.
  */
trait QueryService[F[_]]:

  /**
    * Asks for a value of a type.
    *
    * @tparam A
    *   The type of the value.
    *
    * @param request
    *   The request: a query, a prompt, a conversation, or what the user says.
    *
    * @return
    *   An effect producing the value, once all of it but its streams is read,
    *   with every response it took.
    */
  def send[A : Schema](request: into[QueryRequest[A]]): F[QueryResponse[F, A]]

object QueryService:

  /**
    * Creates a service sending through a prompt service.
    *
    * @tparam F
    *   The effect type.
    *
    * @param prompt
    *   The prompt service to send through.
    *
    * @param config
    *   The settings every request takes, unless it sets its own.
    *
    * @return
    *   A service asking for values through the prompt service.
    */
  def apply[F[_] : Async]
    (
      prompt: PromptService[F],
      config: QueryConfig = QueryConfig(),
    )
    : QueryService[F] = Querying(prompt, config)

extension [F[_] : Async](prompt: PromptService[F])

  /**
    * Asks for values through this prompt service, with the default settings.
    *
    * @return
    *   A service asking for values through this one.
    */
  def query: QueryService[F] = QueryService(prompt)

  /**
    * Asks for values through this prompt service.
    *
    * @param config
    *   The settings every request takes, unless it sets its own.
    *
    * @return
    *   A service asking for values through this one.
    */
  def query(config: QueryConfig): QueryService[F] = QueryService(prompt, config)

/**
  * A [[QueryService]]: a type holding a stream is read as its response arrives,
  * by [[Unfolding]], and any other once it is whole.
  */
private[iris] final class Querying[F[_] : Async]
  (
    prompt: PromptService[F],
    config: QueryConfig,
  )
  extends QueryService[F]:

  override def send[A : Schema as A]
    (request: into[QueryRequest[A]])
    : F[QueryResponse[F, A]] =
    val iterations = request.maxIterations.getOrElse(config.maxIterations)
    val asked      = request
      .prompt
      .copy(conversation =
        Reading.opening(
          request.prompt.conversation,
          request.instructions.getOrElse(Reading.schemaPrompt(A)),
        ),
      )
    Reading.counted(iterations).liftTo[F] *>
      (if A.gradual then Unfolding.read(prompt, A, iterations, asked)
       else Reading.whole(prompt.send(_), A, iterations, asked))

/**
  * The reading of a value from what the model writes, one way for every type:
  * the model is told the type's JSON Schema, and what it writes read as JSON
  * conforming to it, or answered with why it could not be and asked again.
  */
private[iris] object Reading:

  /** What the model is told of the form its response must take. */
  def schemaPrompt(schema: Schema[?]): String =
    "Reply with JSON alone, with no other text and no code fence, " +
      s"conforming to this JSON Schema:\n${ schema.json.noSpaces }"

  /** Refuses fewer than one iteration. */
  def counted(iterations: Int): Either[IllegalArgumentException, Unit] = Either
    .cond(
      iterations >= 1,
      (),
      IllegalArgumentException(
        s"A query needs at least one iteration, not $iterations.",
      ),
    )

  /**
    * The conversation to send first: closing the user's last message with the
    * prompt, or else, after the model's or the tools', adding it in a message
    * of its own, so that the model answers afresh. One holding the prompt
    * already, as one an agent goes on with does, is sent as it is, as is one no
    * provider would answer, so that the provider refuses it before any request.
    */
  def opening
    (
      conversation: Conversation,
      prompt: String,
    )
    : Conversation =
    if conversation.unanswerable.nonEmpty ||
      conversation.messages.exists(_.parts.contains(MessagePart.Text(prompt)))
    then conversation
    else
      conversation.messages.lastOption match
        case Some(Message.User(parts)) => conversation.copy(messages =
            conversation.messages.init :+
              Message.User(parts :+ MessagePart.Text(prompt)),
          )
        case _ => conversation.userSays(prompt)

  /** What follows a response: its value, another attempt, or why neither. */
  enum Verdict[+A]:

    /** The value, or `None` where the model asked for tools. */
    case Answered(value: Option[A])

    /** Another attempt, at the conversation told what was wrong. */
    case Again(conversation: Conversation)

    /** Why the response cannot be read, where asking again could not help. */
    case Failed(why: String)

  /**
    * What follows a response to a conversation: its value, none where it asks
    * for tools, another attempt told what was wrong, where `more` may be made
    * and asking again could help, or why there is none.
    */
  def verdict[A]
    (
      schema: Schema[A],
      conversation: Conversation,
      response: PromptResponse,
      more: Boolean,
    )
    : Verdict[A] =
    if calledTools(response) then Verdict.Answered(None)
    else
      hopeless(response).fold(
        read(schema, response).leftMap(stopped(response, _)) match
          case Right(value)      => Verdict.Answered(Some(value))
          case Left(why) if more =>
            Verdict.Again(corrected(conversation, response, why))
          case Left(why) => Verdict.Failed(why),
      )(Verdict.Failed(_))

  /**
    * Reads a value of a type holding no stream, once each response is whole,
    * asking again while one cannot be read.
    */
  def whole[F[_] : MonadThrow, A]
    (
      send: PromptRequest => F[PromptResponse],
      schema: Schema[A],
      iterations: Int,
      asked: PromptRequest,
    )
    : F[QueryResponse[F, A]] =
    def attempt
      (
        conversation: Conversation,
        sent: Vector[PromptResponse],
      )
      : F[QueryResponse[F, A]] = send(asked.copy(conversation = conversation))
      .flatMap: response =>
        val responses = sent :+ response
        verdict(
          schema,
          conversation,
          response,
          responses.size < iterations,
        ) match
          case Verdict.Answered(value) => QueryResponse(
              value,
              responses.toList.pure[F],
              MonadThrow[F].unit,
              conversation.modelSays(response).pure[F],
            ).pure[F]
          case Verdict.Again(next) => attempt(next, responses)
          case Verdict.Failed(why) =>
            QueryError.Unreadable(why, responses.toList).raiseError
    attempt(asked.conversation, Vector.empty)

  /**
    * Whether a response asks for tools, by its calls or why it stopped, as
    * OpenAI says it stopped as usual for a call it was made to make. Calls
    * count only where it stopped as it would for them: one cut off at the token
    * limit may be cut short, and one the provider stopped, as on a refusal, is
    * no call.
    */
  def calledTools(response: PromptResponse): Boolean =
    response.stopReason == StopReason.ToolCall ||
    response.toolCalls.nonEmpty &&
    (response.stopReason == StopReason.Completed ||
    response.stopReason == StopReason.StopSequence)

  /** The value a response holds, or why it holds none. */
  def read[A]
    (
      schema: Schema[A],
      response: PromptResponse,
    )
    : Either[String, A] = parsed(response.text).flatMap(decoded(schema, _))

  /**
    * Why no reading of a response could be trusted, nor asking again help,
    * where none could: it was cut short, so that even what parses may be cut,
    * as `4` of `42`, stopped for a reason of the provider's, such as a refusal,
    * or said nothing. A response ended by a stop sequence is read, and if it
    * cannot be, asked for again, told so, as only Anthropic reports one.
    */
  def hopeless(response: PromptResponse): Option[String] =
    response.stopReason match
      case StopReason.MaxTokens =>
        Some("It was cut off at the token limit before it was complete.")
      case StopReason.Other(reason)        => Some(s"It stopped, as `$reason`.")
      case _ if response.text.trim.isEmpty => Some("It said nothing.")
      case _                               => None

  /** Why a response could not be read, said to have ended at a stop sequence. */
  private def stopped(response: PromptResponse, why: String): String =
    if response.stopReason == StopReason.StopSequence then
      s"It stopped at a stop sequence. $why"
    else why

  /** The conversation gone on with a response, and why it could not be read. */
  private def corrected
    (
      conversation: Conversation,
      response: PromptResponse,
      why: String,
    )
    : Conversation = conversation
    .modelSays(response.text)
    .userSays(s"That reply could not be read:\n$why\n\nReply again as asked.")

  /**
    * The JSON of a response: the whole text, else what its last code fence
    * holds, as of a draft followed by an answer. A fence within a JSON string
    * is part of it.
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
