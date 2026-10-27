<div align="center">

  <h1>🌈 Iris</h1>
  <p>A provider-agnostic <a href="https://www.scala-lang.org/">Scala</a> client for large language models.</p>

  <span>
    <a href="https://github.com/SgtSwagrid/iris/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/iris/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/iris_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/iris_3.svg" alt="Maven Central" /></a>
    <a href="https://alecdorrington.com/iris"><img src="https://img.shields.io/badge/docs-latest-blue.svg" alt="Documentation" /></a>
  </span>

</div>

> [!WARNING]
> Iris is in beta. It is young, it has few users, and anything may change between minor versions.

One small interface for sending prompts and conversations to a large language model,
with adapters for [Anthropic](https://docs.anthropic.com/en/api/messages),
[OpenAI](https://platform.openai.com/docs/api-reference/chat) and
[Google Gemini](https://ai.google.dev/api/generate-content), so that switching provider is a matter of configuration.
It is built on [Cats Effect](https://typelevel.org/cats-effect/), [sttp](https://sttp.softwaremill.com/) and [Circe](https://circe.github.io/circe/).

Named for [Iris](https://en.wikipedia.org/wiki/Iris_(mythology)), messenger of the gods,
who carried their words to mortals along the rainbow.

## ⬇️ Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies += "com.alecdorrington" %% "iris" % "0.2.0"
```

Compiled with Scala `3.9.0`, with no intention to explicitly support older versions.
For the JVM and, through [Scala.js](https://www.scala-js.org/), for JavaScript, where sbt 2 resolves `%%` to the
right artefact (sbt 1 needs `%%%`).

## 🚀 Usage

The public interface is [`LlmClient`](src/main/scala/LlmClient.scala).
Build one for an explicit [`LlmConfig`](src/main/scala/LlmConfig.scala) with `LlmClient.resource`,
over an sttp backend of your own with `LlmClient(config, backend)`,
or from the environment (see [Configuration](#%EF%B8%8F-configuration)) with `LlmClient.fromEnv`.

Models are named by [`LlmModel`](src/main/scala/LlmModel.scala), each served by one provider,
which is the one a configuration speaks to:

```scala
import com.alecdorrington.iris.{LlmClient, LlmConfig, LlmModel}

LlmClient.resource[IO](LlmConfig(LlmModel.ClaudeOpus5_5, apiKey, maxTokens = 4096))
```

A client prompts its configuration's model alone. For another model, build another client; several
may share one backend through `LlmClient(config, backend)`.

A model Iris does not list yet is named with its provider, as
`LlmModel.of(LlmProvider.Anthropic, "claude-...")`, which gives `None` for a blank name, and the
listed constant for a name Iris lists. A model's `name` is its provider's and its own, as
`anthropic:claude-opus-5-5`, which is how a configuration logs it.

### Single-turn prompts

```scala
import com.alecdorrington.iris.{LlmClient, Prompt}

LlmClient.fromEnv[IO].use {
  case Some(client) => client.send(Prompt("Hello!"))
  case None         => // No provider configured.
}
```

### Conversations

Conversations are stateless: nothing is remembered between calls, and the full history
travels with every request as a [`Chat`](src/main/scala/Chat.scala).
To continue a conversation, append the model's reply and the next user message, then send the chat again:

```scala
import com.alecdorrington.iris.Chat

val chat = Chat().withSystem("You are terse.").user("Name a colour.")
for
  first  <- client.send(chat)
  second <- client.send(chat.assistant(first.text).user("And another."))
yield second.text
```

### Typed answers

`ask` asks for an answer of a type of your own rather than text. Derive a
[`Structured`](src/main/scala/Structured.scala) type for your case classes, sealed traits and enums, and
the model is told the [JSON Schema](https://json-schema.org) its reply must conform to, which is read back
as that type:

```scala
import com.alecdorrington.iris.Structured

enum Mood derives Structured:
  case Pleased, Indifferent, Annoyed

case class Review(summary: String, mood: Mood, score: Int, quotes: List[String]) derives Structured

client.ask[Review](Prompt(s"Review this essay: $essay")) // IO[Review]
```

A reply that cannot be read, as one that is not JSON or leaves a field out, is answered with every reason
it could not be, and the model is asked again, three times in all unless `attempts` says otherwise, as in
`client.ask[Review](chat, attempts = 5)`. JSON is found within a code fence, or within prose around it,
though the model is asked for JSON alone. A reply that asking again could not help fails at once: one cut
off at the token limit, one asking for a tool, one the provider stopped, as on a refusal, and one saying
nothing. The last unreadable reply fails with `LlmError.Unreadable`, which keeps every reply asked for, usage
included, and what the model was told was wrong with the last, which may repeat what it said; both are kept
out of its message, so that it may be logged. Each attempt goes through `send`, so a wrapper (see
[Wrapping a client](#wrapping-a-client)) sees every one too.

Strings, numbers, Booleans, options, lists, vectors, sequences, sets and maps keyed by strings are
structured already. An optional field may be left out. A sealed trait or enum whose cases have no fields
is written as the name of one, as `"Pleased"`, and any other as an object holding one case under its name,
as `{"Circle":{"radius":1.5}}`. A recursive type, such as a tree, refers to itself by name, each type told
apart by its full name and type arguments. Any other type converts to and from one that is structured, and
may refuse what it is given, as the model is then told. Say what it keeps to with `described`, so that the
model knows before it answers; the words stay beside whatever else describes the type, its Scaladoc or the
`@param` of a field of it:

```scala
case class Score(value: Int)

given Structured[Score] = Structured[Int]
  .emap(n => Either.cond(n >= 1 && n <= 10, Score(n), s"$n is not from 1 to 10"))(_.value)
  .described("A whole number from 1 to 10.")
```

In JavaScript, numbers in a reply are read as doubles, so a `Long`, `BigInt` or `BigDecimal` beyond 2^53
loses its precision there.

The schema describes each type to the model as its Scaladoc does: a type, and each case of a sealed trait
or enum, by its description, and each field by its class's `@param` tag, which replaces the description of
the field's type. Code examples are left out, and links written as their labels.

```scala
/**
  * A review of an essay, for its author.
  *
  * @param score
  *   Out of ten, where five is passable.
  */
case class Review(summary: String, mood: Mood, score: Score) derives Structured
```

A comment is read by [Cadmus](https://github.com/SgtSwagrid/cadmus) where its type is compiled, as it always
is for `derives Structured`, and not at all under `-Xdrop-docs`; a type converted with `imap` or `emap` is
described by its own Scaladoc where it has one there, or anywhere if you compile with `-Xread-docs`. Scala keeps
no comment written on a parameter itself, so describe a field with `@param`.

`ask[String]` is the reply's text, as `send` gives it, and the model is told nothing of its form.

What goes in is alike everywhere: a `Prompt`, a chat's `user` and `assistant` messages and `Part.of` each
take any [`Content`](src/main/scala/Content.scala), saying text as it is, a part as itself, and any other
value as JSON, as its `Structured` type writes it, or else as its circe `Encoder` does:

```scala
client.ask[Review](Prompt(essay, system = Some("Review the essay you are given.")))
client.ask[Review](Chat().user(Part.of("Review this draft."), Part.of(draft))) // draft: Draft, Structured
client.ask[Review](chat.assistant(review).user("Now make it kinder."))
```

Several values of one kind make one message of several parts, as `chat.user(first, second)` does; values of
different kinds are made parts first, with `Part.of`.

The schema is asked for in words, at the close of the user's last message, rather than through any
provider's own structured output, so it works alike everywhere, and a cached prefix is sent as it was.

### Tuning and metadata

Each request accepts [`ReplyOptions`](src/main/scala/ReplyOptions.scala)
(token limit, temperature, top-p, stop sequences, effort), and each
[`Reply`](src/main/scala/Reply.scala) carries the reply text along with a
normalised `StopReason` and token usage counts.

```scala
client.send(chat, ReplyOptions(maxTokens = Some(1024), stopSequences = List("\n\n")))
```

> [!NOTE]
> Anthropic's newer models, the default `ClaudeSonnet5_5` among them, no longer accept sampling
> parameters and reject a request which carries them. Iris omits `temperature` and top-p for those
> models, and for any Anthropic model it does not list, rather than let the request fail; both
> still apply to every other provider, and to Anthropic's older models.

A model that thinks before it answers can be told how much to think with an
[`Effort`](src/main/scala/Effort.scala), from `Low` to `Max`. Less thought is quicker and
cheaper, and leaves more of the token limit for the answer, where a hard request can
otherwise be thought about until none is left. It is sent as Anthropic's `effort`,
OpenAI's `reasoning_effort` and a Gemini thinking budget; a model that cannot be told
refuses the request.

```scala
client.send(chat, ReplyOptions(effort = Some(Effort.Medium)))
```

### Pictures and documents

A message is a list of [`Part`](src/main/scala/Chat.scala)s, so text may travel beside
the media it refers to. A message of text alone is still sent as plain text, so nothing
changes for a conversation which carries none.

Text parts in a row are sent as paragraphs of their own, a blank line apart, since every
provider would otherwise run them together: `Part.Text("BOOK 1: notes.txt")` followed by
`Part.Text("Chapter one.")` would reach the model as `BOOK 1: notes.txtChapter one.`. Only
breakpoints may lie between them; a picture, a document or a tool call between two texts
already keeps them apart, and they are sent as they are.

```scala
import com.alecdorrington.iris.Part

chat.user(Part.Text("What is in this picture?"), Part.Media("image/png", base64))
```

Anthropic and Gemini take both pictures and documents. OpenAI's chat completions take
pictures only, and a document there fails with `LlmError.Unsupported` rather than being
dropped or sent as something it is not.

### Streaming

Where a reader is waiting, [`LlmStreamer`](src/main/scala/LlmStreamer.scala) delivers the reply
as it is written. It is a capability apart from `LlmClient`, because it needs a backend which
can stream.

```scala
import com.alecdorrington.iris.{Delta, LlmStreamer}

LlmStreamer.fromEnv[IO].use {
  case Some(llm) => llm.stream(Prompt("Tell me a story.")).evalMap {
    case Delta.Text(text)      => IO.print(text)
    case Delta.End(reason, _)  => IO.println(s"\n($reason)")
  }.compile.drain
  case None => IO.unit
}
```

Nothing is sent until the stream is run, and a failure reaches it as an `LlmError` as it
would anywhere else. `Delta.End` carries the token counts where the provider reports them
as it ends; Anthropic counts the input at the start instead, so `send` remains the way to
have both halves together.

### Tools

Offer the model tools it may ask to have run, and it will say so in `Reply.toolCalls`.
Iris never runs a tool itself; what one does, and whether it is allowed to, is yours to decide.

```scala
import com.alecdorrington.iris.{Part, Tool}

val weather = Tool("weather", "Looks up the weather.", schema)

for
  asked  <- client.send(chat, ReplyOptions(tools = List(weather)))
  result  = Part.ToolResult(asked.toolCalls.head.id, "weather", lookUp(asked.toolCalls.head))
  answer <- client.send(chat.assistant(asked).user(result))
yield answer.text
```

A reply which asked for a tool has `StopReason.ToolCall`, on every provider, and so does a
streamed reply's `Delta.End` — Gemini reports no such reason of its own, so the asking is what
says so.

### Counting tokens

`count` asks the provider what a chat would cost to send, so a conversation can be
checked against a budget or a context window before a reply is spent finding out.

```scala
client.count(Prompt("How long is a piece of string?"))
```

OpenAI offers no such endpoint, and fails with `LlmError.Unsupported` rather than
guessing with a tokeniser of its own.

### Caching

When many requests begin alike, as when one document is asked several questions, put what
they share first and end it with a `Part.CacheBreakpoint`, so that the provider can keep what it
made of the prefix rather than read it afresh every time. `Usage.cachedTokens` says how much of a
prompt was read from the cache.

```scala
val asking = (question: String) =>
  Chat().withSystem("Answer from the document.")
    .user(Part.Text(document), Part.CacheBreakpoint, Part.Text(question))

for
  _       <- client.warm(asking(""))
  answers <- questions.parTraverse(question => client.send(asking(question)))
yield answers
```

Requests sent at once cannot read what none of them has written yet, so `warm` writes the prefix
first: everything up to the last breakpoint, asking for as little reply as the provider allows.
A provider answers only a chat which ends with the user saying something, so a prefix which does
not, as when the last breakpoint opens a message to cache only what came before it, is followed
by the least a user can say.

Anthropic caches only what it is told to, and a breakpoint marks the block before it, at most four
blocks in one chat (breakpoints in a row mark one). OpenAI and Gemini cache long prefixes of their
own accord and are sent nothing.
Each provider keeps a cache per model, and caches nothing shorter than its own minimum.

### Errors

A provider's refusal fails the effect with an [`LlmError`](src/main/scala/LlmError.scala):
`Unsuccessful` for an unsuccessful response, carrying its status and body, `Malformed` for a
response that could not be understood, and `TimedOut` for a request no answer came to within the
configured timeout (`LlmConfig.timeout`), such as one whose reply took too long to write.
`Unreadable` is for an `ask` whose every reply was not the answer asked for. Response
bodies may contain provider detail you would rather not show to your own users, so consider logging
them rather than passing them on.

### Wrapping a client

Iris does no throttling, retrying or logging of its own, so that each host can wrap a client in
exactly the policy it wants. `ask` asks again only for an answer it could not read, and never after a
request that failed. `mapK` runs every request through one transformation, as a limit on
requests in flight does:

```scala
Semaphore[IO](16).map(permits => client.mapK(permits.permit.surroundK))
```

A wrapper that treats requests differently extends `LlmClient.Forwarding`, which passes every
request on, and overrides only what it changes:

```scala
def logged(client: LlmClient[IO]): LlmClient[IO] = new LlmClient.Forwarding(client):
  override def send(chat: Chat, options: ReplyOptions): IO[Reply] =
    client.send(chat, options).flatTap(reply => IO.println(reply.usage))
```

Both pass `warm` on to the client they wrap, as a wrapper must: the default `warm` sends through
`send`, which would lose however the provider itself warms.

## ⚙️ Configuration

`LlmConfig.fromEnv` (and so `LlmClient.fromEnv`) reads these environment variables:

| Variable            | Meaning                                     | Default                        |
|---------------------|---------------------------------------------|--------------------------------|
| `LLM_PROVIDER`      | `anthropic`, `openai` or `gemini`           | `LLM_MODEL`'s, else by key    |
| `ANTHROPIC_API_KEY` | API key for Anthropic                       | -                              |
| `OPENAI_API_KEY`    | API key for OpenAI                          | -                              |
| `GEMINI_API_KEY`    | API key for Gemini (or `GOOGLE_API_KEY`)    | -                              |
| `LLM_MODEL`         | Model to use, by its API name (see below)   | Provider-specific default      |
| `LLM_MAX_TOKENS`    | Maximum number of tokens in each reply      | `8192`, or the host's default  |
| `LLM_BASE_URL`      | Overrides the provider's API origin         | The provider's own origin      |
| `LLM_TIMEOUT`       | Seconds to wait for a reply                 | `300`                          |

With no key set, `fromEnv` yields `None`. So does a variable which is set but cannot be used:
an unrecognised `LLM_PROVIDER`, rather than falling back to whichever key exists, an `LLM_MODEL`
that names no model Iris lists or one which another provider serves, and an `LLM_MAX_TOKENS`
which is not a positive whole number, rather than quietly reverting to the default and hiding the
mistake. A model Iris does not list is named after its provider and a colon, as
`LLM_MODEL=anthropic:claude-...`, so that a mistyped name is still caught. A Gemini model may
also be written as Gemini's API lists it, as `gemini:models/gemini-...`.

To read several configurations from one environment, as one per kind of request, pass
`LlmConfig.from` a lookup of your own, which it reads every variable above through:

```scala
val fast = LlmConfig.from(name => sys.env.get(s"${name}_FAST").orElse(sys.env.get(name)))
```

A host whose replies run longer than most passes its own default for an unset `LLM_MAX_TOKENS`,
as in `LlmConfig.fromEnv(defaultMaxTokens = 16_000)` or `LlmConfig.from(lookup, 16_000)`.

## 🌐 In the browser

In JavaScript, `LlmClient.resource` and `LlmStreamer.resource` send requests through the
[Fetch API](https://developer.mozilla.org/docs/Web/API/Fetch_API), and everything else is as on the JVM.
There is no environment to read, so `fromEnv` yields `None`: build an `LlmConfig` yourself, or read
one with `LlmConfig.from` from wherever your page keeps its settings.

A request made from a page carries the page's origin, which Anthropic refuses unless told otherwise, so in
JavaScript every request to Anthropic says that it may come from a browser
(`anthropic-dangerous-direct-browser-access`). Whoever can run the page can read its API key: send requests
from a browser only with a key of the user's own, as a browser extension may, never with yours.

## 🤝 Contributing

Issues and pull requests are very welcome; for anything large, please open an issue first.

## 👁️ See also

- [Hecate](https://github.com/SgtSwagrid/hecate), a sibling, for user accounts, sessions, groups and permissions.
- [Eunomia](https://github.com/SgtSwagrid/eunomia), a sibling, for filtering, ordering and paging lists.
- [Dike](https://github.com/SgtSwagrid/dike), a sibling, for ranking by pairwise comparison, with a model as the judge.
- [qr4s](https://github.com/SgtSwagrid/qr4s), a sibling, for generating QR codes, on the JVM and in the browser.
- [Cadmus](https://github.com/SgtSwagrid/cadmus), a sibling, for reading Scaladoc comments at compile time.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
