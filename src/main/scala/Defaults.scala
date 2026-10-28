package com.alecdorrington.iris

import scala.quoted.*

/** The default values of a case class's fields. */
private[iris] object Defaults:

  /**
    * Each field's default, in order, where it has one, made anew whenever it is
    * read. A class nested in another class's instance has none, as its
    * companion cannot be reached without the instance.
    */
  inline def of[A]: List[Option[() => Any]] = ${ defaults[A] }

  private def defaults[A : Type](using Quotes): Expr[List[Option[() => Any]]] =
    import quotes.reflect.*
    val tpe    = TypeRepr.of[A].dealias
    val owner  = tpe.typeSymbol
    val fields = owner
      .primaryConstructor
      .paramSymss
      .find(_.exists(_.isTerm))
      .getOrElse(Nil)
    val arguments = tpe match
      case AppliedType(_, arguments) => arguments
      case _                         => Nil
    val companion = owner.companionModule
    if tpe.isSingleton || !owner.flags.is(Flags.Case) ||
      !reachable(companion.owner)
    then '{ Nil }
    else
      Expr.ofList(
        fields
          .zipWithIndex
          .map((field, index) =>
            owner
              .companionClass
              .declaredMethod(s"$$lessinit$$greater$$default$$${ index + 1 }")
              .headOption
              .filter(_ => field.flags.is(Flags.HasDefault))
              .fold('{ None })(getter =>
                val selected = Ref(companion).select(getter)
                val generic  = getter.paramSymss.exists(_.exists(_.isTypeParam))
                val default  =
                  if generic then selected.appliedToTypes(arguments)
                  else selected
                '{ Some(() => ${ default.asExpr }) },
              ),
          ),
      )

  /** Whether what a symbol owns is reached by its name alone. */
  private def reachable(using Quotes)(owner: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    owner.isPackageDef || owner.isTerm ||
    (owner.flags.is(Flags.Module) && reachable(owner.owner))
