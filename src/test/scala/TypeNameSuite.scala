package com.alecdorrington.iris

import munit.FunSuite

final case class Wrapping[F[_]](values: F[Int])

class TypeNameSuite extends FunSuite:

  test("a type constructor as an argument is named as it is written"):
    val named = TypeName.of[Wrapping[List]]
    assert(
      named.full.startsWith("com.alecdorrington.iris.Wrapping("),
      named,
    )
    assert(named.short.contains("List"), named)

  test("a union is named as it is written, not as no symbol"):
    val named = TypeName.of[Int | String]
    assert(!named.full.contains("<none>"), named)
    assert(named.short.contains("|"), named)
