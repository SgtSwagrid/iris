package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.{access, config, model}
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.scalajs.js

/**
  * Streamed replies whose requests are collected while they are read, as a busy
  * process may collect them: Node's `fetch` has a request follow its signal
  * only weakly, so a client that does not hold its requests can no longer abort
  * them, and leaves their connections open. Node runs the tests with
  * `--expose-gc`, so that a test may collect.
  */
class CollectionSuite extends FunSuite:

  /** The first part of a reply, opening an array, after which nothing comes. */
  private val begun = "data: {\"type\":\"content_block_delta\"," +
    "\"delta\":{\"type\":\"text_delta\",\"text\":\"[\\\"a\\\",\"}}\n\n"

  private val gc = js.Dynamic.global.gc

  /** Collects all that nothing holds, a few times over, a task apart. */
  private val collected: IO[Unit] = (IO(gc()) >> IO.sleep(5.millis))
    .replicateA_(3)

  test("a stream dropped once its request is collected closes its connection"):
    assume(
      !js.isUndefined(gc),
      "Node was not run with --expose-gc.",
    )
    Server(Some(begun))
      .flatMap(served =>
        model
          .prompt[IO](
            access.copy(baseUrl = Some(served.address)),
            config,
          )
          .map(served -> _),
      )
      .use((served, prompt) =>
        prompt
          .stream(Conversation.userSays("Count."))
          .evalTap(_ => collected)
          .take(1)
          .compile
          .drain *>
          IO.fromFutureCancelable(IO((served.closed, IO.unit)))
            .timeoutTo(
              2.seconds,
              IO(fail("The client left its connection open.")),
            ),
      )
      .timeout(20.seconds)
      .unsafeToFuture(): Future[Unit]
