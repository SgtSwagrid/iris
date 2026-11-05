# CLAUDE.md

Every provider (`iris-providers`), and `Providers`, which chooses a provider and model at runtime, as from the
environment. Its tests are those of everything a provider takes part in, with core's example types (`test->test`).
The repository's [CLAUDE.md](../CLAUDE.md) describes the whole, and [http/CLAUDE.md](http/CLAUDE.md) what every
provider must do.

- Models are cases of their provider's own type (`AnthropicModel`, `OpenAiModel`, `GeminiModel`, each a
  `ProviderModel`), never strings: each names its provider (a prompt service's is its model's) and its API `id`,
  which `Providers.model` reads back. A model Iris does not list is its provider's `Other`, made only by its
  companion's `of(id)`, through `ProviderModel.of`, so that a listed id is always its listed case and a blank one
  none (and Gemini's `models/` is dropped). It is read as `provider:id`, so that a bare name must still be a listed
  one, and `Providers.model` refuses a listed id named after another provider, which `of` alone cannot know. List a
  new model as a case of its own. What one provider's models differ in (Anthropic's sampling and prefill, OpenAI's
  highest reasoning effort) is kept in its provider's object, as sets of the older cases that differ from the newest,
  so that `Other` is taken to be as new as the newest. A prompt service prompts its own model alone: no setting names
  another, so a request can never reach a provider that does not serve its model. A provider's `defaultModel`,
  `model` and `listed` are `def`s, as the provider and its models' companion each refer to the other while being
  made. `model.prompt(api, config)` takes any provider's `ApiService`: the design wanted its own provider's alone,
  but typing api services by provider was judged not worth its code (2026-11-05).
- A set but unrecognised `LLM_PROVIDER` or `LLM_MODEL` means unconfigured (`None`), never a fall back to key inference.
