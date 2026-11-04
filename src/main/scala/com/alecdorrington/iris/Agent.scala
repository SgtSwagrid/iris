package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.{Async, Deferred}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
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
    : Conversion[B, AgentRequest[F, A]] =
    value => AgentRequest(QueryRequest(PromptRequest.said(content)(value)))

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

/** An [[AgentService]] asking through a query service, round after round. */
private[iris] final class Agency[F[_] : Async]
  (
    query: QueryService[F],
    config: AgentConfig[F],
  )
  extends AgentService[F]:

  override def send[A : Schema as A]
    (request: into[AgentRequest[F, A]])
    : F[AgentResponse[F, A]] =
    val tools = Toolbox(request.tools.getOrElse(config.tools))
    Emit
      .element(A)
      .filter(_ => tools.tools.nonEmpty)
      .fold(Rounds.run(query.send[A](_), tools, request.query))(
        Emitting.run(query, tools, request.query, A, _),
      )

/**
  * The rounds of an agent's request: a query sent, the tools it asks for run,
  * and the query sent again, going on from it with their results, until the
  * model gives its value or asks for a tool not in the box. Each round after
  * the first caches the whole conversation, so that the next reads the rounds
  * before it from the cache and writes only its own.
  */
private[iris] object Rounds:

  def run[F[_] : MonadThrow, A]
    (
      send: QueryRequest[A] => F[QueryResponse[F, A]],
      tools: Toolbox[F],
      asked: QueryRequest[A],
    )
    : F[AgentResponse[F, A]] =
    def round
      (
        request: QueryRequest[A],
        done: Vector[QueryResponse[F, Any]],
      )
      : F[AgentResponse[F, A]] = send(request).flatMap: response =>
      val rounds   = done :+ response
      val answered = AgentResponse(
        response.value,
        rounds.toList.pure[F],
        response.cancel,
      ).pure[F]
      if response.value.nonEmpty then answered
      else
        response
          .iterations
          .map(_.last.toolCalls)
          .flatMap(calls =>
            if !tools.runs(calls) then answered
            else
              (tools.ran(calls), response.conversation).flatMapN(
                (results, conversation) =>
                  round(
                    onwards(request, conversation, results),
                    rounds,
                  ),
              ),
          )
    round(offered(tools, asked), Vector.empty)

  /** The query offering the box's tools, beside those it offers already. */
  def offered[F[_], A]
    (
      tools: Toolbox[F],
      asked: QueryRequest[A],
    )
    : QueryRequest[A] = asked.copy(prompt =
    asked.prompt.copy(tools = tools.offered(asked.prompt.tools)),
  )

  /** The query going on from a conversation with the tools' results. */
  def onwards[A]
    (
      request: QueryRequest[A],
      conversation: Conversation,
      results: List[MessagePart.ToolResult],
    )
    : QueryRequest[A] = request.copy(prompt =
    request
      .prompt
      .copy(
        conversation = conversation.toolSays(results*),
        cacheAll = true,
      ),
  )

/**
  * The rounds of an agent's request for a stream, whose elements the model
  * gives through [[Emit]], one call each, so that it may call the box's tools
  * between them: the stream given at once, and fed each element as its round
  * ends, in a fiber of its own, until the model calls [[Emit.done]] in a round
  * that asks for nothing more, or stops calling tools. A model writing the
  * elements as JSON instead has them read as a query reads any value.
  */
