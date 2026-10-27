# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is Iris, a Scala 3 library giving one provider-agnostic interface to the APIs of large language models, with
adapters for Anthropic, OpenAI and Google Gemini, built on Cats Effect, sttp and Circe. It is in beta.

It is one module in `com.alecdorrington.iris`, cross-built for the JVM and Scala.js. `LlmClient` is the public interface: callers `ask` a `Chat`
for an answer of a type (`Reply` whole, `String`, or any `Structured` type), and `send` is the protected request every
`ask` makes, which `JsonClient` implements and wrappers override (`LlmClient.Forwarding`, `mapK`). Each `LlmProvider` has one `ProviderApi` object (`AnthropicApi`, `OpenAiApi`, `GeminiApi`, sharing
`JsonHttp`), which tells the one `JsonClient` and the one `SseClient` all they need to know of it: its endpoints,
request bodies, credentials, the chats it refuses, how its replies read, and how it counts tokens. Anything
provider-specific goes there, never in the clients. `LlmConfig` holds a model (and so its provider), key, token limit and an optional base URL, and
`LlmConfig.fromEnv` reads them from `LLM_*` and the providers' `*_API_KEY` variables (`LlmConfig.from` through a
lookup of the host's), with the token limit when `LLM_MAX_TOKENS` is unset a parameter, for hosts whose replies run
longer.

- Clients are stateless: no session identity reaches the provider, and a conversation's whole history travels with
  each `Chat`. Keep it that way.
- Every `ProviderApi` must normalise what it returns: the reply text, a `StopReason` (unknown labels kept as `Other`) and
  token usage in `Reply`. An unsuccessful response is `LlmError.Unsuccessful` (status and body) and an unreadable one
  `LlmError.Malformed`, so hosts can tell rate limits from broken responses; a request no answer comes to
  within `LlmConfig.timeout` is `LlmError.TimedOut`, such as one whose reply takes too long to write;
  other transport failures are sttp's own.
