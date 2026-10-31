package com.alecdorrington.iris

import cats.syntax.all.*
import com.alecdorrington.iris.Unfinished.{Event, Expect, Frame, State}
import io.circe.{Decoder, HCursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.util.DynamicVariable

/**
  * A reply's JSON as far as it has arrived, read into its answer as soon as
  * everything in it but its streams is, and then into each stream as each of
  * its elements is. Only brackets, strings and their escapes are followed here;
  * circe parses each value once it is whole, and its schema's own decoder reads
  * it, a stream begun within it stood in for by a placeholder.
  *
  * It is mutable, and read by one fiber alone.
  *
  * @param schema
  *   The schema of the answer.
  *
  * @param live
  *   The stream to hand over for each stream opened, by its number, counting
  *   from `1`.
  */
private[iris] final class Unfinished(schema: Schema[?], live: Int => Any):

  private val written = StringBuilder()

  private val document = Frame(
    Arrival.Streamed(schema),
    true,
    -1,
    Some(0),
  )
  private val events = ListBuffer.empty[Event]

  private var frames: List[Frame] = List(document)
  private var state: State        = State.Before
  private var string: Int         = -1
  private var escaped: Boolean    = false
  private var bare: Int           = -1
  private var streams: Int        = 0
  private var answered: Boolean   = false

  /** The reply so far. */
  def text: String = written.toString

  /** Whether the answer's JSON has ended. */
  def whole: Boolean = state == State.Done

  /** Reads more of the reply, giving what can now be read of the answer. */
  def feed(chunk: String): List[Event] =
    val from = written.length
    written.append(chunk)
    events.clear()
    (from until written.length).foreach(index =>
      step(index, written.charAt(index)),
    )
    if state == State.Within then settle()
    events.toList

  /** Follows one character, skipping a code fence the answer opens with. */
  private def step(index: Int, char: Char): Unit = state match
    case State.Before if char == '`'        => state = State.Fenced
    case State.Before if !char.isWhitespace =>
      state = State.Within
      began(index, char)
    case State.Fenced if char == '\n' => state = State.Before
    case State.Within                 => within(index, char)
    case _                            => ()

  private def within(index: Int, char: Char): Unit =
    if string >= 0 then quoted(index, char)
    else if bare < 0 then structure(index, char)
    else if delimits(char) then
      val start = bare
      bare = -1
      ended(start, index)
      if state == State.Within then structure(index, char)

  private def structure(index: Int, char: Char): Unit = char match
    case _ if char.isWhitespace => ()
    case ','                    => due(Expect.Next, Expect.Member)
    case ':'                    => due(Expect.Colon, Expect.Value)
    case '}' | ']'              => closed(index, char)
    case _                      => began(index, char)

  /** Moves the innermost container on, where it is where it must be. */
  private def due(at: Expect, next: Expect): Unit =
    if frames.head.expect == at then frames.head.expect = next else lost()

  /** A value, or a key where one is due, begins. */
  private def began(index: Int, char: Char): Unit =
    val frame = frames.head
    frame.expect match
      case Expect.Member if !frame.array =>
        if char == '"' then string = index else lost()
      case Expect.Member | Expect.Value =>
        val reserved = frame.coming.get(frame.upcoming)
        if frame.array && !frame.admits(frame.upcoming) then
          failed("It wrote more of a list after the value holding it was read.")
        else if reserved.nonEmpty && char != '[' then
          failed(
            s"It wrote `${ frame.upcoming }` as something other than a list.",
          )
        else
          frame.begin()
          char match
            case '"'       => string = index
            case '{' | '[' =>
              val array = char == '['
              frames = Frame(
                frame.arrivalOf(array),
                array,
                index,
                reserved,
              ) :: frames
            case _ if char == '-' || char.isDigit || "tfn".contains(char) =>
              bare = index
            case _ => lost()
      case _ => lost()

  private def quoted(index: Int, char: Char): Unit =
    if escaped then escaped = false
    else if char == '\\' then escaped = true
    else if char == '"' then
      val start = string
      string = -1
      if frames.head.expectsKey then named(start, index + 1)
      else ended(start, index + 1)

  private def named(start: Int, end: Int): Unit = parsed(start, end)
    .toOption
    .flatMap(_.asString)
    .fold(lost())(keyed)

  /**
    * A key is read, which must not name a member of a value read already, whose
    * reading took it to be left out, or read it already.
    */
  private def keyed(key: String): Unit =
    if frames.head.admits(key) then frames.head.named(key)
    else failed(s"It wrote `$key` after the value holding it was read.")

  /** A container ends, which a value read already may only once it has all. */
  private def closed(index: Int, char: Char): Unit = frames match
    case frame :: (rest @ _ :: _) if frame.closes(char == ']') =>
      frame.missing match
        case Some(member) =>
          failed(s"It left out `$member`, which was being waited for.")
        case None =>
          frames = rest
          frame.live.foreach(stream => events += Event.Ended(stream))
          ended(frame.start, index + 1)
    case _ => lost()

  /**
    * A value within the innermost container is whole, from `start` to `end`,
    * and read if it is an element of a stream, and not handed over already. The
    * answer is read here only where it may be read before it is whole, as the
    * rest are read from the whole reply once it ends.
    */
  private def ended(start: Int, end: Int): Unit =
    val frame  = frames.head
    val handed = frame.handed
    frame.ended(start, end)
    if frame eq document then
      state = State.Done
      if !answered && schema.gradual then delivered(0, schema, start, end)
    else if !handed then
      frame
        .live
        .zip(frame.element)
        .foreach((stream, element) => delivered(stream, element, start, end))

  private def delivered
    (
      stream: Int,
      element: Schema[?],
      start: Int,
      end: Int,
    )
    : Unit = parsed(start, end)
    .flatMap(read(element, _))
    .fold(
      failed,
      value => handedOver(stream, value),
    )

  private def handedOver(stream: Int, value: Any): Unit =
    if stream == 0 then answered = true
    events += Event.Read(stream, value)

  /**
    * Hands over each element of a stream being written that can be read
    * already, outermost first, as handing one over opens the streams within it.
    */
  private def settle(): Unit =
    val path = frames.reverse.toVector
    path
      .indices
      .dropRight(1)
      .foreach(index =>
        for
          stream  <- path(index).live
          element <- path(index).element
          if state == State.Within && !path(index).handed && element.gradual &&
          settles(path, index + 1)
        do handOver(path, index, stream, element),
      )

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

  /**
    * Hands over the element being written of the stream at `index`, opening
    * each stream within it only if it can be read.
    */
  private def handOver
    (
      path: Vector[Frame],
      index: Int,
      stream: Int,
      element: Schema[?],
    )
    : Unit =
    val opening = ListBuffer.empty[Event]
    val holes   = ListBuffer.empty[(Json, Any)]
    built(path, index + 1, opening, holes)
      .flatMap(json => Unfinished.holding(holes.toList)(read(element, json)))
      .fold(
        failed,
        value =>
          events ++= opening
          path(index).handed = true
          handedOver(stream, value),
      )

  /**
    * The JSON of a value settled so far: its whole members, and the one being
    * written where it has settled, each stream among them stood in for.
    */
  private def built
    (
      path: Vector[Frame],
      index: Int,
      opening: ListBuffer[Event],
      holes: ListBuffer[(Json, Any)],
    )
    : Either[String, Json] =
    val frame = path(index)
    frame.arrival match
      case Arrival.Streamed(element)   => opened(frame, element, opening, holes)
      case Arrival.Assembled(_, ready) =>
        val current = open(path, index)
        frame.taken = true
        (
          frame
            .wholes
            .toList
            .traverse((key, start, end) => parsed(start, end).map(key -> _)),
          Option
            .when(current)(index + 1)
            .traverse(child =>
              built(path, child, opening, holes).map(frame.key -> _),
            ),
          Right(
            ready(frame.settled(current), frame.begun)
              .getOrElse(Nil)
              .map(member => member -> reserved(frame, member, opening, holes)),
          ),
        ).mapN(_ ++ _ ++ _).map(frame.json)
      case Arrival.Whole => Left(Unfinished.unparsed)

  /**
    * Opens a stream begun within a value being handed over, with the elements
    * it has whole already, and stands it in for by a placeholder of its own.
    */
  private def opened
    (
      frame: Frame,
      element: Schema[?],
      opening: ListBuffer[Event],
      holes: ListBuffer[(Json, Any)],
    )
    : Either[String, Json] =
    val (stream, hole) = holed(opening, holes)
    frame.live = Some(stream)
    val elements = frame
      .wholes
      .toList
      .traverse((_, start, end) => parsed(start, end).flatMap(read(element, _)))
    frame.wholes.clear()
    elements.map: values =>
      opening ++= values.map(Event.Read(stream, _))
      hole

  /**
    * Opens a stream of a value being handed over before it begins, kept for the
    * member it will be, and stands it in for by a placeholder of its own.
    */
  private def reserved
    (
      frame: Frame,
      member: String | Int,
      opening: ListBuffer[Event],
      holes: ListBuffer[(Json, Any)],
    )
    : Json =
    val (stream, hole) = holed(opening, holes)
    frame.coming(member) = stream
    hole

  /** A new stream, opened, and the placeholder standing in for it. */
  private def holed
    (
      opening: ListBuffer[Event],
      holes: ListBuffer[(Json, Any)],
    )
    : (Int, Json) =
    streams += 1
    val hole = Json.obj("stream" -> streams.asJson)
    holes += hole -> live(streams)
    opening += Event.Opened(streams)
    streams -> hole

  private def read(schema: Schema[?], json: Json): Either[String, Any] =
    Question.decoded(schema, json)

  private def parsed(start: Int, end: Int): Either[String, Json] =
    parse(written.substring(start, end)).leftMap(_ => Unfinished.unparsed)

  /**
    * Stops reading, saying why where the answer was handed over already, and
    * else leaving the reply to be read whole once it ends.
    */
  private def failed(why: String): Unit =
    if answered then events += Event.Failed(why)
    state = State.Lost

  private def lost(): Unit = failed(Unfinished.unparsed)

  private def delimits(char: Char): Boolean = char.isWhitespace ||
    char == ',' || char == ']' || char == '}' || char == ':'

private[iris] object Unfinished:

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
    * The streams that placeholders stand for while a value is read, told apart
    * from any JSON the model wrote by identity.
    */
  private val holes = DynamicVariable[List[(Json, Any)]](Nil)

  /**
    * A value of a schema whose JSON is a stream, read as the stream given, as a
    * placeholder of one is.
    */
  def standIn(schema: Schema[?], stream: Any): Either[String, Any] =
    val hole = Json.obj("stream" -> Json.fromInt(0))
    holding(List(hole -> stream))(Question.decoded(schema, hole))

  /** Reads with the streams that placeholders stand for. */
  private def holding[A](filled: List[(Json, Any)])(read: => A): A = holes
    .withValue(filled)(read)

  /**
    * A stream's decoder, reading a placeholder as the stream it stands for, and
    * any other JSON as `whole` does.
    */
  def filled[A](whole: Decoder[A]): Decoder[A] = new Decoder[A]:

    override def apply(cursor: HCursor): Decoder.Result[A] = standing[A](cursor)
      .fold(whole(cursor))(Right(_))

    override def decodeAccumulating
      (cursor: HCursor)
      : Decoder.AccumulatingResult[A] =
      standing[A](cursor).fold(whole.decodeAccumulating(cursor))(_.validNel)

  private def standing[A](cursor: HCursor): Option[A] = holes
    .value
    .collectFirst:
      case (hole, stream) if hole eq cursor.value => stream.asInstanceOf[A]

  private enum State:
    case Before, Fenced, Within, Done, Lost

  /**
    * What a container is due next: a member, a key's colon, the key's value, or
    * a comma or its end.
    */
  private enum Expect:
    case Member, Colon, Value, Next

  /**
    * A container begun and not yet ended, and as much of it as reading it early
    * needs: its members whole and begun, kept while it may be read before it is
    * whole and is no stream handed over already.
    */
  private final class Frame
    (
      val arrival: Arrival,
      val array: Boolean,
      val start: Int,
      var live: Option[Int] = None,
    ):

    /** Whether the element being written has been handed over already. */
    var handed: Boolean = false

    /** Whether the value it holds has been handed over already. */
    var taken: Boolean = false

    /** The streams handed over before they began, by member. */
    val coming: mutable.Map[String | Int, Int] = mutable.Map.empty

    var expect: Expect = Expect.Member

    /** Whether the value of the member being written has begun. */
    var started: Boolean = false

    var key: String | Int = 0

    var begun: Set[String | Int] = Set.empty

    val wholes: ArrayBuffer[(String | Int, Int, Int)] = ArrayBuffer.empty

    private var count: Int = 0

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

    def named(name: String): Unit =
      key = name
      expect = Expect.Colon
      if tracking then begun += name

    def begin(): Unit =
      if array then key = count
      if array && tracking then begun += count
      started = true

    def ended(start: Int, end: Int): Unit =
      if tracking then wholes += ((key, start, end))
      count += 1
      expect = Expect.Next
      started = false
      handed = false

    /** The members settled, the one being written among them if `current`. */
    def settled(current: Boolean): Set[String | Int] = wholes.map(_._1).toSet ++
      Option.when(started && current)(key)

    def json(members: List[(String | Int, Json)]): Json =
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
