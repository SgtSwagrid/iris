# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is Iris, a Scala 3 library giving one provider-agnostic interface to the APIs of large language models, with
adapters for Anthropic, OpenAI and Google Gemini, built on Cats Effect, sttp and Circe. It is still a prototype.

It is one module in `com.alecdorrington.iris`, cross-built for the JVM and Scala.js, built as four public layers (the
user's design, 2026-11-04, written up at https://claude.ai/artifact/NPwB9TdKkKwjNzpgVJ57Bb), each a service made from
the one below with a request, a response, a configuration and errors of its own:
- `api` (`ApiService`, `ApiRequest`, `ApiConfig`, `ApiError`): one exchange of a provider's own JSON, made by
  `ModelProvider.api`. `HttpApi` implements it over HTTP for every provider, as its `ProviderApi` says (credentials,
  origin, refusals, Gemini's `alt=sse`), streamed requests through `Streaming`'s exchanges; `Retrying` (`retrying`) is its
  middleware, applied by `ModelProvider.api` but over a backend that cannot stream.
- `prompt` (`PromptService`, `PromptRequest`, `PromptResponse`, `PromptEvent`, `PromptConfig`, `PromptError`): one
  exchange of text, made by `model.prompt`. `ProviderPrompt` implements it over an `ApiService`, writing bodies and
  reading responses as the model's `ProviderApi` says, through the internal `Delta`, which it gathers into
  `PromptEvent`s, pricing the response at its end. `metered` and `Budget.limits` (`limitedBy`) are its middleware.
  `TokenCounter` (`model.counter(api)`) stands apart, as counting is no prompt.
- `query` (`QueryService`, `QueryRequest`, `QueryResponse`, `QueryConfig`, `QueryError`): a value of any type with a
  `Schema`, made by `prompt.query`.
- `agent` (`AgentService`, `AgentRequest`, `AgentResponse`, `AgentConfig`): a value with tools run, made by
  `query.agent(tools*)`. A query (`Querying`) reads a type holding no stream once its response is whole
  (`Reading.whole`) and any other as it arrives (`Unfolding`), its schema prompt added once per conversation
  (`Reading.opening`), and gives the conversation as the model saw it last (`QueryResponse.conversation`). An agent
  (`Agency`) runs rounds over `query.send` (`Rounds`): the tools in its `Toolbox` run, the query sent again going on
  from that conversation with their results, each round's query attempting afresh; a stream with tools comes
  through `emit` (`Emitting`, below).
Every service has one abstract method: `send` for `query` and `agent`, and `stream` for `api` and `prompt`, whose
responses hold no type to carry a stream, where `send` is `stream` gathered, forcing `streamed = false`. A request
holds the one below's (`agentRequest.query.prompt.conversation`), and every `send` takes `into[ThatLayersRequest]`
with conversions in the request's companion from text, a conversation and lower requests. A middleware is a function
from a service to one of its layer (`PromptService` and `ApiService` being single-abstract-method traits, a lambda is
one). Configuration (`XConfig`) is what a service is built with, every field defaulted, and a request's `Option`s
override it; nothing is nested as `Options`. Every layer's limit on requests to the one below is `maxIterations`
(`ApiConfig`'s tries, `QueryConfig`'s attempts), and each response lists them as `iterations`. Callers query for a
value of any type with a `Schema`, `String` included, as a uniform `QueryResponse[F, A]`: its `value` as soon as all
but its streams is read, and its `iterations`, an `F[List[PromptResponse]]` of every response the query took
(attempts, tool and `emit` rounds), once the last ends; the response is their summary, totalling them (`usage`,
`cost`). A query of a type holding no stream sends whole (`Schema.gradual` false); every other streams. A service
over a backend that cannot stream answers a streamed request whole, reading its SSE body at once, so it still answers
a type holding a stream, all at once. A query needs `Async`, as it reads a streamed response in a fiber of its own
(unscoped, by the user's choice: read to its end once the value is given, unless `QueryResponse.cancel`, which the
user asked for, cancels that fiber); tests in `SyncIO` ask through `Fixtures.askWhole`, which is `Rounds.run` over `Reading.whole`
over a prompt service's `send`. Each `ModelProvider` has one `ProviderApi` object (`AnthropicApi`, `OpenAiApi`,
`GeminiApi`, sharing `JsonHttp`), which tells `HttpApi` and `ProviderPrompt` all they need to know of it: its paths,
request bodies, credentials, the conversations it refuses, how its responses read, and how it counts tokens.
Anything provider-specific goes there, never in the services. `Model.fromEnv`, `ApiConfig.fromEnv` and
`PromptConfig.fromEnv` read `LLM_*` and the providers' `*_API_KEY` variables (each `from` through a lookup of the
host's), `PromptService.fromEnv` all three, with the token limit when `LLM_MAX_TOKENS` is unset a parameter, for
hosts whose responses run longer. A key not configured is read from the provider's variable when its `ApiService`
is made, and a request with none fails with `ApiError.Misconfigured`.

- Names are plain and of one sense (the user's 0.5.0 naming pass): never a name as readily taken for a verb as for
  what it names, as `Reply` and `Answer` were (`PromptRequest`, with its prefix, reads only as a thing), nor "assistant" (a message's owners are the user, the model and the tools, each provider naming them as
  its API does). Public types take their layer's name (`ApiService`, `PromptRequest`, `QueryResponse`, `AgentConfig`;
  the layers were named `api`, `prompt`, `query` and `agent` by the user, who rejected `model`, read as a data model,
  and `wire`, `text`, `structured`), never `Llm`, and never a name sttp has too (`Response`, `RequestOptions`), as hosts
  import `sttp.client4.*` beside Iris. A conversation's messages are said by `userSays`, `modelSays` and `toolSays`. `Message.Model` hides the
  `Model` type under `import Message.*`, so owners are written `Message.Model` and `Message.User`.
- Clients are stateless: no session identity reaches the provider, and a conversation's whole history travels with
  each `Conversation`. Keep it that way.
- Every `ProviderApi` must normalise what it returns: the reply text, a `StopReason` (unknown labels kept as `Other`) and
  token usage in `PromptResponse`. An unsuccessful response is a refusal for now, `ApiError.RateLimited` (`429`)
  or `Overloaded` (`503`, Anthropic's `529`), or a failure for now, `Transient` (`408`, `409`, `500`, `502`,
  `504`, as the providers' SDKs retry them, a review's request), each with the wait the provider asked for, or
  else `ApiError.Unsuccessful` (status and body), as `Refusal.of` says; an unreadable one is `ApiError.Malformed`;
  a request no answer comes to within `ApiConfig.timeout` is `ApiError.TimedOut`, such as one whose reply
  takes too long to write; other transport failures are sttp's own. Each `ProviderApi` reads its own wait
  (`retryAfter`: `retry-after` in whole seconds for all; OpenAI's `retry-after-ms` first; else the reset of a
  limit whose remaining header is `0`, Anthropic's an RFC 3339 time, read by hand through `ProviderApi.utc`
  as JavaScript has no `java.time`, OpenAI's a Go duration; Gemini's `RetryInfo.retryDelay` in the body), and
  says which refusals waiting cannot lift (`lasting`: Anthropic's `enforced_spend_limit_reached`, OpenAI's
  `insufficient_quota` and spent credit or limits by type or code, Gemini's `QuotaFailure` of a `quotaId`
  holding `PerDay`, as its free tier's), which stay `Unsuccessful`. The `x-should-retry` header (undocumented,
  but the providers' SDKs heed it) outweighs status and body alike: `true` makes any status one to send again
  (`Transient`, or `RateLimited` or `Overloaded` by its status), `false` none. Anthropic's streamed
  `error` event is read as a refusal of its type's status (`overloaded_error` as `529`, `api_error` as `500`); OpenAI's and Gemini's
  streams are not read for errors yet. Waits are clamped (`Refusal.nanos`) so that adding them never
  overflows.
- The JDK's client counts a request's read timeout only until its reply begins, so `ModelProvider.api` wraps the backend
  it makes in `Deadlined`, which gives each exchange that long whole, body included, and `Streaming.bounded` gives each
  next part of a stream that long. Neither may wait for the read it gives up on, which cannot be cancelled: sttp
  reads a body in `F.blocking`, and fs2's reactive-streams subscriber waits for a stream's next part in `F.async_`.
  So `Deadlined` uses `timeoutAndForget`, never `timeout` or `timeoutTo`, and `bounded` reads the stream on a fiber
  of its own, timing out each take from it and cancelling that fiber without waiting for it (as `timeoutOnPull`
  would wait). What is given up on may never end, as a socket the server closed can leave the read blocked, so each
  stall may hold a blocking thread (a reply) or two fibers (a stream, unless its exchange is aborted, below) for as
  long as the backend lives, and a reply arriving after its request was given up on is read and dropped; both are
  the price of never hanging the caller.
- A streamed reply given up on (`QueryResponse.cancel`, a stream dropped early, a timeout or a failure) must close
  its connection at once, or the provider goes on writing, and charging for, a reply nobody reads, as Code Switch saw
  of a cancelled one. sttp's backends do not: `FetchFs2Backend` cancels a body's reader only once it is read to its
  end or fails, never when dropped, and aborts its own `AbortController` only until the reply begins; and both it
  (`fromFuture` of `reader.read()`) and the JDK's (fs2's reactive-streams subscriber, in `async_`) wait for each part
  uninterruptibly, so a cancel waits for the next part, and for good where the reply is silent. So `Streaming`
  streams each reply through an `Exchange` of its own, a backend and an `abort`, which `Stream.bracket` aborts once
  the stream ends, however it ends (after the body's end it does nothing, so a connection read to its end is reused),
  and which `Async.cancelable` aborts where the request is cancelled before its reply begins. `Platform.backends`
  makes them: in JavaScript, a `FetchFs2Backend` for each reply whose `customizeRequest` gives the request a signal of
  Iris's own, which sttp's own still aborts, and which keeps every request it makes for as long as it lives, as Node's
  `fetch` (undici) has a request follow its signal only through a weak reference to the request's own controller, so
  a request nobody held, collected while its reply was read, could no longer be aborted (`AbortSuite` failed now and
  then on a busy machine, main too; `CollectionSuite` collects mid-reply, as Node runs JS tests with `--expose-gc`,
  `Test / jsEnv` in `build.sbt`); on the JVM, one JDK client for every backend (built as sttp builds its
  own, and shut down on release with `shutdownNow`, which sttp's own never did: `close` would wait for requests given
  up on, which may never end; only from Java 21, so it is called only there), each reply's through an `Abortable` wrapper, whose `AbortableBody` cancels the body's subscription, closing
  its connection, and ends the body with an error, freeing the read. A host's backend (`ModelProvider.api(config,
  backend)`) aborts nothing of Iris's (`Exchange.abort` is `unit`). `AbortSuite` checks each way of giving up against
  a local server on each platform (the JDK's sockets, Node's `http`), which notes when the client closes its
  connection, while it writes nothing.
  `ModelProvider.api` over a plain `Backend` takes it as it is, and needs only `Sync`, as the shared tests run in
  `SyncIO`.
- Models are `Model` cases, never strings: each names the provider serving it (a prompt service's is its model's)
  and its API `id`, which `Model.parse` reads back. A model Iris does not list is a `Model.Other`,
  made only by `Model.of(provider, id)` (so that a listed id is always its listed case, and a blank one none, and
  Gemini's `models/` is dropped) and read as `provider:id`, so that a bare name must still be a listed one; list a new model as a case of its own. What one provider's models differ in
  (Anthropic's sampling and prefill, OpenAI's highest reasoning effort) is kept in its `ProviderApi` (`AnthropicApi`,
  `OpenAiApi`), as sets of the older cases that differ from the newest, so that `Other` is taken to be as new as the newest. A prompt service prompts its own model alone: no setting names another,
  so a request can never reach a provider that does not serve its model. `ModelProvider.defaultModel` must stay a `def`: as a
  constructor parameter, the two enums would each read the other while it is still being made, and see `null`.
- A set but unrecognised `LLM_PROVIDER` or `LLM_MODEL` means unconfigured (`None`), never a fall back to key inference.
- The library does no throttling or logging of its own; those are the host's middleware. It retries only two
  things: a query asks again after a response it cannot read, and an `ApiService` made by `ModelProvider.api` (but
  over a backend that cannot stream) sends again a request refused or failed for now (`RateLimited`, `Overloaded`,
  `Transient`), as its `ApiConfig` (`maxIterations`, `backoff`, `patience`) says, through `Retrying`, the api
  layer's middleware, so that a host's `Budget` or meter on the prompt layer sees one request. Alec agreed (2026-11-03, for Code
  Switch's many tabs) that backing off from providers' limits belongs in Iris, as the providers' own SDKs do it.
  It waits as asked plus up to `backoff` at random, else `backoff` doubling, less up to half at random, and
  never past `patience` in all, then gives the last refusal, with the wait asked for, for the host's user. Every
  request is a stream there, a whole one of one element, so a request is sent again only while it has emitted
  nothing (a `Ref` set by its first chunk; `Stream.handleErrorWith` closes the failed attempt's scope, aborting its
  exchange). Events that only open a stream (`ProviderApi.opening`: Anthropic's `message_start` and
  `ping`) are held back until another comes (`HttpApi.held`), so that a refusal right after them, before any text, is
  still sent again, and a streamed `error` event fails the api's stream (`ProviderApi.failure`), below `Retrying`. An `ApiService` over a plain `Backend` needs only `Sync`, so cannot wait, and sends each request once. Its tests (`RetrySuite`) run in virtual time through
  cats-effect's `TestControl` (`cats-effect-testkit`, a test dependency), whose `realTime` starts at 1970, and
  pass `Retrying` a fixed draw. A transport failure or `TimedOut` is not sent again, as a request given up on
  may have been answered, and charged for.
  `PromptService.metered` (the user approved it for Code Switch's spending total) runs a host's function on each
  response's `Usage` and `cost` at its `PromptEvent.Ended`: once per attempt and round of a query or an agent,
  before it is read, and once for `warmCache`, `send` and `warmCache` being `stream` gathered (one stopped before its
  end reports nothing). The one policy Iris offers is opt-in: a `Budget` (the user asked for USD limits per session),
  a `Ref` of what was spent shared by every prompt service it `limits`, each refusing requests over `metered`, which
  charges them. It refuses a
  request, or a stream before it starts, once the limit is spent (`PromptError.OverBudget`), then charges each reply's
  `cost`, so requests in flight, a running stream among them, may take it past the limit (the user chose this soft
  limit, which in-flight requests may overshoot, never reset); a reply with no cost fails (`PromptError.Unpriced`)
  after the provider has charged for it, which is documented, not prevented (the user chose this over a flag
  saying whether a service can price, by which `limits` would refuse up front). Counting tokens is no prompt, so
  is never refused. Each tool round and `emit` round of an agent is a prompt of its own, so is metered and
  checked: a `Budget` is what bounds the tool loop below by what it spends. Services themselves stay
  stateless. Iris runs a tool only where the host hands an agent one with its implementation (`RunnableTool`), which
  the user chose, as handing it over says it may run: the agent then runs any the model asks for alone (`Toolbox.runs`), tells
  it their outputs and asks it to go on (`Rounds.onwards`), each round's query with `maxIterations` attempts of its
  own. The rounds are as many as the model asks for unless the agent's own `maxIterations` (`AgentConfig`, or
  `AgentRequest` for one) caps them, as the design gives every layer one: the last round's response is then given
  with no value, its calls unrun (`Rounds.run`), and a stream through `emit` fails (`Emitting.called`). Uncapped is
  the default, as the user chose to bound a runaway loop by what it spends, through a metered prompt service, a
  count of rounds saying little of cost. `PromptRequest.tools` still offers tools for the host to run, reported in `PromptResponse.toolCalls`; a
  reply asking for any of those ends the ask with no value. Where tools are run and the answer is itself a
  stream (`Emit.element`), its elements come through a tool of Iris's own, `emit`, one call each (wrapped under
  `element`, any `$defs` hoisted), so the model can call tools between them: measured on Haiku 4.5, it batches
  emits per reply and interleaves lookups as told. Emit is the agent's tools, as the design has it: each round is a
  query for `List[E]` instructed by `Emit.prompt` (`QueryRequest.instructions`), and its calls are read in order once
  its response ends (`Emitting.turned`), each element fed then, so a stream's elements arrive a round at a time, not
  as each call arrives (the user's design; no host streamed with tools). In the reply giving its last element the model calls a
  second tool, `done` (the user chose a tool of its own over a flag on `emit`), and that reply ends the stream
  with no request after it (`Turn.ends`), unless it also asks for another tool or has an element refused,
  when `done` is told it was not taken (`Emit.unfinished`) and the stream goes on. A reply calling `done`
  with no element emitted has its text read as a whole query's is (`Reading.read`), so that elements written as
  JSON instead are kept. Measured on Haiku 4.5 and 5.5 (2026-10-31, 16 runs), `done` came with the last emit
  every time, never early, sparing the request each stream otherwise ends with to hear the model stop. `emit` takes one element per call, by the user's choice: a call reaches Iris only
  whole, so a list would hold its elements back, and models already batch emits as parallel calls. An
  unreadable element is told back, the reply's later emits refused unread (`Emit.skipped`) so that elements
  keep their order, and the model emits again from it, up to `attempts` times in a row per element. What
  each call is told is kept aligned with the calls (`Turn.told`), never by id, which Gemini repeats. A
  model writing JSON instead is read as its round's query reads any value, asked again where it cannot be;
  once elements have come, a response asking for nothing ends the stream, read once (`maxIterations` 1), its
  failure to read caught where it stopped as the model meant. A response cut off or stopped still feeds the
  elements of its whole calls before the stream fails, and a failure carries every round's responses. Tools for the host to run are refused beside `emit`
  (`Emit.unclaimed`), as an answer already given could not end at their calls, as are tools named `emit` or
  `done`. Gemini refuses a tool whose object schema has no properties, so `GeminiApi` declares one taking
  nothing (`properties` empty, and `additionalProperties` absent or `false`), as `done`, with no `parameters`;
  a map's schema, or one with no `properties` at all, is sent as it is.
  Each request after a round (and any correction after one) closes with a breakpoint of Iris's own
  (`PromptRequest.cacheAll`, added by `ProviderPrompt` as it is sent, so the conversation an agent goes on with holds
  none and the mark moves on), so
  it reads the earlier rounds from the cache and writes only its own, where N rounds once cost O(N²) uncached input.
  Only the newest end is marked, as Anthropic finds the previous write itself, up to 20 blocks back, a run of
  tool calls or of results counting as one. It goes after the results, as the schema prompt lies before every round
  in the opening message: a round's breakpoint caches it, harmlessly, as it is the same throughout an ask.
  Anthropic bills a write for all past the highest cache hit, so the first request after the opening writes all
  past the host's last breakpoint (the whole prompt where there is none), and the last request's write is never
  read. So with no host breakpoint a one-round ask pays 25% more on its second request's whole input, and the trade
  pays only where another request follows more than about 28% of the time (0.25 / 0.9); a host breakpoint after the
  fixed prefix keeps the writes to the rounds (README says so).
- A `MessagePart.ToolCall` goes back to its provider as the reply gave it. Gemini may give a call no `id`, so the tool's
  name stands in, and is never sent as one (`GeminiApi.issued`), on the call or its `functionResponse`. Gemini puts a
  `thoughtSignature` on the part beside a `functionCall` (the first of calls made together), which its thinking
  models need back on that part (else `MISSING_THOUGHT_SIGNATURE`), so it is kept, opaque, as `MessagePart.ToolCall.signature`,
  which other providers ignore. A signature on text is not kept, as `PromptResponse.text` joins the parts, and Google requires
  one back only with a call.
- A `MessagePart.CacheBreakpoint` ends a prefix worth caching and says nothing to the model. Anthropic is told of one by
  `cache_control` on the block before it (in its message, else the previous message's last, else the system message),
  marking at most four blocks per conversation, counted as sent (breakpoints in a row mark one), else refused, but for
  breakpoints closing a conversation (its last parts, which only an ask's rounds and `warmCache` write), marking its last
  block only where the others leave room (`AnthropicApi.placed`). So an ask's own never crowd out the host's nor get
  a conversation refused: where the host's mark four, rounds go unmarked, the host's choice winning. OpenAI and Gemini cache
  prefixes unasked and are sent nothing, so their APIs drop them (`Message.withoutBreakpoints`). `PromptService.warmCache` sends
  `Conversation.cachePrefix` asking for no tokens, which `ProviderPrompt` raises to the least the provider allows
  (`ProviderApi.leastMaxTokens`: Anthropic `max_tokens: 0`, others 1), an ordinary request, so middleware sees it
  as any other. `cachePrefix` always ends with the user saying something:
  breakpoints left alone in the last message move back to what came before, and a prefix ending at the system message
  or the model's turn is followed by a user message of `.`.
- `Usage` is seven counts, none including another, so that each can be priced alone (rates per count are planned):
  uncached, cache reads, cache writes, uncached and cached audio, visible and thinking. Every provider has every
  count, `0` where it reports none or never charges apart (Anthropic, and OpenAI from GPT-5.6, charge for cache
  writes, Gemini not; only Gemini reports audio, by `promptTokensDetails`/`cacheTokensDetails`, and prices it higher
  on some models; where it gives no `cacheTokensDetails`, its cache is taken to hold audio in the prompt's share).
  Each `ProviderApi` splits its own: Anthropic's `input_tokens` leaves the cache out and its `output_tokens` holds
  `output_tokens_details.thinking_tokens`; OpenAI's prompt holds `cached_tokens` and `cache_write_tokens`, and its
  completion `reasoning_tokens` (its Responses API splits writes by lifetime, 30 minutes or 12 hours, at different
  rates, but Iris uses Chat Completions without `prompt_cache_options`, so one write count is enough); Gemini's
  prompt holds its cache, and `thoughtsTokenCount` lies outside `candidatesTokenCount`.
  Gemini leaves out every count of `0` (protobuf's JSON), so a count left out is `0`, never unknown, and a reply
  with no usage at all has `Usage()`: `PromptResponse.usage` and `PromptEvent.Ended.usage` are never optional (the user chose zeros
  over `None`). `inputTokens`, `cachedTokens` and `outputTokens` are totals of the counts, the whole prompt, the part
  of it read from the cache, and the whole reply. Streams count too: Anthropic's start counts the request and its
  stop the reply (taking what it leaves out from the start), and OpenAI's usage comes in a chunk of its own after
  `finish_reason`, so its `PromptEvent.Ended` comes when the stream does.
- Each `ProviderApi` lists its models' `Rates` (`rates(model, at)`), `Usd` per million tokens on the standard tier,
  copied by hand from its pricing page (no provider serves prices by API, so they are listed rather than fetched
  from a third party's catalogue), with any change the provider has announced from its day (`ProviderApi.utc`,
  midnight UTC, Iris's assumption as Google names no time zone, by hand as JavaScript has no `java.time`).
  GPT-5.6 Sol's rates are a promotion "at least through November 21, 2026" with no later price given: a run of
  `/update-iris` after that day re-checks them. A long-prompt threshold counts the whole prompt, cache included, as
  Anthropic documents; OpenAI and Google do not say, and are taken to count alike. Every listed model must have rates (`RatesSuite`
  checks), so a new model needs its prices. A rate per `Usage` count: a count charged nothing apart is charged as
  its kind (cache writes as input on Gemini and OpenAI before GPT-5.6, audio as input but on Gemini models
  pricing it apart, thinking as output), and a long-prompt tier is `Rates.Above`, which prices the whole request
  once its `inputTokens` exceed it (Claude Haiku 5.5 over 100K, GPT-5.6 and later over 272K, Gemini 2.5 Pro over
  200K). Figures go through `ProviderApi.usd` and derived ones are worked out in `BigDecimal`, never `Double`.
  `ProviderPrompt` prices each response as it ends, by the effect's `Clock`, at `PromptConfig.rates`, else
  those listed, so `PromptResponse.cost` is per request, summed over a query or an agent only where every response
  has one, and `None` for an `Other` model with no rates configured. Iris prices nothing it does not send: batch,
  priority or fast mode, data residency, server tools. `/update-iris` keeps the rates current.
  Money is a `Usd` (an `amount: BigDecimal`), never a bare number, and Iris converts no currency, as every provider
  prices in dollars: a host billed in another converts at its own rate (the user chose this over a general money
  type, or conversion within Iris).
- Providers run adjacent text blocks together (Anthropic was measured to: two blocks count exactly as their
  texts concatenated), so every `ProviderApi` serialises a message's parts through `Message.separated`, which opens
  with a blank line each text part following another with only breakpoints (or empty text) between them. A part's
  text depends only on the parts before it, never after, so a cached prefix is sent alike whatever follows it.
  OpenAI separates after taking tool calls and results out of a message, as they then no longer lie between its
  texts. Callers never write a separator of their own between parts.
  `JsonHttp.plain` joins parts already separated, and `Message.text` joins text parts with the same blank line.
- `PromptRequest.effort` (`Effort`, `Low` to `Max`) is sent as Anthropic's `output_config.effort`, OpenAI's
  `reasoning_effort` (`Max` as `max`, or as `high` on the models in `OpenAiApi.cappedAtHigh`, such as `gpt-5`) and Gemini's `generationConfig.thinkingConfig.thinkingBudget`
  (fixed amounts within every thinking Gemini model's range). Iris never chooses one, and a model that cannot take one
  refuses the request; retrying at a lower effort is the host's to do.
- `QueryService.send[A : Schema]` reads every type one way, `String` included, and always gives a
  `QueryResponse[F, A]`: `value: Option[A]`, `None` where the model asked for a tool, and `iterations`, every
  response the query took, the last the one the value was read from. Tool calls count only under `ToolCall`,
  `Completed` or `StopSequence` (`Reading.calledTools`), never under a refusal or the token limit. Never read one type differently from another, as
  0.2's `Answer` type class once did, nor give some types a different result: the user wants every ask
  uniform. The provider's own response is a `PromptResponse`, which a prompt's `send` and `warmCache` give. A query is built on
  its prompt service alone, provider-agnostic, passing `Reading.whole` its `send` as a function: the schema is asked for in words closing the
  last user message (never the system message, so a cached prefix is sent alike), not through a provider's
  native structured output. It stays after every breakpoint of the host's, so is never cached with the host's
  prefix (only within an ask, by a round's, above), by the user's choice on the
  evidence of a live measurement (2026-10-28, Claude Haiku, Sonnet and Opus 5.5, 144 asks over pieces of 100 to
  2,800 tokens). Moved before the varying content, at the last breakpoint, it was followed as reliably (every
  ask read at the first attempt either way) and saved about 600 input tokens a request, some 3–5% of a long
  review's cost, but it would lose a continuing conversation's cached prefix (the schema is not kept in its
  history), make `warmCache` take the type, and shifted Haiku's reviews somewhat. Revisit only on new evidence.
  An unreadable reply is answered with every decoding failure (decoders accumulate)
  and asked again, `attempts` requests in all, then `QueryError.Unreadable`, carrying every response. A reply asking
  for a tool, by its stop reason or its calls (OpenAI says `stop` for a forced call), is given at once with no
  answer. One stopped at `MaxTokens` or an `Other` reason, or blank, fails at once, before any reading, as what
  parses of a cut reply may be cut (`4` of `42`): an identical conversation is never resent. A stop sequence is read, and
  asked again if it can't be, the model told so, as only Anthropic reports one and others would read it as
  complete. A conversation `Conversation.unanswerable` refuses (no messages, a silent one, or none of the user's) is sent
  uninstructed, so `JsonHttp.refuseUnanswerable` refuses it with the provider's name before any request; a conversation
  ending with the model's message gets the schema prompt in a user message after it, so the model answers afresh
  (the user chose this over continuing a prefill). `QueryResponse.usage` totals every response's. `UnreadableResponse`'s message carries neither the responses nor `detail`, which is what
  the model was told, circe's own failures, which quote the reply's values, so hosts may log it. JSON is read
  from the whole reply, then its last fence, and nowhere else (the user chose this over finding JSON amid prose),
  so a fence inside a JSON string is never taken.
- A `Message` is one case per owner (`Message.User`, `Message.Model`, `Message.Tool`; the user chose this over a
  `Role` beside any parts), each holding only that owner's parts: `UserPart` (text, media), `ModelPart` (text, tool
  calls) and `ToolPart` (tool results), sealed traits the part case classes extend, `CacheBreakpoint` all three. What
  every owner's message does alike goes through `Message.edited`, a polymorphic function over its parts given the
  breakpoint, the one part every owner writes. The tools' results are their own message (`toolSays`), never the
  user's: Anthropic and Gemini have no role for them, so a tool message and any user message after it are sent as
  one user turn, results first (`JsonHttp.turns`, which runs messages of one role in a row together before
  `Message.separated`), and OpenAI sends one `tool` message per result. Gemini's count keeps its system message a
  user content of its own, as before. A tool call's `arguments` are the text the model wrote, never parsed by a
  provider reading a reply (a streamed call's fragments are joined, `{}` where none came): what reads them, as
  `RunnableTool.of` and emit do, tells the model when they are no JSON. Anthropic and Gemini take them as an
  object, so refuse a call whose arguments are none before any request (`JsonHttp.refuseUnreadableArguments`).
- What goes in is a `Content[A]` everywhere: `String` as text, a value with a `Schema` as its
  JSON, else an `Encoder`'s JSON, and a type's own given `Content` ahead of all (being monomorphic), each written as
  text (`MessagePart.Text`); a part goes in as itself where its message holds its kind, and nowhere else. `NotGiven`s
  keep the givens apart, `Schema` winning (a derived one too), so a value is said as the schema the model is told
  describes it. `Conversation.userSays` and a conversation's `userSays`/`modelSays` take `into[UserPart]*` and `into[ModelPart]*` (Scala 3.9's
  `scala.Conversion.into`), so values of any kinds mix in one call, each converted by `UserPart.fromContent` or `ModelPart.fromContent`, an
  `inline given` summoning its `Content` with `summonInline`: an `into` parameter needs no
  `scala.language.implicitConversions` where it is called, which a plain `Conversion` would (a feature warning,
  fatal in CI), and the inline summon reports `Content`'s own `@implicitNotFound` at the argument, where a
  `using` clause would leave a bare type mismatch. A spliced list must already be of parts (`values.map(MessagePart.of)*`).
  `fromContent` lies in `UserPart`'s and `ModelPart`'s implicit scope, as it must for a caller's `into` call to find it, so it converts
  wherever one is expected: `val p: UserPart = "text"` compiles with a feature warning (an error under `WERROR`)
  where it was a type mismatch. Only `into` parameters take a value unwarned.
  No type text may be (`NotGiven[String <:< A]`) is said as JSON: `List("a", 1.5).map(MessagePart.of)` infers
  `String | Double`, which has a schema, and must still be refused. A single-turn prompt is `Conversation.userSays(content*)`, the
  companion's beside the instance's, never an overload of `Conversation.apply`, which would capture `Conversation(messages)` (the
  case class's own `apply` needs the default `instructions`, so loses). `Content`'s givens for a schema and an encoder
  refuse parts (`NotGiven[A <:< MessagePart]`), so that a part of another owner's kind, as a tool call given to
  `userSays`, is refused rather than said as JSON.
- Media types are `MediaType` cases, as models are `Model` cases: `MediaType.Listed` (the pictures and PDFs
  more than one provider takes) and `MediaType.Other`, made only by `MediaType.of` (lower case, parameters
  dropped), so that a listed name is always its listed case. Anthropic and OpenAI each list the media they take
  (`media`) and refuse any other with `PromptError.Unsupported` through `JsonHttp.refuseUnsupportedMedia`; Gemini takes many
  more (audio, video) and is sent any, judging for itself. OpenAI's chat completions also take PDFs as `file`
  parts, which Iris does not send yet (unverified: whether `file_data` needs a `data:` prefix and a
  `filename`).
- `Schema` is one type class of schema, encoder and decoder, so the three cannot disagree; never derive one
  from a circe codec, which says nothing of its JSON (the user chose a compile error over an any-JSON schema).
  Each schema holds its `parts` and an `outline`, its own JSON given how to write each part, from which `rendered`
  finds recurring types for `$defs`; there is no enum of shapes (the user chose this). Any case class, sealed
  trait or enum is derived wherever it is needed, by `DerivedSchemas`' `derivedSchema`, but never over a circe
  `Encoder` of its own (`NotGiven[Encoder[A]]`), which says how it is written already, so `Content` says it as
  that writes it (review found auto-derivation sending `NonEmptyList` as head and tail); `derives Schema` derives
  one anyway. Any union or literal type is given by `alternativeSchema` (the `Alternatives` macro); a trait `Schema` extends, so every given of `Schema`'s
  own outranks them by inheritance, as does a type's own given in its companion by being monomorphic. Both are
  `transparent inline`, as is everything they expand (`derived`, `summoned`, `Alternatives.of`): only a
  transparent expansion fails during implicit search, so that a type with a field of no schema has none
  (`NotGiven` holds, and `Content` falls back to its `Encoder`), where a non-transparent one would claim every type
  and fail later. Fields and cases are summoned by `summonAll`, a compiler intrinsic, in one inline level: each
  nested expansion counts towards `-Xmax-inlines` (32), so a recursive helper per field reached it at about eight
  nested types. `derived` ties recursion with a local `given Schema[A] = self` (marked `@unused`, which
  `-Wunused` flags where nothing recurs), so a recursive type needs no `derives`, and fields are summoned lazily
  so that it can be made before the fields referring to it; it renders a recursive type into `$defs`. Defaults
  come from the companion's public `$lessinit$greater$default$N` getters (`Defaults`; a private constructor's
  are skipped, as they cannot be called where a type is derived), which make a field optional and are read
  where it is left out, but are never shown in the schema, which must be the same on every ask to be cached
  (a default may be `UUID.randomUUID()`); an optional field is left out only where reading it back gives the
  same. A union is tagged, as a sealed trait is, under each alternative's name (short unless two clash, a
  literal's its value), as reading untagged JSON as the first alternative that reads it took a `Cat` for a
  `Dog`; only a union of literals alone is untagged, its values being distinct. Its alternatives must have
  distinct erased classes, as a value is written as the first whose runtime test it passes. Circe's `Json`
  has no schema, by the user's choice that the model is never told nothing. Constraints (minimum, pattern) stay in Scaladoc and `described` (the user
  chose this over annotations or Iron for now). A type is told apart
  by its `TypeName`, its full name with its type arguments applied, never by identity (a generic recursive
  type's given is a `def`, so it would never meet itself) nor by simple name (two `User`s, or `Tree[Int]` beside
  `Tree[String]`, would share a definition). A generic type is derived once for every argument, so the `TypeName`
  macro names its arguments at runtime from their own `Schema` instances. Definitions are shown by short
  name, in full only where two short names clash, in parentheses (`Tree(Int)`), which a `$ref` may hold. Cases of a sealed trait are
  externally tagged (`{"Circle":{...}}`), or just their names where none has fields. Descriptions come from
  Scaladoc through [Cadmus](https://github.com/SgtSwagrid/cadmus) (`Scaladoc.of`, rendered `plain`), whose
  comments the compiler keeps by default for sources compiled in the same run, as a `derives` clause's type always
  is (a type derived where it is needed elsewhere needs `-Xread-docs`, and under `-Xdrop-docs` there are none, which Cadmus gives as `None` from 0.1.1);
  a comment on a parameter is never kept, so fields are described by the class's `@param` tags, which `record`
  aligns with the `Mirror`'s labels. A field's words always come before its type's own description, never in
  place of it (the user wants the model to read both), joined as paragraphs, then any `described` words. `imap`/`emap` are inline so as to read the target type's. Test derivation from
  outside the package (`SchemaSuite` is in `com.alecdorrington.example`), as inline code reaches private
  helpers only through accessors.
- Unset optional fields are omitted one level deep, by `JsonHttp.objectOf`. Never deep-drop nulls: inside a
  tool's schema or a tool call's arguments, a null is a value.
- A streamed request has the same body as a whole one but for `stream: true` (`Settings.streamed`). Each
  `ProviderApi.deltas` reads the whole stream of events, remembering across them what it must (`zipWithScan1`):
  Gemini's whether a tool was asked for, as it may say so before it says it stopped, Anthropic's the counts it
  started with, and OpenAI's how it stopped until its usage comes.
- A query of a type holding an `fs2.Stream` (`Unfolding`) gives its `QueryResponse` as soon as all of `A` but its streams is
  read; each stream (not within a list or map, which are read whole)
  is then fed as its elements close. The user chose live streams in the value over snapshots of a partial value,
  knowing the costs: `Schema[Stream[F, A]]`'s encoder throws (a stream cannot be said without being run), nothing is
  asked again once handed over (a later failure fails the unfinished streams and `responses`: `UnreadableResponse` for a reply,
  a request's own error), and `F` must be the client's (the cast is unchecked). Tool calls stream as
  `Delta.ToolCall`, each once whole (Anthropic's and OpenAI's argument fragments gathered by a `Calling`), so a streamed
  ask takes tools as any does. The model is told a stream as an array, the same schema as a `List`. Each schema's `Arrival` says how it is read early (`Whole`, `Streamed`, or `Assembled` from
  members, by key or index, `ready` once those begun have settled, giving the streams still to come); fields are
  taken to be written in order (`Schema.awaited`), so an optional one absent before a begun one is left out, and
  required streams after the last begun are handed over before they begin (`Frame.coming`), so that every trailing
  stream of a value is live. A value is handed over before it is whole only while a member it is writing is open and
  read already (`Unfinished.open`): until a stream begins, a reply that cannot be read can still be asked again, and
  a union whose case is whole waits to be whole itself. So how a reply's parts arrive can decide whether a value is
  given early (a stream opening and closing within one part is never seen open); tests that mean a stream to be
  seen open feed it a character at a time. A given answer's reply is checked at its end as `verdict` checks one
  (`Unfolding.fault`): tool calls, a stop, the token limit or unended JSON fail it as `UnreadableResponse`. Once a value is handed over (`Frame.taken`), a member it
  knows arriving later, or a reserved stream never begun, fails the reply rather than be lost. `Unfinished` follows only brackets, strings,
  escapes, commas and colons
  (circe parses each value once whole, and the schema's own decoder reads it, so every value is decoded by the
  same code as a whole reply), standing in for each open stream with a placeholder `Json` found by identity
  through a `DynamicVariable` while decoding. A type with no stream is not read early, but at the end through
  `Reading.verdict`, as a whole query reads it. `Unfolding` drives it in a background fiber, keeping each stream's elements
  and end in a `SignallingRef` (never a `Channel`, which serves one reader), so a stream reads alike however often,
  as `Stream.emits` from a whole reply does.
- What differs between the platforms is in `Platform` alone, one per platform (`src/main/scalajvm`,
  `src/main/scalajs`): the backends `resource` makes (the JDK's HTTP client, or the Fetch API, and how each aborts a
  stream, above), and whether a
  request may come from a browser's page, for which `AnthropicApi` adds `anthropic-dangerous-direct-browser-access`.
  Everything else is shared, so use nothing the JS side lacks: no `java.util.Locale` (lower case by character, as
  `ModelProvider.parse` does), nor `java.time`. The tests run on both, so they run their effects in `SyncIO`,
  never `IO.unsafeRunSync`, which JavaScript lacks, or, where they need concurrency (`StreamingAskerSuite`), return
  `IO`'s `unsafeToFuture` with a timeout, which munit awaits on both; tests of one platform alone go in
  `src/test/scalajs` or `src/test/scalajvm`.
- `ApiConfig.toString` redacts the API key, so hosts may log a configuration. Keep any new secret out of `toString`
  and out of every error's message likewise.

See [README.md](README.md) for usage.

### Where this code lives

Iris is developed here: make every change in this repository, by pull request. The shared configuration (workflows,
Scalafmt, IDE settings, `project/plugins-common.sbt`, `project/plugins-library.sbt`, `LICENSE.md`, `CONTRIBUTING.md`)
is synced into it from [Scala Library Config](https://github.com/SgtSwagrid/scala-library-config) by
[GitHub Graph](https://github.com/SgtSwagrid/github-graph), which overwrites local edits, so change those files there.
`build.sbt`, `release.sbt`, `project/Dependencies.scala`, `project/plugins-scalajs.sbt`, `README.md`, this file and the
sources belong to the library.

Other projects use Iris as a dependency from Maven Central, never from source, so a change one of them needs reaches
it only through a release (below).

### Build

- The library is one `projectMatrix`, `iris` on the JVM and `irisJS` in JavaScript, published as `iris_3` and
  `iris_sjs1_3`, which `irisRoot` aggregates (publishing nothing itself, and documenting the JVM side only).
- The matrix pins `sourceDirectory` to this build's base, as sbt otherwise resolves a matrix's sources against the
  working directory, which is not this build's when another includes it.
- `irisRoot` shares the matrix's directory, so clears its source and resource directories alike: IntelliJ would
  otherwise count it among the owners of the shared sources and highlight them by its classpath, which has none of
  the library's dependencies (`Not found: fs2`).
- Sources sit in their packages' directories (`src/main/scala/com/alecdorrington/iris/`), with no `packagePrefix`,
  unlike the user's single-platform projects: IntelliJ's sbt import never gives a prefix to the module holding the
  sources the JVM and JS share (`iris-sources`), so every `package` line there was flagged on each fresh import.

### Releases

1. Land the changes on `main` by pull request. The README's installation line names no version (it reads
   `{version}`, pointing to the latest release), so a release leaves it as it is. Iris is still a prototype,
   versioned by early semver (`versionScheme`): while it is `0.x`, the minor version goes up for any change that can
   break a user's build or behaviour, and the patch version for anything else.
2. Publish a GitHub release of `main` tagged `v<version>`, with notes written for a user, as the earlier releases' are
   (the installation line, then what is Changed, Added and Fixed). `publish.yml` then publishes `iris_3` and
   `iris_sjs1_3` to Maven Central through `sbt-ci-release`, which takes the version from the tag, and `docs.yml`
   publishes the Scaladoc at [alecdorrington.com/iris](https://alecdorrington.com/iris/).
3. Once Maven Central serves the new version, bump it in each project using Iris, by pull request there. Their
   Scala Steward would otherwise propose it on its next weekly run. A version that breaks callers needs their code
   changed too, as README's *Upgrading* section says. For 0.3.0, as found in review: Themis passes requests on
   with `super.send` in `Throttle`, `Allowances` and `Recall` (whose replies are `Answer[Nothing]`), asks where it
   sent in `ComparisonService`, `EvaluationService`, `TaskImportService` and `TranscriptionService`, reading
   `value`, its `MaxTokens` branches (`TranscriptionService`, `EvaluationService`) become `Unreadable` cases,
   and its test fakes override the protected `send`. Codeswitch's `Translator` asks `ask[List[String]]` and drops
   its own array parsing and JSON-format prompt, and its `explain` takes `Unreadable`. For 0.5.0, Themis's
   `Allowances.cost` reads `response.usage` where it folded over it, dropping its guess of a token per character for a
   reply with none, and its tests (`AllowancesSuite`, `TaskEvaluationSuite`) make `Usage` by name, not `Some`; both
   projects take every rename of README's *Upgrading from 0.4* table, their wrappers overriding `send(request)` and
   their fakes `countTokens(request)`, and read an ask's `responses` (or its `usage` and `cost` totals) where they
   read the answer's own fields. Code Switch, still on 0.3, also needs `Async` where it asks (five helpers take
   `MonadThrow`) and 0.4's `Structured` as `Schema`. Themis also needs a `MediaType` for each `MessagePart.Media`, its
   own role names where it read `Role.code`, `warmCache(request)` in its overrides, and `Chat(instructions = …)` where
   it wrote `system = …` (nine places).

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).
- Document every public type and member: a summary, then `@param` for each explicit parameter,
  `@tparam` for each type parameter, and `@return` for any result but `Unit`, each one short
  sentence. Summaries read: types "A ...", values "The ...", Booleans "Whether ...", methods a
  third-person verb ("Sends ..."), never "Returns ...". Private members get a comment only for a
  non-obvious contract or gotcha, usually in one sentence.

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- Use the `gh` CLI to do the above.
