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
    *   The token counts of the whole reply.
    *
    * @param cost
    *   What the reply cost in US dollars, as [[Answer.cost]] says.
    */
  case End
    (
      stopReason: StopReason,
      usage: Usage,
      cost: Option[BigDecimal] = None,
    )
