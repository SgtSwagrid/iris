# CLAUDE.md

OpenAI's provider (`iris-openai`): `OpenAi`, the `Provider` speaking the Chat Completions API, and its models,
`OpenAiModel`. What every provider must do is in [http/CLAUDE.md](../http/CLAUDE.md), and how caching, usage, rates
and effort are sent by each provider in the repository's [CLAUDE.md](../../CLAUDE.md).

- `sampling` holds the models that still take `temperature` and `top_p`: every listed one before GPT-6.2, whose guide
  says to leave both out of a Chat Completions request. An unlisted model gets neither, a proxy's own included.
- `cappedAtHigh` holds the models whose highest reasoning effort is `high`, to which `Max` is sent as `high`.
- Tool results go as one `tool` message each, taken out of a message before its texts are separated.
- It offers no endpoint counting tokens, so its models' `counter` is `None`.
- It takes PDFs as `file` parts, which Iris does not send yet.
