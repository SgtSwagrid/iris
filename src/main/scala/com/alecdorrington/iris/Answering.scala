package com.alecdorrington.iris

/**
  * An answer handed over by [[LlmClient.askStreamed]] while its reply is still
  * being written: its value, as soon as everything in it but its streams is
  * read, and the whole answer, once the reply ends.
  *
  * @tparam F
  *   The effect type.
  *
  * @tparam A
  *   The type of the value.
  *
  * @param value
  *   The value read from the reply, each `fs2.Stream` within it delivering its
  *   elements as the model writes them, each time it is read from the first. It
  *   is `None` only where the model asked for a tool, though none can be
  *   offered.
  *
  * @param answer
  *   The whole answer, once the reply ends: its text, why the model stopped,
  *   the usage of every attempt, and the same value. Where the reply cannot be
  *   read to its end, it fails as the value's streams not yet ended do: with
  *   [[LlmError.Unreadable]] where the reply cannot be read, as the request
  *   fails where it fails partway, and with an `IllegalStateException` where
  *   the answer's resource is released first.
  */
final case class Answering[F[_], A](value: Option[A], answer: F[Answer[A]])
