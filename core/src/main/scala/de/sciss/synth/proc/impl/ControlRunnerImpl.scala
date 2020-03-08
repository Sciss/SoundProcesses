/*
 *  ControlRunnerImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2020 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc.impl

import de.sciss.lucre.expr.{Context, IControl}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.TxnLike.peer
import de.sciss.lucre.stm.{Obj, Sys, UndoManager}
import de.sciss.synth.proc.Runner.{Attr, Failed, Prepared, Running, Stopped}
import de.sciss.synth.proc.{Control, ExprContext, Runner, Universe}

import scala.annotation.tailrec
import scala.concurrent.stm.Ref
import scala.util.{Failure, Success, Try}

object ControlRunnerImpl {
  def apply[S <: Sys[S]](obj: Control[S])(implicit tx: S#Tx, universe: Universe[S]): Runner[S] =
    new Impl(tx.newHandle(obj))

  private final class Impl[S <: Sys[S]](objH: stm.Source[S#Tx, Control[S]])(implicit val universe: Universe[S])
    extends BasicRunnerInternalImpl[S] {

    type Repr = Control[S]

    private[this] val ctlRef  = Ref(Option.empty[Try[IControl[S]]])
    private[this] val attrRef = Ref(Context.emptyAttr[S])(NoManifest)

    def tpe: Obj.Type = Control

    def stop()(implicit tx: S#Tx): Unit = {
      disposeData()
      state = Stopped
    }

    override def toString = s"Runner.Control${hashCode().toHexString}"

    override protected def disposeData()(implicit tx: S#Tx): Unit = {
      super.disposeData()
      attrRef() = Context.emptyAttr
      disposeCtl()
    }

    private def disposeCtl()(implicit tx: S#Tx): Unit =
      ctlRef.swap(None) match {
        case Some(Success(c)) => c.dispose()
        case _ =>
      }

    @tailrec
    def prepare(attr: Attr[S])(implicit tx: S#Tx): Unit = {
      state match {
        case Stopped  =>
          attrRef() = attr
          val tr    = mkRef()
          state = tr match {
            case Success(_)   => Prepared
            case Failure(ex)  => Failed(ex)
          }

        case Prepared =>

        case _ => // running or done/failed; go back to square one
          stop()
          prepare(attr)
      }
    }

    @tailrec
    def run()(implicit tx: S#Tx): Unit = {
      state match {
        case Stopped =>
          mkRef()
          runWithRef()

        case Prepared =>
          runWithRef()

        case Running =>

        case _ => // done/failed; go back to square one
          stop()
          run()
      }
    }

    private def runWithRef()(implicit tx: S#Tx): Unit = {
      val trOpt = ctlRef()
      trOpt.foreach { tr =>
        state = Running
        val tr1 = tr.flatMap { c =>
          Try(c.initControl())
        }
        tr1 match {
          // do not set Running here; we do that initially (above),
          // and if the logic stops `ThisRunner`, the state
          // will already have been set.
          case Success(_)   => // Running
          case Failure(ex)  => state = Failed(ex)
        }
      }
    }

    private def mkRef()(implicit tx: S#Tx): Try[IControl[S]] = {
      disposeCtl()
      val ctl   = objH()
      implicit val u: UndoManager[S]  = UndoManager()
      val attr  = attrRef()
      implicit val ctx: Context[S]    = ExprContext(Some(objH), attr, Some(this))
      val g     = ctl.graph.value
      val res   = Try(g.expand[S])
      ctlRef()  = Some(res)
      res
    }

//    object progress extends Runner.Progress[S#Tx] with DummyObservableImpl[S] {
//      def current(implicit tx: S#Tx): Double = -1
//    }
  }
}
