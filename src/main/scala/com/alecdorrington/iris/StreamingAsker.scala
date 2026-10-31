package com.alecdorrington.iris

import cats.effect.{Async, Deferred, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json

/**
  * The asking of an answer given as its reply unfolds: as soon as all of it but
  * its streams is read, each stream then fed as its elements are. Until then,
  * it is asked as [[WholeAsker]] asks, and asked again where its reply cannot
  * be read; afterwards, a reply that cannot be read to its end fails the
  * streams not yet ended, and the answer's reply. Where tools are run and the
  * answer is itself a stream, its elements are given through [[Emit]] instead,
  * one call of it each, so that the model may call the tools between them, and
  * the stream ends with the reply in which the model says it is done.
  */
private[iris] object StreamingAsker:

  /**
    * Asks the model through a client's `stream` until its answer can be given,
    * reading the reply to its end in a fiber of its own, which stops early only
    * where the ask, or the answer, is cancelled.
    */
  def ask[F[_] : Async, A : Schema as A]
    (
      stream: (Chat, ModelRequest.Options) => Stream[F, Delta],
      chat: Chat,
      options: ModelRequest.Options,
      attempts: Int,
      tools: Toolbox[F],
    )
    : F[ValidatedResponse[F, A]] =
    val emitting = Emit.element(A).filter(_ => tools.tools.nonEmpty)
    val base     = tools.offered(options)
    val offered  = emitting.fold(base)(element =>
      base.copy(tools = base.tools ++ Emit.tools(element)),
    )
    (WholeAsker.counted(attempts) *> Emit.unclaimed(emitting, options, offered))
      .liftTo[F] *>
      (
        Ref.of[F, Map[Int, SignallingRef[F, Feed]]](Map.empty),
        Deferred[F, Either[Throwable, Option[A]]],
        Deferred[
          F,
          Either[Throwable, List[ModelResponse]],
        ],
      ).mapN(Asking(
          stream(_, offered),
          A,
          attempts,
          tools,
          emitting,
          _,
          _,
          _,
        ))
        .flatMap(asking =>
          // Started and watched at once, so that no cancel falls between.
          Async[F].uncancelable(poll =>
            asking
              .read(WholeAsker.opening(
                chat,
                emitting.fold(WholeAsker.schemaPrompt(A))(_ => Emit.prompt),
              ))
              .start
              .flatMap(reading =>
                poll(asking.value.get.rethrow)
                  .onCancel(reading.cancel)
                  .map(ValidatedResponse(
                    _,
                    asking.responses.get.rethrow,
                    reading.cancel,
                  )),
              ),
          ),
        )

  /**
    * What a stream has been fed: its elements so far, each read from the first,
    * and how it ended, once it has. Every element is kept for as long as the
    * stream is, so that it reads alike however often.
    */
  private final case class Feed
    (
      elements: Vector[Any] = Vector.empty,
      end: Option[Either[Throwable, Unit]] = None,
    ):

    def fed(element: Any): Feed = copy(elements = elements :+ element)

    /** This feed ended, unless it has already. */
    def ended(outcome: Either[Throwable, Unit]): Feed =
      if end.isDefined then this else copy(end = Some(outcome))

  /**
    * One reply of an answer given through [[Emit]], as it arrives: its text,
    * the calls in it, what the model is told of each, aligned with them, or
    * `None` for a tool to run, whether an emit in it was refused, after which
    * every later one is too, so that elements arrive in order, and whether it
    * called [[Emit.done]].
    */
  private final case class Round
    (
      text: String = "",
      calls: Vector[MessagePart.ToolCall] = Vector.empty,
      told: Vector[Option[String]] = Vector.empty,
      refused: Boolean = false,
      done: Boolean = false,
    ):

    def called
      (
        call: MessagePart.ToolCall,
        told: Option[String] = None,
      )
      : Round = copy(
      calls = calls :+ call,
      told = this.told :+ told,
    )

    /**
      * This round with [[Emit.done]] called, of which the model is told only
      * where the answer goes on.
      */
    def ending(call: MessagePart.ToolCall): Round = called(
      call,
      Some(if refused then Emit.skipped else Emit.unfinished),
    ).copy(done = true)

    /** The calls of tools to run. */
    def others: List[MessagePart.ToolCall] = calls
      .zip(told)
      .collect { case (call, None) => call }
      .toList

    /**
      * Whether the answer ends with this reply: it called [[Emit.done]], and
      * has no tool to run, nor element to give again.
      */
    def ends: Boolean = done && !refused && others.isEmpty

  /**
    * How far an answer given through [[Emit]] has gone: its elements read, the
    * refusals in a row since the last, and the replies read whole, as one
    * giving none through it is.
    */
  private final case class Progress
    (fed: Int = 0, wrong: Int = 0, tried: Int = 0)

  /**
    * The asking of one answer: the feeds of its streams, by number, the
    * answer's value, once given, and its reply, once that ends. Where it is
    * `emitting`, its answer is a stream of elements of that schema, numbered
    * `1`, given through [[Emit]].
    */
  private final class Asking[F[_] : Async, A]
    (
      stream: Chat => Stream[F, Delta],
      schema: Schema[A],
      attempts: Int,
      tools: Toolbox[F],
      emitting: Option[Schema[?]],
      feeds: Ref[F, Map[Int, SignallingRef[F, Feed]]],
      val value: Deferred[F, Either[Throwable, Option[A]]],
      val responses: Deferred[
        F,
        Either[Throwable, List[ModelResponse]],
      ],
    ):

    /**
      * Reads the reply to the chat, and any asked for after it, failing what is
      * still to be read where it cannot be, or the ask is cancelled first.
      */
    def read(chat: Chat): F[Unit] = emitting
      .fold(attempt(chat, Vector.empty, 0))(element =>
        opened(Emit.stream) *> emitted(chat, Vector.empty, Progress(), element),
      )
      .handleErrorWith(failed(_).uncancelable)
      .onCancel(failed(IllegalStateException(
        "The answer was cancelled before its reply ended.",
      )))

    /** Reads the reply to the chat, after the replies so far, `tried` read. */
    private def attempt
      (
        chat: Chat,
        replies: Vector[ModelResponse],
        tried: Int,
      )
      : F[Unit] = Async[F]
      .delay(Unfinished(schema, live))
      .flatMap(unfinished =>
        stream(chat)
          .evalMapAccumulate(Vector.empty[MessagePart.ToolCall]):
            case (calls, Delta.Text(text)) => Async[F]
                .delay(unfinished.feed(text))
                .flatMap(_.traverse_(performed(unfinished, replies)))
                .as(calls -> None)
            case (calls, Delta.ToolCall(call)) => (calls :+ call, None).pure[F]
            case (calls, end: Delta.End)       => (calls, Some(end)).pure[F]
          .collectFirst { case (calls, Some(end)) => calls -> end }
          .compile
          .last
          .flatMap(ending =>
            ended(
              unfinished,
              chat,
              replies :+ ModelResponse(
                text = unfinished.text,
                stopReason = ending.fold(StopReason.Unknown)(_._2.stopReason),
                usage = ending.fold(Usage())(_._2.usage),
                cost = ending.flatMap(_._2.cost),
                toolCalls = ending.fold(Nil)(_._1.toList),
              ),
              ending.isDefined,
              tried,
            ),
          ),
      )

    /** Does as an event of the reply says, while it is still arriving. */
    private def performed
      (
        unfinished: Unfinished,
        replies: Vector[ModelResponse],
      )
      (event: Unfinished.Event)
      : F[Unit] = event match
      case Unfinished.Event.Opened(stream)     => opened(stream)
      case Unfinished.Event.Read(0, read)      => handOver(read)
      case Unfinished.Event.Read(stream, read) =>
        feeds.get.flatMap(_(stream).update(_.fed(read)))
      case Unfinished.Event.Ended(stream) =>
        feeds.get.flatMap(_(stream).update(_.ended(Right(()))))
      case Unfinished.Event.Failed(detail) => WholeAsker
          .unreadable(
            replies :+ ModelResponse(unfinished.text, StopReason.Unknown),
            detail,
          )
          .raiseError

    /**
      * Ends the reply once it has ended, or its stream has without saying so,
      * reading it whole where its answer was not given already, as the
      * `tried`th, and running the tools it asks for, where all are in the box.
      */
    private def ended
      (
        unfinished: Unfinished,
        chat: Chat,
        replies: Vector[ModelResponse],
        said: Boolean,
        tried: Int,
      )
      : F[Unit] = value
      .tryGet
      .flatMap:
        case _ if !said =>
          WholeAsker
            .unreadable(
              replies,
              "Its stream ended before it said it was over.",
            )
            .raiseError
        case Some(_) => fault(replies, unfinished.whole) match
            case None      => responses.complete(Right(replies.toList)).void
            case Some(why) => WholeAsker.unreadable(replies, why).raiseError
        case None => WholeAsker.verdict(
            schema,
            chat,
            replies,
            attempts,
            tried + 1,
            tools,
          ) match
            case WholeAsker.Verdict.Answered(read, whole) =>
              value.complete(Right(read)) *>
                responses.complete(Right(whole)).void
            case WholeAsker.Verdict.Run(calls) => tools
                .ran(calls)
                .flatMap(results =>
                  attempt(
                    WholeAsker.onwards(chat, replies.last, results),
                    replies,
                    tried,
                  ),
                )
            case WholeAsker.Verdict.Again(next) =>
              attempt(next, replies, tried + 1)
            case WholeAsker.Verdict.Failed(error) => error.raiseError

    /**
      * Why the reply an answer was given from cannot end it, where it cannot,
      * as [[WholeAsker.verdict]] would say of it whole: it asked for a tool, as
      * no answer may once given, or is cut off, stopped or empty, or its JSON
      * did not end.
      */
    private def fault
      (
        replies: Vector[ModelResponse],
        whole: Boolean,
      )
      : Option[String] =
      val last = replies.last
      Option
        .when(WholeAsker.calledTools(last))(
          if whole then "It asked for a tool once its answer was given."
          else "It asked for a tool partway through its answer.",
        )
        .orElse(WholeAsker.hopeless(last))
        .orElse(Option.unless(whole)("It ended before its JSON did."))

    /**
      * Reads a reply to the chat whose elements arrive through [[Emit]], after
      * the replies so far: each element is fed the answer's stream as its call
      * ends, and each tool asked for is run once the reply has, before the
      * model is asked to go on. The answer is given as soon as the reply
      * begins.
      */
    private def emitted
      (
        chat: Chat,
        replies: Vector[ModelResponse],
        progress: Progress,
        element: Schema[?],
      )
      : F[Unit] = stream(chat)
      .evalTap(_ => standIn.liftTo[F].flatMap(handOver))
      .evalMapAccumulate((Round(), progress)):
        case ((round, progress), Delta.Text(text)) =>
          ((round.copy(text = round.text + text), progress), None).pure[F]
        case ((round, progress), Delta.ToolCall(call))
          if call.tool == Emit.name =>
          if round.refused then
            ((round.called(call, Some(Emit.skipped)), progress), None).pure[F]
          else
            Emit.read(element, call) match
              case Right(read) => feeds
                  .get
                  .flatMap(_(Emit.stream).update(_.fed(read)))
                  .as((
                    (
                      round.called(call, Some(Emit.taken)),
                      progress.copy(fed = progress.fed + 1, wrong = 0),
                    ),
                    None,
                  ))
              case Left(why) => (
                  (
                    round
                      .called(call, Some(Emit.refused(why)))
                      .copy(refused = true),
                    progress.copy(wrong = progress.wrong + 1),
                  ),
                  None,
                ).pure[F]
        case ((round, progress), Delta.ToolCall(call))
          if call.tool == Emit.done =>
          ((round.ending(call), progress), None).pure[F]
        case ((round, progress), Delta.ToolCall(call)) =>
          ((round.called(call), progress), None).pure[F]
        case (state, end: Delta.End) => (state, Some(end)).pure[F]
      .collectFirst { case (state, Some(end)) => state -> end }
      .compile
      .last
      .flatMap:
        case None => WholeAsker
            .unreadable(
              replies,
              "Its stream ended before it said it was over.",
            )
            .raiseError
        case Some(((round, progress), end)) => afterwards(
            chat,
            replies :+ ModelResponse(
              round.text,
              end.stopReason,
              end.usage,
              end.cost,
              round.calls.toList,
            ),
            round,
            progress,
            element,
          )

    /**
      * What follows a reply of an answer given through [[Emit]]: the answer's
      * end, where it asks for nothing more, or says it is done, or else the
      * tools it asks for run, and the model asked to go on, or why it cannot
      * be.
      */
    private def afterwards
      (
        chat: Chat,
        replies: Vector[ModelResponse],
        round: Round,
        progress: Progress,
        element: Schema[?],
      )
      : F[Unit] = Emit
      .fault(
        replies.last,
        round.others,
        tools,
        progress.wrong,
        attempts,
      )
      .fold(
        if round.calls.isEmpty then whole(chat, replies, progress, element)
        else if round.ends then
          finished(
            written(replies.last, progress, element),
            replies.toList,
          )
        else
          round
            .calls
            .zip(round.told)
            .toList
            .traverse:
              case (call, Some(said)) =>
                MessagePart.ToolResult(call.id, call.tool, said).pure[F]
              case (call, None) => tools.run(call)
            .flatMap(results =>
              emitted(
                WholeAsker.onwards(chat, replies.last, results),
                replies,
                progress,
                element,
              ),
            ),
      )(why => WholeAsker.unreadable(replies, why).raiseError)

    /**
      * Ends an answer given through [[Emit]] once the model asks for nothing
      * more. Where no element came through emit, the reply is read as a whole
      * ask reads one, as a list of the elements, and the model asked again
      * where it cannot be, so that a model ignoring emit is still answered.
      */
    private def whole
      (
        chat: Chat,
        replies: Vector[ModelResponse],
        progress: Progress,
        element: Schema[?],
      )
      : F[Unit] =
      if progress.fed > 0 then finished(Nil, replies.toList)
      else
        WholeAsker.verdict(
          listOf(element),
          chat,
          replies,
          attempts,
          progress.tried + 1,
          tools,
        ) match
          case WholeAsker.Verdict.Answered(read, whole) =>
            finished(read.getOrElse(Nil), whole)
          case WholeAsker.Verdict.Run(_)      => finished(Nil, replies.toList)
          case WholeAsker.Verdict.Again(next) => emitted(
              next,
              replies,
              progress.copy(tried = progress.tried + 1),
              element,
            )
          case WholeAsker.Verdict.Failed(error) => error.raiseError

    /**
      * The elements a reply calling [[Emit.done]] wrote as JSON instead, where
      * none came through emit, as a reply ignoring emit is read, or else none.
      */
    private def written
      (
        reply: ModelResponse,
        progress: Progress,
        element: Schema[?],
      )
      : List[Any] =
      if progress.fed > 0 then Nil
      else WholeAsker.read(listOf(element), reply).getOrElse(Nil)

    /** The schema of a list of the elements. */
    private def listOf(element: Schema[?]): Schema[List[Any]] =
      Schema.listOf(element.asInstanceOf[Schema[Any]])

    /** Ends an [[Emit]] stream, after any last elements, and its reply. */
    private def finished
      (
        elements: List[Any],
        whole: List[ModelResponse],
      )
      : F[Unit] = feeds
      .get
      .flatMap(
        _(Emit.stream).update(elements.foldLeft(_)(_.fed(_)).ended(Right(()))),
      ) *> responses.complete(Right(whole)).void

    /** The answer an [[Emit]] stream is: its stream, stood in for. */
    private lazy val standIn: Either[ModelError, Any] = Unfinished
      .standIn(schema, live(Emit.stream))
      .leftMap(why => ModelError.Malformed("Iris", why))

    private def opened(stream: Int): F[Unit] = SignallingRef
      .of[F, Feed](Feed())
      .flatMap(feed => feeds.update(_ + (stream -> feed)))

    /** Gives the answer, unless it has been given already. */
    private def handOver(read: Any): F[Unit] = value
      .complete(Right(Some(read.asInstanceOf[A])))
      .void

    /** Fails whatever is still to be read of the answer. */
    private def failed(error: Throwable): F[Unit] =
      value.complete(Left(error)) *> responses.complete(Left(error)) *>
        feeds
          .get
          .flatMap(_.values.toList.traverse_(_.update(_.ended(Left(error)))))

    /**
      * A stream read from its feed, once the reader has opened it: every
      * element fed it from the first, however often it is read.
      */
    private def live(stream: Int): Any = Stream
      .eval(feeds.get.map(_(stream)))
      .flatMap(
        _.discrete
          .takeThrough(_.end.isEmpty)
          .zipWithPrevious
          .flatMap((previous, feed) =>
            Stream.emits(
              feed.elements.drop(previous.fold(0)(_.elements.size)),
            ) ++
              Stream
                .fromOption(feed.end.flatMap(_.left.toOption))
                .flatMap(Stream.raiseError[F]),
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
      emitting: Option[Schema[?]],
      options: ModelRequest.Options,
      offered: ModelRequest.Options,
    )
    : Either[IllegalArgumentException, Unit] =
    if emitting.isEmpty then Right(())
    else if options.tools.nonEmpty then
      Left(IllegalArgumentException(
        s"A tool cannot be offered for the host to run where a streamed answer is given through `$name`, as the answer could not end at its call: give it to `ask` to run.",
      ))
    else
      List(name, done)
        .find(reserved => offered.tools.count(_.name == reserved) != 1)
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
    : Either[String, Any] = call
    .arguments
    .hcursor
    .downField("element")
    .focus
    .toRight("It gave no `element`.")
    .flatMap(WholeAsker.decoded(element, _))

  /**
    * Why a reply of an answer given through it cannot go on, where it cannot:
    * it was cut off or stopped, asked for a tool not in the box, or emitted an
    * element that could not be read `wrong` times in a row, as many as the
    * attempts allowed.
    */
  def fault
    (
      reply: ModelResponse,
      others: List[MessagePart.ToolCall],
      tools: Toolbox[?],
      wrong: Int,
      attempts: Int,
    )
    : Option[String] = reply.stopReason match
    case StopReason.MaxTokens =>
      Some("It was cut off at the token limit before it was complete.")
    case StopReason.Other(reason) => Some(s"It stopped, as `$reason`.")
    case _ if others.nonEmpty && !tools.runs(others) =>
      Some(s"It asked for a tool it was not given to run: ${ others
          .map(call => s"`${ call.tool }`")
          .distinct
          .mkString(", ") }.")
    case _ if wrong >= attempts =>
      Some(
        s"It emitted an element that could not be read $wrong times in a row.",
      )
    case _ => None
