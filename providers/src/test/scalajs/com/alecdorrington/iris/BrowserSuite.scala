package com.alecdorrington.iris

import com.alecdorrington.iris.anthropic.*
import com.alecdorrington.iris.providers.*
import munit.FunSuite

class BrowserSuite extends FunSuite:

  test("a request to Anthropic says that it may come from a page"):
    assert(
      Anthropic
        .credentials("key")
        .exists(header =>
          header.name == "anthropic-dangerous-direct-browser-access" &&
          header.value == "true",
        ),
    )

  test("there is no environment to configure a model from"):
    assertEquals(Providers.fromEnv(), None)
