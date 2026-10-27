package com.alecdorrington.iris

/**
  * A single-turn prompt. For multi-turn conversations, use [[Chat]].
  *
  * {{{
  * Prompt("Name a colour.")
  * Prompt(essay, system = Some("Review the essay you are given."))
  * }}}
  *
  * @tparam A
  *   The type of what the user says.
  *
  * @param user
  *   What the user says, as its [[Content]] says it: text, a part, or a value
  *   as JSON.
  *
  * @param system
  *   The system message, if any.
  */
final case class Prompt[A]
  (user: A, system: Option[String] = None)
  (using Content[A]):

  /** This prompt as a single-message chat. */
  def toChat: Chat = Chat(List(Message(Role.User, user)), system)
