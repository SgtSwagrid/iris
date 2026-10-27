package com.alecdorrington.iris

import scala.quoted.*

/**
  * The name of a type, its arguments applied: in full, to tell types apart, and
  * short, to show where it does not clash.
  *
  * @param full
  *   The name in full, packages included.
  *
  * @param short
  *   The name alone.
  */
private[iris] final case class TypeName(full: String, short: String):

  /** This type constructor's name, applied to its arguments'. */
  def applied(arguments: List[TypeName]): TypeName =
    if arguments.isEmpty then this
    else
      TypeName(
        s"$full(${ arguments.map(_.full).mkString(",") })",
        s"$short(${ arguments.map(_.short).mkString(",") })",
      )

private[iris] object TypeName:

  /** The name of a type in Scala's standard library. */
  def standard(name: String): TypeName = TypeName(s"scala.$name", name)

  /**
    * The name of a type, as it is compiled, its arguments named as their
    * structured types name them, so that a generic type derived once for every
    * argument is still named for each.
    */
  inline def of[A]: TypeName = ${ naming[A] }

  private def naming[A : Type](using Quotes): Expr[TypeName] =
    import quotes.reflect.*
    TypeRepr.of[A].dealias match
      case AppliedType(constructor, arguments) =>
        val named   = symbolic(constructor)
        val applied = Expr.ofList(arguments.map(argument))
        '{ $named.applied($applied) }
      case other => symbolic(other)

  /** A type argument's name, as its structured type has it, where it has one. */
  private def argument
    (using Quotes)
    (argument: quotes.reflect.TypeRepr)
    : Expr[TypeName] = argument.asType match
    case '[a] => Expr.summon[Structured[a]] match
        case Some(structured) => '{ $structured.name }
        case None             => naming[a]

  /** The name of a type's symbol: a singleton's term, else its class. */
  private def symbolic
    (using Quotes)
    (repr: quotes.reflect.TypeRepr)
    : Expr[TypeName] =
    import quotes.reflect.*
    val symbol = repr match
      case singleton: TermRef => singleton.termSymbol
      case other              => other.typeSymbol
    val full  = Expr(symbol.fullName.replace("$", ""))
    val short = Expr(symbol.name.replace("$", ""))
    '{ TypeName($full, $short) }
