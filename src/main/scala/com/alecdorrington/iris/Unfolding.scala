package com.alecdorrington.iris

import cats.effect.{Async, Deferred, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef

/**
  * The asking of an answer given as its reply unfolds: as soon as all of it but
  * its streams is read, each stream then fed as its elements are. Until then,
  * it is asked as [[Question]] asks, and asked again where its reply cannot be
  * read; afterwards, a reply that cannot be read to its end fails the streams
  * not yet ended, and the answer's reply.
  */
private[iris] object Unfolding:

  /**
    * Asks the model through a client's `stream` until its answer can be given,
    * reading the reply to its end in a fiber of its own, which stops early only
    * where the ask is cancelled before its answer is given.
    */
  def ask[F[_] : Async, A : Schema as A]
    (
      stream: (Chat, ReplyOptions) => Stream[F, Delta],
      chat: Chat,
      options: ReplyOptions,
      attempts: Int,
    )
    : F[Answer[F, A]] = Question.counted(attempts).liftTo[F] *>
    (
      Ref.of[F, Map[Int, SignallingRef[F, Feed]]](Map.empty),
      Deferred[F, Either[Throwable, Option[A]]],
      Deferred[F, Either[Throwable, Reply]],
    ).mapN(Asking(stream(_, options), A, attempts, _, _, _))
      .flatMap(asking =>
        // Started and watched at once, so that no cancel falls between.
        Async[F]
          .uncancelable(poll =>
            asking
              .read(Question.opening(chat, A))
              .start
              .flatMap(reading =>
                poll(asking.value.get.rethrow)
                  .onCancel(reading.cancel)
                  .map(Answer(_, asking.reply.get.rethrow, reading.cancel)),
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
    * The asking of one answer: the feeds of its streams, by number, the
    * answer's value, once given, and its reply, once that ends.
    */
  private final class Asking[F[_] : Async, A]
    (
      stream: Chat => Stream[F, Delta],
      schema: Schema[A],
      attempts: Int,
      feeds: Ref[F, Map[Int, SignallingRef[F, Feed]]],
      val value: Deferred[F, Either[Throwable, Option[A]]],
      val reply: Deferred[F, Either[Throwable, Reply]],
    ):

    /**
      * Reads the reply to the chat, and any asked for after it, failing what is
      * still to be read where it cannot be, or the ask is cancelled first.
      */
    def read(chat: Chat): F[Unit] = attempt(chat, Vector.empty)
      .handleErrorWith(failed(_).uncancelable)
      .onCancel(failed(
        IllegalStateException("The answer was cancelled before its reply ended."),
      ))

    /** Reads the reply to the chat, after the replies so far. */
    private def attempt(chat: Chat, replies: Vector[Reply]): F[Unit] = Async[F]
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
                toolCalls = ending.fold(Nil)(_._1.toList),
              ),
              ending.isDefined,
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
            replies :+ Reply(unfinished.text, StopReason.Unknown),
            detail,
          )
          .raiseError

    /**
      * Ends the reply once it has ended, or its stream has without saying so,
      * reading it whole where its answer was not given already.
      */
    private def ended
      (
        unfinished: Unfinished,
        chat: Chat,
        replies: Vector[Reply],
        said: Boolean,
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
        case None => Question.verdict(schema, chat, replies, attempts) match
            case Question.Verdict.Answered(read, whole) =>
              value.complete(Right(read)) *> reply.complete(Right(whole)).void
            case Question.Verdict.Again(next)   => attempt(next, replies)
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
