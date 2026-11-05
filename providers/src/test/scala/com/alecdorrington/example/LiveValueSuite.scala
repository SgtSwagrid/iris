// Outside Iris's package, so that streams are derived as for a user.
package com.alecdorrington.example

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.alecdorrington.iris.*
import com.alecdorrington.iris.agent.*
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
import com.alecdorrington.iris.query.*
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

final case class Lookup(word: String)

sealed trait Listing

final case class Listed(items: Stream[IO, Int]) extends Listing

final case class Refused(why: String) extends Listing

class LiveValueSuite extends FunSuite:

  /** A reply written `size` characters at a time, ending as said. */
  private def written
    (
      text: String,
      size: Int = 1,
      stop: StopReason = StopReason.Completed,
    )
    : Stream[IO, PromptEvent] = part(text, size) ++ Stream.emit(ended(stop))

  /** Part of a reply, written `size` characters at a time. */
  private def part(text: String, size: Int): Stream[IO, PromptEvent] = Stream
    .emits(text.grouped(size).map(wrote).toList)

  /** More of a reply's text. */
  private def wrote(fragment: String): PromptEvent =
    PromptEvent.Written(PromptEvent.Part.Text, fragment)

  /** A tool the model asks to have run, as its call arrives whole. */
  private def called(call: MessagePart.ToolCall): PromptEvent = PromptEvent
    .Written(
      PromptEvent.Part.Call(call.id, call.tool, call.signature),
      call.arguments,
    )

  /**
    * The end of a reply, stopping as said, of which a scripted service fills in
    * the text and calls before it.
    */
  private def ended(stop: StopReason, usage: Usage = Usage()): PromptEvent =
    PromptEvent.Ended(PromptResponse("", stop, usage))

  /** A reply's events, its end holding all before it, as a service gives it. */
  private def gathered
    (events: Stream[IO, PromptEvent])
    : Stream[IO, PromptEvent] = events
    .mapAccumulate(PromptResponse("")):
      case (so, event @ PromptEvent.Written(PromptEvent.Part.Text, text)) =>
        (so.copy(text = so.text + text), event)
      case (
          so,
          event @ PromptEvent.Written(
            PromptEvent.Part.Call(id, tool, signature),
            arguments,
          ),
        ) => (
          so.copy(toolCalls =
            so.toolCalls :+ MessagePart.ToolCall(id, tool, arguments, signature),
          ),
          event,
        )
      case (so, PromptEvent.Ended(end)) =>
        val whole = so.copy(
          stopReason = end.stopReason,
          usage = end.usage,
          cost = end.cost,
        )
        (whole, PromptEvent.Ended(whole))
    .map(_._2)

  /**
    * A prompt service streaming each reply in turn, keeping each conversation
    * it was sent. A type holding no stream is sent whole: the next reply,
    * gathered.
    */
  private def streamer
    (replies: Stream[IO, PromptEvent]*)
    : IO[(PromptService[IO], IO[List[Conversation]])] = (
    Ref.of[IO, List[Stream[IO, PromptEvent]]](replies.toList),
    Ref.of[IO, List[Conversation]](Nil),
  ).mapN((left, sent) =>
    val scripted: PromptService[IO] = request =>
      Stream
        .eval(
          sent.update(_ :+ sending(request)) *>
            left.modify(all => (all.tail, all.head)),
        )
        .flatten
        .through(gathered)
    (scripted, sent.get),
  )

  /**
    * The conversation a provider is sent, closed with a breakpoint where the
    * request caches it all, as a prompt service closes it.
    */
  private def sending(request: PromptRequest): Conversation =
    val messages = request.conversation.messages
    if !request.cacheAll then request.conversation
    else
      request
        .conversation
        .copy(messages =
          messages.init :+
            (messages.last match
              case Message.User(parts) =>
                Message.User(parts :+ MessagePart.CacheBreakpoint)
              case Message.Model(parts) =>
                Message.Model(parts :+ MessagePart.CacheBreakpoint)
              case Message.Tool(parts) =>
                Message.Tool(parts :+ MessagePart.CacheBreakpoint)),
        )

  /** Runs a test's effect, giving up on one that waits for good. */
  private def run[A](effect: IO[A]): Future[A] = effect
    .timeout(10.seconds)
    .unsafeToFuture()

  /** The answer asked of a client streaming one reply, its streams read. */
  private def unfolded[A : Schema, B]
    (reply: Stream[IO, PromptEvent])
    (read: A => IO[B])
    : IO[B] = streamer(reply).flatMap((llm, _) =>
    llm
      .query
      .send[A](Conversation.userSays("Translate."))
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
          .query
          .send[Page](Conversation.userSays("Translate."))
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
              .product(answering.attempts.map(_.size))
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
        .query
        .send[A](Conversation.userSays("Translate."))
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
              llm
                .query
                .send[Two](Conversation.userSays("Count."))
                .flatMap(_.attempts.attempt)
            else
              llm
                .query
                .send[Gloss](Conversation.userSays("Translate."))
                .flatMap(_.attempts.attempt),
          )
          .map:
            case Left(QueryError.Unreadable(detail, _)) =>
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
          .query
          .send[Page](Conversation.userSays("Translate."))
          .flatMap(answering =>
            answering
              .value
              .traverse(_.words.compile.toList)
              .product(answering.attempts.map(_.size)),
          )
        conversations <- sent
      yield
        assertEquals(read, (Some(List(Word("a"))), 2))
        assert(
          conversations.last.messages.last.text.contains("could not be read"),
          conversations.last,
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
              .query
              .send[Page](Conversation.userSays("Translate."))
              .flatMap(answering =>
                (
                  answering.value.get.words.attempt.compile.toList,
                  answering.attempts.attempt,
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
              case Left(QueryError.Unreadable(detail, _)) =>
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
    val timedOut = ApiError.TimedOut("Anthropic", 1.second)
    run(
      streamer(
        part(
          """{"title":"Hi","words":[{"text":"a"},""",
          4,
        ) ++ Stream.raiseError[IO](timedOut),
      ).flatMap((llm, _) =>
        llm
          .query
          .send[Page](Conversation.userSays("Translate."))
          .flatMap(answering =>
            (
              answering.value.get.words.attempt.compile.toList,
              answering.attempts.attempt,
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
            .query
            .send[Stream[IO, Verse]](Conversation.userSays("Translate."))
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
                  Left(_: QueryError.Unreadable),
                ),
              ),
              Left(_: QueryError.Unreadable),
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
          .query
          .send[Page](Conversation.userSays("Translate."))
          .flatMap: answering =>
            val words = answering.value.get.words
            for
              both <- (
                words.evalTap(_ => gate.complete(())).compile.toList,
                words.take(1).compile.toList,
              ).parTupled
              ended <- answering.attempts.as(answering.value.map(_.title))
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
    val look = MessagePart.ToolCall(
      "call_1",
      "look",
      Json.obj("word" -> Json.fromString("Hi")).noSpaces,
    )
    run(
      streamer(Stream(
        wrote("Let me look."),
        called(look),
        ended(StopReason.ToolCall, Usage()),
      )).flatMap((llm, _) =>
        llm
          .query
          .send[Page](PromptRequest(
            Conversation.userSays("Translate."),
            tools = List(Tool("look", "Looks up.", Json.obj())),
          ))
          .flatMap(answer =>
            answer
              .attempts
              .map(responses =>
                (answer.value.isEmpty, responses.last.toolCalls),
              ),
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
            .query
            .send[Page](Conversation.userSays("Translate."))
            .flatMap(answer =>
              answer
                .attempts
                .product(answer.value.traverse(_.words.compile.toList)),
            ),
        )
        .map((responses, words) =>
          assertEquals(
            (responses.last.text, responses.size, words),
            (page, 1, Some(List(Word("a"), Word("b")))),
          ),
        ),
    )

  /** Claude Haiku 4.5 over a backend that answers every request with the body. */
  private def haiku(body: String): PromptService[IO] = AnthropicModel
    .ClaudeHaiku4_5
    .prompt(
      Anthropic.api(
        ApiConfig(key = Some("key")),
        BackendStub(CatsMonadAsyncError[IO]())
          .whenAnyRequest
          .thenRespond(ResponseStub.adjust(Fixtures.streamed(Anthropic, body))),
      ),
      PromptConfig(maxTokens = 512),
    )

  test("a backend that cannot stream gives a streamed type's answer at once"):
    // Its events are read all at once, as the body is.
    val whole = haiku("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\"title\":\"Hi\","}}
        |
        |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"\"words\":[{\"text\":\"a\"},{\"text\":\"b\"}]}"}}
        |
        |data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
        |
        |""".stripMargin)
    run(
      whole
        .query
        .send[Page](Conversation.userSays("Translate."))
        .flatMap(_.value.traverse(_.words.compile.toList))
        .map(words => assertEquals(words, Some(List(Word("a"), Word("b"))))),
    )

  test("a value is given early only while one of its streams is open"):
    run(
      for
        // Misnamed, so no stream of it opens: read whole, and asked again.
        (llm, _) <- streamer(
          written("""{"First":[1],"Second":[2]}"""),
          written("""{"first":[1],"second":[2]}"""),
        )
        read <- llm
          .query
          .send[Two](Conversation.userSays("Count."))
          .flatMap(two =>
            two
              .value
              .traverse(two => (two.first ++ two.second).compile.toList)
              .product(two.attempts.map(_.size)),
          )
        // A case whole before another begins waits, and reads as a whole reply.
        (unions, _) <- streamer(
          written("""{"Refused":{"why":"x"},"Listed":{"items":[1]}}"""),
        )
        listed <- unions
          .query
          .send[Listing](Conversation.userSays("List."))
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
    val look = MessagePart.ToolCall("call_1", "look", "{}")
    run(
      List(
        // A character at a time, so that the stream is seen open.
        (
          part(page, 1) ++ Stream(
            called(look),
            ended(StopReason.ToolCall, Usage()),
          ),
          "once its answer was given",
        ),
        (
          part(page.dropRight(1), 1) ++ Stream(
            called(look),
            ended(StopReason.ToolCall, Usage()),
          ),
          "partway through its answer",
        ),
        (written(page, 4, StopReason.MaxTokens), "token limit"),
        (written(page, 4, StopReason.Other("refusal")), "refusal"),
      ).traverse_((reply, why) =>
        streamer(reply)
          .flatMap((llm, _) =>
            llm
              .query
              .send[Page](Conversation.userSays("Translate."))
              .flatMap(answer =>
                (
                  answer.value.traverse(_.words.compile.toList),
                  answer.attempts.attempt,
                ).tupled,
              ),
          )
          .map((words, reply) =>
            assertEquals(words, Some(List(Word("a"))), why)
            reply match
              case Left(QueryError.Unreadable(detail, _)) =>
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
        (llm, _) <- streamer(reply)
        asking   <-
          llm.query.send[Page](Conversation.userSays("Translate.")).start
        _         <- begun.get
        _         <- asking.cancel
        cancelled <- ended.get
      yield assert(cancelled),
    )

  test("a cancelled answer stops reading, failing what it had still to give"):
    run(
      for
        ended <- Deferred[IO, Boolean]
        reply =
          (part(
            """{"title":"Hi","words":[{"text":"a"},""",
            4,
          ) ++ Stream.never[IO]).onFinalizeCase(exit =>
            ended.complete(exit == Resource.ExitCase.Canceled).void,
          )
        (llm, _) <- streamer(reply)
        answer   <- llm.query.send[Page](Conversation.userSays("Translate."))
        _        <- answer.cancel
        words    <- answer.value.get.words.attempt.compile.toList
        whole    <- answer.attempts.attempt
        stopped  <- ended.get
      yield
        assert(stopped)
        List(words.last, whole).foreach:
          case Left(error: IllegalStateException) => assert(
              error.getMessage.contains("cancelled"),
              error,
            )
          case other => fail(s"Not cancelled: $other"),
    )

  test("a type holding no stream is asked of a service with one request"):
    val anthropic = haiku(
      """{"content":[{"type":"text","text":"{\"name\":\"Ada\",\"age\":36}"}],
        "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""",
    )
    run(
      anthropic
        .query
        .send[Person](Conversation.userSays("Invent a person."))
        .flatMap(answer => answer.usage.map(answer.value -> _.outputTokens))
        .map(read => assertEquals(read, (Some(Person("Ada", 36, None)), 2))),
    )

  /** A tool looking a word up, which says each word it was asked about. */
  private def dictionary(asked: Ref[IO, List[String]]): RunnableTool[IO] =
    RunnableTool.of[IO, Lookup]("look", "Looks up a word.")(lookup =>
      asked.update(_ :+ lookup.word).as(s"${ lookup.word }, as looked up"),
    )

  private def emit(text: String, id: String = ""): PromptEvent =
    called(MessagePart.ToolCall(
      if id.isEmpty then s"emit-$text" else id,
      "emit",
      Json.obj("element" -> Json.obj("text" -> Json.fromString(text))).noSpaces,
    ))

  /** An emitted element that cannot be read, its text a number. */
  private def garbled(id: String): PromptEvent = called(MessagePart.ToolCall(
    id,
    "emit",
    Json.obj("element" -> Json.obj("text" -> Json.fromInt(5))).noSpaces,
  ))

  /** A call saying every element has been emitted. */
  private val done: PromptEvent =
    called(MessagePart.ToolCall("done-1", "done", "{}"))

  private def look(word: String, id: String = ""): PromptEvent =
    called(MessagePart.ToolCall(
      if id.isEmpty then s"look-$word" else id,
      "look",
      Json.obj("word" -> Json.fromString(word)).noSpaces,
    ))

  /**
    * A prompt service streaming a list as JSON, keeping the tools it was
    * offered.
    */
  private def offering(offered: Ref[IO, List[List[Tool]]]): PromptService[IO] =
    request =>
      Stream.exec(offered.update(_ :+ request.tools)) ++
        written("[]").through(gathered)

  /** Asks for a stream of words given through emit, the dictionary run. */
  private def emitted
    (llm: PromptService[IO], attempts: Int = 3)
    : IO[AgentResponse[IO, Stream[IO, Word]]] = Ref
    .of[IO, List[String]](Nil)
    .flatMap(asked =>
      llm
        .query
        .agent(dictionary(asked))
        .send[Stream[IO, Word]](QueryRequest(
          PromptRequest(Conversation.userSays("Translate.")),
          Some(attempts),
        )),
    )

  private val stop     = Stream(ended(StopReason.Completed))
  private val toolStop = Stream(ended(StopReason.ToolCall))

  test("a stream given through emit runs the tools asked for between elements"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        gate        <- Deferred[IO, Unit]
        (llm, sent) <- streamer(
          Stream(emit("a"), emit("b"), look("c")) ++ toolStop,
          // Not written until the first element has been read.
          Stream.exec(gate.get) ++ Stream(emit("c")) ++ toolStop,
          stop,
        )
        read <- llm
          .query
          .agent(dictionary(asked))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(answer =>
            answer
              .value
              .get
              .evalTap(_ => gate.complete(()))
              .compile
              .toList
              .product(answer.responses.map(_.size)),
          )
        looked        <- asked.get
        conversations <- sent
      yield
        assertEquals(
          read,
          (List(Word("a"), Word("b"), Word("c")), 3),
        )
        assertEquals(looked, List("c"))
        assertEquals(
          conversations(1)
            .messages
            .last
            .parts
            .collect { case result: MessagePart.ToolResult => result }
            .map(_.output),
          List("ok", "ok", "c, as looked up"),
        ),
    )

  test("an emitted element that cannot be read is told, and emitted again"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        (llm, sent) <- streamer(
          Stream(called(MessagePart.ToolCall(
            "emit-1",
            "emit",
            Json.obj("element" -> Json.obj("text" -> Json.fromInt(5))).noSpaces,
          ))) ++ toolStop,
          Stream(emit("a")) ++ toolStop,
          stop,
        )
        words <- llm
          .query
          .agent(dictionary(asked))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(_.value.get.compile.toList)
        conversations <- sent
      yield
        assertEquals(words, List(Word("a")))
        assert(
          conversations(1)
            .messages
            .last
            .parts
            .collect { case result: MessagePart.ToolResult => result }
            .head
            .output
            .contains("could not be read"),
          conversations(1),
        ),
    )

  test("a model writing its stream as JSON, not through emit, is read whole"):
    run(
      for
        asked    <- Ref.of[IO, List[String]](Nil)
        (llm, _) <- streamer(written("""[{"text":"a"},{"text":"b"}]"""))
        words    <- llm
          .query
          .agent(dictionary(asked))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(_.value.get.compile.toList)
      yield assertEquals(words, List(Word("a"), Word("b"))),
    )

  test("tools given are run for a type holding no stream before its answer"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        (llm, sent) <- streamer(
          Stream(look("Ada")) ++ toolStop,
          written("""{"name":"Ada","age":36}"""),
        )
        person <- llm
          .query
          .agent(dictionary(asked))
          .send[Person](Conversation.userSays("Invent a person."))
          .flatMap(answer => answer.responses.map(answer.value -> _.size))
        looked        <- asked.get
        conversations <- sent
      yield
        assertEquals(
          person,
          (Some(Person("Ada", 36, None)), 2),
        )
        assertEquals(looked, List("Ada"))
        assertEquals(
          conversations(1)
            .messages
            .last
            .parts
            .collect { case result: MessagePart.ToolResult => result }
            .map(_.output),
          List("Ada, as looked up"),
        ),
    )

  test(
    "a tool named emit or done is refused beside a stream given through them",
  ):
    run(List("emit", "done").traverse_(name =>
      Ref
        .of[IO, List[String]](Nil)
        .flatMap(asked =>
          streamer().flatMap((llm, _) =>
            llm
              .query
              .agent(
                dictionary(asked),
                RunnableTool[IO](
                  Tool(name, "Clashes.", Json.obj()),
                  _ => IO.pure("ok"),
                ),
              )
              .send[Stream[IO, Word]](Conversation.userSays("Translate."))
              .attempt,
          ),
        )
        .map:
          case Left(error: IllegalArgumentException) => assert(
              error.getMessage.contains(s"named `$name`"),
              error,
            )
          case other => fail(s"Not refused: $other"),
    ))

  test(
    "a tool for the host to run is refused beside a stream given through emit",
  ):
    run(
      Ref
        .of[IO, List[String]](Nil)
        .flatMap(asked =>
          streamer().flatMap((llm, _) =>
            llm
              .query
              .agent(dictionary(asked))
              .send[Stream[IO, Word]](PromptRequest(
                Conversation.userSays("Translate."),
                tools = List(Tool(
                  "weather",
                  "Says the weather.",
                  Json.obj(),
                )),
              ))
              .attempt,
          ),
        )
        .map:
          case Left(error: IllegalArgumentException) => assert(
              error.getMessage.contains("host"),
              error,
            )
          case other => fail(s"Not refused: $other"),
    )

  test("emit is offered beside the tools run, taking an element of the stream"):
    run(
      for
        offered <- Ref.of[IO, List[List[Tool]]](Nil)
        words <- emitted(offering(offered)).flatMap(_.value.get.compile.toList)
        tools <- offered.get
      yield
        assertEquals(words, Nil)
        assertEquals(
          tools.head.map(_.name),
          List("look", "emit", "done"),
        )
        assertEquals(
          tools
            .head(1)
            .parameters
            .hcursor
            .downField("properties")
            .downField("element")
            .focus,
          Some(Schema[Word].json),
        ),
    )

  test("an element refused is emitted again before those after it are read"):
    run(
      for
        (llm, sent) <- streamer(
          Stream(emit("a"), garbled("emit-b"), emit("c")) ++ toolStop,
          Stream(emit("b"), emit("c")) ++ toolStop,
          stop,
        )
        words         <- emitted(llm).flatMap(_.value.get.compile.toList)
        conversations <- sent
      yield
        assertEquals(
          words,
          List(Word("a"), Word("b"), Word("c")),
        )
        conversations(1)
          .messages
          .last
          .parts
          .collect { case result: MessagePart.ToolResult => result }
          .map(_.output) match
            case List(taken, refused, skipped) =>
              assertEquals(taken, "ok")
              assert(
                refused.contains("could not be read"),
                refused,
              )
              assert(
                skipped.contains("emit again from that one"),
                skipped,
              )
            case other => fail(s"Not told of each: $other"),
    )

  /**
    * Where each conversation sent holds a breakpoint: its message, and its
    * part.
    */
  private def breakpoints
    (conversations: List[Conversation])
    : List[List[(Int, Int)]] = conversations.map(conversation =>
    for
      (message, at) <- conversation.messages.zipWithIndex
      (part, index) <- message.parts.zipWithIndex
      if part == MessagePart.CacheBreakpoint
    yield at -> index,
  )

  test("each request after a round of tools closes with a breakpoint alone"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        (llm, sent) <- streamer(
          Stream(emit("a"), look("b")) ++ toolStop,
          Stream(emit("b")) ++ toolStop,
          stop,
        )
        words           <- emitted(llm).flatMap(_.value.get.compile.toList)
        (whole, wholly) <- streamer(
          Stream(look("Ada")) ++ toolStop,
          written("""{"name":"Ada","age":36}"""),
        )
        person <- whole
          .query
          .agent(dictionary(asked))
          .send[Person](Conversation.userSays("Invent a person."))
          .map(_.value)
        streamed <- sent
        read     <- wholly
      yield
        assertEquals(
          (words, person),
          (List(Word("a"), Word("b")), Some(Person("Ada", 36, None))),
        )
        // After the results of each round: the first has two, the next one.
        assertEquals(
          breakpoints(streamed),
          List(Nil, List(2 -> 2), List(4 -> 1)),
        )
        assertEquals(
          breakpoints(read),
          List(Nil, List(2 -> 1)),
        ),
    )

  test("calls sharing an id are each answered, in order"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        (llm, sent) <- streamer(
          Stream(
            emit("a", "emit"),
            emit("b", "emit"),
            look("c", "look"),
            look("d", "look"),
          ) ++ toolStop,
          stop,
        )
        words <- llm
          .query
          .agent(dictionary(asked))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(_.value.get.compile.toList)
        looked        <- asked.get
        conversations <- sent
      yield
        assertEquals(words, List(Word("a"), Word("b")))
        assertEquals(looked, List("c", "d"))
        assertEquals(
          conversations(1)
            .messages
            .last
            .parts
            .collect { case result: MessagePart.ToolResult => result }
            .map(_.output),
          List(
            "ok",
            "ok",
            "c, as looked up",
            "d, as looked up",
          ),
        ),
    )

  test("a reply calling done ends the stream, with no request after it"):
    run(
      for
        (llm, sent) <- streamer(Stream(emit("a"), emit("b"), done) ++ toolStop)
        read        <- emitted(llm).flatMap(answer =>
          answer.value.get.compile.toList.product(answer.responses),
        )
        conversations <- sent
      yield
        val (words, responses) = read
        assertEquals(words, List(Word("a"), Word("b")))
        assertEquals(conversations.size, 1)
        assertEquals(
          responses.map(response =>
            (response.stopReason, response.toolCalls.map(_.tool)),
          ),
          List((StopReason.ToolCall, List("emit", "emit", "done"))),
        ),
    )

  test("done alone, with no element emitted, ends the stream empty"):
    run(
      for
        (llm, sent)   <- streamer(Stream(done) ++ toolStop)
        words         <- emitted(llm).flatMap(_.value.get.compile.toList)
        conversations <- sent
      yield
        assertEquals(words, Nil)
        assertEquals(conversations.size, 1),
    )

  test("done after elements written as JSON, none emitted, ends with those"):
    run(
      for
        (llm, sent) <- streamer(
          Stream(
            wrote("""[{"text":"a"},{"text":"b"}]"""),
            done,
          ) ++ toolStop,
        )
        words         <- emitted(llm).flatMap(_.value.get.compile.toList)
        conversations <- sent
      yield
        assertEquals(words, List(Word("a"), Word("b")))
        assertEquals(conversations.size, 1),
    )

  test("done beside a tool to run is not taken, and the stream goes on"):
    run(
      for
        asked       <- Ref.of[IO, List[String]](Nil)
        (llm, sent) <- streamer(
          Stream(emit("a"), look("b"), done) ++ toolStop,
          Stream(emit("b"), done) ++ toolStop,
        )
        words <- llm
          .query
          .agent(dictionary(asked))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(_.value.get.compile.toList)
        looked        <- asked.get
        conversations <- sent
      yield
        assertEquals(words, List(Word("a"), Word("b")))
        assertEquals(looked, List("b"))
        assertEquals(conversations.size, 2)
        conversations(1)
          .messages
          .last
          .parts
          .collect { case result: MessagePart.ToolResult => result }
          .map(_.output) match
            case List(taken, found, untaken) =>
              assertEquals(taken, "ok")
              assertEquals(found, "b, as looked up")
              assert(
                untaken.contains("call `done` again"),
                untaken,
              )
            case other => fail(s"Not told of each: $other"),
    )

  test("done beside an element refused is not taken, before it or after"):
    run(
      List(
        Stream(emit("a"), garbled("emit-b"), done) ->
          "emit again from that one",
        Stream(emit("a"), done, garbled("emit-b")) -> "call `done` again",
      ).traverse_((first, untaken) =>
        for
          (llm, sent) <- streamer(
            first ++ toolStop,
            Stream(emit("b"), done) ++ toolStop,
          )
          words         <- emitted(llm).flatMap(_.value.get.compile.toList)
          conversations <- sent
        yield
          assertEquals(words, List(Word("a"), Word("b")))
          assertEquals(conversations.size, 2)
          val told = conversations(1)
            .messages
            .last
            .parts
            .collect { case result: MessagePart.ToolResult => result }
          assert(
            told.find(_.tool == "done").exists(_.output.contains(untaken)),
            told,
          ),
      ),
    )

  test("an element emitted wrong as often as attempts allow fails the stream"):
    run(
      for
        (llm, _) <- streamer(
          Stream(garbled("emit-1")) ++ toolStop,
          // Read, so that the next element has its own attempts.
          Stream(emit("a")) ++ toolStop,
          Stream(garbled("emit-2")) ++ toolStop,
          Stream(garbled("emit-3")) ++ toolStop,
        )
        words <-
          emitted(llm, attempts = 2).flatMap(_.value.get.attempt.compile.toList)
      yield words match
        case List(
            Right(word),
            Left(error: QueryError.Unreadable),
          ) =>
          assertEquals(word, Word("a"))
          assertEquals(error.responses.size, 4)
          assert(
            error.detail.contains("2 times in a row"),
            error.detail,
          )
        case other => fail(s"Not failed: $other"),
    )

  test(
    "a stream given through emit fails still calling tools in its last round",
  ):
    run(
      for
        asked    <- Ref.of[IO, List[String]](Nil)
        (llm, _) <- streamer(
          Stream(emit("a"), look("b")) ++ toolStop,
          Stream(emit("b"), look("c")) ++ toolStop,
          stop,
        )
        words <- llm
          .query
          .agent(AgentConfig(
            List(dictionary(asked)),
            maxIterations = Some(2),
          ))
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(_.value.get.attempt.compile.toList)
        looked <- asked.get
      yield
        assertEquals(looked, List("b"))
        words match
          case List(
              Right(first),
              Right(second),
              Left(error: QueryError.Unreadable),
            ) =>
            assertEquals(
              List(first, second),
              List(Word("a"), Word("b")),
            )
            assertEquals(error.responses.size, 2)
            assert(
              error.detail.contains("after 2 rounds"),
              error.detail,
            )
          case other => fail(s"Not failed: $other"),
    )

  test("a reply cut off after its elements were read fails the stream"):
    run(
      (
        List(
          StopReason.MaxTokens,
          StopReason.Other("refusal"),
        ),
        List(
          Stream(emit("a")),
          Stream(emit("a"), done),
        ),
      ).tupled
        .traverse_((stopped, calls) =>
          for
            (llm, _) <- streamer(calls ++ Stream(ended(stopped, Usage())))
            read     <- emitted(llm).flatMap(answer =>
              answer
                .value
                .get
                .attempt
                .compile
                .toList
                .product(answer.responses.attempt),
            )
          yield read match
            case (
                List(
                  Right(word),
                  Left(_: QueryError.Unreadable),
                ),
                Left(_: QueryError.Unreadable),
              ) => assertEquals(word, Word("a"))
            case other => fail(s"Not failed on $stopped: $other"),
        ),
    )

  test("a tool failing as it runs fails the stream and reply, untold"):
    val broken = RunnableTool[IO](
      Tool("look", "Looks up a word.", Json.obj()),
      _ => IO.raiseError(IllegalStateException("Broken.")),
    )
    run(
      for
        (llm, sent) <- streamer(Stream(emit("a"), look("b")) ++ toolStop)
        read        <- llm
          .query
          .agent(broken)
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
          .flatMap(answer =>
            answer
              .value
              .get
              .attempt
              .compile
              .toList
              .product(answer.responses.attempt),
          )
        conversations <- sent
      yield
        read match
          case (
              List(
                Right(word),
                Left(error: IllegalStateException),
              ),
              Left(same: IllegalStateException),
            ) =>
            assertEquals(word, Word("a"))
            assertEquals(error.getMessage, "Broken.")
            assertEquals(same.getMessage, "Broken.")
          case other => fail(s"Not failed: $other")
        assertEquals(conversations.size, 1),
    )

  test("a cancelled answer stops a tool running between its elements"):
    run(
      for
        started <- Deferred[IO, Unit]
        stopped <- Deferred[IO, Unit]
        waiting = RunnableTool[IO](
          Tool("look", "Looks up a word.", Json.obj()),
          _ =>
            (started.complete(()) *> IO.never[String]).onCancel(
              stopped.complete(()).void,
            ),
        )
        (llm, _) <- streamer(Stream(emit("a"), look("b")) ++ toolStop)
        answer   <- llm
          .query
          .agent(waiting)
          .send[Stream[IO, Word]](Conversation.userSays("Translate."))
        _     <- started.get
        _     <- answer.cancel
        _     <- stopped.get
        words <- answer.value.get.attempt.compile.toList
      yield words match
        case List(
            Right(word),
            Left(error: IllegalStateException),
          ) =>
          assertEquals(word, Word("a"))
          assert(
            error.getMessage.contains("cancelled"),
            error,
          )
        case other => fail(s"Not cancelled: $other"),
    )

  test("a reply giving no elements through emit, nor as JSON, is asked again"):
    run(
      for
        (llm, sent) <- streamer(
          written("Here are the words."),
          written("""[{"text":"a"}]"""),
        )
        words         <- emitted(llm).flatMap(_.value.get.compile.toList)
        conversations <- sent
      yield
        assertEquals(words, List(Word("a")))
        assert(
          conversations(1).messages.last.text.contains("could not be read"),
          conversations(1),
        ),
    )

  test("a reply saying nothing, through emit or else, fails the stream"):
    run(
      for
        (llm, _) <- streamer(written(""))
        read     <- emitted(llm).flatMap(answer =>
          answer
            .value
            .get
            .compile
            .toList
            .attempt
            .product(answer.responses.attempt),
        )
      yield read match
        case (
            Left(error: QueryError.Unreadable),
            Left(_: QueryError.Unreadable),
          ) => assert(
            error.detail.contains("said nothing"),
            error.detail,
          )
        case other => fail(s"Not failed: $other"),
    )

  test("a stream given through emit is summed over every reply asked for"):
    run(
      for
        (llm, _) <- streamer(
          Stream(
            emit("a"),
            ended(
              StopReason.ToolCall,
              Usage(uncachedTokens = 3),
            ),
          ),
          Stream(ended(
            StopReason.Completed,
            Usage(uncachedTokens = 7, visibleTokens = 2),
          )),
        )
        answer    <- emitted(llm)
        usage     <- answer.usage
        responses <- answer.responses
      yield assertEquals(
        (usage, responses.size),
        (Usage(uncachedTokens = 10, visibleTokens = 2), 2),
      ),
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

  test("a request is what the user says, a conversation, or a lower layer's"):
    val person       = """{"name":"Ada","age":36}"""
    val conversation = Conversation.userSays("Invent a person.")
    val prompt       = PromptRequest(conversation)
    val query        = QueryRequest[Person](prompt)
    run(
      for
        (llm, sent) <- streamer(List.fill(12)(written(person, 8))*)
        prompted    <- List(
          llm.send("Invent a person."),
          llm.send(conversation),
          llm.send(prompt),
        ).sequence
        queried <- List(
          llm.query.send[Person]("Invent a person."),
          llm.query.send[Person](conversation),
          llm.query.send[Person](prompt),
          llm.query.send[Person](query),
        ).traverse(_.map(_.value))
        agent = llm.query.agent()
        acted <- List(
          agent.send[Person]("Invent a person."),
          agent.send[Person](conversation),
          agent.send[Person](prompt),
          agent.send[Person](query),
          agent.send[Person](AgentRequest[IO, Person](query)),
        ).traverse(_.map(_.value))
        conversations <- sent
      yield
        assertEquals(
          prompted.map(_.text),
          List.fill(3)(person),
        )
        assertEquals(
          queried ++ acted,
          List.fill(9)(Some(Person("Ada", 36, None))),
        )
        // Each was sent what the user says, closed by the schema for a value.
        assertEquals(conversations.size, 12)
        assert(
          conversations.forall(
            _.messages
              .map(_.text)
              .headOption
              .exists(_.startsWith("Invent a person.")),
          ),
          conversations,
        ),
    )

  test("an agent's iterations are its rounds, each closed by the tools it ran"):
    run(
      for
        asked    <- Ref.of[IO, List[String]](Nil)
        (llm, _) <- streamer(
          Stream(look("Ada")) ++ toolStop,
          // Unreadable, so asked again within the round.
          written("Ada, 36."),
          written("""{"name":"Ada","age":36}"""),
        )
        answer <- llm
          .query
          .agent(dictionary(asked))
          .send[Person](Conversation.userSays("Invent a person."))
        rounds <- answer.iterations
        sizes  <- rounds.traverse(_.attempts.map(_.size))
        all    <- answer.responses
      yield
        assertEquals(
          answer.value,
          Some(Person("Ada", 36, None)),
        )
        assertEquals(sizes, List(1, 2))
        assertEquals(
          rounds.map(_.value),
          List(None, Some(Person("Ada", 36, None))),
        )
        assertEquals(
          all.map(_.stopReason),
          List(
            StopReason.ToolCall,
            StopReason.Completed,
            StopReason.Completed,
          ),
        ),
    )
