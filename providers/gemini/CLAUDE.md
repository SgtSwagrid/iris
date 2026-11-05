# CLAUDE.md

Google's Gemini API (`iris-gemini`): `Gemini`, the `Provider` speaking it, and its models, `GeminiModel`. It is named
after the API, so that Vertex AI could be a module of its own. What every provider must do is in
[http/CLAUDE.md](../http/CLAUDE.md), and how caching, usage, rates and effort are sent by each provider in the
repository's [CLAUDE.md](../../CLAUDE.md).

- A model may be named after `models/`, as Gemini lists them, which `GeminiModel.of` drops.
- It leaves out every count of `0` (protobuf's JSON), so a count left out is `0`, never unknown.
- A call may come with no `id`, for which its name stands in, never sent as one (`issued`).
- A streamed request goes to `streamGenerateContent` with `alt=sse`, a path apart from a whole one's.
- Media of any type is sent, Gemini judging for itself what it takes.
