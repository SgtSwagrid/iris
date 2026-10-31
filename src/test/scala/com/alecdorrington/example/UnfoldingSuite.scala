// Outside Iris's package, so that streams are derived as for a user.
package com.alecdorrington.example

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.alecdorrington.iris.*
import fs2.Stream
import io.circe.Json
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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

  /** A streamer giving each reply in turn, keeping each chat it was sent. */
  private def streamer
    (replies: Stream[IO, Delta]*)
    : IO[(LlmStreamer[IO], IO[List[Chat]])] = (
    Ref.of[IO, List[Stream[IO, Delta]]](replies.toList),
    Ref.of[IO, List[Chat]](Nil),
  ).mapN((left, sent) =>
    val scripted = new LlmStreamer[IO]:
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

  /** The answer asked of a streamer of one reply, with its streams read. */
  private def unfolded[A : Schema, B]
    (reply: Stream[IO, Delta])
    (read: A => IO[B])
    : IO[B] = streamer(reply).flatMap((llm, _) =>
    llm
      .ask[A](Chat.of("Translate."))
      .use(answering =>
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
          .use: answering =>
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
              .product(answering.answer.map(_.attempts))
      yield assertEquals(
        read,
        (Some("Hi" -> List(Word("a"), Word("b", Some("be")))), 1),
      ),
    )

  test("every element is read whole, however the reply is cut into parts"):
    val strings = """```json
["a]\"b", "c,d{", "", "\\"]
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
          ).tupled,
        )
        .map(_.foreach(read =>
          assertEquals(
            read,
            (List("a]\"b", "c,d{", "", "\\"), List(1L, 22L, 333L, -400L)),
          ),
        )),
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
          .use(answering =>
            answering
              .value
              .traverse(_.words.compile.toList)
              .product(answering.answer.map(_.attempts)),
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
              .use(answering =>
                (
                  answering.value.get.words.attempt.compile.toList,
                  answering.answer.attempt,
                ).tupled,
              ),
          )
          .map((words, answer) =>
            assertEquals(
              words.take(1),
              List(Right(Word("a"))),
              why,
            )
            assertEquals(words.size, 2, why)
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

  test("streams of a released answer fail rather than wait for good"):
    run(
      streamer(part("""{"title":"Hi","words":[""", 4) ++ Stream.never[IO])
        .flatMap((llm, _) => llm.ask[Page](Chat.of("Translate.")).use(IO.pure))
        .flatMap(_.value.get.words.compile.toList.attempt)
        .map(read => assert(read.isLeft, read)),
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
