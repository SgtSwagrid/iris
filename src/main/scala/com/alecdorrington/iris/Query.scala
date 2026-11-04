package com.alecdorrington.iris

import cats.Functor
import cats.effect.Async
import cats.syntax.functor.*
import cats.syntax.traverse.*
import scala.Conversion.into
import scala.compiletime.summonInline

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
  */
final case class QueryRequest[A]
  (
    prompt: PromptRequest,
    maxIterations: Option[Int] = None,
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
    value =>
      QueryRequest(PromptRequest(
        Conversation(List(Message.User(List(content.text(value))))),
      ))

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
  */
final case class QueryResponse[F[_], +A]
  (
    value: Option[A],
    iterations: F[List[PromptResponse]],
    cancel: F[Unit],
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
  * Schema, closing the conversation's last message of the user's, and what it
  * writes is read as JSON conforming to it. A response that cannot be read is
  * followed by why, and the model asked again, until one can be or the
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

  /** The prompt service this one sends through. */
  def prompt: PromptService[F]

  /** The settings every request to this service takes, unless it sets its own. */
  def config: QueryConfig

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
    : QueryService[F] = Querying(prompt, config, Toolbox(Nil))

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
  * A [[QueryService]] running the tools in a box wherever the model asks for
  * them alone, as an [[AgentService]] does, and none for a query of its own.
  */
private[iris] final class Querying[F[_] : Async]
  (
    override val prompt: PromptService[F],
    override val config: QueryConfig,
    tools: Toolbox[F],
  )
  extends QueryService[F]:

  override def send[A : Schema as A]
    (request: into[QueryRequest[A]])
    : F[QueryResponse[F, A]] =
    val asked      = request.prompt
    val iterations = request.maxIterations.getOrElse(config.maxIterations)
    if A.gradual then
      StreamingAsker.ask(
        (conversation, offered) =>
          prompt.stream(asked.copy(
            conversation = conversation,
            tools = offered,
          )),
        asked.conversation,
        asked.tools,
        iterations,
        tools,
      )
    else
      WholeAsker.ask(
        conversation =>
          prompt.send(asked.copy(
            conversation = conversation,
            tools = tools.offered(asked.tools),
          )),
        asked.conversation,
        iterations,
        tools,
      )
