package com.alecdorrington.iris

/**
  * An amount of US dollars, the currency every provider prices in. Iris
  * converts it to no other: a host billed in another currency converts it at
  * its own rate, which the one billing it sets. Nor does it count tax, credits
  * or a discount of one's own.
  *
  * @param amount
  *   The number of dollars.
  */
final case class Usd(amount: BigDecimal):

  /**
    * Adds another amount of dollars to this one.
    *
    * @param other
    *   The other amount.
    *
    * @return
    *   Both amounts together.
    */
  def + (other: Usd): Usd = Usd(amount + other.amount)

  /**
    * Multiplies this amount by a number, as for a discount.
    *
    * @param factor
    *   The number to multiply by.
    *
    * @return
    *   The amount so many times over.
    */
  def * (factor: BigDecimal): Usd = Usd(amount * factor)

object Usd:

  /** No dollars. */
  val zero: Usd = Usd(BigDecimal(0))

  /** Amounts ordered from least to most. */
  given Ordering[Usd] = Ordering.by(_.amount)
