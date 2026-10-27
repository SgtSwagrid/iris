// Outside Iris's package, so that access is checked as for a user.
package com.alecdorrington.example

import munit.FunSuite

class AccessSuite extends FunSuite:

  /** A client to compile against, never made. */
  def client: com.alecdorrington.iris.LlmClient[cats.effect.SyncIO] =
    throw AssertionError("A client was made.")

  test("a caller asks, and cannot send"):
    val errors = compileErrors(
      "client.send(com.alecdorrington.iris.Chat(), com.alecdorrington.iris.ReplyOptions())",
    )
    assert(
      errors.contains("cannot be accessed"),
      errors,
    )

  test("a wrapper overrides send, passing it on through super"):
    val errors =
      compileErrors("""new com.alecdorrington.iris.LlmClient.Forwarding(client):
        override protected def send
          (
            chat: com.alecdorrington.iris.Chat,
            options: com.alecdorrington.iris.ReplyOptions,
          )
          : cats.effect.SyncIO[com.alecdorrington.iris.Reply[Nothing]] =
          super.send(chat, options)""")
    assertEquals(errors, "")
