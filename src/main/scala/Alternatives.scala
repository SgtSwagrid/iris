package com.alecdorrington.iris

import scala.quoted.*

/** The schemas of unions and literal types, which have no mirrors. */
private[iris] object Alternatives:

  /**
    * The schema of a union, any one of its alternatives, or of a literal type,
    * its value alone. Transparent, so that a type which is neither, or an
    * alternative with no schema, makes it not given, rather than not compile.
    */
  transparent inline def of[A]: Schema[A] = ${ alternatives[A] }

  private def alternatives[A : Type](using Quotes): Expr[Schema[A]] =
    import quotes.reflect.*
    TypeRepr.of[A].dealias match
      case union: OrType =>
        val choices = flattened(union)
        distinguished(choices)
        val schemas = choices.map(choice => summoned(choice))
        val tests   = choices.map(choice => test(choice))
        '{
          Schema.anyOf[A](
            TypeName.of[A],
            ${ Expr.ofList(schemas) },
            ${ Expr.ofList(tests) },
          )
        }
      case literal @ ConstantType(constant) => literal.widen.asType match
          case '[w] =>
            val widened = summoned(literal.widen).asExprOf[Schema[w]]
            val value   = Literal(constant).asExprOf[A]
            '{
              Schema.literal[A](
                TypeName.of[A],
                $value,
                $widened.encoder($value.asInstanceOf[w]),
              )
            }
      case other => report.errorAndAbort(s"${ other.show } is no union")

  /** A union's alternatives, as it is written. */
  private def flattened
    (using Quotes)
    (union: quotes.reflect.TypeRepr)
    : List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    union.dealias match
      case OrType(left, right) => flattened(left) ++ flattened(right)
      case other               => List(other)

  private def summoned
    (using Quotes)
    (choice: quotes.reflect.TypeRepr)
    : Expr[Schema[?]] =
    import quotes.reflect.*
    choice.asType match
      case '[c] => Expr
          .summon[Schema[c]]
          .getOrElse(report.errorAndAbort(s"${ choice.show } has no Schema"))

  /**
    * Whether a value is of an alternative: its value, for a literal or another
    * singleton, or else its class, its type arguments erased.
    */
  private def test
    (using Quotes)
    (choice: quotes.reflect.TypeRepr)
    : Expr[Any => Boolean] =
    import quotes.reflect.*
    choice match
      case ConstantType(constant) =>
        val value = Literal(constant).asExpr
        '{ (candidate: Any) => candidate == $value }
      case singleton: TermRef =>
        val value = Ref(singleton.termSymbol).asExpr
        '{ (candidate: Any) => candidate == $value }
      case _ => erased(choice).asType match
          case '[e] => '{ (candidate: Any) => ${ instance[e]('candidate) } }

  /**
    * Whether a value is an instance of a type, built as a tree so that the test
    * is not flagged as unchecked, its arguments being erased already.
    */
  private def instance[E : Type]
    (using Quotes)
    (value: Expr[Any])
    : Expr[Boolean] =
    import quotes.reflect.*
    TypeApply(
      Select.unique(value.asTerm, "isInstanceOf"),
      List(TypeTree.of[E]),
    ).asExprOf[Boolean]

  /** A type, its arguments erased, as a runtime test sees it. */
  private def erased
    (using Quotes)
    (choice: quotes.reflect.TypeRepr)
    : quotes.reflect.TypeRepr =
    import quotes.reflect.*
    choice match
      case AppliedType(constructor, arguments) =>
        constructor.appliedTo(arguments.map(_ => TypeBounds.empty))
      case other => other

  /** Refuses a union whose alternatives a runtime test could not tell apart. */
  private def distinguished
    (using Quotes)
    (choices: List[quotes.reflect.TypeRepr])
    : Unit =
    import quotes.reflect.*
    val classes = choices.filterNot(_.isSingleton)
    for
      (first, index) <- classes.zipWithIndex
      second         <- classes.drop(index + 1)
      if erased(first) <:< erased(second) || erased(second) <:< erased(first)
    do
      report.errorAndAbort(s"${ first.show } and ${ second
          .show } cannot be told apart at runtime")
