package com.alecdorrington.iris

import scala.quoted.*
import scala.util.matching.Regex

/**
  * What a Scaladoc comment says: its description, and those of the parameters
  * its `@param` tags name.
  *
  * @param description
  *   The comment's description, before its tags, if it has one.
  *
  * @param params
  *   The descriptions of the parameters, by name.
  */
private[iris] final case class Scaladoc
  (
    description: Option[String],
    params: Map[String, String],
  )

private[iris] object Scaladoc:

  /**
    * The description of a type, a case object or an enum's case, from its
    * Scaladoc, where it is compiled alongside.
    */
  inline def of[A]: Option[String] = ${ describing[A] }

  /**
    * The descriptions of a case class's fields, in order, from its `@param`
    * tags. Scala keeps no comment written on a parameter itself.
    */
  inline def fields[A]: List[Option[String]] = ${ describingFields[A] }

  /** Reads a comment as written, delimiters and margin included. */
  def parse(comment: String): Scaladoc =
    val (body, tags) = lines(comment).span(!_.startsWith("@"))
    Scaladoc(prose(body), params(tagged(tags)))

  private def describing[A : Type](using Quotes): Expr[Option[String]] =
    Expr(documented(subject[A]).flatMap(_.description))

  private def describingFields[A : Type]
    (using Quotes)
    : Expr[List[Option[String]]] =
    import quotes.reflect.*
    val owner  = TypeRepr.of[A].typeSymbol
    val params = documented(owner).fold(Map.empty)(_.params)
    Expr(owner.caseFields.map(field => params.get(field.name)))

  /** The symbol a type is documented on: a singleton's term, else its class. */
  private def subject[A : Type](using Quotes): quotes.reflect.Symbol =
    import quotes.reflect.*
    TypeRepr.of[A] match
      case singleton: TermRef => singleton.termSymbol
      case other              => other.typeSymbol

  private def documented
    (using Quotes)
    (symbol: quotes.reflect.Symbol)
    : Option[Scaladoc] = symbol
    .docstring
    .orElse(symbol.moduleClass.docstring)
    .map(parse)

  /** The comment's lines, without its delimiters or the margin of each. */
  private def lines(comment: String): List[String] = comment
    .stripPrefix("/**")
    .stripSuffix("*/")
    .linesIterator
    .map(_.trim.stripPrefix("*").trim)
    .toList

  /** The lines of each tag, continued until the next. */
  private def tagged(lines: List[String]): List[List[String]] = lines
    .foldLeft(List.empty[List[String]]):
      case (tags, line) if line.startsWith("@") => List(line) :: tags
      case (last :: tags, line)                 => (last :+ line) :: tags
      case (tags, _)                            => tags
    .reverse

  private def params(tags: List[List[String]]): Map[String, String] = tags
    .flatMap:
      case first :: rest if first.startsWith("@param") =>
        first.split("\\s+", 3).toList match
          case _ :: name :: said => prose(said ++ rest).map(name -> _)
          case _                 => None
      case _ => None
    .toMap

  /**
    * The text of the lines as prose: code examples left out, links as their
    * labels, and each paragraph on a line of its own.
    */
  private def prose(lines: List[String]): Option[String] =
    val text =
      linked(lines.mkString("\n").replaceAll("(?s)\\{\\{\\{.*?}}}", ""))
    Some(
      text
        .split("\\n\\s*\\n")
        .map(_.split("\\s+").filter(_.nonEmpty).mkString(" "))
        .filter(_.nonEmpty)
        .mkString("\n\n"),
    ).filter(_.nonEmpty)

  /** The text with each `[[link]]` written as its label, or else its target. */
  private def linked(text: String): String = link.replaceAllIn(
    text,
    found =>
      Regex.quoteReplacement(Option(found.group(2)).getOrElse(found.group(1))),
  )

  private val link: Regex = """\[\[([^\]\s]+)(?:\s+([^\]]+))?]]""".r
