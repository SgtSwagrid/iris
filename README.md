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
with adapters for [Anthropic](https://platform.claude.com/docs/en/api/messages),
[OpenAI](https://platform.openai.com/docs/api-reference/chat) and
[Google Gemini](https://aistudio.google.com/api-reference/generate-content), so that switching provider is a matter of configuration.
It is built on [Cats Effect](https://typelevel.org/cats-effect/), [sttp](https://sttp.softwaremill.com/) and [Circe](https://circe.io/circe/).

Named for [Iris](https://en.wikipedia.org/wiki/Iris_(mythology)), messenger of the gods,
who carried their words to mortals along the rainbow.

## ⬇️ Installation

The following applies for [Scala 3](https://www.scala-lang.org/) projects built with [sbt 2](https://www.scala-sbt.org/).
Add the library to your `build.sbt` file, with the desired version number (e.g. [latest](../../releases/latest)):

```scala
libraryDependencies += "com.alecdorrington" %% "iris" % "{version}"
```

## 🚀 Usage

Iris is four layers, each a service of its own, made from the one below it, and each public, so that you
send to whichever you need:

| Layer                                                                 | One request means                                | Service          |
|-----------------------------------------------------------------------|--------------------------------------------------|------------------|
| [`api`](src/main/scala/com/alecdorrington/iris/Api.scala)             | one exchange with a provider, in its own JSON    | `ApiService`     |
| [`prompt`](src/main/scala/com/alecdorrington/iris/Prompt.scala)       | one exchange of text with the model              | `PromptService`  |
| [`query`](src/main/scala/com/alecdorrington/iris/Query.scala)         | one value of a type, read from what it wrote     | `QueryService`   |
| [`agent`](src/main/scala/com/alecdorrington/iris/Agent.scala)         | one value, with tools run until it comes         | `AgentService`   |

Each service is built with its layer's settings (`ApiConfig`, `PromptConfig`, `QueryConfig`, `AgentConfig`),
every one defaulted, and each request may set its own for one call. A request holds the request of the
layer below (`agentRequest.query.prompt.conversation`), and every `send` takes text, a conversation or a
lower layer's request as well as its own, filling in the rest from its service's settings.

A stack is built from its model, one step a layer:

```scala
import com.alecdorrington.iris.*

Model.ClaudeSonnet5_5.prompt[IO]().use: prompt =>
  prompt.query.agent(dictionary).send[Review]("Review this essay: ...")
```

`model.prompt[IO]()` makes the model's prompt service, with an API service and HTTP client of its own,
held in a cats-effect `Resource`; its key is the provider's own variable's (`ANTHROPIC_API_KEY`), unless
`ApiConfig(key = Some(...))` gives one. `PromptService.fromEnv[IO]` reads the model and its settings from
the environment (see [Configuration](#%EF%B8%8F-configuration)), giving `None` where none is configured.

Models are named by [`Model`](src/main/scala/com/alecdorrington/iris/Model.scala), each served by one provider. A model Iris does
not list yet is named with its provider, as `Model.of(ModelProvider.Anthropic, "claude-...")`, which gives
`None` for a blank name, and the listed constant for a name Iris lists. A model's `name` is its provider's
and its own, as `anthropic:claude-opus-5-5`. Several models of one provider may share a connection: make
its API service once, `ModelProvider.Anthropic.api[IO]()`, and each model's prompt service over it,
`model.prompt(api, PromptConfig())`.

### Prompting

The prompt layer is a plain exchange of text: a conversation in, the model's next message out, as a
[`PromptResponse`](src/main/scala/com/alecdorrington/iris/Prompt.scala) with its `text`, why the model stopped (`stopReason`),
its token `usage`, its `cost` and the `toolCalls` it made.

```scala
prompt.send("Hello!").map(_.text)
```

A [`PromptRequest`](src/main/scala/com/alecdorrington/iris/Prompt.scala) may set the token limit, temperature, top-p, stop
sequences, effort and the tools offered for one request, each otherwise its service's
[`PromptConfig`](src/main/scala/com/alecdorrington/iris/Prompt.scala):

```scala
prompt.send(PromptRequest(conversation, maxTokens = Some(1024), temperature = Some(0.2)))
Model.ClaudeOpus5_5.prompt[IO](config = PromptConfig(maxTokens = 4096))
```

A response's `Usage` is alike for every provider, and no count includes another, so that each can be priced
alone: tokens read afresh, read from the cache and written to it, audio read afresh and from the cache, and
the response's visible and thinking tokens. A count the provider does not report, or never charges for
apart, is `0`. `inputTokens`, `cachedTokens` and `outputTokens` total them, and usages add up with `+`.

> [!NOTE]
> Anthropic's newer models, the default `ClaudeSonnet5_5` among them, no longer accept sampling
> parameters and reject a request which carries them. Iris omits `temperature` and top-p for those
> models, and for any Anthropic model it does not list, rather than let the request fail; both
> still apply to every other provider, and to Anthropic's older models.

A model that thinks before it answers can be told how much to think with an
[`Effort`](src/main/scala/com/alecdorrington/iris/Effort.scala), from `Low` to `Max`. Less thought is quicker and
cheaper, and leaves more of the token limit for the answer, where a hard request can otherwise be thought
about until none is left. It is sent as Anthropic's `effort`, OpenAI's `reasoning_effort` and a Gemini
thinking budget; a model that cannot be told refuses the request.

### Conversations

Conversations are stateless: nothing is remembered between calls, and the full history
travels with every request as a [`Conversation`](src/main/scala/com/alecdorrington/iris/Conversation.scala).
To go on with a conversation, append what the model said and the user's next words, then send it again:

```scala
val conversation = Conversation().withInstructions("You are terse.").userSays("Name a colour.")
for
  first  <- prompt.send(conversation)
  second <- prompt.send(conversation.modelSays(first).userSays("And another."))
yield second.text
```

### Typed answers

The query layer asks for a value of any type, `String` included, alike for every type: a
[`QueryResponse`](src/main/scala/com/alecdorrington/iris/Query.scala) holding its `value`, read and validated against the
type's schema, and its `iterations`, an effect giving every `PromptResponse` it took, the last the one the
value was read from; `usage` and `cost` total them. The iterations are an effect as a value may be given
before the last ends (see [Streaming](#streaming)); for most types it has ended already. Every case class,
sealed trait and enum whose fields have one has a [`Schema`](src/main/scala/com/alecdorrington/iris/Schema.scala), derived wherever it is
needed, and the model is told the [JSON Schema](https://json-schema.org) its response must conform to, which is read
back as that type:

```scala
enum Mood:
  case Pleased, Indifferent, Annoyed

case class Review(summary: String, mood: Mood, score: Int, quotes: List[String] = Nil)

prompt.query.send[Review](s"Review this essay: $essay") // IO[QueryResponse[IO, Review]]
```

`derives Schema` derives a type's schema once, beside it, rather than wherever it is needed, and where a
field's type has none, says which. A type with a circe `Encoder` of its own has no schema derived for it,
as its `Encoder` already says how it is written, and is said to the model as that writes it; `derives Schema`
derives one anyway. Circe's `Json` has none, as it would tell the model nothing; nor does a type nested so
deeply that deriving it goes beyond the compiler's limit of inlines, which `-Xmax-inlines` raises.

A response that cannot be read, as one that is not JSON or leaves a field out, is answered with every reason
it could not be, and the model is asked again, three times in all unless the service's
`QueryConfig.maxIterations`, or the request's own, says otherwise, as in
`query.send[Review](QueryRequest(PromptRequest(conversation), maxIterations = Some(5)))`. JSON is read from the whole reply, or else from its last code
fence, though the model is asked for JSON alone; JSON amid prose with no fence is asked for again. The model
is told what was wrong as circe says it. A reply asking for a tool is given at once, with no `value`. A reply
that asking again could not help fails at once: one cut off at the token limit, which is never read, as what
parses may be cut short, one the provider stopped, as on a refusal, and one saying nothing. A conversation in which
the user says nothing fails with `PromptError.Unsendable` before any request. The last unreadable response fails
with `QueryError.Unreadable`, which keeps every reply asked for, usage included, and what the model was told was
wrong with the last, which may repeat what it said; both are kept out of its message, so that it may be
logged. Each attempt is a prompt of its own, so middleware on the prompt service (see
[Middleware](#middleware)) sees every one too.

Strings, numbers, Booleans, options, lists, vectors, sequences, sets and maps keyed by strings have schemas
already. A case class, or a named tuple, is an object of its fields, of which one that is optional or has a
default may be left out, and is read as `None` or its default. A sealed trait or enum whose cases have no
fields is written as the name of one, as `"Pleased"`, and any other as an object holding one case under its
name, as `{"Circle":{"radius":1.5}}`, as an `Either` holds its value under `Left` or `Right`, and a union, as
`Int | String`, holds its value under the name of its alternative, as `{"Int":1}`. A value is written as the
alternative its class tells, so `List[Int] | List[String]` has none. A literal type is its value, and a union
of literal types alone, as `"low" | "high"`, one of them. A tuple is an array of its elements, and an
fs2 `Stream` an array of its values, which a query delivers as they are written (see
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

What goes in is alike everywhere: `Conversation.userSays`, a conversation's `userSays` and `modelSays`, and the
query and agent layers' `send`, each
take any values with a [`Content`](src/main/scala/com/alecdorrington/iris/Content.scala), of any kinds together, as parts of one message in
reading order. Text is said as it is, a part as itself, and any other value as JSON, as its `Schema` writes it,
or else, where it has none, as its circe `Encoder` does:

```scala
query.send[Review](Conversation.userSays(essay).withInstructions("Review the essay you are given."))
query.send[Review](Conversation.userSays("Review this draft.", draft, MessagePart.CacheBreakpoint, "Be brief.")) // draft: Draft
query.send[Review](conversation.modelSays(review).userSays("Now make it kinder."))
query.send[Review](draft) // the draft alone, as JSON
```

A value holding an fs2 `Stream`, as an answer may, has a schema but cannot be said, as its stream
would have to be run: saying it throws. Say a list of the stream's elements instead.

A type of your own is said as a given `Content` of its own says it, as text, ahead of any `Schema` or `Encoder`:

```scala
given Content[Money] = money => MessagePart.Text(s"${money.amount} USD")
```

Each message is one of its owner's, and holds only the parts that owner writes: the user's (`Message.User`)
text, media and breakpoints, the model's (`Message.Model`) text, tool calls and breakpoints, and the tools'
(`Message.Tool`) results and breakpoints, so that a tool call in a user's message cannot be built. Append
the tools' results with `conversation.toolSays(results*)`.

A list of parts is given as `conversation.userSays(parts*)`, and a list of other values as `conversation.userSays(values.map(MessagePart.of)*)`.

The schema is asked for in words, at the close of the user's last message, rather than through any
provider's own structured output, so it works alike everywhere, and a cached prefix is sent as it was.


### Costs

Each `PromptResponse` also says what it `cost`, in US dollars, as a
[`Usd`](src/main/scala/com/alecdorrington/iris/Usd.scala): each response priced as it came, at the
[`Rates`](src/main/scala/com/alecdorrington/iris/Rates.scala) Iris lists for its model, and a query's or an agent's
`cost` totals every response it took, as its `usage` does. Rates are per million tokens, one for each of
`Usage`'s counts, as the provider charges on its standard tier, and a model charging more for a long prompt
has rates `above` a number of prompt tokens, which then apply to the whole request. A price change the
provider has announced is listed from midnight UTC on its day (Iris's assumption, as Google names no time
zone), so each response is priced at the rates in force when it arrives.

A model Iris does not list has no rates, and its cost is `None`, unless its `PromptConfig` gives rates of its
own, which also stand in for those listed, as for a proxy charging its own prices, or a discount:

```scala
model.prompt[IO](config = PromptConfig(rates = Some(Rates.of(Usd(BigDecimal(3)), Usd(BigDecimal(15))))))
```

`Rates.listed(model, at)` gives the rates Iris lists ahead of any request, as for estimating one.
No provider publishes its prices through its API, so Iris lists them as they stood at its release:
a later change it could not know of needs a newer Iris or rates of your own.

Every provider prices in US dollars, so Iris converts no currency: a host billed in another
converts at its own rate, which whoever bills it sets. Nor does a cost count tax, credits or a
discount of your own.

### Metering

`prompt.metered(meter)` runs a function on what each request spent, as its response ends: its `Usage`
and its `cost`, `None` where Iris knows no rates for the model. It runs once for each prompt, so for every
attempt of a query and every round of an agent, on that response before it is read, and once for warming
the cache. A request that fails with no response is not metered, nor a stream stopped before its end,
which reports no usage. A meter that fails fails its request, and the meters of requests sent at once may
run at once, so a meter adding to a total of its own keeps it safe:

```scala
prompt.metered((_, cost) => cost.traverse_(spent => total.update(_ + spent)))
```

### Budgets

A [`Budget`](src/main/scala/com/alecdorrington/iris/Budget.scala) limits what requests may cost together, in US
dollars, as for a session: every prompt service it limits spends from it, whatever its model or provider.
A request is refused with `PromptError.OverBudget` once as much as the limit has been spent, before
anything is sent, and is charged what its response cost when it ends. Every attempt of a query is charged,
every round of an agent, and warming, as `metered` meters them, so a budget is what bounds a tool loop,
which nothing else caps.

```scala
for
  budget <- Budget[IO](Usd(BigDecimal(5)))
  answer <- prompt.limitedBy(budget).query.send[String](conversation)
  left   <- budget.remaining
yield (answer, left)
```

A response's cost is known only once it ends, so requests already sent when the limit is reached are
charged in full, and may take the spending past it: by as many requests as are in flight at once. A stream
running then goes on to its end, and one stopped before its end is charged nothing, though the provider may
charge for what it wrote. What is spent never resets: a budget for each day, or each month, is a new
`Budget` for each.

A budget needs prices: a response with no cost, from a model Iris does not list whose `PromptConfig` gives
no rates, fails its request with `PromptError.Unpriced`. By then the provider has charged for it and the
response is lost, as is every later one from that model, so give such a model `rates` before limiting it.

### Pictures and documents

A message is a list of [`MessagePart`](src/main/scala/com/alecdorrington/iris/Conversation.scala)s, so text may travel beside
the media it refers to. A message of text alone is still sent as plain text, so nothing
changes for a conversation which carries none.

Text parts in a row are sent as paragraphs of their own, a blank line apart, since every
provider would otherwise run them together: `MessagePart.Text("BOOK 1: notes.txt")` followed by
`MessagePart.Text("Chapter one.")` would reach the model as `BOOK 1: notes.txtChapter one.`. Only
breakpoints may lie between them; a picture, a document or a tool call between two texts
already keeps them apart, and they are sent as they are.

```scala
import com.alecdorrington.iris.{MediaType, MessagePart}

conversation.userSays("What is in this picture?", MessagePart.Media(MediaType.Png, bytes)) // or its Base64, as a String
```

Media is of a [`MediaType`](src/main/scala/com/alecdorrington/iris/MediaType.scala): `Png`, `Jpeg`, `Gif`,
`Webp` or `Pdf`, or any other, such as `audio/mp3`, named by `MediaType.of`. Each provider is sent only what
it takes, and anything else fails with `PromptError.Unsupported` before any request, rather than being dropped
or sent as something it is not:

| Provider  | Takes                                                                      |
|-----------|----------------------------------------------------------------------------|
| Anthropic | `Png`, `Jpeg`, `Gif` (its first frame) and `Webp` pictures, and `Pdf`       |
| OpenAI    | `Png`, `Jpeg`, `Gif` (not animated) and `Webp` pictures                     |
| Gemini    | anything, which it judges itself: it lists no `Gif`, but audio and video    |


### Streaming

`prompt.stream` gives a response as it is written: a
[`PromptEvent`](src/main/scala/com/alecdorrington/iris/Prompt.scala) `Written` for more of its text, or of a tool call's arguments
(each call arrives whole), and one `Ended` at last, carrying the whole `PromptResponse`, the provider's report
rather than anything the model wrote. `send` is `stream` gathered, sent whole. A service made by
`model.prompt[IO]()` streams as the response arrives; one made over a backend of your own does where that
backend is an sttp `StreamBackend` for fs2, and otherwise delivers every event at once, as the response
ends.

```scala
prompt.stream("Tell me a story.").evalMap {
  case PromptEvent.Written(PromptEvent.Part.Text, text) => IO.print(text)
  case PromptEvent.Written(_, _)                        => IO.unit
  case PromptEvent.Ended(response)                      => IO.println(s"\n(${response.stopReason})")
}.compile.drain
```

Nothing is sent until the stream is run, and a failure reaches it as it would anywhere else. A stream that
is cancelled, dropped before its end (as by `take`), or times out closes its connection at once on a service
made by `model.prompt[IO]()`, so the provider stops writing, and charging for, the rest. Over a backend of
your own, it closes as that backend does: sttp's own leave the connection open while the response is written.

A value is streamed too, wherever its type says so: any [fs2 `Stream`](https://fs2.io) within it, other than
within a list or map, which are read whole. A query then gives the value as soon as everything in it but its
streams is read, and each stream delivers each of its elements as soon as the model has written it whole,
which suits showing a long answer a piece at a time. Only streams are ever partial: every other value is read
whole before it is given. A type holding no stream is asked with one whole request, and answered once its
response ends.

```scala
import fs2.Stream

case class Word(text: String, translation: String)
case class Page(title: String, words: Stream[IO, Word])

query.send[Page](s"Translate: $text").flatMap { answer =>
  answer.value.traverse_(page => page.words.evalMap(show).compile.drain) *>
    answer.usage
}
```

The model is told the same JSON Schema either way, a stream being an array, so its response is plain JSON;
only its reading differs. The response goes on being read in a fiber of its own once the value is given, to
its end, even where nothing reads its streams, so its tokens are spent and its connection held to its end,
unless `answer.cancel` stops it, closing its connection as a dropped stream does: its streams not yet ended,
and its `iterations`, then fail with an `IllegalStateException`. A failure of the response reaches only its
streams and `iterations`, unseen where neither is read. A stream may be read as often as you like, each time
from its first element, keeping every element as long as it is held, and streams may be nested, as a stream
of pages each with a stream of words. A query needs `Async`, to read in a fiber.

The model writes a value's fields in order, so a value holding a stream is given as soon as a stream in it
has begun, each field it has begun is read or begun as a stream, each it has passed over may be left out (an
optional field is taken to be once a later one begins), and each it has yet to begin is a required stream,
given to begin when it does. So `Lesson(title: String, words: Stream[IO, Word], examples: Stream[IO,
String])` is given as `words` begins, and both stream as they are written. Any other field after a stream,
an optional one included, holds back the value it is in until the response has written it, and with it any
stream before it, which is then whole: put streams last. A field written after its value was given, as one
written out of order, or a stream that never begins, fails the response. A value is given early only while
one of its streams is seen open, so how the response's parts happen to arrive may decide whether it is: one
whose stream opens and closes within a part is read whole, and asked for again if it cannot be read.

A streamed response's JSON is read from its start, or from within a code fence it opens with, and reading
ends with it; what follows, which a response read whole would be refused for, is not read. Until the value
is given, a response that cannot be read is answered and asked again, as for any type; one that does not
begin with its JSON, or a code fence holding it, is read only once whole. Once the value is given it is
never asked for again: what fails afterwards fails each of its streams not yet ended, after the elements
they delivered already, and `iterations`. A response that cannot be read to its end, as one cut off at the
token limit, stopped by the provider, asking for a tool, or with an element that cannot be read, fails with
`QueryError.Unreadable`, as a whole response would, and a request failing partway, as on a timeout, fails
as it would anywhere.

### Tools

The agent layer runs tools: give it tools with what running each does, and it offers them to the model,
runs any it asks for, tells it their outputs, and asks it to go on, as often as it asks, before giving the
value. What a tool does, and whether it may, is yours to decide in `run`. A tool's arguments are a type with
a `Schema`, which describes them to the model and reads them back; arguments that cannot be read, or are no
JSON, are answered with why, for the model to call it again. A `run` that fails fails the request with its
error, the model not told, so turn any failure the model should hear of into output instead. A
response asking for tools does not count against the query's `maxIterations`, and nothing counts how many
rounds there are: an agent runs tools for as long as the model asks for them, each round a request, so bound what it may spend
with a [budget](#budgets), rather than how many rounds it takes.

```scala
import com.alecdorrington.iris.RunnableTool

case class Lookup(word: String)

val dictionary = RunnableTool.of[IO, Lookup]("dictionary", "Looks up a word.")(lookup =>
  IO(definitions(lookup.word)))

prompt.query.agent(dictionary).send[Review](conversation)
```

An `AgentResponse` gives its `value`, its `iterations`, the query of each round, and `responses`, every
prompt they took, oldest first.

Where the value is itself a stream, as `Stream[IO, Word]`, and tools are given, the model gives its elements
through a tool of its own, `emit`, one call each, and may call the others between them: each element reaches
the stream as soon as its call has, in order, and the stream goes on after each tool has run. In the
response giving its last element, the model calls a second tool of Iris's, `done`, and that response ends the
stream with no request after it; a model that does not stops in a response of its own, a request later.
`done` is not taken in a response that also asks for another tool, or gives an element that cannot be read,
and the stream goes on. An element that cannot be read is answered with why, and the model emits it again,
and those after it, up to the query's `maxIterations` times for each element. A model writing its elements as
a JSON array instead has them read from its response once it ends, and is asked again where they cannot be.
A response cut off at the token limit, or stopped, ends the stream with `QueryError.Unreadable` after the
elements it gave, as a failing `run` ends it with its error. Taking only some of the elements does not stop
the model writing the rest, nor the tools it asks for running: `answer.cancel` does. Tools for you to run,
in a `PromptRequest`'s `tools`, cannot be offered beside such a stream, whose value, given already, could not
end at their calls, nor tools named `emit` or `done`. Nested streams are streamed this way only without
tools.

Each round sends the whole conversation again, the rounds before it included, so each request after a round
closes with a `MessagePart.CacheBreakpoint` of the agent's own: it reads every earlier round from the cache
and writes only its own, where the provider caches only what it is told to (Anthropic). The newest end alone
is marked, after your own breakpoints, and where yours already mark the four blocks Anthropic allows, the
rounds are left unmarked rather than crowd one of yours out. A write costs a quarter more than reading
afresh, so end what every request shares, such as the instructions and any documents, with a breakpoint of
your own, so that only the rounds are ever written.

Or offer the model tools for you to run yourself, in a `PromptRequest`'s `tools`, and it says so in its
response's `toolCalls`, with no `value`: append the response and each tool's result, and ask again.

```scala
val weather = Tool("weather", "Looks up the weather.", schema)

for
  asked    <- query.send[String](PromptRequest(conversation, tools = List(weather)))
  sent     <- asked.iterations
  call      = sent.last.toolCalls.head
  result    = MessagePart.ToolResult(call.id, "weather", lookUp(call))
  answer   <- query.send[String](PromptRequest(
                conversation.modelSays(sent.last).toolSays(result),
                tools = List(weather),
              ))
yield answer.value
```

Append the response itself, with `conversation.modelSays(response)`, so that each call goes back as it came:
its `id` is the provider's, by which its result is matched (where Gemini gives a call none, the tool's name
stands in), and Gemini's thinking models need back the thought signature a call carries, its `signature`, to
go on with their reasoning. A response which asked for a tool has `StopReason.ToolCall`, on every provider:
Gemini reports no such reason of its own, so the asking is what says so.

### Counting tokens

A model's `counter` asks its provider what a request would cost to send, so a conversation can be checked
against a budget or a context window before a response is spent finding out. Counting is no exchange of text,
so it is a service of its own, over the provider's API service; OpenAI offers no such endpoint, so its
models have none.

```scala
ModelProvider.Anthropic.api[IO]().use: api =>
  model.counter(api).traverse(_.countTokens("How long is a piece of string?"))
```

### Caching

When many requests begin alike, as when one document is asked several questions, put what they share first
and end it with a `MessagePart.CacheBreakpoint`, so that the provider can keep what it made of the prefix
rather than read it afresh every time. `Usage.cachedTokens` says how much of a prompt was read from the cache,
and `cacheWriteTokens` how much was written to it, where the provider charges for writing it (Anthropic, and
OpenAI from GPT-5.6).

```scala
val asking = (question: String) =>
  Conversation().withInstructions("Answer from the document.")
    .userSays(document, MessagePart.CacheBreakpoint, question)

for
  _       <- prompt.warmCache(asking(""))
  answers <- questions.parTraverse(question => query.send[String](asking(question)))
yield answers.flatMap(_.value)
```

Requests sent at once cannot read what none of them has written yet, so `warmCache` writes the prefix first:
everything up to the last breakpoint, asking for as little response as the provider allows. A provider
answers only a conversation which ends with the user (or the tools) saying something, so a prefix which does
not, as when the last breakpoint opens a message to cache only what came before it, is followed by the least
a user can say.

Anthropic caches only what it is told to, and a breakpoint marks the block before it, at most four blocks in
one conversation (breakpoints in a row mark one), but breakpoints closing a conversation, as an agent puts
after each round, mark its last block only where the others leave room. OpenAI and Gemini cache long
prefixes of their own accord and are sent nothing. Each provider keeps a cache per model, and caches nothing
shorter than its own minimum.

### Errors

Each layer fails with errors of its own. The api layer's
[`ApiError`](src/main/scala/com/alecdorrington/iris/Api.scala) is a provider's refusal or failure: `RateLimited` (`429`) or
`Overloaded` (`503`, or Anthropic's `529`) for a request it refused for now and went on refusing (below),
carrying the wait it asked for, if any, `Transient` for one it failed for now (`408`, `409`, `500`, `502`,
`504`) and went on failing, `Unsuccessful` for any other unsuccessful response, carrying its status and body,
`Malformed` for a response that could not be understood, `Misconfigured` for a base URL that is no URL or no
key, and `TimedOut` for a request no answer came to within `ApiConfig.timeout`, such as one whose response
took too long to write or stalled partway. A service made by `model.prompt[IO]()` gives each response that
long whole, from sending the request to reading it; a stream gives each next part that long. One over a
backend of your own times requests out as that backend does.

The prompt layer's `PromptError` is a request refused before it is sent: `Unsendable` for a conversation
the provider would refuse, `Unsupported` for what it cannot do, as media it does not take, and `OverBudget`
and `Unpriced` for a [budget](#budgets)'s refusals. The query layer's `QueryError.Unreadable` is for a query
whose every response was not the value asked for, or whose response, once its value was given, could not be
read to its end. Response bodies may contain provider detail you would rather not show to your own users, so
consider logging them rather than passing them on.

### Rate limits

Providers limit how many requests, and how many tokens, an account may send a minute, and refuse the excess
for now, as they do when too busy to answer. An API service made by `model.prompt[IO]()` or
`ModelProvider.api[IO]()`, or over a backend of your own that can stream, sends a request it refused so again
once the wait the provider asked for is over: its `retry-after` (OpenAI's `retry-after-ms` first), else the
reset of the limit spent (`anthropic-ratelimit-*-reset`, `x-ratelimit-reset-*`), or Gemini's `RetryInfo`,
and up to a second more at random, so that requests refused together do not all come back at once. Where the
provider asks for no wait, it waits a second, then twice as long after each refusal, less up to half at
random. It gives up after four tries, or before a wait that would take more than a minute in all, failing
with `RateLimited` or `Overloaded`, which carries the wait the provider last asked for, if any, for your
users. A request the provider failed for now, as the providers' own SDKs retry it (a timeout, `408`, a
conflict, `409`, or a failure of its own, `500`, `502` or `504`), is sent again alike, and given as
`Transient`. The provider's `x-should-retry` header, which its SDKs heed, outweighs both: `true` sends again
a request it failed in any way, and `false` none.

```scala
case ApiError.RateLimited(provider, wait, _) =>
  s"$provider is limiting requests. Try again ${ wait.fold("soon")(wait => s"in ${ wait.toSeconds } s") }."
case ApiError.Overloaded(provider, _, _) => s"$provider is busy. Try again soon."
```

`ApiConfig` says how many tries to make (`maxIterations`, the first included, so `1` sends each once), how
long to wait where the provider names no wait (`backoff`), and how long to wait in all (`patience`):

```scala
model.prompt[IO](api = ApiConfig(maxIterations = 6, backoff = 2.seconds, patience = 5.minutes))
```

Nothing else is sent again, as waiting cannot change it: a refused key, a request the provider cannot take,
a response cut off at the token limit, or a refusal saying the account's quota, credit, monthly spend or
day's requests are used up (Anthropic's `enforced_spend_limit_reached`, OpenAI's `insufficient_quota` and the
like, or a Gemini quota of a day, as its free tier's). Waiting is cancelled with its request. A request is
sent again only while none of its response has come, so a stream refused once its response has begun, as
Anthropic may refuse one with an `overloaded_error` event, fails with `Overloaded` after what it streamed.
A service over a backend that cannot stream, whose effect need not be able to wait, sends each request once.

### Middleware

Iris does no throttling or logging of its own, and retries only what a provider refused for now (above), so
that each host wraps whichever layer it means in exactly the policy it wants. A middleware is a function from
a service to another of its layer, and the prompt and api services have one method to write, `stream`, so a
lambda is one. The prompt layer is where nearly every policy goes, as every request that spends passes
through it, an agent's every round and a query's every attempt included:

```scala
def logged(prompt: PromptService[IO]): PromptService[IO] = request =>
  prompt.stream(request).evalTap {
    case PromptEvent.Ended(response) => IO.println(response.usage)
    case _                           => IO.unit
  }

Semaphore[IO](16).map(permits => (request => Stream.resource(permits.permit) >> prompt.stream(request)): PromptService[IO])
```

`send` and `warmCache` are `stream` gathered, so a middleware on `stream` sees them too.

### Upgrading from 0.5

`ModelClient` is four layers in 0.6, each a service of its own (see [Usage](#-usage)): most of what a
client did is a prompt service's, and `ask` is a query's `send`, or an agent's where it ran tools:

| 0.5                                                   | 0.6                                                                  |
|-------------------------------------------------------|----------------------------------------------------------------------|
| `ModelClient.resource[F](ModelConfig(model, key, n))` | `model.prompt[F](ApiConfig(key = Some(key)), PromptConfig(maxTokens = n))` |
| `ModelClient.fromEnv[F]`                              | `PromptService.fromEnv[F]`                                           |
| `ModelClient(config, backend)`                        | `model.prompt(model.provider.api(apiConfig, backend), promptConfig)` |
| `client.ask[A](conversation, options, attempts)`      | `prompt.query.send[A](QueryRequest(PromptRequest(conversation, ...), Some(attempts)))` |
| `client.ask[A](conversation, tools = tools)`          | `prompt.query.agent(tools*).send[A](conversation)`                   |
| `ValidatedResponse`, its `responses`                  | `QueryResponse`, its `iterations` (an agent's `AgentResponse.responses`) |
| `client.stream(request)`, `Delta`                     | `prompt.stream(request)`, `PromptEvent`                              |
| `ModelRequest(conversation, ModelRequest.Options(...))` | `PromptRequest(conversation, ...)`, its settings flat              |
| `ModelResponse`                                       | `PromptResponse`                                                     |
| `client.countTokens(request)`                         | `model.counter(api).map(_.countTokens(request))`                     |
| `client.warmCache(request)`, `client.metered(meter)`  | `prompt.warmCache(request)`, `prompt.metered(meter)`                 |
| `budget.limits(client)`                               | `prompt.limitedBy(budget)`                                           |
| `ModelClient.Forwarding`, `mapK`                      | a function from one `PromptService` to another (see [Middleware](#middleware)) |
| `ModelConfig`                                         | the model, `ApiConfig` (key, base URL, timeout, retries) and `PromptConfig` (token limit, sampling, effort, rates) |
| `ModelConfig.fromEnv`, `ModelConfig.from`             | `Model.fromEnv`, `ApiConfig.fromEnv`, `PromptConfig.fromEnv`, and their `from` |
| `Retries(attempts, backoff, patience)`, `Retries.none` | `ApiConfig(maxIterations = ..., backoff = ..., patience = ...)`, `maxIterations = 1` |
| `ModelError`                                          | `ApiError`, `PromptError`, `QueryError` (`UnreadableResponse` is `QueryError.Unreadable`) |
| `Chat`, a request's `chat`                            | `Conversation`, its `conversation`                                   |

- A message is one case for each of its owners, holding only the parts that owner writes:
  `Message.User` (text, media and breakpoints), `Message.Model` (text, tool calls and breakpoints) and
  `Message.Tool` (tool results and breakpoints). `Role` and `Message(role, parts)` are gone: match on the
  case. The tools' results are a message of their own, appended with `conversation.toolSays(results*)`,
  where they were the user's; each provider sends them as its API takes them.
- `MessagePart.ToolCall.arguments` is the text the model wrote, a `String`, where it was circe's `Json`:
  parse it where you read it. A `RunnableTool`'s `run` takes it as written, and a tool made by
  `RunnableTool.of` tells the model when it is no JSON. A streamed call whose arguments are no JSON is
  passed on as written, where it failed the reply as `Malformed`.
- A `Content` writes a value as text (`MessagePart.Text`), and a part goes into a message as itself,
  where its message holds its kind: a type's own content may no longer write it as media.
- A service made by `model.prompt[F]()` or over a backend that can stream waits out a provider's
  refusals for now, as [Rate limits](#rate-limits) says. Give its `ApiConfig` `maxIterations = 1` to have
  each refusal at once, as before.
- A refusal for now is `ApiError.RateLimited` (`429`) or `ApiError.Overloaded` (`503`, `529`), and a
  failure for now `ApiError.Transient` (`408`, `409`, `500`, `502`, `504`), which carry the wait asked
  for, where each was `Unsuccessful`, which no longer has a `retryAfter`.
- A streamed response that Anthropic refuses partway, with an `error` event, fails with that error,
  where it was read as one that ended without saying so.
- A service over a backend that cannot stream answers a streamed request whole, reading its events at
  once; counting tokens with OpenAI is no longer offered at all, where it failed with `Unsupported`.
- `warmCache` asks for no tokens of response, which each provider raises to the least it allows.

### Upgrading from 0.4

Names are plainer in 0.5, and the roles in a chat are the user and the model. Most changes are renames,
which find and replace makes:

| 0.4                                               | 0.5                                                         |
|---------------------------------------------------|-------------------------------------------------------------|
| `LlmClient`, `LlmConfig`, `LlmError`, `LlmProvider` | `ModelClient`, `ModelConfig`, `ModelError`, `ModelProvider` |
| `LlmModel`                                        | `Model`                                                     |
| `LlmStreamer`                                     | `ModelClient`, as every client streams                      |
| `ReplyOptions`                                    | `ModelRequest.Options`                                      |
| `Answer[A]`, as `ask` gives it                    | `ValidatedResponse[F, A]`                                   |
| `Answer[Nothing]`, a reply as the provider gave it | `ModelResponse`                                            |
| `Part`                                            | `MessagePart`                                               |
| `message.content`                                 | `message.parts`                                             |
| `message.uncached`                                | `message.withoutBreakpoints`                                |
| `Role.Assistant`                                  | `Role.Model`                                                |
| `Chat.of(...)`                                    | `Chat.userSays(...)`                                        |
| `chat.user(...)`, `chat.assistant(...)`           | `chat.userSays(...)`, `chat.modelSays(...)`                 |
| `chat.withSystem(text)`, `chat.system`, `Chat(system = …)` | `chat.withInstructions(text)`, `chat.instructions`, `Chat(instructions = …)` |
| `chat.cacheable`                                  | `chat.cachePrefix`                                          |
| `client.count(chat, options)`                     | `client.countTokens(ModelRequest(chat, options))`           |
| `client.warm(chat, options)`                      | `client.warmCache(ModelRequest(chat, options))`             |
| `streamer.stream(chat, options)`                  | `client.stream(ModelRequest(chat, options))`                |
| `LlmProvider.key`                                 | `ModelProvider.id`                                          |
| `LlmError.Unreadable(attempts, detail, answers)`  | `ModelError.UnreadableResponse(detail, responses)`; `attempts` is `responses.size` |

- `send`, `stream`, `countTokens` and `warmCache` take one `ModelRequest`, a chat and its options, where they
  took the two apart; `ask` still takes them apart. A wrapper overrides `send(request: ModelRequest)` and passes
  the request on with `super.send(request)`.
- `ask` gives a `ValidatedResponse[F, A]`: its `value` as before, its `responses`, an effect giving every
  `ModelResponse` the ask took (each with its `text`, `stopReason`, `usage`, `cost` and `toolCalls`), and
  `cancel`, which stops a streamed response. `answer.usage` is now an effect totalling every response's, as
  `answer.cost` is. Read `answer.responses.map(_.size)` where you read `answer.attempts`, and the last
  response's fields where you read the answer's `text`, `stopReason` or `toolCalls`. Append the last
  response to a chat, `chat.modelSays(responses.last)`, where you appended the answer.
- `send` and `warmCache` give a `ModelResponse`, one response as the provider gave it, which has no
  `attempts`; `ModelError.UnreadableResponse` keeps every one, as `responses`.
- `Role.code` is gone: each provider names a role as its own API does.
- `ask` needs `Async`, as it reads a streamed response in a fiber of its own, so a client in `SyncIO` can
  no longer ask.
- `ModelResponse.usage` and `Delta.End.usage` are a `Usage`, never `None`: a count the provider did not
  report is `0`. Read `usage` where you matched `Some(usage)`.
- `Usage` is made of seven counts, named as you make one (`Usage(uncachedTokens = 10, visibleTokens = 2)`).
  `inputTokens`, `cachedTokens` and `outputTokens` are now totals of them, meaning what they did.
- `outputTokens` now counts Gemini's thinking, which it left out, and streams from Anthropic and
  OpenAI now count their usage, so totals may be higher than before.
- `Chat.userSays`, a chat's `userSays` and `modelSays` and `Message` take values of any kinds together, so
  `chat.user(Part.of("Review this."), Part.of(draft))` is now `chat.userSays("Review this.", draft)`. A
  spliced list must be of parts: where you spliced other values, write
  `chat.userSays(values.map(MessagePart.of)*)`.
- `MessagePart.Media` takes a `MediaType`, where `Part.Media` took a string: write
  `MessagePart.Media(MediaType.Png, data)`, or `MediaType.of(name)` for a name read at runtime, which is
  `None` where the name is no media type. It also takes the content's bytes, encoding them itself.
  Anthropic now refuses media other than pictures and PDFs before sending it, where its API refused it.
- `LlmStreamer` is gone, as every client streams: `LlmStreamer.resource`, `fromEnv` and `apply` are
  `ModelClient.resource`, `fromEnv` and `apply`. `ModelClient(config, backend)` over an sttp
  `StreamBackend` for fs2 streams too; over any other backend, a stream delivers its response whole, once
  it ends.
- `Delta` has a new case, `ToolCall`, which a match over every case must take.
- `MessagePart.ToolCall` has a fourth field, `signature`, Gemini's thought signature, so a match takes
  `MessagePart.ToolCall(id, tool, arguments, _)`. A Gemini call's `id` is Gemini's own where it gives one,
  no longer always the tool's name.
- A class implementing `ModelClient` itself, rather than through `ModelClient.Forwarding`, may implement
  `stream`, which unless it does delivers each response whole, through `send`. A wrapper that sees every
  request overrides it as it does `send`.
- A `ModelResponse` has a `cost`, an `Option[Usd]`, after `usage`, and `Delta.End` has one too, last.
- `ModelClient.apply` needs a `Clock` of its effect, which prices each response as it comes, as `IO` and
  `SyncIO` have.
- `ModelError` has two new cases, `OverBudget` and `Unpriced`, which a match over every case must take.

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

`PromptService.fromEnv` reads these environment variables, through `Model.fromEnv`, `ApiConfig.fromEnv` and
`PromptConfig.fromEnv`:

| Variable            | Meaning                                     | Default                        |
|---------------------|---------------------------------------------|--------------------------------|
| `LLM_PROVIDER`      | `anthropic`, `openai` or `gemini`           | `LLM_MODEL`'s, else by key    |
| `ANTHROPIC_API_KEY` | API key for Anthropic                       | -                              |
| `OPENAI_API_KEY`    | API key for OpenAI                          | -                              |
| `GEMINI_API_KEY`    | API key for Gemini (or `GOOGLE_API_KEY`)    | -                              |
| `LLM_MODEL`         | Model to use, by its API name (see below)   | Provider-specific default      |
| `LLM_MAX_TOKENS`    | Maximum number of tokens in each response   | `8192`, or the host's default  |
| `LLM_BASE_URL`      | Overrides the provider's API origin         | The provider's own origin      |
| `LLM_TIMEOUT`       | Seconds to wait for a response              | `300`                          |

With no key set, `fromEnv` yields `None`. So does a variable which is set but cannot be used:
an unrecognised `LLM_PROVIDER`, rather than falling back to whichever key exists, an `LLM_MODEL`
that names no model Iris lists or one which another provider serves, and an `LLM_MAX_TOKENS`
which is not a positive whole number, rather than quietly reverting to the default and hiding the
mistake. A model Iris does not list is named after its provider and a colon, as
`LLM_MODEL=anthropic:claude-...`, so that a mistyped name is still caught. A Gemini model may
also be written as Gemini's API lists it, as `gemini:models/gemini-...`.

To read several configurations from one environment, as one per kind of request, pass each `from` a
lookup of your own, which it reads every variable above through:

```scala
val fast = (name: String) => sys.env.get(s"${name}_FAST").orElse(sys.env.get(name))
(Model.from(fast), ApiConfig.from(fast), PromptConfig.from(fast)).tupled
```

A host whose responses run longer than most passes its own default for an unset `LLM_MAX_TOKENS`, as in
`PromptService.fromEnv[IO](defaultMaxTokens = 16_000)` or `PromptConfig.from(lookup, 16_000)`.

## 🌐 In the browser

In JavaScript, a service made by `model.prompt[IO]()` sends requests through the
[Fetch API](https://developer.mozilla.org/docs/Web/API/Fetch_API), and everything else is as on the JVM.
There is no environment to read, so `fromEnv` yields `None`: give `ApiConfig` a key yourself, or read the
configurations with their `from` from wherever your page keeps its settings.

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
