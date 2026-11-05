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

It is six modules under `com.alecdorrington.iris`, each an artifact cross-built for the JVM and Scala.js, all of one
version (the user's design, 2026-11-04, written up at https://claude.ai/artifact/NPwB9TdKkKwjNzpgVJ57Bb):
- `core/` (`iris-core`): the `prompt`, `query` and `agent` layers, conversations, `Usage`, `Usd`, `Rates` and schemas.
  No sttp, no models, no platform-specific code.
- `providers/http/` (`iris-http`): the `api` layer, what every provider shares over HTTP, `Platform`, and the
  `Provider` and `ProviderModel` traits.
- `providers/anthropic/`, `providers/openai/`, `providers/gemini/` (`iris-anthropic`, `iris-openai`, `iris-gemini`):
  each provider's object (`Anthropic`, `OpenAi`, `Gemini`) and its models (`AnthropicModel`, `OpenAiModel`,
  `GeminiModel`).
- `providers/` (`iris-providers`, its sources in `providers/src`): every provider, and `Providers`, which chooses one
  at runtime. Its tests are those of everything a provider takes part in; core's own are in `core/src/test`.

Each module has a CLAUDE.md of its own for what is particular to it. Packages follow the layers: the vocabulary they
share (`Conversation`, `Message` and its parts, `Usage`, `Usd`, `Rates`, `MediaType`) is in `com.alecdorrington.iris`,
then `.api`, `.prompt`, `.query` and `.agent`, a package per provider, and `.providers`. A source in a subpackage
opens with chained package clauses (`package com.alecdorrington.iris`, then `package query`), so that the root's
members need no import, and internals stay `private[iris]` across modules. The four layers are each a service made
from the one below, with a request, a response, a configuration and errors of its own:
- `api` (`ApiService`, `ApiRequest`, `ApiConfig`, `ApiError`): one exchange of a provider's own JSON, made by
  `provider.api`. `HttpApi` implements it over HTTP for every provider, as its `Provider` says (credentials,
  origin, refusals, Gemini's `alt=sse`), streamed requests through `Streaming`'s exchanges; `Retrying` (`retrying`) is its
  middleware, applied by `provider.api` but over a backend that cannot stream.
- `prompt` (`PromptService`, `PromptRequest`, `PromptResponse`, `PromptEvent`, `PromptConfig`, `PromptError`): one
  exchange of text, made by `model.prompt` (a `ProviderModel`'s). `ProviderPrompt` implements it over an `ApiService`,
  writing bodies and reading responses as the model's `Provider` says, through the internal `Delta`, which it gathers
  into `PromptEvent`s, pricing the response at its end. `metered` and `Budget.limits` (`limitedBy`) are its
  middleware. `TokenCounter` (`model.counter(api)`) stands apart, as counting is no prompt.
- `query` (`QueryService`, `QueryRequest`, `QueryResponse`, `QueryConfig`, `QueryError`): a value of any type with a
  `Schema`, made by `prompt.query`.
- `agent` (`AgentService`, `AgentRequest`, `AgentResponse`, `AgentConfig`): a value with tools run, made by
  `query.agent(tools*)`. A query (`Querying`) reads a type holding no stream once its response is whole
  (`Reading.whole`) and any other as it arrives (`Unfolding`), its schema prompt added once per conversation
  (`Reading.opening`), and gives the conversation as the model saw it last (`QueryResponse.conversation`). An agent
  (`Agency`) runs rounds over `query.send` (`Rounds`): the tools in its `Toolbox` run, the query sent again going on
  from that conversation with their results, each round's query attempting afresh; a stream with tools comes
  through `emit` (`Emitting`, in core's CLAUDE.md).
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
over a prompt service's `send`. Each provider is one object implementing `Provider` (`Anthropic`, `OpenAi`,
`Gemini`, sharing `JsonHttp`), whose `private[iris]` members tell `HttpApi` and `ProviderPrompt` all they need to know of it: its paths,
request bodies, credentials, the conversations it refuses, how its responses read, and how it counts tokens.
Anything provider-specific goes there, never in the services. `Providers.fromEnv`, `ApiConfig.fromEnv` and
`PromptConfig.fromEnv` read `LLM_*` and the providers' `*_API_KEY` variables (each `from` through a lookup of the
host's), `Providers.prompt` all three, with the token limit when `LLM_MAX_TOKENS` is unset a parameter, for
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
- The library does no throttling or logging of its own; those are the host's middleware. It retries only two
  things: a query asks again after a response it cannot read, and an `ApiService` made by `provider.api` (but
  over a backend that cannot stream) sends again a request refused or failed for now (`RateLimited`, `Overloaded`,
  `Transient`), as its `ApiConfig` (`maxIterations`, `backoff`, `patience`) says, through `Retrying`, the api
  layer's middleware, so that a host's `Budget` or meter on the prompt layer sees one request. Alec agreed (2026-11-03, for Code
  Switch's many tabs) that backing off from providers' limits belongs in Iris, as the providers' own SDKs do it.
  It waits as asked plus up to `backoff` at random, else `backoff` doubling, less up to half at random, and
  never past `patience` in all, then gives the last refusal, with the wait asked for, for the host's user. Every
  request is a stream there, a whole one of one element, so a request is sent again only while it has emitted
  nothing (a `Ref` set by its first chunk; `Stream.handleErrorWith` closes the failed attempt's scope, aborting its
  exchange). Events that only open a stream (`Provider.opening`: Anthropic's `message_start` and
  `ping`) are held back until another comes (`HttpApi.held`), so that a refusal right after them, before any text, is
  still sent again, and a streamed `error` event fails the api's stream (`Provider.failure`), below `Retrying`. An `ApiService` over a plain `Backend` needs only `Sync`, so cannot wait, and sends each request once. Its tests (`RetrySuite`) run in virtual time through
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
  `done`. Gemini refuses a tool whose object schema has no properties, so `Gemini` declares one taking
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
- A `MessagePart.CacheBreakpoint` ends a prefix worth caching and says nothing to the model. Anthropic is told of one by
  `cache_control` on the block before it (in its message, else the previous message's last, else the system message),
  marking at most four blocks per conversation, counted as sent (breakpoints in a row mark one), else refused, but for
  breakpoints closing a conversation (its last parts, which only an ask's rounds and `warmCache` write), marking its last
  block only where the others leave room (`Anthropic.placed`). So an ask's own never crowd out the host's nor get
  a conversation refused: where the host's mark four, rounds go unmarked, the host's choice winning. OpenAI and Gemini cache
  prefixes unasked and are sent nothing, so their APIs drop them (`Message.withoutBreakpoints`). `PromptService.warmCache` sends
  `Conversation.cachePrefix` asking for no tokens, which `ProviderPrompt` raises to the least the provider allows
  (`Provider.leastMaxTokens`: Anthropic `max_tokens: 0`, others 1), an ordinary request, so middleware sees it
  as any other. `cachePrefix` always ends with the user saying something:
  breakpoints left alone in the last message move back to what came before, and a prefix ending at the system message
  or the model's turn is followed by a user message of `.`.
- `Usage` is seven counts, none including another, so that each can be priced alone (rates per count are planned):
  uncached, cache reads, cache writes, uncached and cached audio, visible and thinking. Every provider has every
  count, `0` where it reports none or never charges apart (Anthropic, and OpenAI from GPT-5.6, charge for cache
  writes, Gemini not; only Gemini reports audio, by `promptTokensDetails`/`cacheTokensDetails`, and prices it higher
  on some models; where it gives no `cacheTokensDetails`, its cache is taken to hold audio in the prompt's share).
  Each `Provider` splits its own: Anthropic's `input_tokens` leaves the cache out and its `output_tokens` holds
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
- Each `Provider` lists its models' `Rates` (`rates(model, at)`), `Usd` per million tokens on the standard tier,
  copied by hand from its pricing page (no provider serves prices by API, so they are listed rather than fetched
  from a third party's catalogue), with any change the provider has announced from its day (`Provider.utc`,
  midnight UTC, Iris's assumption as Google names no time zone, by hand as JavaScript has no `java.time`).
  GPT-5.6 Sol's rates are a promotion "at least through November 21, 2026" with no later price given: a run of
  `/update-iris` after that day re-checks them. A long-prompt threshold counts the whole prompt, cache included, as
  Anthropic documents; OpenAI and Google do not say, and are taken to count alike. Every listed model must have rates (`RatesSuite`
  checks), so a new model needs its prices. A rate per `Usage` count: a count charged nothing apart is charged as
  its kind (cache writes as input on Gemini and OpenAI before GPT-5.6, audio as input but on Gemini models
  pricing it apart, thinking as output), and a long-prompt tier is `Rates.Above`, which prices the whole request
  once its `inputTokens` exceed it (Claude Haiku 5.5 over 100K, GPT-5.6 and later over 272K, Gemini 2.5 Pro over
  200K). Figures go through `Provider.usd` and derived ones are worked out in `BigDecimal`, never `Double`.
  `ProviderPrompt` prices each response as it ends, by the effect's `Clock`, at `PromptConfig.rates`, else
  those listed, so `PromptResponse.cost` is per request, summed over a query or an agent only where every response
  has one, and `None` for an `Other` model with no rates configured. Iris prices nothing it does not send: batch,
  priority or fast mode, data residency, server tools. `/update-iris` keeps the rates current.
  Money is a `Usd` (an `amount: BigDecimal`), never a bare number, and Iris converts no currency, as every provider
  prices in dollars: a host billed in another converts at its own rate (the user chose this over a general money
  type, or conversion within Iris).
- Providers run adjacent text blocks together (Anthropic was measured to: two blocks count exactly as their
  texts concatenated), so every `Provider` serialises a message's parts through `Message.separated`, which opens
  with a blank line each text part following another with only breakpoints (or empty text) between them. A part's
  text depends only on the parts before it, never after, so a cached prefix is sent alike whatever follows it.
  OpenAI separates after taking tool calls and results out of a message, as they then no longer lie between its
  texts. Callers never write a separator of their own between parts.
  `JsonHttp.plain` joins parts already separated, and `Message.text` joins text parts with the same blank line.
- `PromptRequest.effort` (`Effort`, `Low` to `Max`) is sent as Anthropic's `output_config.effort`, OpenAI's
  `reasoning_effort` (`Max` as `max`, or as `high` on the models in `OpenAi.cappedAtHigh`, such as `gpt-5`) and Gemini's `generationConfig.thinkingConfig.thinkingBudget`
  (fixed amounts within every thinking Gemini model's range). Iris never chooses one, and a model that cannot take one
  refuses the request; retrying at a lower effort is the host's to do.

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

- The library is six `projectMatrix`es, one a module (`irisCore`, `irisHttp`, `irisAnthropic`, `irisOpenAi`,
  `irisGemini`, `irisProviders`, each with its `JS` twin), made alike by `module` in `build.sbt` and published as
  `iris-<module>_3` and `iris-<module>_sjs1_3`, each named after its directory. `irisRoot` aggregates them,
  publishing nothing itself, and documents their JVM sides as one site.
- Each matrix pins `sourceDirectory` to its directory under this build's base, as sbt otherwise resolves a matrix's
  sources against the working directory, which is not this build's when another includes it.
- Core depends on `fs2-core` itself, at the version sttp's fs2 module brings, as without sttp nothing else gives it.
  `irisProviders`' tests depend on core's (`test->test`) for its example types (`Person`, `Colour`).
- Sources sit in their packages' directories (`core/src/main/scala/com/alecdorrington/iris/query/`), with no `packagePrefix`,
  unlike the user's single-platform projects: IntelliJ's sbt import never gives a prefix to the module holding the
  sources the JVM and JS share (`iris-sources`), so every `package` line there was flagged on each fresh import.

### Releases

1. Land the changes on `main` by pull request. The README's installation line names no version (it reads
   `{version}`, pointing to the latest release), so a release leaves it as it is. Iris is still a prototype,
   versioned by early semver (`versionScheme`): while it is `0.x`, the minor version goes up for any change that can
   break a user's build or behaviour, and the patch version for anything else.
2. Publish a GitHub release of `main` tagged `v<version>`, with notes written for a user, as the earlier releases' are
   (the installation line, then what is Changed, Added and Fixed). `publish.yml` then publishes every module's
   artifacts (`iris-core_3` to `iris-providers_sjs1_3`) to Maven Central through `sbt-ci-release`, which takes the version from the tag, and `docs.yml`
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
