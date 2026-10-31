package com.alecdorrington.iris

import scala.concurrent.duration.FiniteDuration

/**
  * What a model's tokens cost, in [[Usd]] per million tokens, with one rate for
  * each of [[Usage]]'s counts, as its provider charges on its standard tier. A
  * count the provider charges nothing apart for is charged as the rest of its
  * kind: cache writes as input where writing the cache costs nothing more,
  * audio as other input where it is priced alike, and thinking as the reply.
  *
  * @param uncached
  *   The rate of tokens of the request read afresh, other than audio.
  *
  * @param cacheRead
  *   The rate of tokens of the request read from the cache, other than audio.
  *
  * @param cacheWrite
  *   The rate of tokens of the request written to the cache.
  *
  * @param uncachedAudio
  *   The rate of tokens of audio read afresh.
  *
  * @param cacheReadAudio
  *   The rate of tokens of audio read from the cache.
  *
  * @param visible
  *   The rate of tokens the model replied with.
  *
  * @param thinking
  *   The rate of tokens the model thought in.
  *
  * @param above
  *   The rates instead of these for a request whose prompt is longer, where the
  *   provider charges more for a long prompt.
  */
final case class Rates
  (
    uncached: Usd,
    cacheRead: Usd,
    cacheWrite: Usd,
    uncachedAudio: Usd,
    cacheReadAudio: Usd,
    visible: Usd,
    thinking: Usd,
    above: Option[Rates.Above] = None,
  ):

  /**
    * Prices one request's token counts, at the rates for the length of its
    * prompt. Counts summed over several requests may be priced at the wrong
    * rates, where the provider charges more for a long prompt, so each request
    * is priced alone, as [[ModelResponse.cost]] is.
    *
    * @param usage
    *   The request's token counts.
    *
    * @return
    *   What the request cost.
    */
  def cost(usage: Usage): Usd = above match
    case Some(Rates.Above(tokens, longer)) if usage.inputTokens > tokens =>
      longer.cost(usage)
    case _ => Usd(
        List(
          uncached       -> usage.uncachedTokens,
          cacheRead      -> usage.cacheReadTokens,
          cacheWrite     -> usage.cacheWriteTokens,
          uncachedAudio  -> usage.uncachedAudioTokens,
          cacheReadAudio -> usage.cacheReadAudioTokens,
          visible        -> usage.visibleTokens,
          thinking       -> usage.thinkingTokens,
        ).map((rate, tokens) => rate.amount * BigDecimal(tokens)).sum /
          Rates.perMillion,
      )

object Rates:

  private val perMillion = BigDecimal(1_000_000)

  /**
    * Rates for a request whose prompt is longer than some number of tokens,
    * which apply to the whole of it. The prompt is every token of the request,
    * read from the cache or not, as Anthropic says it counts it: OpenAI and
    * Google do not say whether they count cached tokens.
    *
    * @param tokens
    *   The number of tokens of the prompt beyond which these rates apply.
    *
    * @param rates
    *   The rates of such a request.
    */
  final case class Above(tokens: Int, rates: Rates)

  /**
    * Rates charging every token of the prompt alike, and every token of the
    * reply alike, as a provider which charges nothing apart for its cache or
    * for audio does.
    *
    * @param input
    *   The rate of every token of the prompt.
    *
    * @param output
    *   The rate of every token of the reply.
    *
    * @return
    *   The rates.
    */
  def of(input: Usd, output: Usd): Rates = Rates(
    input,
    input,
    input,
    input,
    input,
    output,
    output,
  )

  /**
    * The rates Iris lists for a model, as its provider charged them at a
    * moment, such as for estimating a request before it is sent.
    *
    * @param model
    *   The model.
    *
    * @param at
    *   The moment, since the start of 1970 in UTC, as `Clock.realTime` gives
    *   it.
    *
    * @return
    *   The rates, or `None` for a model Iris does not list.
    */
  def listed(model: Model, at: FiniteDuration): Option[Rates] = ProviderApi
    .of(model.provider)
    .rates(model, at)
