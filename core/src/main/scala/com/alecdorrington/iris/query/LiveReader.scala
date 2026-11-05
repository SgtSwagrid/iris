package com.alecdorrington.iris
package query

import cats.effect.{Async, Deferred, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.alecdorrington.iris.prompt.*
import fs2.Stream
import fs2.concurrent.SignallingRef

/**
  * What a stream has been fed: its elements so far, each read from the first,
  * and how it ended, once it has. Every element is kept for as long as the
  * stream is, so that it reads alike however often.
  */
private[iris] final case class StreamBuffer
  (
    elements: Vector[Any] = Vector.empty,
    end: Option[Either[Throwable, Unit]] = None,
  ):

  def fed(element: Any): StreamBuffer = copy(elements = elements :+ element)

  /** This feed ended, unless it has already. */
  def ended(outcome: Either[Throwable, Unit]): StreamBuffer =
    if end.isDefined then this else copy(end = Some(outcome))

/** The feeds of a value's streams, by number, each opened as it begins. */
private[iris] final class StreamBuffers[F[_] : Async]
  (
    feeds: Ref[
      F,
      Map[Int, SignallingRef[F, StreamBuffer]],
    ],
  ):

  def open(stream: Int): F[Unit] = SignallingRef
    .of[F, StreamBuffer](StreamBuffer())
    .flatMap(feed => feeds.update(_ + (stream -> feed)))

  def feed(stream: Int, element: Any): F[Unit] = feeds
    .get
    .flatMap(_(stream).update(_.fed(element)))

  def end(stream: Int): F[Unit] = feeds
    .get
    .flatMap(_(stream).update(_.ended(Right(()))))

  /** Fails every stream not yet ended. */
  def fail(error: Throwable): F[Unit] = feeds
    .get
    .flatMap(_.values.toList.traverse_(_.update(_.ended(Left(error)))))

  /**
    * A stream read from its feed, once the reader has opened it: every element
    * fed it from the first, however often it is read.
    */
  def live(stream: Int): Any = Stream
    .eval(feeds.get.map(_(stream)))
    .flatMap(
      _.discrete
        .takeThrough(_.end.isEmpty)
        .zipWithPrevious
        .flatMap((previous, feed) =>
          Stream.emits(feed.elements.drop(previous.fold(0)(_.elements.size))) ++
            Stream
              .fromOption(feed.end.flatMap(_.left.toOption))
              .flatMap(Stream.raiseError[F]),
        ),
    )

private[iris] object StreamBuffers:

  def apply[F[_] : Async]: F[StreamBuffers[F]] = Ref
    .of[
      F,
      Map[Int, SignallingRef[F, StreamBuffer]],
    ](Map.empty)
    .map(new StreamBuffers(_))

/**
  * The reading of a value holding a stream as its response arrives: the value
  * given as soon as all of it but its streams is read, each stream then fed as
  * its elements close. Until then, a response that cannot be read is asked for
  * again, as one read whole is; afterwards, one that cannot be read to its end
  * fails the streams not yet ended, and the query's attempts.
  */
private[iris] final class LiveReader[F[_] : Async, A]
  (
    prompt: PromptService[F],
    schema: Schema[A],
    attempts: Int,
    asked: PromptRequest,
    feeds: StreamBuffers[F],
    value: Deferred[F, Either[Throwable, Option[A]]],
    ended: Deferred[
      F,
      Either[
        Throwable,
        (List[PromptResponse], Conversation),
      ],
    ],
  ):

  /**
    * Reads the response to the request, and any asked for after it, failing
    * what is still to be read where it cannot be, or the query is cancelled
    * first.
    */
  def read: F[Unit] = attempt(asked.conversation, Vector.empty)
    .handleErrorWith(failed(_).uncancelable)
    .onCancel(failed(
      IllegalStateException("The answer was cancelled before its reply ended."),
    ))

  /** Reads the response to the conversation, after those sent so far. */
  private def attempt
    (
      conversation: Conversation,
      sent: Vector[PromptResponse],
    )
    : F[Unit] = Async[F]
    .delay(PartialParser(schema, feeds.live))
    .flatMap(unfinished =>
      prompt
        .stream(asked.copy(conversation = conversation))
        .evalMap:
          case PromptEvent.Written(PromptEvent.Part.Text, text) => Async[F]
              .delay(unfinished.feed(text))
              .flatMap(_.traverse_(performed(unfinished, sent)))
              .as(none[PromptResponse])
          case PromptEvent.Written(_, _)   => none[PromptResponse].pure[F]
          case PromptEvent.Ended(response) => response.some.pure[F]
        .unNone
        .compile
        .last
        .flatMap:
          case Some(response) => answered(
              unfinished,
              conversation,
              sent :+ response,
            )
          case None => unreadable(
              sent :+ PromptResponse(unfinished.text, StopReason.Unknown),
              "Its stream ended before it said it was over.",
            ),
    )

  /** Does as an event of the response says, while it is still arriving. */
  private def performed
    (
      unfinished: PartialParser,
      sent: Vector[PromptResponse],
    )
    (event: PartialParser.Event)
    : F[Unit] = event match
    case PartialParser.Event.Opened(stream) => feeds.open(stream)
    case PartialParser.Event.Read(0, read)  =>
      value.complete(Right(Some(read.asInstanceOf[A]))).void
    case PartialParser.Event.Read(stream, read) => feeds.feed(stream, read)
    case PartialParser.Event.Ended(stream)      => feeds.end(stream)
    case PartialParser.Event.Failed(detail)     => unreadable(
        sent :+ PromptResponse(unfinished.text, StopReason.Unknown),
        detail,
      )

  /**
    * Ends a response, checking it as a whole one is where its value was given
    * already, and otherwise reading it whole, as [[ValueReader.verdict]] does.
    */
  private def answered
    (
      unfinished: PartialParser,
      conversation: Conversation,
      responses: Vector[PromptResponse],
    )
    : F[Unit] =
    val response = responses.last
    val done     = ended
      .complete(Right(responses.toList -> conversation.modelSays(response)))
      .void
    value
      .tryGet
      .flatMap:
        case Some(_) =>
          fault(response, unfinished.whole).fold(done)(unreadable(responses, _))
        case None => ValueReader.verdict(
            schema,
            conversation,
            response,
            responses.size < attempts,
          ) match
            case ValueReader.Verdict.Answered(read) =>
              value.complete(Right(read)) *> done
            case ValueReader.Verdict.Again(next) => attempt(next, responses)
            case ValueReader.Verdict.Failed(why) => unreadable(responses, why)

  /**
    * Why the response a value was given from cannot end it, where it cannot: it
    * asked for a tool, as no value may once given, or is cut off, stopped or
    * empty, or its JSON did not end.
    */
  private def fault(response: PromptResponse, whole: Boolean): Option[String] =
    Option
      .when(ValueReader.calledTools(response))(
        if whole then "It asked for a tool once its answer was given."
        else "It asked for a tool partway through its answer.",
      )
      .orElse(ValueReader.hopeless(response))
      .orElse(Option.unless(whole)("It ended before its JSON did."))

  private def unreadable
    (
      responses: Vector[PromptResponse],
      why: String,
    )
    : F[Unit] = QueryError.Unreadable(why, responses.toList).raiseError

  /** Fails whatever is still to be read of the value. */
  private def failed(error: Throwable): F[Unit] = value.complete(Left(error)) *>
    ended.complete(Left(error)) *> feeds.fail(error)

private[iris] object LiveReader:

  /**
    * Reads a value holding a stream, its response read to its end in a fiber of
    * its own, which stops early only where the query, or its value, is
    * cancelled.
    */
  def read[F[_] : Async, A]
    (
      prompt: PromptService[F],
      schema: Schema[A],
      attempts: Int,
      asked: PromptRequest,
    )
    : F[QueryResponse[F, A]] = (
    StreamBuffers[F],
    Deferred[F, Either[Throwable, Option[A]]],
    Deferred[
      F,
      Either[
        Throwable,
        (List[PromptResponse], Conversation),
      ],
    ],
  ).flatMapN((feeds, value, ended) =>
    val whole = ended.get.rethrow
    // Started and watched at once, so that no cancel falls between.
    Async[F].uncancelable(poll =>
      LiveReader(
        prompt,
        schema,
        attempts,
        asked,
        feeds,
        value,
        ended,
      ).read
        .start
        .flatMap(reading =>
          poll(value.get.rethrow)
            .onCancel(reading.cancel)
            .map(QueryResponse(
              _,
              whole.map(_._1),
              reading.cancel,
              whole.map(_._2),
            )),
        ),
    ),
  )
