package com.alecdorrington.iris

import munit.FunSuite

class ScaladocSuite extends FunSuite:

  test("a comment of one line is its description"):
    assertEquals(
      Scaladoc.parse("/** Warm. */"),
      Scaladoc(Some("Warm."), Map.empty),
    )

  test("a description's lines are joined, and its paragraphs kept apart"):
    val comment = """/**
      * A review
      *   of writing.
      *
      * Kept short.
      */"""
    assertEquals(
      Scaladoc.parse(comment).description,
      Some("A review of writing.\n\nKept short."),
    )

  test("code examples are left out, and links written as labels or targets"):
    val comment = """/**
      * Sent by [[LlmClient.ask]] and [[Part.of the part]].
      *
      * {{{
      * client.ask[Review](prompt)
      * }}}
      */"""
    assertEquals(
      Scaladoc.parse(comment).description,
      Some("Sent by LlmClient.ask and the part."),
    )

  test("each parameter is described by its tag, however it is wrapped"):
    val comment = """/**
      * A person.
      *
      * @tparam A
      *   Ignored.
      *
      * @param name
      *   Their name,
      *   in full.
      *
      * @param age Their age.
      * @return
      *   Ignored.
      */"""
    assertEquals(
      Scaladoc.parse(comment),
      Scaladoc(
        Some("A person."),
        Map(
          "name" -> "Their name, in full.",
          "age"  -> "Their age.",
        ),
      ),
    )

  test("a comment of tags alone has no description"):
    assertEquals(
      Scaladoc.parse("/** @param name Their name. */").description,
      None,
    )
