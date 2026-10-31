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
> Iris is still a prototype. Anything could be broken or change between minor versions.

One small interface for sending prompts and conversations to a large language model,
with adapters for [Anthropic](https://docs.anthropic.com/en/api/messages),
[OpenAI](https://platform.openai.com/docs/api-reference/chat) and
[Google Gemini](https://ai.google.dev/api/generate-content), so that switching provider is a matter of configuration.
It is built on [Cats Effect](https://typelevel.org/cats-effect/), [sttp](https://sttp.softwaremill.com/) and [Circe](https://circe.github.io/circe/).

Named for [Iris](https://en.wikipedia.org/wiki/Iris_(mythology)), messenger of the gods,
who carried their words to mortals along the rainbow.

## ⬇️ Installation

The following applies for [Scala 3](https://www.scala-lang.org/) projects built with [sbt 2](https://www.scala-sbt.org/).
Add the library to your `build.sbt` file, with the desired version number (e.g. [latest](../../releases/latest)):

```scala
libraryDependencies += "com.alecdorrington" %% "iris" % "{version}"
```

## 🚀 Usage

The public interface is [`LlmClient`](src/main/scala/com/alecdorrington/iris/LlmClient.scala).
Build one for an explicit [`LlmConfig`](src/main/scala/com/alecdorrington/iris/LlmConfig.scala) with `LlmClient.resource`,
over an sttp backend of your own with `LlmClient(config, backend)`,
or from the environment (see [Configuration](#%EF%B8%8F-configuration)) with `LlmClient.fromEnv`.

Models are named by [`LlmModel`](src/main/scala/com/alecdorrington/iris/LlmModel.scala), each served by one provider,
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

### Asking

Every reply is asked for with `ask`, whose type says what the answer is: `String`, or any type of your
own as [below](#typed-answers). Every type is asked for alike, and every ask gives the same: an
[`Answer`](src/main/scala/com/alecdorrington/iris/Answer.scala) holding its `value`, and its `reply`, an effect giving the
[`Reply`](src/main/scala/com/alecdorrington/iris/Answer.scala) it was read from: its `text`, why the model stopped
(`stopReason`), the token `usage` of every attempt, the `toolCalls` it made, and how many `attempts` it
took. The reply is an effect as an answer may be given before its reply ends (see [Streaming](#streaming));
for most types it has ended already. Being an effect, it makes answers equal only where they are the same
answer: compare their values and replies instead. A single-turn prompt is a chat of one message, `Chat.of`:

```scala
import com.alecdorrington.iris.{Chat, LlmClient}

LlmClient.fromEnv[IO].use {
  case Some(client) => client.ask[String](Chat.of("Hello!")).map(_.value)
  case None         => // No provider configured.
}
```

### Conversations

Conversations are stateless: nothing is remembered between calls, and the full history
travels with every request as a [`Chat`](src/main/scala/com/alecdorrington/iris/Chat.scala).
To continue a conversation, append the model's reply and the next user message, then send the chat again:

```scala
import com.alecdorrington.iris.Chat

val chat = Chat().withSystem("You are terse.").user("Name a colour.")
for
  first  <- client.ask[String](chat)
  reply  <- first.reply
  second <- client.ask[String](chat.assistant(reply).user("And another."))
yield second.value
```

### Typed answers

`ask` asks for an answer of a type of your own, as it does for `String`. Every case class, sealed trait
and enum whose fields have one has a [`Schema`](src/main/scala/com/alecdorrington/iris/Schema.scala), derived wherever it is needed,
and the model is told the [JSON Schema](https://json-schema.org) its reply must conform to, which is read back
as that type:

```scala
enum Mood:
  case Pleased, Indifferent, Annoyed

case class Review(summary: String, mood: Mood, score: Int, quotes: List[String] = Nil)

client.ask[Review](Chat.of(s"Review this essay: $essay")) // IO[Answer[IO, Review]]
```

`derives Schema` derives a type's schema once, beside it, rather than wherever it is needed, and where a
field's type has none, says which. A type with a circe `Encoder` of its own has no schema derived for it,
as its `Encoder` already says how it is written, and is said to the model as that writes it; `derives Schema`
derives one anyway. Circe's `Json` has none, as it would tell the model nothing; nor does a type nested so
deeply that deriving it goes beyond the compiler's limit of inlines, which `-Xmax-inlines` raises.

A reply that cannot be read, as one that is not JSON or leaves a field out, is answered with every reason
it could not be, and the model is asked again, three times in all unless `attempts` says otherwise, as in
`client.ask[Review](chat, attempts = 5)`. JSON is read from the whole reply, or else from its last code
fence, though the model is asked for JSON alone; JSON amid prose with no fence is asked for again. The model
is told what was wrong as circe says it. A reply asking for a tool is given at once, with no `value`. A reply
that asking again could not help fails at once: one cut off at the token limit, which is never read, as what
parses may be cut short, one the provider stopped, as on a refusal, and one saying nothing. A chat in which
the user says nothing fails with `LlmError.Unsendable` before any request. The last unreadable reply fails
with `LlmError.Unreadable`, which keeps every reply asked for, usage included, and what the model was told was
wrong with the last, which may repeat what it said; both are kept out of its message, so that it may be
logged. Each attempt is a request of its own, so a wrapper (see [Wrapping a client](#wrapping-a-client)) sees
every one too.

Strings, numbers, Booleans, options, lists, vectors, sequences, sets and maps keyed by strings have schemas
already. A case class, or a named tuple, is an object of its fields, of which one that is optional or has a
default may be left out, and is read as `None` or its default. A sealed trait or enum whose cases have no
fields is written as the name of one, as `"Pleased"`, and any other as an object holding one case under its
name, as `{"Circle":{"radius":1.5}}`, as an `Either` holds its value under `Left` or `Right`, and a union, as
`Int | String`, holds its value under the name of its alternative, as `{"Int":1}`. A value is written as the
alternative its class tells, so `List[Int] | List[String]` has none. A literal type is its value, and a union
of literal types alone, as `"low" | "high"`, one of them. A tuple is an array of its elements, and an
fs2 `Stream` an array of its values, which `ask` delivers as they are written (see
[Streaming](#streaming)). A recursive type, such as a tree, refers to itself by name, each type told
apart by its full name and type arguments. Any other type converts to and from one that has a schema, and
may refuse what it is given, as the model is then told. Say what it keeps to with `described`, so that the
model knows before it answers; the words stay beside whatever else describes the type, its Scaladoc or the
`@param` of a field of it:

```scala
case class Score(value: Int)

given Schema[Score] = Schema[Int]
  .emap(n => Either.cond(n >= 1 && n <= 10, Score(n), s"$n is not from 1 to 10"))(_.value)
  .described("A whole number from 1 to 10.")
```

In JavaScript, numbers in a reply are read as doubles, so a `Long`, `BigInt` or `BigDecimal` beyond 2^53
loses its precision there.

The schema describes each type to the model as its Scaladoc does: a type, and each case of a sealed trait
or enum, by its description, and each field by its class's `@param` tag, followed by the description of the
field's type, so that the model reads both. Code examples are left out, and links written as their labels.

```scala
/**
  * A review of an essay, for its author.
  *
  * @param score
  *   Out of ten, where five is passable.
  */
case class Review(summary: String, mood: Mood, score: Score) derives Schema
```

A comment is read by [Cadmus](https://github.com/SgtSwagrid/cadmus) where its type is compiled alongside, as
it always is for `derives Schema`, and not at all under `-Xdrop-docs`; a type derived where it is needed, or
converted with `imap` or `emap`, is described by its own Scaladoc where it is compiled alongside, or anywhere
if you compile with `-Xread-docs`. Scala keeps
no comment written on a parameter itself, so describe a field with `@param`.

`String` is asked for as every type is: the model is told the schema `{"type":"string"}` and replies with a
JSON string, which is read back as any other answer.

What goes in is alike everywhere: `Chat.of`, a chat's `user` and `assistant` messages and `Message` each
take any values with a [`Content`](src/main/scala/com/alecdorrington/iris/Content.scala), of any kinds together, as parts of one message in
reading order. Text is said as it is, a part as itself, and any other value as JSON, as its `Schema` writes it,
or else, where it has none, as its circe `Encoder` does:

```scala
client.ask[Review](Chat.of(essay).withSystem("Review the essay you are given."))
client.ask[Review](Chat.of("Review this draft.", draft, Part.CacheBreakpoint, "Be brief.")) // draft: Draft
client.ask[Review](chat.assistant(review).user("Now make it kinder."))
```

A value holding an fs2 `Stream`, as an answer may, has a schema but cannot be said, as its stream
would have to be run: saying it throws. Say a list of the stream's elements instead.

A type of your own is said as a given `Content` of its own says it, ahead of any `Schema` or `Encoder`, as a
picture may be:

```scala
given Content[Scan] = scan => Part.Media("image/png", scan.base64)
```

A list of parts is given as `chat.user(parts*)`, and a list of other values as `chat.user(values.map(Part.of)*)`.

The schema is asked for in words, at the close of the user's last message, rather than through any
provider's own structured output, so it works alike everywhere, and a cached prefix is sent as it was.

### Tuning and metadata

Each request accepts [`ReplyOptions`](src/main/scala/com/alecdorrington/iris/ReplyOptions.scala)
(token limit, temperature, top-p, stop sequences, effort), and each
[`Reply`](src/main/scala/com/alecdorrington/iris/Answer.scala) carries the reply text along with a
normalised `StopReason` and its token `Usage`, which adds up with `+`. Its counts are alike for every
provider, and none includes another, so that each can be priced alone: tokens read afresh, read from
the cache and written to it, audio read afresh and from the cache, and the reply's visible and
thinking tokens. A count the provider does not report, or never charges for apart, is `0`.
`inputTokens`, `cachedTokens` and `outputTokens` total them.

```scala
client.ask[String](chat, ReplyOptions(maxTokens = Some(1024), temperature = Some(0.2)))
```

A stop sequence stops the model in the text it writes, which for `ask` is JSON: one inside a JSON string,
as a newline, which is written escaped, never matches, and one that does leaves JSON cut short, which is
asked for again.

> [!NOTE]
> Anthropic's newer models, the default `ClaudeSonnet5_5` among them, no longer accept sampling
> parameters and reject a request which carries them. Iris omits `temperature` and top-p for those
> models, and for any Anthropic model it does not list, rather than let the request fail; both
> still apply to every other provider, and to Anthropic's older models.

A model that thinks before it answers can be told how much to think with an
[`Effort`](src/main/scala/com/alecdorrington/iris/Effort.scala), from `Low` to `Max`. Less thought is quicker and
cheaper, and leaves more of the token limit for the answer, where a hard request can
otherwise be thought about until none is left. It is sent as Anthropic's `effort`,
OpenAI's `reasoning_effort` and a Gemini thinking budget; a model that cannot be told
refuses the request.

```scala
client.ask[String](chat, ReplyOptions(effort = Some(Effort.Medium)))
```

### Costs

Each `Reply`, and so each answer's `reply`, also says what it `cost`, in US dollars, as a
[`Usd`](src/main/scala/com/alecdorrington/iris/Usd.scala): each reply priced as it came, at the
[`Rates`](src/main/scala/com/alecdorrington/iris/Rates.scala) Iris lists for its model, and summed over
an ask's attempts as its usage is. Rates are per million tokens, one for each of `Usage`'s counts,
as the provider charges on its standard tier, and a model charging more for a long prompt has
rates `above` a number of prompt tokens, which then apply to the whole request. A price change
the provider has announced is listed from midnight UTC on its day (Iris's assumption, as Google names
no time zone), so each reply is priced at the rates in force when it arrives. A stream's `Delta.End` says what its reply cost alike.

A model Iris does not list has no rates, and its cost is `None`, unless the configuration gives
rates of its own, which also stand in for those listed, as for a proxy charging its own prices, or
a discount:

```scala
LlmConfig(model, apiKey, maxTokens = 4096, rates = Some(Rates.of(Usd(BigDecimal(3)), Usd(BigDecimal(15)))))
```

`Rates.listed(model, at)` gives the rates Iris lists ahead of any request, as for estimating one.
No provider publishes its prices through its API, so Iris lists them as they stood at its release:
a later change it could not know of needs a newer Iris or rates of your own.

Every provider prices in US dollars, so Iris converts no currency: a host billed in another
converts at its own rate, which whoever bills it sets. Nor does a cost count tax, credits or a
discount of your own.

### Metering

`client.metered(meter)` runs a function on what each request spent, as its reply comes: its
`Usage` and its `cost`, `None` where Iris knows no rates for the model. It is called once for every
attempt of an `ask`, on that attempt's reply before it is read, so an ask that fails after three
attempts is metered three times, once for warming, and once for each `stream`, at its `Delta.End`.
Counting costs nothing, so is never metered, nor is a request that fails with no reply, nor a stream
stopped before its end, which reports no usage. A meter that fails fails its request, and the meters of
requests sent at once may run at once, so a meter adding to a total of its own keeps it safe:

```scala
client.metered((_, cost) => cost.traverse_(spent => total.update(_ + spent)))
```

### Budgets

A [`Budget`](src/main/scala/com/alecdorrington/iris/Budget.scala) limits what requests may cost
together, in US dollars, as for a session: every client it `limits` spends from it, whatever its
model or provider. A request is refused with `LlmError.OverBudget` once as much as the limit has
been spent, before anything is sent, a stream before it starts, and is charged what its reply cost when the reply comes. Every
attempt of an `ask` is charged, and so is warming, as `metered` meters them; counting tokens costs
nothing, so goes on. Each round of an `ask` that runs tools is a request of its own, so a budget is
what bounds a tool loop, which nothing else caps.

```scala
for
  budget <- Budget[IO](Usd(BigDecimal(5)))
  limited = budget.limits(client)
  answer <- limited.ask[String](chat)
  left   <- budget.remaining
yield (answer, left)
```

A reply's cost is known only once it comes, so requests already sent when the limit is reached
are charged in full, and may take the spending past it: by as many requests as are in flight at
once, so a host sending six at a time may pass it by up to six replies' cost. A stream running then
goes on to its end, and one stopped before its end is charged nothing, though the provider may
charge for what it wrote. What is spent never resets: a budget for each day, or each month, is a new
`Budget` for each.

A budget needs prices: a reply with no cost, from a model Iris does not list whose configuration
gives no rates, fails its request with `LlmError.Unpriced`. By then the provider has charged for
it and the reply is lost, as is every later one from that model, so give such a model `rates` in
its `LlmConfig` before limiting it. A budget is never needed: a client without one spends without
limit.

### Pictures and documents

A message is a list of [`Part`](src/main/scala/com/alecdorrington/iris/Chat.scala)s, so text may travel beside
the media it refers to. A message of text alone is still sent as plain text, so nothing
changes for a conversation which carries none.

Text parts in a row are sent as paragraphs of their own, a blank line apart, since every
provider would otherwise run them together: `Part.Text("BOOK 1: notes.txt")` followed by
`Part.Text("Chapter one.")` would reach the model as `BOOK 1: notes.txtChapter one.`. Only
breakpoints may lie between them; a picture, a document or a tool call between two texts
already keeps them apart, and they are sent as they are.

```scala
import com.alecdorrington.iris.{MediaType, Part}

chat.user("What is in this picture?", Part.Media(MediaType.Png, bytes)) // or its Base64, as a String
```

Media is of a [`MediaType`](src/main/scala/com/alecdorrington/iris/MediaType.scala): `Png`, `Jpeg`, `Gif`,
`Webp` or `Pdf`, or any other, such as `audio/mp3`, named by `MediaType.of`. Each provider is sent only what
it takes, and anything else fails with `LlmError.Unsupported` before any request, rather than being dropped
or sent as something it is not:

| Provider  | Takes                                                                      |
|-----------|----------------------------------------------------------------------------|
| Anthropic | `Png`, `Jpeg`, `Gif` (its first frame) and `Webp` pictures, and `Pdf`       |
| OpenAI    | `Png`, `Jpeg`, `Gif` (not animated) and `Webp` pictures                     |
| Gemini    | anything, which it judges itself: it lists no `Gif`, but audio and video    |

### Streaming

Where a reader is waiting, a client delivers the reply as it is written, with `stream`. A client
made by `resource` or `fromEnv` streams it as it arrives; one made over a backend of your own does
where that backend is an sttp `StreamBackend` for fs2, and otherwise delivers it whole, once it ends.

```scala
import com.alecdorrington.iris.{Delta, LlmClient}

LlmClient.fromEnv[IO].use {
  case Some(client) => client.stream(Chat.of("Tell me a story.")).evalMap {
    case Delta.Text(text)        => IO.print(text)
    case Delta.ToolCall(call)    => IO.println(s"\n(asks for ${call.tool})")
    case Delta.End(reason, _, _) => IO.println(s"\n($reason)")
  }.compile.drain
  case None => IO.unit
}
```

Nothing is sent until the stream is run, and a failure reaches it as an `LlmError` as it
would anywhere else. A tool call arrives whole, once its arguments have, and `Delta.End` carries the
reply's token counts.

An answer is streamed too, wherever its type says so: any [fs2 `Stream`](https://fs2.io) within it,
other than within a list or map, which are read whole. `ask` then gives the answer as soon as
everything in it but its streams is read, and each stream delivers each of its elements as soon as the
model has written it whole, which suits showing a long answer a piece at a time. Only streams are ever
partial: every other value is read whole before the answer is given. A type holding no stream is asked
with one request, and answered once its reply ends.

```scala
import fs2.Stream

case class Word(text: String, translation: String)
case class Page(title: String, words: Stream[IO, Word])

client.ask[Page](Chat.of(s"Translate: $text")).flatMap { answer =>
  answer.value.traverse_(page => page.words.evalMap(show).compile.drain) *>
    answer.reply.map(_.usage)
}
```

The model is told the same JSON Schema either way, a stream being an array, so its reply is plain
JSON; only its reading differs. The reply goes on being read in a fiber of its own once the answer is
given, to its end, even where nothing reads its streams, so its tokens are spent and its connection
held to its end, unless `answer.cancel` stops it: its streams not yet ended, and its `reply`, then fail
with an `IllegalStateException`. A failure of the reply reaches only its streams and `reply`, unseen
where neither is read.
A stream may be read as often as you like, each time from its first element, keeping every element as
long as it is held, and streams may be nested, as a stream of pages each with a stream of words.
`ask` needs `Async`, to read in a fiber.

The model writes a value's fields in order, so a value holding a stream is given as soon as a stream
in it has begun, each field it has begun is read or begun as a stream, each it has passed over may be
left out (an optional field is taken to be once a later one begins), and each it has yet to begin is a
required stream, given to begin when it does. So `Lesson(title: String, words: Stream[IO, Word],
examples: Stream[IO, String])` is given as `words` begins, and both stream as they are written. Any
other field after a stream, an optional one included, holds back the value it is in until the reply
has written it, and with it any stream before it, which is then whole: put streams last. A field
written after its value was given, as one written out of order, or a stream that never begins, fails
the reply. A value is given early only while one of its streams is seen open, so how the reply's parts
happen to arrive may decide whether it is: one whose stream opens and closes within a part is read
whole, and asked for again if it cannot be read.

A streamed reply's JSON is read from its start, or from within a code fence it opens with, and reading
ends with it; what follows, which a reply read whole would be refused for, is not read. Until the
answer is given, a reply that cannot be read is answered and asked again, as for any type; a reply that
does not begin with its JSON, or a code fence holding it, is read only once whole. Once the answer is
given it is never asked for again: what fails afterwards fails each of its streams not yet ended, after
the elements they delivered already, and `reply`. A reply that cannot be read to its end, as one cut
off at the token limit, stopped by the provider, asking for a tool, or with an element that cannot be
read, fails with `LlmError.Unreadable`, as a whole reply would, and a request failing partway, as on a
timeout, fails as it would anywhere. A stream cannot be said to the
model as JSON, as it would have to be run; say a list of its elements.

### Tools

Give `ask` tools it may run, with what running each does, and it offers them to the model, runs any it asks
for, tells it their outputs, and asks it to go on, as often as it asks, before giving the answer. What a
tool does, and whether it may, is yours to decide in `run`. A tool's arguments are a type with a
`Schema`, which describes them to the model and reads them back; arguments that cannot be read are
answered with why, for the model to call it again. A `run` that fails fails the ask with its error, the
model not told, so turn any failure the model should hear of into output instead. A reply asking for tools
does not count against `attempts`, and nothing counts how many such replies there are: an ask runs tools
for as long as the model asks for them, each round a request, so bound what an ask may spend in all, as you
would any request's cost, rather than how many rounds it takes. Without such a bound, a model that never
stops asking for tools is asked again, and paid for, without end.

```scala
import com.alecdorrington.iris.RunnableTool

case class Lookup(word: String)

val dictionary = RunnableTool.of[IO, Lookup]("dictionary", "Looks up a word.")(lookup =>
  IO(definitions(lookup.word)))

client.ask[Review](chat, tools = List(dictionary))
```

Where the answer is itself a stream, as `Stream[IO, Word]`, and tools are given, the model gives its
elements through a tool of its own, `emit`, one call each, and may call the others between them: each
element reaches the stream as soon as its call has, in order, and the stream goes on after each tool has
run. An element that cannot be read is answered with why, and the model emits it again, and those after it,
up to `attempts` times for each element. A model writing its elements as a JSON array instead has them read
from its reply once it ends, and is asked again where they cannot be. A reply cut off at the token limit,
or stopped, ends the stream with `LlmError.Unreadable` after the elements it gave, as a failing `run` ends
it with its error. Taking only some of the elements does not stop the model writing the rest, nor the tools
it asks for running: `answer.cancel` does. Tools for you to run, in `ReplyOptions.tools`, cannot be offered
beside such a stream, whose answer, given already, could not end at their calls. Nested streams are
streamed this way only without tools.

Or offer the model tools for you to run yourself, through `ReplyOptions.tools`, and it will say so in its
reply's `toolCalls`, with no `value`: append the reply and each tool's result, and ask again.

```scala
import com.alecdorrington.iris.{Part, Tool}

val weather = Tool("weather", "Looks up the weather.", schema)

for
  asked  <- client.ask[String](chat, ReplyOptions(tools = List(weather)))
  reply  <- asked.reply
  call    = reply.toolCalls.head
  result  = Part.ToolResult(call.id, "weather", lookUp(call))
  answer <- client.ask[String](chat.assistant(reply).user(result), ReplyOptions(tools = List(weather)))
yield answer.value
```

Append the reply itself, with `chat.assistant(reply)`, so that each call goes back as it came: its
`id` is the provider's, by which its result is matched (where Gemini gives a call none, the tool's
name stands in), and Gemini's thinking models need back the thought signature a call carries, its
`signature`, to go on with their reasoning.

A reply which asked for a tool has `StopReason.ToolCall`, on every provider, and so does a
streamed reply's `Delta.End` — Gemini reports no such reason of its own, so the asking is what
says so. A streamed answer may ask for tools too, its calls in its reply's `toolCalls` once it ends.

### Counting tokens

`count` asks the provider what a chat would cost to send, so a conversation can be
checked against a budget or a context window before a reply is spent finding out.

```scala
client.count(Chat.of("How long is a piece of string?"))
```

OpenAI offers no such endpoint, and fails with `LlmError.Unsupported` rather than
guessing with a tokeniser of its own.

### Caching

When many requests begin alike, as when one document is asked several questions, put what
they share first and end it with a `Part.CacheBreakpoint`, so that the provider can keep what it
made of the prefix rather than read it afresh every time. `Usage.cachedTokens` says how much of a
prompt was read from the cache, and `cacheWriteTokens` how much was written to it, where the provider
charges for writing it (Anthropic, and OpenAI from GPT-5.6).

```scala
val asking = (question: String) =>
  Chat().withSystem("Answer from the document.")
    .user(document, Part.CacheBreakpoint, question)

for
  _       <- client.warm(asking(""))
  answers <- questions.parTraverse(question => client.ask[String](asking(question)))
yield answers.flatMap(_.value)
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

A provider's refusal fails the effect with an [`LlmError`](src/main/scala/com/alecdorrington/iris/LlmError.scala):
`Unsuccessful` for an unsuccessful response, carrying its status and body, `Malformed` for a
response that could not be understood, and `TimedOut` for a request no answer came to within the
configured timeout (`LlmConfig.timeout`), such as one whose reply took too long to write or stalled
partway. A client made by `resource` or `fromEnv` gives each reply that long whole, from sending the
request to reading the reply; a stream gives each next part of its reply that long. A client over a
backend of your own times requests out as that backend does.
`Unreadable` is for an `ask` whose every reply was not the answer asked for, or whose reply, once
its answer was given, could not be read to its end. Response
bodies may contain provider detail you would rather not show to your own users, so consider logging
them rather than passing them on.

### Wrapping a client

Iris does no throttling, retrying or logging of its own, so that each host can wrap a client in
exactly the policy it wants. `ask` asks again only for an answer it could not read, and never after a
request that failed. `mapK` runs every request through one transformation, as a limit on
requests in flight does, and each step of a stream alike, so a policy holding a stream as a whole goes
in a wrapper's `stream` instead:

```scala
Semaphore[IO](16).map(permits => client.mapK(permits.permit.surroundK))
```

A wrapper that treats requests differently extends `LlmClient.Forwarding`, which passes every
request on, and overrides only what it changes. Each attempt of an `ask` is made through the protected
`send`, or through `stream` for a type holding a stream; `count` and `warm` go their own ways. So a
wrapper seeing every request overrides all four, passing each on through `super`:

```scala
def logged(client: LlmClient[IO]): LlmClient[IO] = new LlmClient.Forwarding(client):
  override protected def send(chat: Chat, options: ReplyOptions): IO[Reply] =
    super.send(chat, options).flatTap(reply => IO.println(reply.usage))
  override def stream(chat: Chat, options: ReplyOptions): Stream[IO, Delta] =
    super.stream(chat, options).evalTap {
      case Delta.End(_, usage, _) => IO.println(usage)
      case _                   => IO.unit
    }
  override def count(chat: Chat, options: ReplyOptions): IO[Int] =
    super.count(chat, options).flatTap(tokens => IO.println(tokens))
  override def warm(chat: Chat, options: ReplyOptions): IO[Reply] =
    super.warm(chat, options).flatTap(reply => IO.println(reply.usage))
```

Both pass `warm` on to the client they wrap, as a wrapper must: the default `warm` sends through
`send`, which would lose however the provider itself warms.

### Upgrading from 0.4

- `ask` gives an `Answer[F, A]`: its `value` as before, its `reply`, an effect giving the reply's
  `text`, `stopReason`, `usage`, `toolCalls` and `attempts`, and `cancel`, which stops a streamed reply. Read `answer.reply.map(_.usage)` where you read
  `answer.usage`, and append `reply` to a chat, where you appended the answer.
- A reply as the provider gave it, an `Answer[Nothing]`, is a `Reply`: `send` and `warm` give one, a
  chat's `assistant` takes one, and `LlmError.Unreadable` keeps them, as `replies`.
- `ask` needs `Async`, as it reads a streamed reply in a fiber of its own, so a client in `SyncIO` can
  no longer ask.
- `Reply.usage` and `Delta.End.usage` are a `Usage`, never `None`: a count the provider did not
  report is `0`, and an ask's usage is that of every attempt, summed. Read `usage` where you matched
  `Some(usage)`.
- `Usage` is made of seven counts, named as you make one (`Usage(uncachedTokens = 10, visibleTokens = 2)`).
  `inputTokens`, `cachedTokens` and `outputTokens` are now totals of them, meaning what they did.
- `outputTokens` now counts Gemini's thinking, which it left out, and streams from Anthropic and
  OpenAI now count their usage, so totals may be higher than before.
- `Chat.of`, a chat's `user` and `assistant` messages and `Message` take values of any kinds together, so
  `chat.user(Part.of("Review this."), Part.of(draft))` is now `chat.user("Review this.", draft)`. A spliced
  list must be of parts: where you spliced other values, write `chat.user(values.map(Part.of)*)`.
- `Part.Media` takes a `MediaType`, where it took a string: write `Part.Media(MediaType.Png, data)`, or
  `MediaType.of(name)` for a name read at runtime, which is `None` where the name is no media type. It
  also takes the content's bytes, encoding them itself. Anthropic now refuses media other than pictures
  and PDFs before sending it, where its API refused it.
- `LlmStreamer` is gone, as every client streams: `LlmStreamer.resource`, `fromEnv` and `apply` are
  `LlmClient.resource`, `fromEnv` and `apply`, and `streamer.stream(chat)` is `client.stream(chat)`.
  `LlmClient(config, backend)` over an sttp `StreamBackend` for fs2 streams too; over any other backend,
  a stream delivers its reply whole, once it ends.
- `Delta` has a new case, `ToolCall`, which a match over every case must take.
- `Part.ToolCall` has a fourth field, `signature`, Gemini's thought signature, so a match takes
  `Part.ToolCall(id, tool, arguments, _)`. A Gemini call's `id` is Gemini's own where it gives one, no
  longer always the tool's name.
- A class implementing `LlmClient` itself, rather than through `LlmClient.Forwarding`, may implement
  `stream`, which unless it does delivers each reply whole, through `send`. A wrapper that sees every
  request overrides it as it does `send`.
- A `Reply` has a `cost`, an `Option[Usd]`, after `usage`, and `Delta.End` has one too, last.
- `LlmClient.apply` needs a `Clock` of its effect, which prices each reply as it comes, as `IO` and
  `SyncIO` have.
- `LlmError` has two new cases, `OverBudget` and `Unpriced`, which a match over every case must take.

### Upgrading from 0.3

- `Structured` is now `Schema`: `derives Schema`, `Schema[A]` and `Schema.of`, and a schema's JSON is
  `Schema[A].json`, where it was `.schema`. A `derives` clause is no longer needed.
- A field with a default is no longer required: a reply may leave it out, and it is read as its default.
- A field's `@param` no longer replaces its type's description, but comes before it.

### Upgrading from 0.2

- `client.send(chat, options)` is `client.ask[String](chat, options)`, an `Answer` whose `value` is the text,
  beside the stop reason, usage and tool calls 0.2's `Reply` had. The model is now told to reply
  with a JSON string, as for any type, which changes what a reply is:
  - `.text` is the JSON the model wrote, quoted and escaped; read `.value` for the text itself.
  - A reply cut off at the token limit, stopped by the provider, as on a refusal, or blank now fails with
    `LlmError.Unreadable`, where `send` gave it. Code that looked at a returned `StopReason.MaxTokens` matches
    `Unreadable(_, _, answers)` and looks at `answers.last.stopReason`.
  - A reply of prose, or text unquoted (`Paris`), is not JSON, so is asked for again, three requests in all
    unless `attempts` says otherwise, each seen by a wrapper.
  - A prompt asking for JSON of its own asks for that type instead, rather than for `String`, whose schema
    would contradict the prompt.
  - Stop sequences match the JSON the model writes, as *Tuning and metadata* says.
- 0.2's `Reply` is now `Answer`, and generic: name it as `Answer[?]`, or `Answer[Nothing]` for a reply as the provider
  gave it, and match it by its six fields, `value` and `attempts` the last.
- `Prompt(text)` is `Chat.of(text)`, and `Prompt(text, Some(system))` is `Chat.of(text).withSystem(system)`.
  `ask`, `count` and `stream` take that chat where they took a prompt. A chat in which the user says nothing
  is refused.
- A wrapper extends `LlmClient.Forwarding` and passes a request on with `super.send`, where it called the
  wrapped client's `send`, whose replies are now `Answer[Nothing]`; one holding another client asks it.
- A reply asking for a tool is an `Answer` with no `value` and its `toolCalls`.
- `LlmError` has a new case, `Unreadable`, which a match over every case must take.

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

In JavaScript, `LlmClient.resource` sends requests through the
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
