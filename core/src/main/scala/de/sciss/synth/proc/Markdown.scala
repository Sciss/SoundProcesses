/*
 *  Markdown.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2018 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc

import de.sciss.lucre.event.Targets
import de.sciss.lucre.expr.Expr
import de.sciss.lucre.expr.impl.ExprTypeImpl
import de.sciss.lucre.stm.Sys
import de.sciss.serial.ImmutableSerializer
import de.sciss.synth.proc

object Markdown extends ExprTypeImpl[String, Markdown] {
  import proc.{Markdown => Repr}

  final val typeID          = 29
  final val valueSerializer = ImmutableSerializer.String

  protected def mkConst[S <: Sys[S]](id: S#ID, value: A)(implicit tx: S#Tx): Const[S] =
    new _Const[S](id, value)

  protected def mkVar[S <: Sys[S]](targets: Targets[S], vr: S#Var[Ex[S]], connect: Boolean)
                                  (implicit tx: S#Tx): Var[S] = {
    val res = new _Var[S](targets, vr)
    if (connect) res.connect()
    res
  }

  private[this] final class _Const[S <: Sys[S]](val id: S#ID, val constValue: A)
    extends ConstImpl[S] with Repr[S]

  private[this] final class _Var[S <: Sys[S]](val targets: Targets[S], val ref: S#Var[Ex[S]])
    extends VarImpl[S] with Repr[S]

  type Value = String
}

/** The markdown type is more or less the same
  * as a `StringObj`, but with dedicated type id.
  * In the future, we will have to evaluate whether
  * this makes sense and whether we need bridge
  * methods between `Markdown <=> StringObj`.
  *
  * Markdown links and inserted objects are found
  * through the object's attribute map.
  */
trait Markdown[S <: Sys[S]] extends Expr[S, String]