private[iris] final class Emitting[F[_] : Async]
  (
    query: QueryService[F],
    tools: Toolbox[F],
    element: Schema[?],
    limit: Int,
    feeds: Feeds[F],
    rounds: Deferred[
      F,
      Either[Throwable, List[QueryResponse[F, Any]]],
    ],
  ):

  private given listed: Schema[List[Any]] =
    Schema.listOf(element.asInstanceOf[Schema[Any]])

  /** Asks round after round, failing the stream where it cannot go on. */
  def run(request: QueryRequest[List[Any]]): F[Unit] =
    round(request, Progress(), Vector.empty)
      .handleErrorWith(failed(_).uncancelable)
      .onCancel(failed(IllegalStateException(
        "The answer was cancelled before its reply ended.",
      )))

  /**
    * How far the stream has gone: its elements fed, and the refusals in a row
    * since the last.
    */
  private final case class Progress(fed: Int = 0, wrong: Int = 0)

  /**
    * One round's calls, as they are read: what the model is told of each,
    * aligned with them, or `None` for a tool to run, whether an element in it
    * was refused, after which every later one is too, so that elements arrive
    * in order, and whether it called [[Emit.done]].
    */
  private final case class Turn
    (
      told: Vector[Option[String]] = Vector.empty,
      refused: Boolean = false,
      done: Boolean = false,
      others: Vector[MessagePart.ToolCall] = Vector.empty,
    ):

    def said(word: String): Turn = copy(told = told :+ Some(word))

    /** Whether the stream ends: done, and nothing to run nor give again. */
    def ends: Boolean = done && !refused && others.isEmpty

  /**
    * Sends a round's query, going on from it as its calls say. Once elements
    * have come, a response asking for nothing ends the stream however it reads.
    */
  private def round
    (
      request: QueryRequest[List[Any]],
      progress: Progress,
      done: Vector[QueryResponse[F, Any]],
    )
    : F[Unit] = query
    .send[List[Any]](request.copy(maxIterations =
      if progress.fed > 0 then Some(1) else request.maxIterations,
    ))
    .attempt
    .flatMap:
      case Right(response) => response.value match
          case Some(elements) => finished(
              if progress.fed > 0 then Nil else elements,
              done :+ response,
            )
          case None => (response.iterations, response.conversation).flatMapN(
              (responses, conversation) =>
                called(
                  request,
                  conversation,
                  responses,
                  progress,
                  done :+ response,
                ),
            )
      case Left(QueryError.Unreadable(why, responses)) =>
        val last = responses.last
        if progress.fed > 0 && last.toolCalls.isEmpty && settled(last) then
          finished(
            Nil,
            done :+ QueryResponse(
              None,
              responses.pure[F],
              Async[F].unit,
              request.prompt.conversation.pure[F],
            ),
          )
        // The whole calls of a response cut off or stopped still give their
        // elements, before the stream fails.
        else
          turned(last.toolCalls, progress) *> unreadable(done, responses, why)
      case Left(error) => error.raiseError

  /** Reads a round's calls, feeding each element, then goes on as they say. */
  private def called
    (
      request: QueryRequest[List[Any]],
      conversation: Conversation,
      responses: List[PromptResponse],
      progress: Progress,
      done: Vector[QueryResponse[F, Any]],
    )
    : F[Unit] =
    val calls = responses.last.toolCalls
    turned(calls, progress).flatMap: (turn, progress) =>
      val strangers = turn.others.filterNot(call => tools.runs(List(call)))
      if strangers.nonEmpty then
        unreadable(
          done.init,
          responses,
          s"It asked for a tool it was not given to run: ${ strangers
              .map(call => s"`${ call.tool }`")
              .distinct
              .mkString(", ") }.",
        )
      else if progress.wrong >= limit then
        unreadable(
          done.init,
          responses,
          s"It emitted an element that could not be read ${ progress
              .wrong } times in a row.",
        )
      else if turn.ends then
        finished(
          if progress.fed > 0 then Nil
          else Reading.read(listed, responses.last).getOrElse(Nil),
          done,
        )
      else
        calls
          .zip(turn.told)
          .traverse:
            case (call, Some(said)) =>
              MessagePart.ToolResult(call.id, call.tool, said).pure[F]
            case (call, None) => tools.run(call)
          .flatMap(results =>
            round(
              Rounds.onwards(request, conversation, results),
              progress,
              done,
            ),
          )

  /** A round's calls, each element fed as it is read, in order. */
  private def turned
    (
      calls: List[MessagePart.ToolCall],
      progress: Progress,
    )
    : F[(Turn, Progress)] = calls.foldLeftM((Turn(), progress)):
    case ((turn, progress), call) if call.tool == Emit.name =>
      if turn.refused then (turn.said(Emit.skipped), progress).pure[F]
      else
        Emit.read(element, call) match
          case Right(read) => feeds
              .feed(Emit.stream, read)
              .as((turn.said(Emit.taken), Progress(progress.fed + 1, 0)))
          case Left(why) => (
              turn.said(Emit.refused(why)).copy(refused = true),
              progress.copy(wrong = progress.wrong + 1),
            ).pure[F]
    case ((turn, progress), call) if call.tool == Emit.done =>
      (
        turn
          .said(if turn.refused then Emit.skipped else Emit.unfinished)
          .copy(done = true),
        progress,
      ).pure[F]
    case ((turn, progress), call) => (
        turn.copy(
          told = turn.told :+ None,
          others = turn.others :+ call,
        ),
        progress,
      ).pure[F]

  /** Whether a response stopped as the model meant, not cut off or stopped. */
  private def settled(response: PromptResponse): Boolean =
    response.stopReason match
      case StopReason.MaxTokens | StopReason.Other(_) => false
      case _                                          => true

  /** Fails with every response the rounds before took, and this one's. */
  private def unreadable
    (
      before: Vector[QueryResponse[F, Any]],
      responses: List[PromptResponse],
      why: String,
    )
    : F[Unit] = before
    .flatTraverse(_.iterations.map(_.toVector))
    .flatMap(earlier =>
      QueryError.Unreadable(why, earlier.toList ++ responses).raiseError,
    )

  /** Ends the stream, after any last elements, and its rounds. */
  private def finished
    (
      elements: List[Any],
      done: Vector[QueryResponse[F, Any]],
    )
    : F[Unit] = elements.traverse_(feeds.feed(Emit.stream, _)) *>
    feeds.end(Emit.stream) *> rounds.complete(Right(done.toList)).void

  /** Fails the stream, and its rounds. */
  private def failed(error: Throwable): F[Unit] =
    rounds.complete(Left(error)) *> feeds.fail(error)

