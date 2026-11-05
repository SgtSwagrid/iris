package com.alecdorrington.iris
package query

import cats.syntax.all.*
import com.alecdorrington.iris.query.PartialParser.{
  Building, Event, Expect, Frame, State,
}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*

/**
  * A reply's JSON as far as it has arrived, read into its answer as soon as
  * everything in it but its streams is, and then into each stream as each of
  * its elements is. Only brackets, strings and their escapes are followed here;
  * circe parses each value once it is whole, and its schema's own decoder reads
  * it, a stream begun within it stood in for by a placeholder. Each part read
  * gives a parser of its own, holding all of the reply so far.
  *
  * @param schema
  *   The schema of the answer.
  *
  * @param live
  *   The stream to hand over for each stream opened, by its number, counting
  *   from `1`.
  *
  * @param text
  *   The reply so far.
  */
private[iris] final case class PartialParser private (
  schema: Schema[?],
  live: Int => Any,
  text: String,
  frames: List[Frame],
  state: State,
  string: Int,
  escaped: Boolean,
  bare: Int,
  streams: Int,
  answered: Boolean,
  events: Vector[Event],
):

  /** Whether the answer's JSON has ended. */
  def whole: Boolean = state == State.Done

  /**
    * Reads more of the reply, giving the parser that has read it, and what can
    * now be read of the answer.
    */
  def feed(chunk: String): (PartialParser, List[Event]) =
    val from    = text.length
    val stepped = chunk
      .indices
      .foldLeft(copy(text = text + chunk, events = Vector.empty))(
        (parser, offset) => parser.step(from + offset, chunk.charAt(offset)),
      )
    val settled =
      if stepped.state == State.Within then stepped.settle else stepped
    (settled.copy(events = Vector.empty), settled.events.toList)

  /** Follows one character, skipping a code fence the answer opens with. */
  private def step(index: Int, char: Char): PartialParser = state match
    case State.Before if char == '`'        => copy(state = State.Fenced)
    case State.Before if !char.isWhitespace =>
      copy(state = State.Within).began(index, char)
    case State.Fenced if char == '\n' => copy(state = State.Before)
    case State.Within                 => within(index, char)
    case _                            => this

  private def within(index: Int, char: Char): PartialParser =
    if string >= 0 then quoted(index, char)
    else if bare < 0 then structure(index, char)
    else if delimits(char) then
      val after = copy(bare = -1).ended(bare, index)
      if after.state == State.Within then after.structure(index, char)
      else after
    else this

  private def structure(index: Int, char: Char): PartialParser = char match
    case _ if char.isWhitespace => this
    case ','                    => due(Expect.Next, Expect.Member)
    case ':'                    => due(Expect.Colon, Expect.Value)
    case '}' | ']'              => closed(index, char)
    case _                      => began(index, char)

  /** Moves the innermost container on, where it is where it must be. */
  private def due(at: Expect, next: Expect): PartialParser =
    if frames.head.expect == at then inner(_.copy(expect = next)) else lost

  /** A value, or a key where one is due, begins. */
  private def began(index: Int, char: Char): PartialParser =
    val frame = frames.head
    frame.expect match
      case Expect.Member if !frame.array =>
        if char == '"' then copy(string = index) else lost
      case Expect.Member | Expect.Value =>
        val reserved = frame.coming.get(frame.upcoming)
        if frame.array && !frame.admits(frame.upcoming) then
          failed("It wrote more of a list after the value holding it was read.")
        else if reserved.nonEmpty && char != '[' then
          failed(
            s"It wrote `${ frame.upcoming }` as something other than a list.",
          )
        else
          val begun  = frame.begin
          val opened = copy(frames = begun :: frames.tail)
          char match
            case '"'       => opened.copy(string = index)
            case '{' | '[' =>
              val array = char == '['
              opened.copy(frames =
                Frame(
                  begun.arrivalOf(array),
                  array,
                  index,
                  reserved,
                ) :: opened.frames,
              )
            case _ if char == '-' || char.isDigit || "tfn".contains(char) =>
              opened.copy(bare = index)
            case _ => lost
      case _ => lost

  private def quoted(index: Int, char: Char): PartialParser =
    if escaped then copy(escaped = false)
    else if char == '\\' then copy(escaped = true)
    else if char == '"' then
      val unquoted = copy(string = -1)
      if frames.head.expectsKey then unquoted.named(string, index + 1)
      else unquoted.ended(string, index + 1)
    else this

  private def named(start: Int, end: Int): PartialParser = parsed(start, end)
    .toOption
    .flatMap(_.asString)
    .fold(lost)(keyed)

  /**
    * A key is read, which must not name a member of a value read already, whose
    * reading took it to be left out, or read it already.
    */
  private def keyed(key: String): PartialParser =
    if frames.head.admits(key) then inner(_.named(key))
    else failed(s"It wrote `$key` after the value holding it was read.")

  /** A container ends, which a value read already may only once it has all. */
  private def closed(index: Int, char: Char): PartialParser = frames match
    case frame :: (rest @ _ :: _) if frame.closes(char == ']') =>
      frame.missing match
        case Some(member) =>
          failed(s"It left out `$member`, which was being waited for.")
        case None => copy(
            frames = rest,
            events = events ++ frame.live.map(Event.Ended(_)),
          ).ended(frame.start, index + 1)
    case _ => lost

  /**
    * A value within the innermost container is whole, from `start` to `end`,
    * and read if it is an element of a stream, and not handed over already. The
    * answer is read here only where it may be read before it is whole, as the
    * rest are read from the whole reply once it ends.
    */
  private def ended(start: Int, end: Int): PartialParser =
    val frame = frames.head
    val after = inner(_.ended(start, end))
    if frames.tail.isEmpty then
      val done = after.copy(state = State.Done)
      if !answered && schema.gradual then done.delivered(0, schema, start, end)
      else done
    else if frame.handed then after
    else
      frame
        .live
        .zip(frame.element)
        .fold(after)((stream, element) =>
          after.delivered(stream, element, start, end),
        )

  private def delivered
    (
      stream: Int,
      element: Schema[?],
      start: Int,
      end: Int,
    )
    : PartialParser = parsed(start, end)
    .flatMap(ValueReader.decoded(element, _))
    .fold(failed, handedOver(stream, _))

  private def handedOver(stream: Int, value: Any): PartialParser = copy(
    answered = answered || stream == 0,
    events = events :+ Event.Read(stream, value),
  )

  /**
    * Hands over each element of a stream being written that can be read
    * already, outermost first, as handing one over opens the streams within it.
    */
  private def settle: PartialParser = (0 until frames.size - 1).foldLeft(this):
    (parser, index) =>
      val frame = parser.path(index)
      (for
        stream  <- frame.live
        element <- frame.element
        if parser.state == State.Within && !frame.handed && element.gradual &&
        PartialParser.settles(parser.path, index + 1)
      yield parser.handOver(index, stream, element)).getOrElse(parser)

  /** The containers begun, outermost first. */
  private def path: Vector[Frame] = frames.reverse.toVector

  /**
    * Hands over the element being written of the stream at `index`, opening
    * each stream within it only if it can be read.
    */
  private def handOver
    (
      index: Int,
      stream: Int,
      element: Schema[?],
    )
    : PartialParser = built(Building(path, streams), index + 1)
    .flatMap((building, json) =>
      ValueReader.decoded(element, json, building.holes).map(building -> _),
    )
    .fold(
      failed,
      (building, value) =>
        copy(
          frames = building
            .path
            .updated(
              index,
              building.path(index).copy(handed = true),
            )
            .reverse
            .toList,
          streams = building.streams,
          events = events ++ building.opening,
        ).handedOver(stream, value),
    )

  /**
    * The JSON of a value settled so far: its whole members, and the one being
    * written where it has settled, each stream among them stood in for.
    */
  private def built
    (building: Building, index: Int)
    : Either[String, (Building, Json)] =
    val frame = building.path(index)
    frame.arrival match
      case Arrival.Streamed(element)   => opened(building, index, element)
      case Arrival.Assembled(_, ready) =>
        val current = PartialParser.open(building.path, index)
        val taken   = building.changed(index, _.copy(taken = true))
        for
          wholes <- frame
            .wholes
            .toList
            .traverse((key, start, end) => parsed(start, end).map(key -> _))
          (child, written) <-
            if current then
              built(taken, index + 1).map((child, json) =>
                child -> List(frame.key -> json),
              )
            else Right(taken -> Nil)
        yield
          val (reserved, holed) = ready(frame.settled(current), frame.begun)
            .getOrElse(Nil)
            .foldLeft(child -> Vector.empty[(String | Int, Json)]):
              case ((building, holed), member) =>
                val (next, hole) = building.reserved(index, member, live)
                next -> (holed :+ (member -> hole))
          reserved -> frame.json(wholes ++ written ++ holed)
      case Arrival.Whole => Left(PartialParser.unparsed)

  /**
    * Opens a stream begun within a value being handed over, with the elements
    * it has whole already, and stands it in for by a placeholder of its own.
    */
  private def opened
    (
      building: Building,
      index: Int,
      element: Schema[?],
    )
    : Either[String, (Building, Json)] =
    val frame                   = building.path(index)
    val (holed, (stream, hole)) = building.holed(live)
    frame
      .wholes
      .toList
      .traverse((_, start, end) =>
        parsed(start, end).flatMap(ValueReader.decoded(element, _)),
      )
      .map(values =>
        holed
          .changed(
            index,
            _.copy(live = Some(stream), wholes = Vector.empty),
          )
          .copy(opening = holed.opening ++ values.map(Event.Read(stream, _))) ->
          hole,
      )

  private def parsed(start: Int, end: Int): Either[String, Json] =
    parse(text.substring(start, end)).leftMap(_ => PartialParser.unparsed)

  /** Changes the innermost container. */
  private def inner(change: Frame => Frame): PartialParser =
    copy(frames = change(frames.head) :: frames.tail)

  /**
    * Stops reading, saying why where the answer was handed over already, and
    * else leaving the reply to be read whole once it ends.
    */
  private def failed(why: String): PartialParser = copy(
    events = if answered then events :+ Event.Failed(why) else events,
    state = State.Lost,
  )

  private def lost: PartialParser = failed(PartialParser.unparsed)

  private def delimits(char: Char): Boolean = char.isWhitespace ||
    char == ',' || char == ']' || char == '}' || char == ':'

