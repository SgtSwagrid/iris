package com.alecdorrington.iris

import com.alecdorrington.iris.Fixtures.config
import munit.FunSuite

class BrowserSuite extends FunSuite:

  test("a request to Anthropic says that it may come from a page"):
    assert(
      AnthropicApi
        .credentials(config)
        .exists(header =>
          header.name == "anthropic-dangerous-direct-browser-access" &&
          header.value == "true",
        ),
    )

  test("there is no environment to configure a client from"):
    assertEquals(ModelConfig.fromEnv(), None)
