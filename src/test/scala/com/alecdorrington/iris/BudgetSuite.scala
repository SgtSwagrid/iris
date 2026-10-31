package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import cats.syntax.all.*
import com.alecdorrington.iris.Fixtures.{askWhole, config}
import io.circe.Json
import munit.FunSuite
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.{BackendStub, ResponseStub}

class BudgetSuite extends FunSuite:

  private def usd(figure: String) = Usd(BigDecimal(figure))

  /**
    * Anthropic's reply of the text, read from 100,000 tokens and written in
    * 20,000: $0.20 on Claude Haiku 4.5.
    */
  private def said(text: String) =
    s"""{"content":[{"type":"text","text":${ Json.fromString(text).noSpaces }}],
       |"stop_reason":"end_turn",
       |"usage":{"input_tokens":100000,"output_tokens":20000}}""".stripMargin

  /**
    * A client of the configuration answering each request with the next of the
    * replies, the last again once they run out, and counting the requests sent;
    * one to count tokens is answered with 12.
    */
  private def client
    (
      configured: LlmConfig,
      sent: Ref[SyncIO, Int],
      replies: String*,
    )
    : LlmClient[SyncIO] = LlmClient[SyncIO](
    configured,
    BackendStub(CatsMonadError[SyncIO]())
      .whenAnyRequest
      .thenRespondF(request =>
        if request.uri.path.lastOption.contains("count_tokens") then
          SyncIO(ResponseStub.adjust("""{"input_tokens":12}"""))
        else
          sent
            .getAndUpdate(_ + 1)
            .map(count =>
              ResponseStub.adjust(replies(count.min(replies.size - 1))),
            ),
      ),
  )

  private val hi = said("\"Hi\"")

  private val usage = Usage(
    uncachedTokens = 100_000,
    visibleTokens = 20_000,
  )

  /** What a meter of the client saw, once the client had done what it did. */
  private def metering
    (configured: LlmConfig, replies: String*)
    (doing: LlmClient[SyncIO] => SyncIO[Any])
    : List[(Usage, Option[Usd])] = (for
    sent <- Ref.of[SyncIO, Int](0)
    seen <- Ref.of[SyncIO, List[(Usage, Option[Usd])]](List.empty)
    _    <- doing(client(configured, sent, replies*).metered((usage, cost) =>
      seen.update(_ :+ (usage -> cost)),
    )).attempt
    meter <- seen.get
  yield meter).unsafeRunSync()

  test("a meter sees each attempt of an ask, and warming, but not counting"):
    // The first reply is prose, not JSON, so it is asked for again.
    assertEquals(
      metering(config, said("Hi"), hi)(client =>
        client.askWhole[String](Chat.of("Hi")) >> client.warm(Chat.of(
          Part.of("A document."),
          Part.CacheBreakpoint,
        )) >> client.count(Chat.of("Hi")),
      ),
      List.fill(3)(usage -> Some(usd("0.2"))),
    )
    // An ask that never reads is metered for every attempt, before it fails.
    assertEquals(
      metering(config, said("Hi"))(_.askWhole[String](Chat.of("Hi"))).size,
      3,
    )

  test("a meter sees a stream once, at its end, and none stopped before it"):
    assertEquals(
      metering(config, hi)(_.stream(Chat.of("Hi")).compile.drain),
      List(usage -> Some(usd("0.2"))),
    )
    assertEquals(
      metering(config, hi)(_.stream(Chat.of("Hi")).take(1).compile.drain),
      List.empty,
    )

  test("a meter is told no cost for a model iris cannot price"):
    val next = LlmModel.of(LlmProvider.Anthropic, "claude-next").get
    assertEquals(
      metering(config.copy(model = next), hi)(_.askWhole[String](Chat.of(
        "Hi",
      ))),
      List(usage -> None),
    )

  test("a meter that fails fails its request"):
    val failing = client(config, Ref.unsafe[SyncIO, Int](0), hi).metered(
      (_, _) => SyncIO.raiseError(Exception("full")),
    )
    assertEquals(
      failing
        .askWhole[String](Chat.of("Hi"))
        .attempt
        .unsafeRunSync()
        .left
        .map(_.getMessage),
      Left("full"),
    )

  test("a budget charges each reply its cost, and refuses once spent"):
    val (spent, remaining, refused, requests) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("0.5"))
      limited = budget.limits(client(config, sent, hi))
      // Each ask costs $0.20; the third takes the spending past the limit.
      _        <- limited.askWhole[String](Chat.of("Hi")).replicateA(3)
      refused  <- limited.askWhole[String](Chat.of("Hi")).attempt
      spent    <- budget.spent
      left     <- budget.remaining
      requests <- sent.get
    yield (spent, left, refused, requests)).unsafeRunSync()
    assertEquals(spent, usd("0.6"))
    assertEquals(remaining, Usd.zero)
    assertEquals(
      refused,
      Left(LlmError.OverBudget(usd("0.5"), usd("0.6"))),
    )
    // The refused request was never sent.
    assertEquals(requests, 3)

  test("every attempt of an ask is charged, and warming, but not counting"):
    val (asked, warmed, counted) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("10"))
      // The first reply is prose, not JSON, so it is asked for again.
      limited = budget.limits(client(config, sent, said("Hi"), hi))
      _     <- limited.askWhole[String](Chat.of("Hi"))
      asked <- budget.spent
      _     <- limited.warm(Chat.of(
        Part.of("A document."),
        Part.CacheBreakpoint,
      ))
      warmed  <- budget.spent
      _       <- limited.count(Chat.of("Hi"))
      counted <- budget.spent
    yield (asked, warmed, counted)).unsafeRunSync()
    assertEquals(asked, usd("0.4"))
    assertEquals(warmed, usd("0.6"))
    assertEquals(counted, usd("0.6"))

  test(
    "a budget refuses a stream before it starts, and charges one at its end",
  ):
    val (refused, spent, requests) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("0.1"))
      limited = budget.limits(client(config, sent, hi))
      // The first stream runs, so is charged in full, past the limit.
      _        <- limited.stream(Chat.of("Hi")).compile.drain
      refused  <- limited.stream(Chat.of("Hi")).compile.drain.attempt
      spent    <- budget.spent
      requests <- sent.get
    yield (refused, spent, requests)).unsafeRunSync()
    assertEquals(
      refused,
      Left(LlmError.OverBudget(usd("0.1"), usd("0.2"))),
    )
    assertEquals(spent, usd("0.2"))
    assertEquals(requests, 1)

  test("a budget stops a tool loop, which nothing else caps"):
    // The model asks for the tool every time, so only the budget ends the ask.
    val looking = """{"content":[{"type":"tool_use","id":"call_1","name":"look",
      |"input":{"word":"hi"}}],"stop_reason":"tool_use",
      |"usage":{"input_tokens":100000,"output_tokens":20000}}""".stripMargin
    val (refused, spent, runs) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      ran    <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("0.5"))
      look = RunnableTool[SyncIO](
        Tool(
          "look",
          "Looks up a word.",
          Json.obj("type" -> Json.fromString("object")),
        ),
        _ => ran.update(_ + 1).as("Found."),
      )
      limited = Fixtures.Sending(budget.limits(client(config, sent, looking)))
      refused <- Question
        .ask[SyncIO, String](
          limited.sent(_),
          Chat.of("Look up hi."),
          Question.attempts,
          Toolbox(List(look)),
        )
        .attempt
      spent <- budget.spent
      runs  <- ran.get
    yield (refused.left.toOption, spent, runs)).unsafeRunSync()
    // Three rounds of $0.20 pass the $0.50 limit, and the fourth is refused.
    assertEquals(
      refused,
      Some(LlmError.OverBudget(usd("0.5"), usd("0.6"))),
    )
    assertEquals(spent, usd("0.6"))
    assertEquals(runs, 3)

  test("one budget limits several clients together, and counting when spent"):
    val (spent, counted) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("0.7"))
      haiku = budget.limits(client(config, sent, hi))
      // Claude Sonnet 4.6's $3 and $15 per million tokens make $0.60.
      sonnet = budget.limits(client(
        config.copy(model = LlmModel.ClaudeSonnet4_6),
        sent,
        hi,
      ))
      _       <- haiku.askWhole[String](Chat.of("Hi"))
      _       <- sonnet.askWhole[String](Chat.of("Hi"))
      spent   <- budget.spent
      counted <- haiku.count(Chat.of("Hi"))
    yield (spent, counted)).unsafeRunSync()
    assertEquals(spent, usd("0.8"))
    assertEquals(counted, 12)

  test(
    "a reply with no cost fails a budgeted request, and none spends nothing",
  ):
    val next = LlmModel.of(LlmProvider.Anthropic, "claude-next").get
    val (unpriced, spent, nothing) = (for
      sent     <- Ref.of[SyncIO, Int](0)
      budget   <- Budget[SyncIO](usd("1"))
      unpriced <- budget
        .limits(client(config.copy(model = next), sent, hi))
        .askWhole[String](Chat.of("Hi"))
        .attempt
      spent   <- budget.spent
      none    <- Budget[SyncIO](Usd.zero)
      nothing <- none
        .limits(client(config, sent, hi))
        .askWhole[String](Chat.of("Hi"))
        .attempt
    yield (unpriced, spent, nothing)).unsafeRunSync()
    assertEquals(unpriced, Left(LlmError.Unpriced))
    assertEquals(spent, Usd.zero)
    assertEquals(
      nothing,
      Left(LlmError.OverBudget(Usd.zero, Usd.zero)),
    )

  test("a refusal says what was spent, in dollars"):
    assertEquals(
      LlmError.OverBudget(usd("5"), usd("5.25")).getMessage,
      "Over budget: US$5.25 of US$5 spent",
    )
