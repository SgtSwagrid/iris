package com.alecdorrington.iris

import cats.effect.{Ref, SyncIO}
import cats.syntax.all.*
import com.alecdorrington.iris.Fixtures.{access, askWhole, config, model}
import com.alecdorrington.iris.agent.*
import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.api.*
import com.alecdorrington.iris.prompt.*
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

  /** The same reply, streamed. */
  private def streamed(text: String) =
    s"""data: {"type":"message_start","message":{"usage":{"input_tokens":100000,"output_tokens":1}}}
       |
       |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${ Json
        .fromString(text)
        .noSpaces }}}
       |
       |data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20000}}
       |
       |""".stripMargin

  /**
    * Anthropic's API answering each request with the next of the replies, the
    * last again once they run out, and counting the requests sent; one to count
    * tokens is answered with 12.
    */
  private def api
    (sent: Ref[SyncIO, Int], replies: String*)
    : ApiService[SyncIO] = Anthropic.api(
    access,
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

  /** A model's prompt service over such an API. */
  private def service
    (
      model: ProviderModel,
      sent: Ref[SyncIO, Int],
      replies: String*,
    )
    : PromptService[SyncIO] = model.prompt(api(sent, replies*), config)

  private val hi = said("\"Hi\"")

  private val hiStreamed = streamed("\"Hi\"")

  private val usage = Usage(
    uncachedTokens = 100_000,
    visibleTokens = 20_000,
  )

  /**
    * What a meter of a model's prompt service saw, once the service, and a
    * counter over the same API, had done what they did.
    */
  private def metering
    (model: ProviderModel, replies: String*)
    (
      doing: (
        PromptService[SyncIO],
        TokenCounter[SyncIO],
      ) => SyncIO[Any],
    )
    : List[(Usage, Option[Usd])] = (for
    sent <- Ref.of[SyncIO, Int](0)
    seen <- Ref.of[SyncIO, List[(Usage, Option[Usd])]](List.empty)
    answering = api(sent, replies*)
    _ <- doing(
      model
        .prompt(answering, config)
        .metered((usage, cost) => seen.update(_ :+ (usage -> cost))),
      model.counter(answering).get,
    ).attempt
    meter <- seen.get
  yield meter).unsafeRunSync()

  test("a meter sees each attempt of an ask, and warming, but not counting"):
    // The first reply is prose, not JSON, so it is asked for again.
    assertEquals(
      metering(model, said("Hi"), hi)((prompt, counter) =>
        prompt.askWhole[String](Conversation.userSays("Hi")) >>
          prompt.warmCache(Conversation.userSays(
            MessagePart.of("A document."),
            MessagePart.CacheBreakpoint,
          )) >> counter.countTokens(Conversation.userSays("Hi")),
      ),
      List.fill(3)(usage -> Some(usd("0.2"))),
    )
    // An ask that never reads is metered for every attempt, before it fails.
    assertEquals(
      metering(model, said("Hi"))((prompt, _) =>
        prompt.askWhole[String](Conversation.userSays("Hi")),
      ).size,
      3,
    )

  test("a meter sees a stream once, at its end, and none stopped before it"):
    assertEquals(
      metering(model, hiStreamed)((prompt, _) =>
        prompt.stream(Conversation.userSays("Hi")).compile.drain,
      ),
      List(usage -> Some(usd("0.2"))),
    )
    assertEquals(
      metering(model, hiStreamed)((prompt, _) =>
        prompt.stream(Conversation.userSays("Hi")).take(1).compile.drain,
      ),
      List.empty,
    )

  test("a meter is told no cost for a model iris cannot price"):
    val next = Anthropic.model("claude-next").get
    assertEquals(
      metering(next, hi)((prompt, _) =>
        prompt.askWhole[String](Conversation.userSays("Hi")),
      ),
      List(usage -> None),
    )

  test("a meter that fails fails its request"):
    val failing = service(model, Ref.unsafe[SyncIO, Int](0), hi).metered(
      (_, _) => SyncIO.raiseError(Exception("full")),
    )
    assertEquals(
      failing
        .askWhole[String](Conversation.userSays("Hi"))
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
      limited = budget.limits(service(model, sent, hi))
      // Each ask costs $0.20; the third takes the spending past the limit.
      _ <- limited.askWhole[String](Conversation.userSays("Hi")).replicateA(3)
      refused  <- limited.askWhole[String](Conversation.userSays("Hi")).attempt
      spent    <- budget.spent
      left     <- budget.remaining
      requests <- sent.get
    yield (spent, left, refused, requests)).unsafeRunSync()
    assertEquals(spent, usd("0.6"))
    assertEquals(remaining, Usd.zero)
    assertEquals(
      refused,
      Left(PromptError.OverBudget(usd("0.5"), usd("0.6"))),
    )
    // The refused request was never sent.
    assertEquals(requests, 3)

  test("every attempt of an ask is charged, and warming, but not counting"):
    val (asked, warmed, counted) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("10"))
      // The first reply is prose, not JSON, so it is asked for again.
      answering = api(sent, said("Hi"), hi)
      limited   = model.prompt(answering, config).limitedBy(budget)
      _     <- limited.askWhole[String](Conversation.userSays("Hi"))
      asked <- budget.spent
      _     <- limited.warmCache(Conversation.userSays(
        MessagePart.of("A document."),
        MessagePart.CacheBreakpoint,
      ))
      warmed <- budget.spent
      _ <- model.counter(answering).get.countTokens(Conversation.userSays("Hi"))
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
      limited = budget.limits(service(model, sent, hiStreamed))
      // The first stream runs, so is charged in full, past the limit.
      _       <- limited.stream(Conversation.userSays("Hi")).compile.drain
      refused <- limited
        .stream(Conversation.userSays("Hi"))
        .compile
        .drain
        .attempt
      spent    <- budget.spent
      requests <- sent.get
    yield (refused, spent, requests)).unsafeRunSync()
    assertEquals(
      refused,
      Left(PromptError.OverBudget(usd("0.1"), usd("0.2"))),
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
      refused <- budget
        .limits(service(model, sent, looking))
        .askWhole[String](
          Conversation.userSays("Look up hi."),
          tools = List(look),
        )
        .attempt
      spent <- budget.spent
      runs  <- ran.get
    yield (refused.left.toOption, spent, runs)).unsafeRunSync()
    // Three rounds of $0.20 pass the $0.50 limit, and the fourth is refused.
    assertEquals(
      refused,
      Some(PromptError.OverBudget(usd("0.5"), usd("0.6"))),
    )
    assertEquals(spent, usd("0.6"))
    assertEquals(runs, 3)

  test("one budget limits several services together, and counting when spent"):
    val (spent, counted) = (for
      sent   <- Ref.of[SyncIO, Int](0)
      budget <- Budget[SyncIO](usd("0.7"))
      haiku = budget.limits(service(model, sent, hi))
      // Claude Sonnet 4.6's $3 and $15 per million tokens make $0.60.
      sonnet = budget.limits(service(
        AnthropicModel.ClaudeSonnet4_6,
        sent,
        hi,
      ))
      _       <- haiku.askWhole[String](Conversation.userSays("Hi"))
      _       <- sonnet.askWhole[String](Conversation.userSays("Hi"))
      spent   <- budget.spent
      counted <- model
        .counter(api(sent, hi))
        .get
        .countTokens(Conversation.userSays("Hi"))
    yield (spent, counted)).unsafeRunSync()
    assertEquals(spent, usd("0.8"))
    assertEquals(counted, 12)

  test(
    "a reply with no cost fails a budgeted request, and none spends nothing",
  ):
    val next                       = Anthropic.model("claude-next").get
    val (unpriced, spent, nothing) = (for
      sent     <- Ref.of[SyncIO, Int](0)
      budget   <- Budget[SyncIO](usd("1"))
      unpriced <- budget
        .limits(service(next, sent, hi))
        .askWhole[String](Conversation.userSays("Hi"))
        .attempt
      spent   <- budget.spent
      none    <- Budget[SyncIO](Usd.zero)
      nothing <- none
        .limits(service(model, sent, hi))
        .askWhole[String](Conversation.userSays("Hi"))
        .attempt
    yield (unpriced, spent, nothing)).unsafeRunSync()
    assertEquals(unpriced, Left(PromptError.Unpriced))
    assertEquals(spent, Usd.zero)
    assertEquals(
      nothing,
      Left(PromptError.OverBudget(Usd.zero, Usd.zero)),
    )

  test("a refusal says what was spent, in dollars"):
    assertEquals(
      PromptError.OverBudget(usd("5"), usd("5.25")).getMessage,
      "Over budget: US$5.25 of US$5 spent",
    )
