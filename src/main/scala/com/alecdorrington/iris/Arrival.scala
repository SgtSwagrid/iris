package com.alecdorrington.iris

/**
  * How a value of a type is read while its JSON is still arriving, as
  * [[LlmStreamer.ask]] reads it: once whole, as a stream, or from its members.
  */
private[iris] enum Arrival:

  /** Only once its JSON is whole. */
  case Whole

  /**
    * As a stream, handed over as soon as its array begins, each element then
    * read as soon as it can be.
    *
    * @param element
    *   The schema of each element.
    */
  case Streamed(element: Schema[?])

  /**
    * From its members, as soon as enough of them have settled: each is whole,
    * or is a stream begun, or a value of its own read before it is whole, and
    * any left are streams still to come, handed over before they begin.
    *
    * @param member
    *   The schema of a member, by its key in an object or index in an array,
    *   where it has one.
    *
    * @param ready
    *   The members still to come, where the members settled, given those begun,
    *   are enough to make up a value.
    */
  case Assembled
    (
      member: (String | Int) => Option[Schema[?]],
      ready: (Set[String | Int], Set[String | Int]) => Option[
        List[String | Int],
      ],
    )

  /** Whether it is read as a stream. */
  def streamed: Boolean = this match
    case Streamed(_) => true
    case _           => false
