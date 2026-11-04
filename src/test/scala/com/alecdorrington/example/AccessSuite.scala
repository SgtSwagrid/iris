// Outside Iris's package, so that access is checked as for a user.
package com.alecdorrington.example

import munit.FunSuite

class AccessSuite extends FunSuite:

  /** A prompt service to compile against, never made. */
  def prompt: com.alecdorrington.iris.PromptService[cats.effect.SyncIO] =
    throw AssertionError("A service was made.")

  test("a wrapper cannot change how send gathers what stream gives"):
    val errors = compileErrors(
      """new com.alecdorrington.iris.PromptService[cats.effect.SyncIO]:
        override def stream
          (request: scala.Conversion.into[com.alecdorrington.iris.PromptRequest])
          : fs2.Stream[cats.effect.SyncIO, com.alecdorrington.iris.PromptEvent] =
          prompt.stream(request)
        override def send
          (request: scala.Conversion.into[com.alecdorrington.iris.PromptRequest])
          (using
            fs2.Compiler[cats.effect.SyncIO, cats.effect.SyncIO],
            cats.MonadThrow[cats.effect.SyncIO],
          )
          : cats.effect.SyncIO[com.alecdorrington.iris.PromptResponse] =
          prompt.send(request)""",
    )
    assert(errors.contains("final"), errors)

  test("a wrapper writes stream alone, passing each request on"):
    val errors = compileErrors(
      """val wrapped: com.alecdorrington.iris.PromptService[cats.effect.SyncIO] =
        request => prompt.stream(request)""",
    )
    assertEquals(errors, "")
