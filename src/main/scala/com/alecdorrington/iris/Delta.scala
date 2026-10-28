package com.alecdorrington.iris

/**
  * A piece of a reply arriving over an [[LlmStreamer]]: a run of [[Text]]
  * followed by one [[End]].
  */
enum Delta:

  /**
    * More of the reply's text, to append to what came before.
    *
    * @param text
    *   The new text.
    */
  case Text(text: String)

  /**
    * The end of the reply.
    *
    * @param stopReason
    *   The reason the model stopped.
    *
    * @param usage
    *   The token counts, where the provider reports them at the end. Anthropic
    *   reports none here; use [[LlmClient.send]] for its usage.
    */
  case End
    (
      stopReason: StopReason,
      usage: Option[Usage],
    )
