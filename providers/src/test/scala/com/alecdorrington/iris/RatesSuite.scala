package com.alecdorrington.iris

import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.gemini.*
import com.alecdorrington.iris.openai.*
import com.alecdorrington.iris.providers.*
import munit.FunSuite

class RatesSuite extends FunSuite:

  private val now = Provider.utc(2026, 11, 1)

  private def usd(figure: String) = Usd(BigDecimal(figure))

  test("dollars add up, scale and order by their amount"):
    assertEquals(usd("0.25") + usd("1.5"), usd("1.75"))
    assertEquals(
      usd("2") * BigDecimal("1.25"),
      usd("2.5"),
    )
    assertEquals(
      List(usd("3"), Usd.zero, usd("1")).sorted,
      List(Usd.zero, usd("1"), usd("3")),
    )

  test("each count is priced at its own rate, per million tokens"):
    val rates = Rates(
      usd("1"),
      usd("2"),
      usd("3"),
      usd("4"),
      usd("5"),
      usd("6"),
      usd("7"),
    )
    assertEquals(
      rates.cost(Usage(10, 20, 30, 40, 50, 60, 70)),
      usd("0.0014"),
    )

  test("a long prompt is priced whole at the rates above its threshold"):
    val rates = Rates
      .of(usd("1"), usd("2"))
      .copy(above = Some(Rates.Above(100, Rates.of(usd("10"), usd("20")))))
    assertEquals(
      rates.cost(Usage(uncachedTokens = 100, visibleTokens = 1)),
      usd("0.000102"),
    )
    // The prompt counts what is read from the cache, too.
    assertEquals(
      rates.cost(Usage(
        uncachedTokens = 50,
        cacheReadTokens = 51,
        visibleTokens = 1,
      )),
      usd("0.00103"),
    )

  test("every model iris lists has rates, and one it does not has none"):
    Providers
      .all
      .flatMap(_.listed)
      .foreach(model => assert(model.rates(now).isDefined, model))
    assertEquals(
      Gemini.model("gemini-next").flatMap(_.rates(now)),
      None,
    )

  test("each provider's rates are read as its pricing page gives them"):
    // Anthropic charges a quarter more than input for writing its cache.
    assertEquals(
      AnthropicModel
        .ClaudeSonnet5_5
        .rates(now)
        .map(_.cost(Usage(1000, 10_000, 2000, 0, 0, 500, 1500))),
      Some(usd("0.028")),
    )
    // Claude Haiku 5.5 charges more for a prompt over 100,000 tokens.
    assertEquals(
      AnthropicModel
        .ClaudeHaiku5_5
        .rates(now)
        .map(_.cost(Usage(uncachedTokens = 100_001))),
      Some(usd("0.0500005")),
    )
    // GPT-6.1 Sol charges more for a prompt over 272K tokens.
    assertEquals(
      OpenAiModel
        .Gpt6_1Sol
        .rates(now)
        .map(_.cost(Usage(
          uncachedTokens = 300_000,
          visibleTokens = 1000,
        ))),
      Some(usd("1.215")),
    )
    // Gemini 2.5 Flash charges more for audio, read afresh or from the cache.
    assertEquals(
      GeminiModel
        .Gemini2_5Flash
        .rates(now)
        .map(_.cost(Usage(
          uncachedAudioTokens = 1_000_000,
          cacheReadAudioTokens = 1_000_000,
        ))),
      Some(usd("1.1")),
    )

  test("a provider's rates change from the day it said they would"):
    def input
      (
        model: ProviderModel,
        year: Int,
        month: Int,
        day: Int,
      ) = model.rates(Provider.utc(year, month, day)).map(_.uncached)
    assertEquals(
      input(GeminiModel.Gemini4Argon, 2026, 11, 30),
      Some(usd("2")),
    )
    assertEquals(
      input(GeminiModel.Gemini4Argon, 2026, 12, 1),
      Some(usd("4")),
    )
    assertEquals(
      input(GeminiModel.Gemini3_8Flash, 2026, 12, 31),
      Some(usd("0.75")),
    )
    assertEquals(
      input(GeminiModel.Gemini3_8Flash, 2027, 1, 1),
      Some(usd("1.5")),
    )

  test("a retired model is priced as the one answering for it from its day"):
    def rates
      (
        model: ProviderModel,
        year: Int,
        month: Int,
        day: Int,
      ) = model.rates(Provider.utc(year, month, day))
    assertEquals(
      rates(GeminiModel.Gemini2_5Pro, 2026, 11, 30).map(_.uncached),
      Some(usd("1.25")),
    )
    assertEquals(
      rates(GeminiModel.Gemini2_5Pro, 2026, 12, 1),
      rates(GeminiModel.Gemini3_8Flash, 2026, 12, 1),
    )
    assertEquals(
      rates(GeminiModel.Gemini2_5Pro, 2027, 1, 1),
      rates(GeminiModel.Gemini3_8Flash, 2027, 1, 1),
    )
    assertEquals(
      rates(GeminiModel.Gemini2_5Flash, 2026, 11, 30).map(_.uncachedAudio),
      Some(usd("1")),
    )
    assertEquals(
      rates(GeminiModel.Gemini2_5Flash, 2026, 12, 1),
      rates(
        GeminiModel.Gemini3_5FlashLite,
        2026,
        12,
        1,
      ),
    )

  test("a day begins at its midnight in utc, counted from 1970"):
    List(
      (1970, 1, 1)  -> 0L,
      (2000, 1, 1)  -> 946_684_800_000L,
      (2024, 2, 29) -> 1_709_164_800_000L,
      (2026, 12, 1) -> 1_796_083_200_000L,
      (2027, 1, 1)  -> 1_798_761_600_000L,
    ).foreach: (day, millis) =>
      val (year, month, date) = day
      assertEquals(
        Provider.utc(year, month, date).toMillis,
        millis,
        day,
      )
