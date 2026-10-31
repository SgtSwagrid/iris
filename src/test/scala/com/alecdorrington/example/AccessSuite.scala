// Outside Iris's package, so that access is checked as for a user.
package com.alecdorrington.example

import munit.FunSuite

class AccessSuite extends FunSuite:

  /** A client to compile against, never made. */
  def client: com.alecdorrington.iris.ModelClient[cats.effect.SyncIO] =
    throw AssertionError("A client was made.")

  test("a caller asks, and cannot send"):
    val errors = compileErrors(
      "client.send(com.alecdorrington.iris.ModelRequest(com.alecdorrington.iris.Chat()))",
    )
    assert(
      errors.contains("cannot be accessed"),
      errors,
    )

  test("a wrapper overrides send, passing it on through super"):
    val errors = compileErrors(
      """new com.alecdorrington.iris.ModelClient.Forwarding(client):
        override protected def send
          (request: com.alecdorrington.iris.ModelRequest)
          : cats.effect.SyncIO[com.alecdorrington.iris.ModelResponse] =
          super.send(request)""",
    )
    assertEquals(errors, "")