private[iris] object Emitting:

  /**
    * Asks for a stream through [[Emit]], given at once, its rounds asked in a
    * fiber of its own, which stops early only where the request, or its value,
    * is cancelled.
    */
  def run[F[_] : Async, A]
    (
      query: QueryService[F],
      tools: Toolbox[F],
      asked: QueryRequest[A],
      schema: Schema[A],
      element: Schema[?],
    )
    : F[AgentResponse[F, A]] =
    val offered = tools.offered(asked.prompt.tools) ++ Emit.tools(element)
    Emit.unclaimed(asked.prompt.tools, offered).liftTo[F] *>
      (
        Feeds[F],
        Deferred[
          F,
          Either[Throwable, List[QueryResponse[F, Any]]],
        ],
      ).flatMapN((feeds, rounds) =>
        feeds.open(Emit.stream) *>
          Unfinished
            .standIn(schema, feeds.live(Emit.stream))
            .leftMap(IllegalStateException(_))
            .liftTo[F]
            .flatMap(value =>
              Emitting(
                query,
                tools,
                element,
                asked.maxIterations.getOrElse(QueryConfig().maxIterations),
                feeds,
                rounds,
              ).run(QueryRequest(
                  asked.prompt.copy(tools = offered),
                  asked.maxIterations,
                  Some(Emit.prompt),
                ))
                .start
                .map(fiber =>
                  AgentResponse(
                    Some(value.asInstanceOf[A]),
                    rounds.get.rethrow,
                    fiber.cancel,
                  ),
                ),
            ),
      )

