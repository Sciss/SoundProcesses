package de.sciss.synth.proc
package impl

import de.sciss.lucre.event.Targets
import de.sciss.lucre.expr.impl.ListImpl
import de.sciss.lucre.stm.impl.ObjSerializer
import de.sciss.lucre.stm.{Copy, Elem, NoSys, Obj, Sys}
import de.sciss.serial.{DataInput, Serializer}

import scala.language.higherKinds

object FolderImpl {

  def apply[S <: Sys[S]](implicit tx: S#Tx): Folder[S] =
    new Impl1[S] {
      protected val targets = Targets[S]
      protected val sizeRef = tx.newIntVar(id, 0)
      protected val headRef = tx.newVar[C](id, null)(CellSer)
      protected val lastRef = tx.newVar[C](id, null)(CellSer)
    }

  def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Folder[S]] =
    anySer.asInstanceOf[Ser[S]]

  private val anySer = new Ser[NoSys]

  // XXX TODO --- DRY - should make this public in expr.ListImpl
  private def copyList[In <: Sys[In], Out <: Sys[Out]](in : Folder[In ], out: Folder[Out])
                                                      (implicit txIn: In#Tx, txOut: Out#Tx,
                                                       context: Copy[In, Out]): Unit = {
    in.iterator.foreach { elem =>
      out.addLast(context(elem))
    }
  }

  private class Ser[S <: Sys[S]] extends ObjSerializer[S, Folder[S]] {
    def tpe = Folder
  }

  def readIdentifiedObj[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Obj[S] = {
    val targets = Targets.read[S](in, access)
    FolderImpl.read(in, access, targets)
  }

  private def read[S <: Sys[S]](in: DataInput, access: S#Acc, _targets: Targets[S])
                               (implicit tx: S#Tx): Impl1[S] =
    new Impl1[S] {
      protected val targets = _targets
      protected val sizeRef = tx.readIntVar(id, in)
      protected val headRef = tx.readVar[C](id, in)
      protected val lastRef = tx.readVar[C](id, in)
    }

  private abstract class Impl1[S <: Sys[S]]
    extends ListImpl.Impl[S, Obj] with Folder[S] {

    in =>

    final def tpe: Obj.Type = Folder

    override def toString = s"Folder$id"

    def modifiableOption: Option[Folder[S]] = Some(this)

    final def copy[Out <: Sys[Out]]()(implicit tx: S#Tx, txOut: Out#Tx, context: Copy[S, Out]): Elem[Out] = {
      val out = FolderImpl[Out]
      context.defer[ListAux](in, out)(copyList(in, out))
      // .connect
      out
    }
  }
}
