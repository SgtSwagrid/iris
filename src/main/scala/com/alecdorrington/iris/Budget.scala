package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Ref
import cats.syntax.all.*
import fs2.Stream

/**
  * A limit on what requests may cost together, in US dollars, for a session or
  * any other span a host chooses: every client it [[limits]] spends from it,
  * whatever its model or provider. A request is refused once as much as the
  * limit has been spent, and is charged what its reply cost when the reply
  * comes. Its cost is known only then, so requests already sent when the limit
  * is reached are charged in full, and may take the spending past it, by as
  * many requests as are in flight at once: a stream running then among them,
  * which goes on to its end. A stream stopped before its end reports no usage,
  * so is charged nothing, though the provider may charge for what it wrote.
  * What is spent never resets, so a budget for each day, say, is a new budget
  * each day.
  *
  * A budget needs prices: a reply with no [[ModelResponse.cost]], as from a
  * model Iris does not list whose configuration gives no [[ModelConfig.rates]],
  * fails its request with [[ModelError.Unpriced]]. By then the provider has
  * charged for it, and the reply is lost, as is every later one from that
  * model, so give such a model rates before limiting it.
  *
  * {{{
  * Budget[IO](Usd(BigDecimal(5))).map(budget => budget.limits(client))
  * }}}
  *
  * @tparam F
  *   The effect type.
  *
  * @param limit
  *   The most that requests may spend together before more are refused.
  */
final class Budget[F[_] : MonadThrow] private (
  val limit: Usd,
  spending: Ref[F, Usd],
):

  /**
    * What the requests this budget limits have cost so far.
    *
    * @return
    *   An effect producing the amount spent.
    */
  def spent: F[Usd] = spending.get

  /**
    * What may still be spent, never less than nothing.
    *
    * @return
    *   An effect producing the amount left.
    */
  def remaining: F[Usd] =
    spent.map(spent => Usd((limit.amount - spent.amount).max(BigDecimal(0))))

  /**
    * Limits a client to this budget. Each request it makes but counting, which
    * costs nothing, is refused with [[ModelError.OverBudget]] once the budget
    * is spent, a stream before it starts, and charged what its reply cost, as
    * [[ModelClient.metered]] meters it. Every attempt of an [[ModelClient.ask]]
    * is a request of its own, and so is warming.
    *
    * @param client
    *   The client to limit.
    *
    * @return
    *   A client sending through the given one, spending from this budget.
    */
  def limits(client: ModelClient[F]): ModelClient[F] =
    val budget = this
    // Charged by `metered`, which meters every request that spends.
    new ModelClient.Forwarding(
      client.metered((_, cost) => budget.charge(cost)),
    ):
      override protected def send(request: ModelRequest): F[ModelResponse] =
        budget.affordable >> super.send(request)

      override def warmCache(request: ModelRequest): F[ModelResponse] =
        budget.affordable >> super.warmCache(request)

      override def stream(request: ModelRequest): Stream[F, Delta] = Stream
        .eval(budget.affordable) >> super.stream(request)

  /** Refuses a request once the budget is spent. */
  private def affordable: F[Unit] = spending
    .get
    .flatMap(spent =>
      MonadThrow[F].raiseWhen(spent.amount >= limit.amount)(
        ModelError.OverBudget(limit, spent),
      ),
    )

  private def charge(cost: Option[Usd]): F[Unit] = cost.fold(
    MonadThrow[F].raiseError(ModelError.Unpriced),
  )(cost => spending.update(_ + cost))

object Budget:

  /**
    * Creates a budget of which nothing is spent yet.
    *
    * @tparam F
    *   The effect type.
    *
    * @param limit
    *   The most that requests may spend together before more are refused.
    *
    * @return
    *   An effect producing the budget.
    */
  def apply[F[_] : MonadThrow : Ref.Make](limit: Usd): F[Budget[F]] = Ref
    .of[F, Usd](Usd.zero)
    .map(new Budget(limit, _))
