package com.alecdorrington.iris

/** The environment's variables, as configurations read them. */
private[iris] object Environment:

  /** An environment variable, where it is set and not blank. */
  def variable(name: String): Option[String] = sys
    .env
    .get(name)
    .filter(_.nonEmpty)

  /** A positive whole number, as a variable may give one. */
  def positive(value: String): Option[Int] = value
    .trim
    .toIntOption
    .filter(_ > 0)
