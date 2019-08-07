/*
 *  TimelineRunnerImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2019 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc.impl

import de.sciss.lucre.event.impl.DummyObservableImpl
import de.sciss.lucre.expr.BooleanObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.TxnLike.peer
import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.Runner.Universe
import de.sciss.synth.proc.{AuralObj, Runner, TimeRef, Timeline, Transport}

import scala.concurrent.stm.Ref

object TimelineRunnerImpl {
  def apply[S <: Sys[S]](obj: Timeline[S])(implicit tx: S#Tx, universe: Runner.Universe[S]): Runner[S] = {
    new Impl(tx.newHandle(obj), universe).init(obj)
  }

  private final class Impl[S <: Sys[S]](val objH: stm.Source[S#Tx, Timeline[S]],
                                        val universe: Universe[S])
    extends BasicRunnerImpl[S] {

    override def toString = s"Runner.Timeline${hashCode().toHexString}"

    def tpe: Obj.Type = Timeline

    private[this] val dispatchedState = Ref[Runner.State](Runner.Stopped)
    private[this] val targetState     = Ref[Runner.State](Runner.Stopped)
    private[this] val auralRef        = Ref(Option.empty[AuralObj[S]])
    private[this] val auralObs        = Ref(Disposable.empty[S#Tx])
    private[this] val schToken        = Ref(-1)

    //    private[this] var tObs: Disposable[S#Tx] = _

    object progress extends Runner.Progress[S#Tx] with DummyObservableImpl[S] {
      def current(implicit tx: S#Tx): Double = -1
    }

    //    def factory: Runner.Factory = Runner.Timeline

    def init(obj: Timeline[S])(implicit tx: S#Tx): this.type = {
      ???
//      val vOpt = t.getView(obj)
//      vOpt.foreach(auralViewAdded)
//      // no need to store the observer, as the transport
//      // will be disposed with the runner
//      /* tObs = */ t.react { implicit tx => {
//        case Transport.ViewAdded  (_, v) => auralViewAdded  (v)
//        case Transport.ViewRemoved(_, v) => auralViewRemoved(v)
//        case _ =>
//      }}
      this
    }

    private def auralViewAdded(v: AuralObj[S])(implicit tx: S#Tx): Unit = {
      val ts = targetState()
      val ds = dispatchedState()
      val newObs = v.react { implicit tx => state =>
        val old = dispatchedState.swap(state)
        if (state != old) fire(state)
      }
      auralObs.swap(newObs).dispose()
      auralRef() = Some(v)
      val vs = v.state
      if (ds != vs) {
        dispatchedState() = vs
        fire(vs)
      }
      if (vs != ts) ts match {
        case Runner.Stopped   => stop   ()
        case Runner.Preparing => prepare(???) // TimeRef.undefined)
        case Runner.Running   => run    ()
        case _ =>
      }
    }

    private def auralViewRemoved(v: AuralObj[S])(implicit tx: S#Tx): Unit = {
      auralObs.swap(Disposable.empty).dispose()
      val s   = Runner.Stopped
      val old = dispatchedState.swap(s)
      if (s != old) {
        fire(s)
      }
    }

    def prepare(attr: Runner.Attr)(implicit tx: S#Tx): Unit = {
      targetState() = Runner.Preparing
      auralRef().foreach(_.prepare(???))  // XXX TODO --- should we pass on `attr`?
    }

    def run()(implicit tx: S#Tx): Unit = {
      targetState() = Runner.Running
      auralRef().foreach { v =>
        val timeRef = TimeRef.Undefined
        v.run(timeRef, ())
        val tl = objH()
        tl.lastEvent.foreach { timeStop =>
          val now = timeRef.force.span.start
          val dt  = timeStop - now
          if (dt > 0) {
            val sch   = universe.scheduler
            val timeS = sch.time
            val token = sch.schedule(timeS + dt) { implicit tx =>
              val tl    = objH()
              val loop  = tl.attr.$[BooleanObj]("loop").exists(_.value) // bit of a hack LOL
              if (loop) {
                stop()
                run()
              }
            }
            cancelSch()
            schToken() = token
          }
        }
      }
    }

    private def cancelSch()(implicit tx: S#Tx): Unit = {
      val token = schToken.swap(-1)
      if (token >= 0) universe.scheduler.cancel(token)
    }

    def stop()(implicit tx: S#Tx): Unit = {
      cancelSch()
      targetState() = Runner.Stopped
      auralRef().foreach(_.stop())
    }

    protected def disposeData()(implicit tx: S#Tx): Unit = {
      cancelSch()
      auralObs.swap(Disposable.empty).dispose()
//      t.dispose()
    }
  }
}