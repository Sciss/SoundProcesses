/*
 *  ControlImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2021 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.proc.impl

import de.sciss.lucre.Event.Targets
import de.sciss.lucre.impl.{GeneratorEvent, ObjCastFormat, SingleEventNode}
import de.sciss.lucre.{AnyTxn, Copy, Elem, Obj, Pull, Txn}
import de.sciss.serial.{DataInput, DataOutput, TFormat}
import de.sciss.proc.Control

import scala.collection.immutable.{IndexedSeq => Vec}

object ControlImpl {
  private final val SER_VERSION = 0x4374  // "Ct"

  def apply[T <: Txn[T]]()(implicit tx: T): Control[T] = new New[T](tx)

  def read[T <: Txn[T]](in: DataInput)(implicit tx: T): Control[T] =
    format[T].readT(in)

  def format[T <: Txn[T]]: TFormat[T, Control[T]] = anyFmt.cast

  private val anyFmt = new Fmt[AnyTxn]

  private class Fmt[T <: Txn[T]] extends ObjCastFormat[T, Control] {
    def tpe: Obj.Type = Control
  }

  def readIdentifiedObj[T <: Txn[T]](in: DataInput)(implicit tx: T): Control[T] = {
    val targets = Targets.read(in)
    val serVer  = in.readShort()
    if (serVer == SER_VERSION) {
      new Read(in, targets, tx)
    } else {
      sys.error(s"Incompatible serialized (found $serVer, required $SER_VERSION)")
    }
  }

  // ---- node impl ----

  private sealed trait Impl[T <: Txn[T]]
    extends Control[T] with SingleEventNode[T, Control.Update[T]] {
    proc =>

    // --- abstract ----

    //    protected def outputsMap: SkipList.Map[T, String, Output[T]]

    // --- impl ----

    final def tpe: Obj.Type = Control

    override def toString: String = s"Control$id"

    def copy[Out <: Txn[Out]]()(implicit tx: T, txOut: Out, context: Copy[T, Out]): Elem[Out] =
      new Impl[Out] { out =>
        protected val targets: Targets[Out]                     = Targets[Out]()
        val graph     : Control.GraphObj.Var[Out]                   = context(proc.graph)
        //        val outputsMap: SkipList.Map[Out, String, Output[Out]]  = SkipList.Map.empty

        connect()
      }

    // ---- key maps ----

    final def connect()(implicit tx: T): this.type = {
      graph.changed ---> changed
      this
    }

    private def disconnect()(implicit tx: T): Unit = {
      graph.changed -/-> changed
    }

    object changed extends Changed
      with GeneratorEvent[T, Control.Update[T]] {
      def pullUpdate(pull: Pull[T])(implicit tx: T): Option[Control.Update[T]] = {
        val graphCh     = graph.changed
        val graphOpt    = if (pull.contains(graphCh)) pull(graphCh) else None
        val stateOpt    = if (pull.isOrigin(this)) Some(pull.resolve[Control.Update[T]]) else None

        val seq0 = graphOpt.fold(Vec.empty[Control.Change[T]]) { u =>
          Vector(Control.GraphChange(u))
        }

        val seq3 = stateOpt.fold(seq0) { u =>
          if (seq0.isEmpty) u.changes else seq0 ++ u.changes
        }
        if (seq3.isEmpty) None else Some(Control.Update(proc, seq3))
      }
    }

    final protected def writeData(out: DataOutput): Unit = {
      out.writeShort(SER_VERSION)
      graph     .write(out)
      //      outputsMap.write(out)
    }

    final protected def disposeData()(implicit tx: T): Unit = {
      disconnect()
      graph     .dispose()
      //      outputsMap.dispose()
    }
  }

  private final class New[T <: Txn[T]](tx0: T) extends Impl[T] {
    protected val targets: Targets[T] = Targets()(tx0)

    val graph: Control.GraphObj.Var[T] = Control.GraphObj.newVar(Control.GraphObj.empty(tx0))(tx0)

    //    val outputsMap: SkipList.Map[T, String, Output[T]]  = SkipList.Map.empty

    connect()(tx0)
  }

  private final class Read[T <: Txn[T]](in: DataInput, protected val targets: Targets[T], tx0: T)
    extends Impl[T] {

    val graph: Control.GraphObj.Var[T] = Control.GraphObj.readVar(in)(tx0)

    //    val outputsMap: SkipList.Map[T, String, Output[T]]  = SkipList.Map.read   (in, access)
  }
}