/**
  * The tool through which an answer that is itself a stream gives its elements,
  * one call of it each, where the ask runs tools, so that the model may call
  * those between them, and the tool, [[done]], through which the model says it
  * has given the last, so that the reply saying so ends the answer.
  */
private[iris] object Emit:

  val name: String = "emit"

  /** The name of the tool through which the model says it is done. */
  val done: String = "done"

  /** The number of the stream its elements feed. */
  val stream: Int = 1

  /** What the model is told of an element read. */
  val taken: String = "ok"

  /**
    * What the model is told of a call of [[done]] in a reply after which the
    * answer goes on, as it also called other tools or gave an element that
    * could not be read.
    */
  val unfinished: String =
    "Not taken, as this reply also called other tools or gave an element " +
      s"that could not be read: call `$done` again once every element has " +
      "been emitted."

  /** What the model is told of an element that could not be read. */
  def refused(why: String): String =
    s"That element could not be read:\n$why\n\nEmit it again, as it should " +
      "be, and the elements after it, in order."

  /** What the model is told of an element after one refused in its reply. */
  val skipped: String =
    "Not read, as an element before it could not be: emit again from that one."

  /** What the model is told of the form its answer must take. */
  val prompt: String =
    s"Give your answer only through the `$name` tool, one call per element, in " +
      "order, writing no other text. Emit each element as soon as you can, " +
      "calling any other tool only when the next element needs it. You may " +
      "make several calls at once. Once every element has been emitted, call " +
      s"`$done` in that same reply, then stop. If there are no elements, " +
      "reply with `[]` alone."

  /** The schema of each element, where an answer of the schema is a stream. */
  def element(schema: Schema[?]): Option[Schema[?]] = schema.arrival match
    case Arrival.Streamed(element) => Some(element)
    case _                         => None

  /**
    * Refuses, beside it, a tool for the host to run, as an answer already given
    * could not end at its call, and a tool of its name or [[done]]'s, which the
    * model could not tell from them, among those `offered`.
    */
  def unclaimed
    (
      requested: List[Tool],
      offered: List[Tool],
    )
    : Either[IllegalArgumentException, Unit] =
    if requested.nonEmpty then
      Left(IllegalArgumentException(
        s"A tool cannot be offered for the host to run where a streamed answer is given through `$name`, as the answer could not end at its call: give it to the agent to run.",
      ))
    else
      List(name, done)
        .find(reserved => offered.count(_.name == reserved) != 1)
        .map(reserved =>
          IllegalArgumentException(
            s"A tool named `$reserved` cannot be run where a streamed answer is given through one of that name.",
          ),
        )
        .toLeft(())

  /**
    * The tools: this, taking an element of the schema under `element`, then
    * [[done]], taking nothing.
    */
  def tools(element: Schema[?]): List[Tool] =
    val json = element.json
    List(
      Tool(
        name,
        "Gives the next element of your answer, in order.",
        Json
          .obj(
            "type"       -> Json.fromString("object"),
            "properties" ->
              Json.obj("element" -> json.mapObject(_.remove("$defs"))),
            "required" -> Json.arr(Json.fromString("element")),
          )
          .deepMerge(
            json
              .hcursor
              .downField("$defs")
              .focus
              .fold(Json.obj())(defs => Json.obj("$defs" -> defs)),
          ),
      ),
      Tool(
        done,
        "Says that every element of your answer has been given.",
        Json.obj(
          "type"       -> Json.fromString("object"),
          "properties" -> Json.obj(),
        ),
      ),
    )

  /** The element a call of it gives, or why it gives none. */
  def read
    (
      element: Schema[?],
      call: MessagePart.ToolCall,
    )
    : Either[String, Any] = parse(call.arguments)
    .toOption
    .flatMap(_.hcursor.downField("element").focus)
    .toRight("It gave no `element`.")
    .flatMap(Reading.decoded(element, _))
