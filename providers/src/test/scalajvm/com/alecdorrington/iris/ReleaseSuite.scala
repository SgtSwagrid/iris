package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.api.*
import munit.FunSuite
import scala.concurrent.duration.*
import sttp.client4.*
import sttp.model.Uri

/** The JDK's client, which a client of Iris's own shuts down with itself. */
class ReleaseSuite extends FunSuite:

  test("releasing a client closes the connections of its requests in flight"):
    Server(None)
      .use(served =>
        Platform
          .backends[IO]
          .use(backends =>
            // Cut off by the release, its failure is expected, so not reported.
            basicRequest
              .get(Uri.unsafeParse(served.address))
              .send(backends.sending)
              .attempt
              .start *> IO.fromFutureCancelable(IO((served.received, IO.unit))),
          ) *>
          IO.fromFutureCancelable(IO((served.closed, IO.unit)))
            .timeoutTo(
              5.seconds,
              IO(fail("The client left its connection open.")),
            ),
      )
      .timeout(20.seconds)
      .unsafeToFuture()
