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
  * it is asked as [[Question]] asks, and asked again where its reply cannot be
  * read; afterwards, a reply that cannot be read to its end fails the streams
  * not yet ended, and the answer's reply. Where tools are run and the answer is
  * itself a stream, its elements are given through [[Emit]] instead, one call
  * of it each, so that the model may call the tools between them.
  */
private[iris] object Unfolding:

  /**
    * Asks the model through a client's `stream` until its answer can be given,
    * reading the reply to its end in a fiber of its own, which stops early only
    * where the ask, or the answer, is cancelled.
    */
  def ask[F[_] : Async, A : Schema as A]
    (
      stream: (Chat, ReplyOptions) => Stream[F, Delta],
      chat: Chat,
      options: ReplyOptions,
      attempts: Int,
      tools: Toolbox[F],
    )
    : F[Answer[F, A]] =
    val emitting = Emit.element(A).filter(_ => tools.tools.nonEmpty)
    val base     = tools.offered(options)
    val offered  = emitting.fold(base)(element =>
      base.copy(tools = base.tools :+ Emit.tool(element)),
    )
    (Question.counted(attempts) *> Emit.unclaimed(emitting, offered)).liftTo[
      F,
    ] *>
      (
        Ref.of[F, Map[Int, SignallingRef[F, Feed]]](Map.empty),
        Deferred[F, Either[Throwable, Option[A]]],
        Deferred[F, Either[Throwable, Reply]],
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
              .read(Question.opening(
                chat,
                emitting.fold(Question.instructions(A))(_ => Emit.instructions),
              ))
              .start
              .flatMap(reading =>
                poll(asking.value.get.rethrow)
                  .onCancel(reading.cancel)
                  .map(Answer(
                    _,
                    asking.reply.get.rethrow,
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
    * the calls in it, and what the model is told of each emit, by its call.
    */
  private final case class Round
    (
      text: String = "",
      calls: Vector[Part.ToolCall] = Vector.empty,
      told: Map[String, String] = Map.empty,
    ):

    def called
      (
        call: Part.ToolCall,
        told: Option[String] = None,
      )
      : Round = copy(
      calls = calls :+ call,
      told = told.fold(this.told)(said => this.told + (call.id -> said)),
    )

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
      val reply: Deferred[F, Either[Throwable, Reply]],
    ):

    /**
      * Reads the reply to the chat, and any asked for after it, failing what is
      * still to be read where it cannot be, or the ask is cancelled first.
      */
    def read(chat: Chat): F[Unit] = emitting
      .fold(attempt(chat, Vector.empty, 0))(element =>
        opened(Emit.stream) *> emitted(chat, Vector.empty, 0, 0, element),
      )
      .handleErrorWith(failed(_).uncancelable)
      .onCancel(failed(IllegalStateException(
        "The answer was cancelled before its reply ended.",
      )))

    /** Reads the reply to the chat, after the replies so far, `tried` read. */
    private def attempt
      (
        chat: Chat,
        replies: Vector[Reply],
        tried: Int,
      )
      : F[Unit] = Async[F]
      .delay(Unfinished(schema, live))
      .flatMap(unfinished =>
        stream(chat)
          .evalMapAccumulate(Vector.empty[Part.ToolCall]):
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
              replies :+ Reply(
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
        replies: Vector[Reply],
      )
      (event: Unfinished.Event)
      : F[Unit] = event match
      case Unfinished.Event.Opened(stream)     => opened(stream)
      case Unfinished.Event.Read(0, read)      => handOver(read)
      case Unfinished.Event.Read(stream, read) =>
        feeds.get.flatMap(_(stream).update(_.fed(read)))
      case Unfinished.Event.Ended(stream) =>
        feeds.get.flatMap(_(stream).update(_.ended(Right(()))))
      case Unfinished.Event.Failed(detail) => Question
          .unreadable(
            replies :+ Reply(unfinished.text, StopReason.Unknown),
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
        replies: Vector[Reply],
        said: Boolean,
        tried: Int,
      )
      : F[Unit] = value
      .tryGet
      .flatMap:
        case _ if !said =>
          Question
            .unreadable(
              replies,
              "Its stream ended before it said it was over.",
            )
            .raiseError
        case Some(_) => fault(replies, unfinished.whole) match
            case None => reply.complete(Right(Question.summed(replies))).void
            case Some(why) => Question.unreadable(replies, why).raiseError
        case None => Question.verdict(
            schema,
            chat,
            replies,
            attempts,
            tried + 1,
            tools,
          ) match
            case Question.Verdict.Answered(read, whole) =>
              value.complete(Right(read)) *> reply.complete(Right(whole)).void
            case Question.Verdict.Run(calls) => tools
                .ran(calls)
                .flatMap(results =>
                  attempt(
                    Question.onwards(chat, replies.last, results),
                    replies,
                    tried,
                  ),
                )
            case Question.Verdict.Again(next) =>
              attempt(next, replies, tried + 1)
            case Question.Verdict.Failed(error) => error.raiseError

    /**
      * Why the reply an answer was given from cannot end it, where it cannot,
      * as [[Question.verdict]] would say of it whole: it asked for a tool, as
      * no answer may once given, or is cut off, stopped or empty, or its JSON
      * did not end.
      */
    private def fault(replies: Vector[Reply], whole: Boolean): Option[String] =
      val last = replies.last
      Option
        .when(Question.calledTools(last))(
          if whole then "It asked for a tool once its answer was given."
          else "It asked for a tool partway through its answer.",
        )
        .orElse(Question.hopeless(last))
        .orElse(Option.unless(whole)("It ended before its JSON did."))

    /**
      * Reads a reply to the chat whose elements arrive through [[Emit]], after
      * the replies so far, `wrong` of the elements in them unreadable and `fed`
      * read: each element is fed the answer's stream as its call ends, and each
      * tool asked for is run once the reply has, before the model is asked to
      * go on. The answer is given as soon as the reply begins.
      */
    private def emitted
      (
        chat: Chat,
        replies: Vector[Reply],
        wrong: Int,
        fed: Int,
        element: Schema[?],
      )
      : F[Unit] = stream(chat)
      .evalTap(_ => standIn.liftTo[F].flatMap(handOver))
      .evalMapAccumulate((Round(), fed)):
        case ((round, fed), Delta.Text(text)) =>
          ((round.copy(text = round.text + text), fed), None).pure[F]
        case ((round, fed), Delta.ToolCall(call)) if call.tool == Emit.name =>
          Emit.read(element, call) match
            case Right(read) => feeds
                .get
                .flatMap(_(Emit.stream).update(_.fed(read)))
                .as(((round.called(call, Some(Emit.taken)), fed + 1), None))
            case Left(why) =>
              ((round.called(call, Some(Emit.refused(why))), fed), None).pure[F]
        case ((round, fed), Delta.ToolCall(call)) =>
          ((round.called(call), fed), None).pure[F]
        case (state, end: Delta.End) => (state, Some(end)).pure[F]
      .collectFirst { case (state, Some(end)) => state -> end }
      .compile
      .last
      .flatMap:
        case None => Question
            .unreadable(
              replies,
              "Its stream ended before it said it was over.",
            )
            .raiseError
        case Some(((round, fed), end)) =>
          val reply = Reply(
            round.text,
            end.stopReason,
            end.usage,
            end.cost,
            round.calls.toList,
          )
          afterwards(
            chat,
            replies :+ reply,
            wrong + round.told.count(_._2 != Emit.taken),
            fed,
            round,
            element,
          )

    /**
      * What follows a reply of an answer given through [[Emit]]: the answer's
      * end, where it asks for nothing more, or else the tools it asks for run,
      * and the model asked to go on, or why it cannot be.
      */
    private def afterwards
      (
        chat: Chat,
        replies: Vector[Reply],
        wrong: Int,
        fed: Int,
        round: Round,
        element: Schema[?],
      )
      : F[Unit] =
      val last   = replies.last
      val others = round.calls.filterNot(_.tool == Emit.name).toList
      Emit
        .fault(last, others, tools, wrong, attempts)
        .fold(
          if round.calls.isEmpty then whole(replies, fed, element)
          else
            tools
              .ran(others)
              .flatMap(results =>
                val told = round.told ++
                  results.map(result => result.callId -> result.output)
                emitted(
                  Question.onwards(
                    chat,
                    last,
                    round
                      .calls
                      .toList
                      .map(call =>
                        Part.ToolResult(call.id, call.tool, told(call.id)),
                      ),
                  ),
                  replies,
                  wrong,
                  fed,
                  element,
                ),
              ),
        )(why => Question.unreadable(replies, why).raiseError)

    /**
      * Ends an answer given through [[Emit]] once the model asks for nothing
      * more: its stream, and its reply. Where no element came through emit, but
      * the reply holds them as JSON, as a model ignoring emit may, they are
      * read from it.
      */
    private def whole
      (
        replies: Vector[Reply],
        fed: Int,
        element: Schema[?],
      )
      : F[Unit] =
      val last = replies.last
      val read =
        if fed > 0 || last.text.trim.isEmpty then Right(Nil)
        else Emit.elements(element, last.text)
      read.fold(
        why => Question.unreadable(replies, why).raiseError,
        elements =>
          feeds
            .get
            .flatMap(_(Emit.stream).update(
              elements.foldLeft(_)(_.fed(_)).ended(Right(())),
            )) *> reply.complete(Right(Question.summed(replies))).void,
      )

    /** The answer an [[Emit]] stream is: its stream, stood in for. */
    private lazy val standIn: Either[LlmError, Any] = Unfinished
      .standIn(schema, live(Emit.stream))
      .leftMap(why => LlmError.Malformed("Iris", why))

    private def opened(stream: Int): F[Unit] = SignallingRef
      .of[F, Feed](Feed())
      .flatMap(feed => feeds.update(_ + (stream -> feed)))

    /** Gives the answer, unless it has been given already. */
    private def handOver(read: Any): F[Unit] = value
      .complete(Right(Some(read.asInstanceOf[A])))
      .void

    /** Fails whatever is still to be read of the answer. */
    private def failed(error: Throwable): F[Unit] =
      value.complete(Left(error)) *> reply.complete(Left(error)) *>
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
  * those between them.
  */
private[iris] object Emit:

  val name: String = "emit"

  /** The number of the stream its elements feed. */
  val stream: Int = 1

  /** What the model is told of an element read. */
  val taken: String = "ok"

  /** What the model is told of an element that could not be read. */
  def refused(why: String): String =
    s"That element could not be read:\n$why\n\nEmit it again, as it should be."

  /** What the model is told of the form its answer must take. */
  val instructions: String =
    s"Give your answer only through the `$name` tool, one call per element, in " +
      "order, writing no other text. Emit each element as soon as you can, " +
      "calling any other tool only when the next element needs it. You may " +
      "make several calls at once. When every element has been emitted, stop."

  /** The schema of each element, where an answer of the schema is a stream. */
  def element(schema: Schema[?]): Option[Schema[?]] = schema.arrival match
    case Arrival.Streamed(element) => Some(element)
    case _                         => None

  /**
    * Refuses a tool of its name beside it, which the model could not tell from
    * it.
    */
  def unclaimed
    (
      emitting: Option[Schema[?]],
      options: ReplyOptions,
    )
    : Either[IllegalArgumentException, Unit] = Either.cond(
    emitting.isEmpty || options.tools.count(_.name == name) == 1,
    (),
    IllegalArgumentException(
      s"A tool named `$name` cannot be offered where a streamed answer is given through one of that name.",
    ),
  )

  /** The tool, taking an element of the schema under `element`. */
  def tool(element: Schema[?]): Tool =
    val json = element.json
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
    )

  /** The element a call of it gives, or why it gives none. */
  def read(element: Schema[?], call: Part.ToolCall): Either[String, Any] = call
    .arguments
    .hcursor
    .downField("element")
    .focus
    .toRight("It gave no `element`.")
    .flatMap(Question.decoded(element, _))

  /** The elements a reply holds as a JSON array, as one ignoring it may. */
  def elements(element: Schema[?], text: String): Either[String, List[Any]] =
    Question
      .parsed(text)
      .flatMap(_.asArray.toRight("It is not a JSON array."))
      .flatMap(_.toList.traverse(Question.decoded(element, _)))

  /**
    * Why a reply of an answer given through it cannot go on, where it cannot:
    * it was cut off or stopped, asked for a tool not in the box, or too many of
    * its elements, `wrong` in all, could not be read.
    */
  def fault
    (
      reply: Reply,
      others: List[Part.ToolCall],
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
      Some(s"$wrong of its elements could not be read.")
    case _ => None
