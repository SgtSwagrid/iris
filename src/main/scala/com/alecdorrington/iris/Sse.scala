package com.alecdorrington.iris

import fs2.{text, Stream}
import io.circe.Json
import io.circe.parser.parse

/**
  * Reading of a [Server-Sent
  * Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
  * body.
  */
private[iris] object Sse:

  private val endMarker = "[DONE]"

  /** The payload of a `data:` line; other lines are ignored. */
  def data(line: String): Option[String] = Option
    .when(line.startsWith("data:"))(line.drop("data:".length).trim)
    .filter(_.nonEmpty)

  /**
    * The events of a body, ending at OpenAI's `[DONE]` whether or not the body
    * does, as a reply whose end waits for the stream's would otherwise wait for
    * a connection held open after it.
    */
  def events[F[_]](body: Stream[F, Byte]): Stream[F, Json] = body
    .through(text.utf8.decode)
    .through(text.lines)
    .map(data)
    .unNone
    .takeWhile(_ != endMarker)
    .map(parse)
    .collect:
      case Right(json) => json
