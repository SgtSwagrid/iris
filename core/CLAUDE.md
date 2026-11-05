# CLAUDE.md

Iris's core (`iris-core`): the `prompt`, `query` and `agent` layers, conversations, usage, money and schemas, with no
provider, no HTTP and no code of one platform's. The repository's [CLAUDE.md](../CLAUDE.md) describes the whole, and
what every module shares.

- `QueryService.send[A : Schema]` reads every type one way, `String` included, and always gives a
  `QueryResponse[F, A]`: `value: Option[A]`, `None` where the model asked for a tool, and `attempts`, every
  response the query took, the last the one the value was read from. Tool calls count only under `ToolCall`,
  `Completed` or `StopSequence` (`ValueReader.calledTools`), never under a refusal or the token limit. Never read one type differently from another, as
  0.2's `Answer` type class once did, nor give some types a different result: the user wants every ask
  uniform. The provider's own response is a `PromptResponse`, which a prompt's `send` and `warmCache` give. A query is built on
  its prompt service alone, provider-agnostic, passing `ValueReader.whole` its `send` as a function: the schema is asked for in words closing the
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
- A query of a type holding an `fs2.Stream` (`LiveReader`) gives its `QueryResponse` as soon as all of `A` but its streams is
  read; each stream (not within a list or map, which are read whole)
  is then fed as its elements close. The user chose live streams in the value over snapshots of a partial value,
  knowing the costs: `Schema[Stream[F, A]]`'s encoder throws (a stream cannot be said without being run), nothing is
  asked again once handed over (a later failure fails the unfinished streams and `responses`: `UnreadableResponse` for a reply,
  a request's own error), and `F` must be the client's (the cast is unchecked). Tool calls stream as
  `ProviderEvent.ToolCall`, each once whole (Anthropic's and OpenAI's argument fragments gathered by a `PartialToolCall`), so a streamed
  ask takes tools as any does. The model is told a stream as an array, the same schema as a `List`. Each schema's `Arrival` says how it is read early (`Whole`, `Streamed`, or `Assembled` from
  members, by key or index, `ready` once those begun have settled, giving the streams still to come); fields are
  taken to be written in order (`Schema.awaited`), so an optional one absent before a begun one is left out, and
  required streams after the last begun are handed over before they begin (`Frame.coming`), so that every trailing
  stream of a value is live. A value is handed over before it is whole only while a member it is writing is open and
  read already (`PartialParser.open`): until a stream begins, a reply that cannot be read can still be asked again, and
  a union whose case is whole waits to be whole itself. So how a reply's parts arrive can decide whether a value is
  given early (a stream opening and closing within one part is never seen open); tests that mean a stream to be
  seen open feed it a character at a time. A given answer's reply is checked at its end as `verdict` checks one
  (`LiveReader.fault`): tool calls, a stop, the token limit or unended JSON fail it as `UnreadableResponse`. Once a value is handed over (`Frame.taken`), a member it
  knows arriving later, or a reserved stream never begun, fails the reply rather than be lost. `PartialParser` follows only brackets, strings,
  escapes, commas and colons
  (circe parses each value once whole, and the schema's own decoder reads it, so every value is decoded by the
  same code as a whole reply), standing in for each open stream with a placeholder `Json` found by identity
  through a `DynamicVariable` while decoding. A type with no stream is not read early, but at the end through
  `ValueReader.verdict`, as a whole query reads it. `LiveReader` drives it in a background fiber, keeping each stream's elements
  and end in a `SignallingRef` (never a `Channel`, which serves one reader), so a stream reads alike however often,
  as `Stream.emits` from a whole reply does.
