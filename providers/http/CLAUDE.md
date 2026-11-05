# CLAUDE.md

What every provider shares over HTTP (`iris-http`): the `api` layer (`ApiService`, `HttpApiService`, `RetryingApiService`), the
`Provider` and `ProviderModel` traits, the prompt service over a provider's API (`ProviderPromptService`), and `Platform`,
the only code of one platform's. The repository's [CLAUDE.md](../../CLAUDE.md) describes the whole, and each
provider's module what is particular to it.

- Every `Provider` must normalise what it returns: the reply text, a `StopReason` (unknown labels kept as `Other`) and
  token usage in `PromptResponse`. An unsuccessful response is a refusal for now, `ApiError.RateLimited` (`429`)
  or `Overloaded` (`503`, Anthropic's `529`), or a failure for now, `Transient` (`408`, `409`, `500`, `502`,
  `504`, as the providers' SDKs retry them, a review's request), each with the wait the provider asked for, or
  else `ApiError.Unsuccessful` (status and body), as `Refusal.of` says; an unreadable one is `ApiError.Malformed`;
  a request no answer comes to within `ApiConfig.timeout` is `ApiError.TimedOut`, such as one whose reply
  takes too long to write; other transport failures are sttp's own. Each `Provider` reads its own wait
  (`retryAfter`: `retry-after` in whole seconds for all; OpenAI's `retry-after-ms` first; else the reset of a
  limit whose remaining header is `0`, Anthropic's an RFC 3339 time, read by hand through `Provider.utc`
  as JavaScript has no `java.time`, OpenAI's a Go duration; Gemini's `RetryInfo.retryDelay` in the body), and
  says which refusals waiting cannot lift (`lasting`: Anthropic's `enforced_spend_limit_reached`, OpenAI's
  `insufficient_quota` and spent credit or limits by type or code, Gemini's `QuotaFailure` of a `quotaId`
  holding `PerDay`, as its free tier's), which stay `Unsuccessful`. The `x-should-retry` header (undocumented,
  but the providers' SDKs heed it) outweighs status and body alike: `true` makes any status one to send again
  (`Transient`, or `RateLimited` or `Overloaded` by its status), `false` none. Anthropic's streamed
  `error` event is read as a refusal of its type's status (`overloaded_error` as `529`, `api_error` as `500`); OpenAI's and Gemini's
  streams are not read for errors yet. Waits are clamped (`Refusal.nanos`) so that adding them never
  overflows.
- The JDK's client counts a request's read timeout only until its reply begins, so `provider.api` wraps the backend
  it makes in `DeadlineBackend`, which gives each exchange that long whole, body included, and `EventStreams.bounded` gives each
  next part of a stream that long. Neither may wait for the read it gives up on, which cannot be cancelled: sttp
  reads a body in `F.blocking`, and fs2's reactive-streams subscriber waits for a stream's next part in `F.async_`.
  So `DeadlineBackend` uses `timeoutAndForget`, never `timeout` or `timeoutTo`, and `bounded` reads the stream on a fiber
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
  uninterruptibly, so a cancel waits for the next part, and for good where the reply is silent. So `EventStreams`
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
  its connection, and ends the body with an error, freeing the read. A host's backend (`Provider.api(config,
  backend)`) aborts nothing of Iris's (`Exchange.abort` is `unit`). `AbortSuite` checks each way of giving up against
  a local server on each platform (the JDK's sockets, Node's `http`), which notes when the client closes its
  connection, while it writes nothing.
  `provider.api` over a plain `Backend` takes it as it is, and needs only `Sync`, as the shared tests run in
  `SyncIO`.
- A `MessagePart.ToolCall` goes back to its provider as the reply gave it. Gemini may give a call no `id`, so the tool's
  name stands in, and is never sent as one (`Gemini.issued`), on the call or its `functionResponse`. Gemini puts a
  `thoughtSignature` on the part beside a `functionCall` (the first of calls made together), which its thinking
  models need back on that part (else `MISSING_THOUGHT_SIGNATURE`), so it is kept, opaque, as `MessagePart.ToolCall.signature`,
  which other providers ignore. A signature on text is not kept, as `PromptResponse.text` joins the parts, and Google requires
  one back only with a call.
- Unset optional fields are omitted one level deep, by `JsonHttp.objectOf`. Never deep-drop nulls: inside a
  tool's schema or a tool call's arguments, a null is a value.
- A streamed request has the same body as a whole one but for `stream: true` (`Settings.streamed`). Each
  `Provider.deltas` reads the whole stream of events, remembering across them what it must (`zipWithScan1`):
  Gemini's whether a tool was asked for, as it may say so before it says it stopped, Anthropic's the counts it
  started with, and OpenAI's how it stopped until its usage comes.
- What differs between the platforms is in `Platform` alone, one per platform (`providers/http/src/main/scalajvm`,
  `providers/http/src/main/scalajs`): the backends `resource` makes (the JDK's HTTP client, or the Fetch API, and how each aborts a
  stream, above), and whether a
  request may come from a browser's page, for which `Anthropic` adds `anthropic-dangerous-direct-browser-access`.
  Everything else is shared, so use nothing the JS side lacks: no `java.util.Locale` (lower case by character, as
  `Providers.provider` does), nor `java.time`. The tests run on both, so they run their effects in `SyncIO`,
  never `IO.unsafeRunSync`, which JavaScript lacks, or, where they need concurrency (`LiveValueSuite`), return
  `IO`'s `unsafeToFuture` with a timeout, which munit awaits on both; tests of one platform alone go in
  `providers/src/test/scalajs` or `providers/src/test/scalajvm`.
- `ApiConfig.toString` redacts the API key, so hosts may log a configuration. Keep any new secret out of `toString`
  and out of every error's message likewise.
