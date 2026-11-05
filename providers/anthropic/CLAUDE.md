# CLAUDE.md

Anthropic's provider (`iris-anthropic`): `Anthropic`, the `Provider` speaking the Messages API, and its models,
`AnthropicModel`. What every provider must do is in [http/CLAUDE.md](../http/CLAUDE.md), and how caching, usage,
rates and effort are sent by each provider in the repository's [CLAUDE.md](../../CLAUDE.md).

- What its models differ in is kept here as sets of the older cases that differ from the newest (`sampling`,
  `prefilling`), so that `AnthropicModel.Other` is taken to be as new as the newest.
- It marks at most four cache breakpoints a request (`maxBreakpoints`), as `cache_control` on the block before each,
  and warming asks it for `max_tokens: 0` (`leastMaxTokens`).
- In a browser, every request says it may come from a page (`anthropic-dangerous-direct-browser-access`, by
  `Platform.browser`), which Anthropic refuses one carrying a page's origin without.
- A stream's `message_start` and `ping` only open it (`opening`), and its `error` event fails it (`failure`).
