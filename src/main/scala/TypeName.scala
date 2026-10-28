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
    * schemas name them, so that a generic type derived once for every argument
    * is still named for each.
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

  /**
    * A type argument's name, as its schema has it, where it has one, or else as
    * it is written, as a type constructor's is.
    */
  private def argument
    (using Quotes)
    (argument: quotes.reflect.TypeRepr)
    : Expr[TypeName] = argument.asType match
    case '[a] => Expr.summon[Schema[a]] match
        case Some(schema) => '{ $schema.name }
        case None         => naming[a]
    case _ => shown(argument)

  /**
    * The name of a type's symbol: a singleton's term, else its class, or else,
    * as for a union or a literal, the type as it is written.
    */
  private def symbolic
    (using Quotes)
    (repr: quotes.reflect.TypeRepr)
    : Expr[TypeName] =
    import quotes.reflect.*
    repr match
      case singleton: TermRef                   => named(singleton.termSymbol)
      case literal: ConstantType                => shown(literal)
      case union: OrType                        => shown(union)
      case both: AndType                        => shown(both)
      case other if other.typeSymbol.isNoSymbol => shown(other)
      case other                                => named(other.typeSymbol)

  private def named
    (using Quotes)
    (symbol: quotes.reflect.Symbol)
    : Expr[TypeName] =
    val full  = Expr(symbol.fullName.replace("$", ""))
    val short = Expr(symbol.name.replace("$", ""))
    '{ TypeName($full, $short) }

  /** A type's name as it is written, in full and in short. */
  private def shown
    (using Quotes)
    (repr: quotes.reflect.TypeRepr)
    : Expr[TypeName] =
    import quotes.reflect.*
    val full  = Expr(repr.show)
    val short = Expr(repr.show(using Printer.TypeReprShortCode))
    '{ TypeName($full, $short) }
