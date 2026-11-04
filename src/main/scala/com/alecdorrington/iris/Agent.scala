package com.alecdorrington.iris

import cats.effect.Async
import cats.syntax.all.*
import scala.Conversion.into
import scala.compiletime.summonInline

/**
  * A request for a value of a type, with tools run wherever the model asks for
  * them, until it gives the value.
  *
  * @tparam F
  *   The effect type, in which the tools run.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param query
  *   The query each round sends.
  *
  * @param tools
  *   The tools to offer the model and run, or `None` for the service's
  *   [[AgentConfig.tools]].
  */
final case class AgentRequest[F[_], A]
  (
    query: QueryRequest[A],
    tools: Option[List[RunnableTool[F]]] = None,
  )

object AgentRequest:

  /**
    * A request of a query, with the service's tools.
    *
    * @tparam F
    *   The effect type.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a query to a request of it.
    */
  given fromQuery[F[_], A]: Conversion[QueryRequest[A], AgentRequest[F, A]] =
    AgentRequest(_)

  /**
    * A request of a prompt, with the service's tools.
    *
    * @tparam F
    *   The effect type.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a prompt to a request of it.
    */
  given fromPrompt[F[_], A]: Conversion[PromptRequest, AgentRequest[F, A]] =
    prompt => AgentRequest(QueryRequest(prompt))

  /**
    * A request of a conversation, with the service's tools.
    *
    * @tparam F
    *   The effect type.
    *
    * @tparam A
    *   The type of the value.
    *
    * @return
    *   The conversion of a conversation to a request of it.
    */
  given fromConversation[F[_], A]
    : Conversion[Conversation, AgentRequest[F, A]] =
    conversation => AgentRequest(QueryRequest(PromptRequest(conversation)))

  /**
    * A request of one message of the user's, saying a value as its [[Content]]
    * says it, with the service's tools.
    *
    * @tparam F
    *   The effect type.
    *
    * @tparam A
    *   The type of the value asked for.
    *
    * @tparam B
    *   The type of the value said.
    *
    * @return
    *   The conversion of a value to a request of it.
    */
  inline given fromContent[F[_], A, B]: Conversion[B, AgentRequest[F, A]] =
    said(summonInline[Content[B]])

  private def said[F[_], A, B]
    (content: Content[B])
    : Conversion[B, AgentRequest[F, A]] = value =>
    AgentRequest(QueryRequest(
      PromptRequest(Conversation(List(Message.User(List(content.text(value)))))),
    ))

/**
  * The settings every [[AgentRequest]] to a service takes, unless it sets its
  * own.
  *
  * @tparam F
  *   The effect type, in which the tools run.
  *
  * @param tools
  *   The tools to offer the model and run.
  */
final case class AgentConfig[F[_]](tools: List[RunnableTool[F]] = List.empty)

/**
  * A value of a type as an [[AgentService]] gives it, alike for every type: the
  * value, as soon as everything in it but its streams is read, and every round
  * it took, once the last ends.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the last response, or `None` where the model asked for
  *   a tool the service was not given to run, for the host to run it.
  *
  * @param iterations
  *   Every round the request took, oldest first, once the last ends: each a
  *   query's responses up to and including one asking for tools, and the last
  *   those the value was read from.
  *
  * @param cancel
  *   Stops reading the last response, as [[QueryResponse.cancel]] does.
  */
final case class AgentResponse[F[_], +A]
  (
    value: Option[A],
    iterations: F[List[QueryResponse[F, Any]]],
    cancel: F[Unit],
  ):

  /**
    * Every response the request took, oldest first, once the last ends.
    *
    * @return
    *   An effect producing every round's responses, in order.
    */
  def responses(using cats.Monad[F]): F[List[PromptResponse]] = iterations
    .flatMap(_.flatTraverse(_.iterations))

  /**
    * The token counts of every response the request took, together.
    *
    * @return
    *   An effect producing the counts, once the last response ends.
    */
  def usage(using cats.Monad[F]): F[Usage] =
    responses.map(_.foldLeft(Usage())(_ + _.usage))

  /**
    * What every response the request took cost, together.
    *
    * @return
    *   An effect producing the cost, once the last response ends, or `None`
    *   where any response has none, as from a model Iris does not list.
    */
  def cost(using cats.Monad[F]): F[Option[Usd]] =
    responses.map(_.traverse(_.cost).map(_.foldLeft(Usd.zero)(_ + _)))

/**
  * A value of a type, with tools run wherever the model asks for them, as
  * [[RunnableTool]]s say, until it gives the value. Each round asks again with
  * the tools' results, and closes its request with a cache breakpoint, so that
  * the rounds before it are read from the cache. A value holding a stream
  * arrives through two tools of Iris's own, `emit`, one element a call, and
  * `done`, so that the model may call tools between its elements. Nothing caps
  * the rounds: a budget on the prompt service bounds what they spend. A service
  * is made from the query service it asks through, as `query.agent(tools*)`.
  *
  * @tparam F
  *   The effect type.
  */
trait AgentService[F[_]]:

  /**
    * Asks for a value of a type, running tools as the model asks.
    *
    * @tparam A
    *   The type of the value.
    *
    * @param request
    *   The request: an agent's, a query, a prompt, a conversation, or what the
    *   user says.
    *
    * @return
    *   An effect producing the value, once all of it but its streams is read,
    *   with every round it took.
    */
  def send[A : Schema](request: into[AgentRequest[F, A]])
    : F[AgentResponse[F, A]]

extension [F[_] : Async](query: QueryService[F])

  /**
    * Runs tools for the requests asked through this query service.
    *
    * @param tools
    *   The tools to offer the model and run.
    *
    * @return
    *   A service asking through this one, running the tools.
    */
  def agent(tools: RunnableTool[F]*): AgentService[F] =
    agent(AgentConfig(tools.toList))

  /**
    * Runs tools for the requests asked through this query service.
    *
    * @param config
    *   The settings every request takes, unless it sets its own.
    *
    * @return
    *   A service asking through this one, running the tools.
    */
  def agent(config: AgentConfig[F]): AgentService[F] = Agency(query, config)

/** An [[AgentService]] asking through a query service's prompts. */
private[iris] final class Agency[F[_] : Async]
  (
    query: QueryService[F],
    config: AgentConfig[F],
  )
  extends AgentService[F]:

  override def send[A : Schema]
    (request: into[AgentRequest[F, A]])
    : F[AgentResponse[F, A]] =
    val toolbox = Toolbox(request.tools.getOrElse(config.tools))
    Querying(query.prompt, query.config, toolbox)
      .send[A](request.query)
      .map(response =>
        AgentResponse(
          response.value,
          response.iterations.map(rounds(_, toolbox, response.value)),
          response.cancel,
        ),
      )

  /**
    * The responses of each round: up to and including those running tools, the
    * last round's the value's.
    */
  private def rounds
    (
      responses: List[PromptResponse],
      toolbox: Toolbox[F],
      value: Option[Any],
    )
    : List[QueryResponse[F, Any]] = responses
    .foldLeft(Vector(Vector.empty[PromptResponse])):
      case (done :+ open, response)
        if WholeAsker.calledTools(response) &&
        toolbox.runs(response.toolCalls) =>
        done :+ (open :+ response) :+ Vector.empty
      case (done :+ open, response) => done :+ (open :+ response)
      case (done, _)                => done
    .filter(_.nonEmpty)
    .toList
    .reverse
    .zipWithIndex
    .map((round, back) =>
      QueryResponse[F, Any](
        Option.when(back == 0)(value).flatten,
        round.toList.pure[F],
        Async[F].unit,
      ),
    )
    .reverse
