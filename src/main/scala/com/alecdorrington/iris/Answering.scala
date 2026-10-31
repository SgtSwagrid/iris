package com.alecdorrington.iris

/**
  * An answer handed over by [[LlmStreamer.ask]] while its reply is still being
  * written: its value, as soon as everything in it but its streams is read, and
  * the whole answer, once the reply ends.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the reply, each `fs2.Stream` within it delivering its
  *   elements as the model writes them, or `None` where the model asked for
  *   tools instead. Each stream may be read once, and only while the answer's
  *   resource is in use.
  *
  * @param answer
  *   The whole answer, once the reply ends: its text, why the model stopped,
  *   the usage of every attempt, and the same value. It fails, as the value's
  *   streams not yet ended do, where the reply cannot be read to its end.
  */
final case class Answering[F[_], A](value: Option[A], answer: F[Answer[A]])
