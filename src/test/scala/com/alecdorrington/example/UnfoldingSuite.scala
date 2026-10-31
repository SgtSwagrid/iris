// Outside Iris's package, so that streams are derived as for a user.
package com.alecdorrington.example

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.alecdorrington.iris.*
import fs2.Stream
import io.circe.Json
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{BackendStub, ResponseStub}

final case class Word(text: String, gloss: Option[String] = None)

final case class Page(title: String, words: Stream[IO, Word])

final case class Card
  (
    title: String,
    note: Option[String] = None,
    words: Stream[IO, Word],
    count: Int = 0,
  )

final case class Verse
  (
    heading: String,
    lines: Stream[IO, String],
  )

final case class Gloss
  (
    title: String,
    note: Option[String] = None,
    words: Stream[IO, Word],
  )

final case class Two
  (
    first: Stream[IO, Int],
    second: Stream[IO, Int],
  )

sealed trait Listing

final case class Listed(items: Stream[IO, Int]) extends Listing

final case class Refused(why: String) extends Listing

class UnfoldingSuite extends FunSuite:

  /** A reply written `size` characters at a time, ending as said. */
  private def written
    (
      text: String,
      size: Int = 1,
      stop: StopReason = StopReason.Completed,
    )
    : Stream[IO, Delta] = part(text, size) ++
    Stream.emit(Delta.End(stop, Usage()))

  /** Part of a reply, written `size` characters at a time. */
  private def part(text: String, size: Int): Stream[IO, Delta] =
    Stream.emits(text.grouped(size).map(Delta.Text(_)).toList)

  /** A client streaming each reply in turn, keeping each chat it was sent. */
  private def streamer
    (replies: Stream[IO, Delta]*)
    : IO[(LlmClient[IO], IO[List[Chat]])] = (
    Ref.of[IO, List[Stream[IO, Delta]]](replies.toList),
    Ref.of[IO, List[Chat]](Nil),
  ).mapN((left, sent) =>
    val scripted = new LlmClient[IO]:
      // A type holding no stream is asked whole: the next reply, gathered.
      override protected def send
        (chat: Chat, options: ReplyOptions)
        : IO[Reply] = stream(chat, options)
        .compile
        .toList
        .map(deltas =>
          Reply(
            text = deltas.collect { case Delta.Text(text) => text }.mkString,
            stopReason = deltas
              .collectFirst { case Delta.End(stop, _) => stop }
              .getOrElse(StopReason.Unknown),
            toolCalls = deltas.collect { case Delta.ToolCall(call) => call },
          ),
        )

      override def count(chat: Chat, options: ReplyOptions): IO[Int] = IO
        .raiseError(Exception("Not counted."))

      override def stream
        (chat: Chat, options: ReplyOptions)
        : Stream[IO, Delta] = Stream
        .eval(
          sent.update(_ :+ chat) *> left.modify(all => (all.tail, all.head)),
        )
        .flatten
    (scripted, sent.get),
  )

  /** Runs a test's effect, giving up on one that waits for good. */
  private def run[A](effect: IO[A]): Future[A] = effect
    .timeout(10.seconds)
    .unsafeToFuture()

  /** The answer asked of a client streaming one reply, its streams read. */
  private def unfolded[A : Schema, B]
    (reply: Stream[IO, Delta])
    (read: A => IO[B])
    : IO[B] = streamer(reply).flatMap((llm, _) =>
    llm
      .ask[A](Chat.of("Translate."))
      .flatMap(answering =>
        answering.value.liftTo[IO](Exception("No value.")).flatMap(read),
      ),
  )

  test("a stream's elements arrive while the reply is still being written"):
    run(
      for
        gate <- Deferred[IO, Unit]
        // The rest of the reply is written only once the first word is read.
        reply = part(
          """{"title":"Hi","words":[{"text":"a"},""",
          8,
        ) ++ Stream.exec(gate.get) ++
          written("""{"text":"b","gloss":"be"}]}""", 8)
        (llm, _) <- streamer(reply)
        read     <- llm
          .ask[Page](Chat.of("Translate."))
          .flatMap: answering =>
            answering
              .value
              .traverse(page =>
                page
                  .words
                  .evalTap(_ => gate.complete(()))
                  .compile
                  .toList
                  .map(page.title -> _),
              )
              .product(answering.reply.map(_.attempts))
      yield assertEquals(
        read,
        (Some("Hi" -> List(Word("a"), Word("b", Some("be")))), 1),
      ),
    )

  test("every element is read whole, however the reply is cut into parts"):
    val strings = """```json
["a]\"b", "c,d{", "", "\\", "\u005d\u0022"]
```"""
    run(
      List(1, 2, 5)
        .traverse(size =>
          (
            unfolded[Stream[IO, String], List[String]](
              written(strings, size),
            )(_.compile.toList),
            unfolded[Stream[IO, Long], List[Long]](
              written("[1, 22,333 ,-4e2]", size),
            )(_.compile.toList),
            unfolded[
              Stream[IO, Option[Boolean]],
              List[Option[Boolean]],
            ](written("[true,null ,false]", size))(_.compile.toList),
          ).tupled,
        )
        .map(_.foreach(read =>
          assertEquals(
            read,
            (
              List("a]\"b", "c,d{", "", "\\", "]\""),
              List(1L, 22L, 333L, -400L),
              List(Some(true), None, Some(false)),
            ),
          ),
        )),
    )

  /**
    * The elements of the stream that `elements` finds in an answer, its reply
    * written past `first` only once the first of them has been read.
    */
  private def early[A : Schema]
    (first: String, rest: String)
    (elements: A => Stream[IO, Any])
    : IO[List[Any]] =
    for
      gate     <- Deferred[IO, Unit]
      (llm, _) <-
        streamer(part(first, 3) ++ Stream.exec(gate.get) ++ written(rest, 3))
      read <- llm
        .ask[A](Chat.of("Translate."))
        .flatMap(
          _.value
            .liftTo[IO](Exception("No value."))
            .flatMap(elements(_).evalTap(_ => gate.complete(())).compile.toList),
        )
    yield read

  test("a stream arrives as it is written wherever it is"):
    run(
      List(
        early[Gloss](
          """{"title":"Hi","words":[{"text":"a"},""",
          """{"text":"b"}]}""",
        )(_.words) -> List(Word("a"), Word("b")),
        early[Option[Stream[IO, Int]]]("[1,", "2]")(_.getOrElse(
          Stream.empty,
        )) -> List(1, 2),
        early[Either[String, Stream[IO, Int]]]("""{"Right":[1,""", "2]}")(
          _.getOrElse(Stream.empty),
        ) -> List(1, 2),
        early[Listing]("""{"Listed":{"items":[1,""", "2]}}") {
          case Listed(items) => items
          case Refused(_)    => Stream.empty
        } -> List(1, 2),
        early[(String, Stream[IO, Int])]("""["x",[1,""", "2]]")(_._2) ->
          List(1, 2),
        // A stream still to come is handed over before it begins.
        early[Two](
          """{"first":[1,""",
          """2],"second":[3]}""",
        )(two => two.first ++ two.second) -> List(1, 2, 3),
      ).traverse_((read, expected) => read.map(assertEquals(_, expected))),
    )

  test("a field written after its value was read fails the answer"):
    run(
      List(
        """{"title":"Hi","words":[{"text":"a"}],"note":"N"}""" -> "note",
        """{"title":"Hi","words":[{"text":"a"}],"words":[]}""" -> "words",
        """{"first":[1]}"""                                    -> "second",
      ).traverse_((reply, why) =>
        // A character at a time, so that each stream is seen open.
        streamer(written(reply))
          .flatMap((llm, _) =>
            if why == "second" then
              llm.ask[Two](Chat.of("Count.")).flatMap(_.reply.attempt)
            else llm.ask[Gloss](Chat.of("Translate.")).flatMap(_.reply.attempt),
          )
          .map:
            case Left(LlmError.Unreadable(_, detail, _)) =>
              assert(detail.contains(s"`$why`"), detail)
            case other => fail(s"Not unreadable: $other"),
      ),
    )

  test("a field written out of order is read once its value is whole"):
    run(
      unfolded[
        Gloss,
        (String, Option[String], List[Word]),
      ](written(
        """{"words":[{"text":"a"}],"note":"N","title":"Hi"}""",
        4,
      ))(gloss => gloss.words.compile.toList.map((gloss.title, gloss.note, _)))
        .map(read => assertEquals(read, ("Hi", Some("N"), List(Word("a"))))),
    )

  test("a stream within a stream arrives as it is written"):
    run(
      unfolded[
        Stream[IO, Verse],
        List[(String, List[String])],
      ](written(
        """[{"heading":"One","lines":["a","b"]},{"heading":"Two","lines":[]},{"heading":"Three","lines":["c"]}]""",
        3,
      ))(
        _.evalMap(section =>
            section.lines.compile.toList.map(section.heading -> _),
          )
          .compile
          .toList,
      ).map(read =>
        assertEquals(
          read,
          List(
            "One"   -> List("a", "b"),
            "Two"   -> Nil,
            "Three" -> List("c"),
          ),
        ),
      ),
    )

  test("a field left out is read as left out, before a stream or after it"):
    run(
      List(
        """{"title":"Hi","words":[{"text":"a"}]}""" -> ("Hi", None, 0),
        """{"title":"Hi","note":"N","words":[{"text":"a"}],"count":3}""" ->
          ("Hi", Some("N"), 3),
      ).traverse_((reply, expected) =>
        unfolded[
          Card,
          ((String, Option[String], Int), List[Word]),
        ](written(reply, 4))(card =>
          card
            .words
            .compile
            .toList
            .map((card.title, card.note, card.count) -> _),
        ).map(read => assertEquals(read, expected -> List(Word("a")))),
      ),
    )

  test("a reply unreadable before its answer is handed over is asked again"):
    run(
      for
        (llm, sent) <- streamer(
          written("""{"title":5,"words":[]}"""),
          written("""{"title":"Hi","words":[{"text":"a"}]}"""),
        )
        read <- llm
          .ask[Page](Chat.of("Translate."))
          .flatMap(answering =>
            answering
              .value
              .traverse(_.words.compile.toList)
              .product(answering.reply.map(_.attempts)),
          )
        chats <- sent
      yield
        assertEquals(read, (Some(List(Word("a"))), 2))
        assert(
          chats.last.messages.last.text.contains("could not be read"),
          chats.last,
        ),
    )

  test("a reply that cannot be read to its end fails its stream and answer"):
    run(
      List(
        written(
          """{"title":"Hi","words":[{"text":"a"},{"te""",
          4,
          StopReason.MaxTokens,
        ) -> "token limit",
        written(
          """{"title":"Hi","words":[{"text":"a"},{"text":5}]}""",
          4,
        ) -> "string",
        written(
          """{"title":"Hi","words":[{"text":"a"},]}""",
          4,
        ) -> "JSON",
        written(
          """{"title":"Hi","words":[{"text":"a"}""",
          4,
        ) -> "ended",
      ).traverse_((reply, why) =>
        streamer(reply)
          .flatMap((llm, _) =>
            llm
              .ask[Page](Chat.of("Translate."))
              .flatMap(answering =>
                (
                  answering.value.get.words.attempt.compile.toList,
                  answering.reply.attempt,
                  answering.value.get.words.attempt.compile.toList,
                ).tupled,
              ),
          )
          .map((words, answer, again) =>
            assertEquals(
              words.take(1),
              List(Right(Word("a"))),
              why,
            )
            assertEquals(words.size, 2, why)
            assertEquals(again, words, why)
            List(words.last, answer).foreach:
              case Left(LlmError.Unreadable(_, detail, _)) =>
                assert(detail.contains(why), detail)
              case other => fail(s"Not unreadable: $other"),
          ),
      ),
    )

  test("a type holding no stream is answered once its reply ends"):
    run(
      unfolded[Person, Person](written(
        "Here:\n```json\n{\"name\":\"Ada\",\"age\":36}\n```",
        5,
      ))(IO.pure).map(read => assertEquals(read, Person("Ada", 36, None))),
    )

  test("a request failing after its answer is handed over fails as it does"):
    val timedOut = LlmError.TimedOut("Anthropic", 1.second)
    run(
      streamer(
        part(
          """{"title":"Hi","words":[{"text":"a"},""",
          4,
        ) ++ Stream.raiseError[IO](timedOut),
      ).flatMap((llm, _) =>
        llm
          .ask[Page](Chat.of("Translate."))
          .flatMap(answering =>
            (
              answering.value.get.words.attempt.compile.toList,
              answering.reply.attempt,
            ).tupled,
          )
          .map((words, answer) =>
            assertEquals(
              words,
              List(Right(Word("a")), Left(timedOut)),
            )
            assertEquals(answer.left.toOption, Some(timedOut)),
          ),
      ),
    )

  test("a stream within a stream that cannot be read fails both"):
    run(
      streamer(written(
        """[{"heading":"One","lines":["a",5]}]""",
        3,
      )).flatMap((llm, _) =>
          llm
            .ask[Stream[IO, Verse]](Chat.of("Translate."))
            .flatMap(
              _.value
                .get
                .evalMap(_.lines.attempt.compile.toList)
                .attempt
                .compile
                .toList,
            ),
        )
        .map:
          case List(
              Right(
                List(
                  Right("a"),
                  Left(_: LlmError.Unreadable),
                ),
              ),
              Left(_: LlmError.Unreadable),
            ) => ()
          case other => fail(s"Not failed within and without: $other"),
    )

  test("a stream reads alike however often, at once, later, or after its end"):
    run(
      for
        gate <- Deferred[IO, Unit]
        reply = part(
          """{"title":"Hi","words":[{"text":"a"},""",
          8,
        ) ++ Stream.exec(gate.get) ++ written("""{"text":"b"}]}""", 8)
        (llm, _) <- streamer(reply)
        read     <- llm
          .ask[Page](Chat.of("Translate."))
          .flatMap: answering =>
            val words = answering.value.get.words
            for
              both <- (
                words.evalTap(_ => gate.complete(())).compile.toList,
                words.take(1).compile.toList,
              ).parTupled
              ended <- answering.reply.as(answering.value.map(_.title))
              again <- words.compile.toList
            yield (both, ended, again)
      yield assertEquals(
        read,
        (
          (List(Word("a"), Word("b")), List(Word("a"))),
          Some("Hi"),
          List(Word("a"), Word("b")),
        ),
      ),
    )

  test("a tool asked for in a streamed reply is in its reply, with no value"):
    val look = Part.ToolCall(
      "call_1",
      "look",
      Json.obj("word" -> Json.fromString("Hi")),
    )
    run(
      streamer(Stream(
        Delta.Text("Let me look."),
        Delta.ToolCall(look),
        Delta.End(StopReason.ToolCall, Usage()),
      )).flatMap((llm, _) =>
        llm
          .ask[Page](
            Chat.of("Translate."),
            ReplyOptions(tools = List(Tool("look", "Looks up.", Json.obj()))),
          )
          .flatMap(answer =>
            answer.reply.map(reply => (answer.value.isEmpty, reply.toolCalls)),
          )
          .map(read => assertEquals(read, (true, List(look)))),
      ),
    )

  test("a reply is read to its end though nothing reads its streams"):
    val page = """{"title":"Hi","words":[{"text":"a"},{"text":"b"}]}"""
    run(
      streamer(written(page, 4))
        .flatMap((llm, _) =>
          llm
            .ask[Page](Chat.of("Translate."))
            .flatMap(answer =>
              answer
                .reply
                .product(answer.value.traverse(_.words.compile.toList)),
            ),
        )
        .map((reply, words) =>
          assertEquals(
            (reply.text, reply.attempts, words),
            (page, 1, Some(List(Word("a"), Word("b")))),
          ),
        ),
    )

  test("a client that cannot stream gives a streamed type's answer whole"):
    val whole = new LlmClient[IO]:
      override protected def send
        (chat: Chat, options: ReplyOptions)
        : IO[Reply] =
        IO.pure(Reply("""{"title":"Hi","words":[{"text":"a"},{"text":"b"}]}"""))

      override def count(chat: Chat, options: ReplyOptions): IO[Int] = IO
        .raiseError(Exception("Not counted."))
    run(
      whole
        .ask[Page](Chat.of("Translate."))
        .flatMap(_.value.traverse(_.words.compile.toList))
        .map(words => assertEquals(words, Some(List(Word("a"), Word("b"))))),
    )

  test("a value is given early only while one of its streams is open"):
    run(
      for
        // Misnamed, so no stream of it opens: read whole, and asked again.
        (llm, sent) <- streamer(
          written("""{"First":[1],"Second":[2]}"""),
          written("""{"first":[1],"second":[2]}"""),
        )
        read <- llm
          .ask[Two](Chat.of("Count."))
          .flatMap(two =>
            two
              .value
              .traverse(two => (two.first ++ two.second).compile.toList)
              .product(two.reply.map(_.attempts)),
          )
        // A case whole before another begins waits, and reads as a whole reply.
        (unions, _) <- streamer(
          written("""{"Refused":{"why":"x"},"Listed":{"items":[1]}}"""),
        )
        listed <- unions
          .ask[Listing](Chat.of("List."))
          .flatMap(
            _.value
              .traverse {
                case Listed(items) => items.compile.toList.map(Right(_))
                case refused       => IO.pure(Left(refused))
              },
          )
      yield
        assertEquals(read, (Some(List(1, 2)), 2))
        assertEquals(listed, Some(Right(List(1)))),
    )

  test("a given answer's reply is checked as a whole reply would be"):
    val page = """{"title":"Hi","words":[{"text":"a"}]}"""
    val look = Part.ToolCall("call_1", "look", Json.obj())
    run(
      List(
        (
          Stream(Delta.Text(page), Delta.ToolCall(look)) ++
            Stream(Delta.End(StopReason.ToolCall, Usage())),
          "once its answer was given",
        ),
        (
          Stream(
            Delta.Text(page.dropRight(1)),
            Delta.ToolCall(look),
          ) ++ Stream(Delta.End(StopReason.ToolCall, Usage())),
          "partway through its answer",
        ),
        (written(page, 4, StopReason.MaxTokens), "token limit"),
        (written(page, 4, StopReason.Other("refusal")), "refusal"),
      ).traverse_((reply, why) =>
        streamer(reply)
          .flatMap((llm, _) =>
            llm
              .ask[Page](Chat.of("Translate."))
              .flatMap(answer =>
                (
                  answer.value.traverse(_.words.compile.toList),
                  answer.reply.attempt,
                ).tupled,
              ),
          )
          .map((words, reply) =>
            assertEquals(words, Some(List(Word("a"))), why)
            reply match
              case Left(LlmError.Unreadable(_, detail, _)) =>
                assert(detail.contains(why), detail)
              case other => fail(s"Not unreadable ($why): $other"),
          ),
      ),
    )

  test("an ask cancelled before its answer is given stops reading"):
    run(
      for
        begun <- Deferred[IO, Unit]
        ended <- Deferred[IO, Boolean]
        reply =
          (part("Let me think.", 4) ++ Stream.exec(begun.complete(()).void) ++
            Stream.never[IO]).onFinalizeCase(exit =>
            ended.complete(exit == Resource.ExitCase.Canceled).void,
          )
        (llm, _)  <- streamer(reply)
        asking    <- llm.ask[Page](Chat.of("Translate.")).start
        _         <- begun.get
        _         <- asking.cancel
        cancelled <- ended.get
      yield assert(cancelled),
    )

  test("a cancelled answer stops reading, failing what it had still to give"):
    run(
      for
        ended <- Deferred[IO, Boolean]
        reply = (part("""{"title":"Hi","words":[{"text":"a"},""", 4) ++
          Stream.never[IO]).onFinalizeCase(exit =>
          ended.complete(exit == Resource.ExitCase.Canceled).void,
        )
        (llm, _) <- streamer(reply)
        answer   <- llm.ask[Page](Chat.of("Translate."))
        _        <- answer.cancel
        words    <- answer.value.get.words.attempt.compile.toList
        whole    <- answer.reply.attempt
        stopped  <- ended.get
      yield
        assert(stopped)
        List(words.last, whole).foreach:
          case Left(error: IllegalStateException) =>
            assert(error.getMessage.contains("cancelled"), error)
          case other => fail(s"Not cancelled: $other"),
    )

  test("a type holding no stream is asked of a client with one request"):
    val anthropic = LlmClient[IO](
      LlmConfig(LlmModel.ClaudeHaiku4_5, "key", 512),
      BackendStub(CatsMonadAsyncError[IO]())
        .whenAnyRequest
        .thenRespond(ResponseStub.adjust(
          """{"content":[{"type":"text","text":"{\"name\":\"Ada\",\"age\":36}"}],
            "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""",
        )),
    )
    run(
      anthropic
        .ask[Person](Chat.of("Invent a person."))
        .flatMap(answer =>
          answer.reply.map(answer.value -> _.usage.outputTokens),
        )
        .map(read => assertEquals(read, (Some(Person("Ada", 36, None)), 2))),
    )

  test("a stream is asked for as a list, and read from a whole reply as one"):
    assertEquals(
      Schema[Stream[IO, Word]].json,
      Schema[List[Word]].json,
    )
    intercept[UnsupportedOperationException](
      Schema[Stream[IO, Word]].encoder(Stream.emit(Word("a"))),
    )
    run(
      Schema[Page]
        .decoder
        .decodeJson(Json.obj(
          "title" -> Json.fromString("Hi"),
          "words" -> Json.arr(Json.obj("text" -> Json.fromString("a"))),
        ))
        .liftTo[IO]
        .flatMap(_.words.compile.toList)
        .map(words => assertEquals(words, List(Word("a")))),
    )