- Models are `LlmModel` cases, never strings: each names the provider serving it (`LlmConfig.provider` is its model's)
  and its API `id`, which `LlmModel.parse` reads back. A model Iris does not list is an `LlmModel.Other`,
  made only by `LlmModel.of(provider, id)` (so that a listed id is always its listed case, and a blank one none, and
  Gemini's `models/` is dropped) and read as `provider:id`, so that a bare name must still be a listed one; list a new model as a case of its own. What one provider's models differ in
  (Anthropic's sampling and prefill, OpenAI's highest reasoning effort) is kept in its `ProviderApi` (`AnthropicApi`,
  `OpenAiApi`), as sets of the older cases that differ from the newest, so that `Other` is taken to be as new as the newest. A client prompts its configuration's model alone: no option names another,
  so a request can never reach a provider that does not serve its model. `LlmProvider.defaultModel` must stay a `def`: as a
  constructor parameter, the two enums would each read the other while it is still being made, and see `null`.
- A set but unrecognised `LLM_PROVIDER` or `LLM_MODEL` means unconfigured (`None`), never a fall back to key inference.
- The library does no throttling, retrying or logging of its own; those are the host's to wrap around a client.
  The one exception is `ask`, which asks again after a reply it cannot read, never after a failed request.
  Nor does it run a tool it is asked for: `ReplyOptions.tools` offers them and `Reply.toolCalls`
  reports what was asked, but what a tool does, and whether it may, is the host's.
- A `Part.CacheBreakpoint` ends a prefix worth caching and says nothing to the model. Anthropic is told of one by
  `cache_control` on the block before it (in its message, else the previous message's last, else the system message),
  marking at most four blocks per chat, counted as sent (breakpoints in a row mark one); OpenAI and Gemini cache
  prefixes unasked and are sent nothing, so their APIs drop them (`Message.uncached`). `LlmClient.warm` sends
  `Chat.cacheable` with the least reply the provider allows (`ProviderApi.leastReply`: Anthropic `max_tokens: 0`,
  others 1); a wrapper around a
  client must forward `warm`, not inherit the default, which `LlmClient.Forwarding` (override only what changes) and
  `mapK` (one transformation for every request) both do. `cacheable` always ends with the user saying something:
  breakpoints left alone in the last message move back to what came before, and a prefix ending at the system message
  or the assistant's turn is followed by a user message of `.`. `Usage.inputTokens` counts the whole prompt on every
  provider (Anthropic's `input_tokens` leaves the cache out, so its reads and writes are added back) and
  `Usage.cachedTokens` the part read from the cache.
- Providers run adjacent text blocks together (Anthropic was measured to: two blocks count exactly as their
  texts concatenated), so every `ProviderApi` serialises a message's parts through `Message.separated`, which opens
  with a blank line each text part following another with only breakpoints (or empty text) between them. A part's
  text depends only on the parts before it, never after, so a cached prefix is sent alike whatever follows it.
  OpenAI separates after taking tool calls and results out of a message, as they then no longer lie between its
  texts. Callers never write a separator of their own between parts.
  `JsonHttp.plain` joins parts already separated, and `Message.text` joins text parts with the same blank line.
- `ReplyOptions.effort` (`Effort`, `Low` to `Max`) is sent as Anthropic's `output_config.effort`, OpenAI's
  `reasoning_effort` (`Max` as `max`, or as `high` on the models in `OpenAiApi.cappedAtHigh`, such as `gpt-5`) and Gemini's `generationConfig.thinkingConfig.thinkingBudget`
  (fixed amounts within every thinking Gemini model's range). Iris never chooses one, and a model that cannot take one
  refuses the request; retrying at a lower effort is the host's to do.
- `LlmClient.ask[A]` needs an `Answer[A]`: `Answer.text` for `String` (the reply as written, nothing said of it),
  else `Answer.json` for any `Structured` type (`NotGiven[A =:= String]` keeps the two apart, whatever the given
  priority rules), and `Answer.reply` for `Reply` (the reply whole, never refused). It is built on `send`
  alone, provider-agnostic, and passes `Answer.ask` the client's `send` as a function, as `send` is protected: the schema is asked for in words closing the
  last user message (never the system message, so a cached prefix is sent alike), not through a provider's
  native structured output. An unreadable reply is answered with every decoding failure (decoders accumulate)
  and asked again, `attempts` requests in all, then `LlmError.Unreadable`, carrying every reply. Asking again
  only helps a complete reply that said something, so one stopped at `MaxTokens`, a `ToolCall` or an `Other`
  reason, or one that is blank, fails at once: an identical chat is never resent. A stop sequence is asked again,
  the model told so, as only Anthropic reports one and others would read it as complete. Those checks come only
  after a read fails, so `ask[String]` still returns truncated text as `send` does, but fails on a tool call,
  which its text could not hold. `Unreadable`'s message carries neither the replies nor `detail`, which is what
  the model was told and may repeat the reply (a host's `emap` message, a `Map`'s keys), so hosts may log it.
  Iris's own wording names no values (`Answer.reason` rewrites circe's, which quote them). JSON is read from the
  whole reply, then its last fence, then its outermost value, so a fence inside a JSON string is never taken.
- What goes in is a `Content[A]` everywhere (`Chat.of`, `Chat.user`/`assistant`, `Message.apply`, `Part.of`):
  `String` as text, a `Part` as itself, a `Structured` value as its JSON, else an `Encoder`'s JSON. `NotGiven`s keep
  the givens apart, `Structured` winning, so a value is said as the schema the model is told describes it.
  A single-turn prompt is `Chat.of(content)`, never an overload of `Chat.apply`, which would capture
  `Chat(messages)` (the case class's own `apply` needs the default `system`, so loses). `Chat.user` and
  `assistant` take `A*` of one content type rather than an overload beside `Part*`: Scala 3 never ranks a varargs
  alternative more specific, so a single part was ambiguous. Mixed kinds go through `Part.of`.
- `Structured` is one type class of schema, encoder and decoder, so the three cannot disagree; never derive one
  from a circe codec. Derivation (`Mirror`s) summons field instances lazily, so that a recursive type's given
  can be made before the fields referring to it, and renders a recursive type into `$defs`. A type is told apart
  by its `TypeName`, its full name with its type arguments applied, never by identity (a generic recursive
  type's given is a `def`, so it would never meet itself) nor by simple name (two `User`s, or `Tree[Int]` beside
  `Tree[String]`, would share a definition). A generic type is derived once for every argument, so the `TypeName`
  macro names its arguments at runtime from their own `Structured` instances. Definitions are shown by short
  name, in full only where two short names clash, in parentheses (`Tree(Int)`), which a `$ref` may hold. Cases of a sealed trait are
  externally tagged (`{"Circle":{...}}`), or just their names where none has fields. Descriptions come from
  Scaladoc through [Cadmus](https://github.com/SgtSwagrid/cadmus) (`Scaladoc.of`, rendered `plain`), whose
  comments the compiler keeps by default for sources compiled in the same run, as a `derives` clause's type always
  is (others need `-Xread-docs`, and under `-Xdrop-docs` there are none, which Cadmus gives as `None` from 0.1.1);
  a comment on a parameter is never kept, so fields are described by the class's `@param` tags, which `record`
  aligns with the `Mirror`'s labels. `imap`/`emap` are inline so as to read the target type's. Test derivation from
  outside the package (`StructuredSuite` is in `com.alecdorrington.example`), as inline code reaches private
  helpers only through accessors.
- Unset optional fields are omitted one level deep, by `JsonHttp.objectOf`/`body`. Never deep-drop nulls: inside a
  tool's schema or a tool call's arguments, a null is a value.
- `LlmStreamer` is a capability apart from `LlmClient`, since streaming needs a backend which can stream.
  `SseClient` speaks the same `ProviderApi` as `JsonClient`, with the same request bodies (`streamed`). Each
  `ProviderApi.reply` reads the whole stream of events, most one event at a time (`Sse.separately`). Gemini's
  remembers across events whether a tool was asked for, as it may say so before it says it stopped.
- What differs between the platforms is in `Platform` alone, one per platform (`src/main/scalajvm`,
  `src/main/scalajs`): the backends `resource` makes (the JDK's HTTP client, or the Fetch API), and whether a
  request may come from a browser's page, for which `AnthropicApi` adds `anthropic-dangerous-direct-browser-access`.
  Everything else is shared, so use nothing the JS side lacks: no `java.util.Locale` (lower case by character, as
  `LlmProvider.parse` does), nor `java.time`. The tests run on both, so they run their effects in `SyncIO`,
  never `IO.unsafeRunSync`, which JavaScript lacks; tests of one platform alone go in `src/test/scalajs` or
  `src/test/scalajvm`.
- `LlmConfig.toString` redacts the API key, so hosts may log a configuration. Keep any new secret out of `toString`
  and out of `LlmError` messages likewise.

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

### Releases

1. Land the changes on `main` by pull request, the last of them setting the README's installation line to the new
   version. Iris is in beta, versioned by early semver (`versionScheme`): while it is `0.x`, the minor version goes up
   for any change that can break a user's build or behaviour, and the patch version for anything else.
2. Publish a GitHub release of `main` tagged `v<version>`, with notes written for a user, as the earlier releases' are
   (the installation line, then what is Changed, Added and Fixed). `publish.yml` then publishes `iris_3` and
   `iris_sjs1_3` to Maven Central through `sbt-ci-release`, which takes the version from the tag, and `docs.yml`
   publishes the Scaladoc at [alecdorrington.com/iris](https://alecdorrington.com/iris/).
3. Once Maven Central serves the new version, bump it in each project using Iris, by pull request there. Their
   Scala Steward would otherwise propose it on its next weekly run. A version that breaks callers needs their code
   changed too, as README's *Upgrading* section says. For 0.3.0, as found in review: Themis passes requests on
   with `super.send` in `Throttle`, `Allowances` and `Recall`, asks `ask[Reply]` in `ComparisonService`,
   `EvaluationService`, `TaskImportService` and `TranscriptionService`, and its test fakes override the protected
   `send`; Codeswitch's `Translator` asks where it sent.

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
