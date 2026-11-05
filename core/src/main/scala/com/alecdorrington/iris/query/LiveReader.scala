package com.alecdorrington.iris
package query

import cats.effect.{Async, Deferred}
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

  /** This buffer ended, unless it has already. */
  def ended(outcome: Either[Throwable, Unit]): StreamBuffer =
    if end.isDefined then this else copy(end = Some(outcome))

/**
  * The buffers of a value's streams, by number, each opened as it begins, all
  * held in one cell, which the reader of the value's reply changes and its
  * streams follow.
  */
private[iris] final class StreamBuffers[F[_] : Async]
  (buffers: SignallingRef[F, Map[Int, StreamBuffer]]):

  def open(stream: Int): F[Unit] =
    buffers.update(_ + (stream -> StreamBuffer()))

  def feed(stream: Int, element: Any): F[Unit] = changed(stream, _.fed(element))

  def end(stream: Int): F[Unit] = changed(stream, _.ended(Right(())))

  /** Fails every stream not yet ended. */
  def fail(error: Throwable): F[Unit] =
    buffers.update(_.view.mapValues(_.ended(Left(error))).toMap)

  /**
    * A stream read from its buffer, once the reader has opened it: every
    * element fed it from the first, however often it is read.
    */
  def live(stream: Int): Any = buffers
    .discrete
    .map(_.get(stream))
    .unNone
    .takeThrough(_.end.isEmpty)
    .zipWithPrevious
    .flatMap((previous, buffer) =>
      Stream.emits(buffer.elements.drop(previous.fold(0)(_.elements.size))) ++
        Stream
          .fromOption(buffer.end.flatMap(_.left.toOption))
          .flatMap(Stream.raiseError[F]),
    )

  private def changed
    (
      stream: Int,
      change: StreamBuffer => StreamBuffer,
    )
    : F[Unit] = buffers.update(_.updatedWith(stream)(_.map(change)))

private[iris] object StreamBuffers:

  def apply[F[_] : Async]: F[StreamBuffers[F]] = SignallingRef
    .of[F, Map[Int, StreamBuffer]](Map.empty)
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
    buffers: StreamBuffers[F],
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
    : F[Unit] = prompt
    .stream(asked.copy(conversation = conversation))
    .evalMapAccumulate(PartialParser(schema, buffers.live)):
      case (parser, PromptEvent.Written(PromptEvent.Part.Text, text)) =>
        val (next, events) = parser.feed(text)
        events.traverse_(performed(next, sent)).as(next -> none[PromptResponse])
      case (parser, PromptEvent.Written(_, _))   => (parser -> none).pure[F]
      case (parser, PromptEvent.Ended(response)) => (parser -> response.some)
          .pure[F]
    .compile
    .last
    .flatMap:
      case Some((parser, Some(response))) =>
        answered(parser, conversation, sent :+ response)
      case ended => unreadable(
          sent :+ PromptResponse(
            ended.fold("")(_._1.text),
            StopReason.Unknown,
          ),
          "Its stream ended before it said it was over.",
        )

  /** Does as an event of the response says, while it is still arriving. */
  private def performed
    (
      parser: PartialParser,
      sent: Vector[PromptResponse],
    )
    (event: PartialParser.Event)
    : F[Unit] = event match
    case PartialParser.Event.Opened(stream) => buffers.open(stream)
    case PartialParser.Event.Read(0, read)  =>
      value.complete(Right(Some(read.asInstanceOf[A]))).void
    case PartialParser.Event.Read(stream, read) => buffers.feed(stream, read)
    case PartialParser.Event.Ended(stream)      => buffers.end(stream)
    case PartialParser.Event.Failed(detail)     => unreadable(
        sent :+ PromptResponse(parser.text, StopReason.Unknown),
        detail,
      )

  /**
    * Ends a response, checking it as a whole one is where its value was given
    * already, and otherwise reading it whole, as [[ValueReader.verdict]] does.
    */
  private def answered
    (
      parser: PartialParser,
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
          fault(response, parser.whole).fold(done)(unreadable(responses, _))
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
    ended.complete(Left(error)) *> buffers.fail(error)

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
  ).flatMapN((buffers, value, ended) =>
    val whole = ended.get.rethrow
    // Started and watched at once, so that no cancel falls between.
    Async[F].uncancelable(poll =>
      LiveReader(
        prompt,
        schema,
        attempts,
        asked,
        buffers,
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
