package com.alecdorrington.iris

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef

/**
  * The asking of an answer handed over as its reply unfolds: as soon as all of
  * it but its streams is read, each stream then fed as its elements are. Until
  * then, it is asked as [[Question]] asks, and asked again where its reply
  * cannot be read; afterwards, a reply that cannot be read to its end fails the
  * streams not yet ended, and the whole answer.
  */
private[iris] object Unfolding:

  /**
    * Asks the model through a client's `stream` until its answer can be handed
    * over, reading the reply in a fiber of its own for as long as the resource
    * is in use.
    */
  def ask[F[_] : Async, A : Schema as A]
    (
      stream: (Chat, ReplyOptions) => Stream[F, Delta],
      chat: Chat,
      options: ReplyOptions,
      attempts: Int,
    )
    : Resource[F, Answering[F, A]] =
    for
      _ <- Resource.eval(
        (Question.counted(attempts) *> toolless(options)).liftTo[F],
      )
      asking <- Resource.eval(
        (
          Ref.of[F, Map[Int, SignallingRef[F, Feed]]](Map.empty),
          Deferred[F, Either[Throwable, Option[A]]],
          Deferred[F, Either[Throwable, Answer[A]]],
        ).mapN(Asking(stream(_, options), A, attempts, _, _, _)),
      )
      _     <- asking.read(Question.opening(chat, A)).background
      value <- Resource.eval(asking.value.get.rethrow)
    yield Answering(value, asking.answer.get.rethrow)

  /** Refuses tools, as the calls of one are not streamed. */
  private def toolless
    (options: ReplyOptions)
    : Either[IllegalArgumentException, Unit] = Either.cond(
    options.tools.isEmpty,
    (),
    IllegalArgumentException(
      "A streamed ask cannot offer tools, as their calls are not streamed: ask with ask instead.",
    ),
  )

  /**
    * What a stream has been fed: its elements so far, each read from the first,
    * and how it ended, once it has.
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
    * The asking of one answer: the feeds of its streams, by number, the
    * answer's value, once handed over, and the whole answer.
    */
  private final class Asking[F[_] : Async, A]
    (
      stream: Chat => Stream[F, Delta],
      schema: Schema[A],
      attempts: Int,
      feeds: Ref[F, Map[Int, SignallingRef[F, Feed]]],
      val value: Deferred[F, Either[Throwable, Option[A]]],
      val answer: Deferred[F, Either[Throwable, Answer[A]]],
    ):

    /**
      * Reads the reply to the chat, and any asked for after it, failing what is
      * still to be read where it cannot be, or the resource is released first.
      */
    def read(chat: Chat): F[Unit] = attempt(chat, Vector.empty)
      .handleErrorWith(failed(_).uncancelable)
      .onCancel(failed(IllegalStateException(
        "The answer's resource was released before its reply ended.",
      )))

    /** Reads the reply to the chat, after the answers so far. */
    private def attempt
      (
        chat: Chat,
        answers: Vector[Answer[Nothing]],
      )
      : F[Unit] = Async[F]
      .delay(Unfinished(schema, live))
      .flatMap(unfinished =>
        stream(chat)
          .evalMapFilter:
            case Delta.Text(text) => Async[F]
                .delay(unfinished.feed(text))
                .flatMap(_.traverse_(performed(unfinished, answers)))
                .as(None)
            case end: Delta.End => Some(end).pure[F]
          .head
          .compile
          .last
          .flatMap(end =>
            ended(
              unfinished,
              chat,
              answers :+ Answer(
                text = unfinished.text,
                stopReason = end.fold(StopReason.Unknown)(_.stopReason),
                usage = end.fold(Usage())(_.usage),
              ),
              end.isDefined,
            ),
          ),
      )

    /** Does as an event of the reply says, while it is still arriving. */
    private def performed
      (
        unfinished: Unfinished,
        answers: Vector[Answer[Nothing]],
      )
      (event: Unfinished.Event)
      : F[Unit] = event match
      case Unfinished.Event.Opened(stream) => SignallingRef
          .of[F, Feed](Feed())
          .flatMap(feed => feeds.update(_ + (stream -> feed)))
      case Unfinished.Event.Read(0, read) =>
        value.complete(Right(Some(read.asInstanceOf[A]))).void
      case Unfinished.Event.Read(stream, read) =>
        feeds.get.flatMap(_(stream).update(_.fed(read)))
      case Unfinished.Event.Ended(stream) =>
        feeds.get.flatMap(_(stream).update(_.ended(Right(()))))
      case Unfinished.Event.Failed(detail) => Question
          .unreadable(
            answers :+ Answer(unfinished.text, StopReason.Unknown),
            detail,
          )
          .raiseError

    /**
      * Ends the reply once it has ended, or its stream has without saying so,
      * reading it whole where its answer was not handed over already.
      */
    private def ended
      (
        unfinished: Unfinished,
        chat: Chat,
        answers: Vector[Answer[Nothing]],
        said: Boolean,
      )
      : F[Unit] = value
      .tryGet
      .flatMap:
        case _ if !said =>
          Question
            .unreadable(
              answers,
              "Its stream ended before it said it was over.",
            )
            .raiseError
        case Some(handed) if unfinished.whole =>
          answer.complete(handed.map(Question.summed(answers, _))).void
        case Some(_) => Question
            .unreadable(
              answers,
              Question
                .hopeless(answers.last)
                .getOrElse("It ended before its JSON did."),
            )
            .raiseError
        case None => Question.verdict(schema, chat, answers, attempts) match
            case Question.Verdict.Answered(whole) =>
              value.complete(Right(whole.value)) *>
                answer.complete(Right(whole)).void
            case Question.Verdict.Again(next)   => attempt(next, answers)
            case Question.Verdict.Failed(error) => error.raiseError

    /** Fails whatever is still to be read of the answer. */
    private def failed(error: Throwable): F[Unit] =
      value.complete(Left(error)) *> answer.complete(Left(error)) *>
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