private[iris] object PartialParser:

  /**
    * A parser of a reply not yet begun.
    *
    * @param schema
    *   The schema of the answer.
    *
    * @param live
    *   The stream to hand over for each stream opened, by its number.
    *
    * @return
    *   The parser.
    */
  def apply(schema: Schema[?], live: Int => Any): PartialParser = PartialParser(
    schema,
    live,
    "",
    List(Frame(
      Arrival.Streamed(schema),
      true,
      -1,
      Some(0),
    )),
    State.Before,
    -1,
    false,
    -1,
    0,
    false,
    Vector.empty,
  )

  private val unparsed = "It is not JSON."

  /** What can be read of an answer once more of its reply has arrived. */
  enum Event:

    /** A stream begins, before any value holding it is read. */
    case Opened(stream: Int)

    /** A value is read: an element of a stream, or the answer, from `0`. */
    case Read(stream: Int, value: Any)

    /** A stream ends. */
    case Ended(stream: Int)

    /** The answer, handed over already, cannot be read further, and why. */
    case Failed(detail: String)

  /**
    * A value of a schema whose JSON is a stream, read as the stream given, as a
    * placeholder of one is.
    */
  def standIn(schema: Schema[?], stream: Any): Either[String, Any] =
    val hole = Json.obj("stream" -> Json.fromInt(0))
    ValueReader.decoded(schema, hole, List(hole -> stream))

  /**
    * Whether a value being written can be read already: a stream, as soon as it
    * begins, or a value read from its members, once enough have settled.
    */
  private def settles(path: Vector[Frame], index: Int): Boolean =
    val frame = path(index)
    frame.arrival match
      case Arrival.Streamed(_)         => true
      case Arrival.Assembled(_, ready) => open(path, index) &&
        ready(frame.settled(true), frame.begun).isDefined
      case Arrival.Whole => false

  /**
    * Whether the member a value is writing is itself read already, though not
    * whole: a stream begun, or a value holding one. A value is read before it
    * is whole only while one is, as until a stream begins within it, a reply
    * that cannot be read can still be asked for again, and once the member is
    * whole the value soon will be.
    */
  private def open(path: Vector[Frame], index: Int): Boolean =
    path.isDefinedAt(index + 1) && settles(path, index + 1)

  private[iris] enum State:
    case Before, Fenced, Within, Done, Lost

  /**
    * What a container is due next: a member, a key's colon, the key's value, or
    * a comma or its end.
    */
  private[iris] enum Expect:
    case Member, Colon, Value, Next

  /**
    * A value being handed over as it is built: the containers, outermost first,
    * as building it changes them, the streams it opens and their placeholders,
    * and the streams opened in all.
    */
  private[iris] final case class Building
    (
      path: Vector[Frame],
      streams: Int,
      opening: Vector[Event] = Vector.empty,
      holes: Schema.Holes = Nil,
    ):

    def changed(index: Int, change: Frame => Frame): Building =
      copy(path = path.updated(index, change(path(index))))

    /** A new stream, opened, and the placeholder standing in for it. */
    def holed(live: Int => Any): (Building, (Int, Json)) =
      val stream = streams + 1
      val hole   = Json.obj("stream" -> stream.asJson)
      copy(
        streams = stream,
        opening = opening :+ Event.Opened(stream),
        holes = (hole -> live(stream)) :: holes,
      ) -> (stream -> hole)

    /**
      * Opens a stream of a value being handed over before it begins, kept for
      * the member it will be, and its placeholder.
      */
    def reserved
      (
        index: Int,
        member: String | Int,
        live: Int => Any,
      )
      : (Building, Json) =
      val (next, (stream, hole)) = holed(live)
      next.changed(
        index,
        frame => frame.copy(coming = frame.coming + (member -> stream)),
      ) -> hole

  /**
    * A container begun and not yet ended, and as much of it as reading it early
    * needs: its members whole and begun, kept while it may be read before it is
    * whole and is no stream handed over already.
    *
    * @param live
    *   The stream it feeds, where it is one handed over.
    *
    * @param handed
    *   Whether the element being written has been handed over already.
    *
    * @param taken
    *   Whether the value it holds has been handed over already.
    *
    * @param coming
    *   The streams handed over before they began, by member.
    *
    * @param started
    *   Whether the value of the member being written has begun.
    */
  private[iris] final case class Frame
    (
      arrival: Arrival,
      array: Boolean,
      start: Int,
      live: Option[Int] = None,
      handed: Boolean = false,
      taken: Boolean = false,
      coming: Map[String | Int, Int] = Map.empty,
      expect: Expect = Expect.Member,
      started: Boolean = false,
      key: String | Int = 0,
      begun: Set[String | Int] = Set.empty,
      wholes: Vector[(String | Int, Int, Int)] = Vector.empty,
      count: Int = 0,
    ):

    def element: Option[Schema[?]] = arrival match
      case Arrival.Streamed(element) => Some(element)
      case _                         => None

    /** How the member being written arrives, a stream only as an array. */
    def arrivalOf(array: Boolean): Arrival =
      member(key).fold(Arrival.Whole)(_.arrival) match
        case Arrival.Streamed(_) if !array => Arrival.Whole
        case arrival                       => arrival

    def expectsKey: Boolean = !array && expect == Expect.Member

    /** The key or index of the member to begin next. */
    def upcoming: String | Int = if array then count else key

    /**
      * Whether a member may begin: any, unless the value it holds has been read
      * already, when only a stream still to come, or a key it does not know.
      */
    def admits(next: String | Int): Boolean = !taken ||
      coming.contains(next) && !begun(next) || !array && member(next).isEmpty

    /** A stream still to come that never began, once it has ended. */
    def missing: Option[String | Int] = coming.keys.find(!begun(_))

    /** Whether it may end here, as it may after a whole member or if empty. */
    def closes(asArray: Boolean): Boolean = array == asArray &&
      (expect == Expect.Next || expect == Expect.Member && count == 0)

    def named(name: String): Frame = copy(
      key = name,
      expect = Expect.Colon,
      begun = if tracking then begun + name else begun,
    )

    def begin: Frame = copy(
      key = if array then count else key,
      begun = if array && tracking then begun + count else begun,
      started = true,
    )

    def ended(start: Int, end: Int): Frame = copy(
      wholes = if tracking then wholes :+ ((key, start, end)) else wholes,
      count = count + 1,
      expect = Expect.Next,
      started = false,
      handed = false,
    )

    /** The members settled, the one being written among them if `current`. */
    def settled(current: Boolean): Set[String | Int] = wholes.map(_._1).toSet ++
      Option.when(started && current)(key)

    def json(members: Seq[(String | Int, Json)]): Json =
      if array then Json.fromValues(members.map(_._2))
      else
        Json.fromFields(members.collect { case (key: String, json) =>
          key -> json
        })

    private def member(key: String | Int): Option[Schema[?]] = arrival match
      case Arrival.Streamed(element)    => Some(element)
      case Arrival.Assembled(member, _) => member(key)
      case Arrival.Whole                => None

    private def tracking: Boolean = live.isEmpty && arrival != Arrival.Whole
