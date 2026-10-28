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
    .filterNot(_ == endMarker)

  def events[F[_]](body: Stream[F, Byte]): Stream[F, Json] = body
    .through(text.utf8.decode)
    .through(text.lines)
    .map(data)
    .unNone
    .map(parse)
    .collect:
      case Right(json) => json

  /**
    * Reads each event alone into deltas, a list as one event may carry both
    * text and the end.
    */
  def separately[F[_]]
    (read: Json => List[Delta])
    (events: Stream[F, Json])
    : Stream[F, Delta] = events.map(read).flatMap(Stream.emits)
