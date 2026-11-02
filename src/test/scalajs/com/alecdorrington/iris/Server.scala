package com.alecdorrington.iris

import cats.effect.{IO, Resource}
import scala.concurrent.Promise
import scala.scalajs.js

/**
  * A local server over Node's `http`, which sees a client close its connection
  * as soon as it does, though nothing is being written.
  */
object Server:

  private val http = js.Dynamic.global.process.getBuiltinModule("node:http")

  /**
    * Serves one request, answering it with `reply` and then nothing more, or
    * not at all.
    */
  def apply(reply: Option[String]): Resource[IO, Served] = Resource
    .eval(IO((Promise[Unit](), Promise[Unit]())))
    .flatMap((received, closed) =>
      Resource
        .make(IO.async_[js.Dynamic](listening =>
          val server = http.createServer(
            (request: js.Dynamic, response: js.Dynamic) =>
              response.on("close", () => closed.trySuccess(()))
              request.on(
                "end",
                () =>
                  received.trySuccess(())
                  reply.foreach(answer(response, _)),
              )
              request.resume(),
          )
          server.listen(0, "127.0.0.1", () => listening(Right(server))),
        ))(server =>
          IO:
            server.closeAllConnections()
            server.close()
            (),
        )
        .map(server =>
          Served(
            s"http://127.0.0.1:${ server.address().port }",
            received.future,
            closed.future,
          ),
        ),
    )

  private def answer(response: js.Dynamic, text: String): Unit =
    response.writeHead(200, js.Dictionary("content-type" -> "text/event-stream"))
    response.write(text)
    ()
