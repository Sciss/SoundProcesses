/*
 *  TransportImpl.scala
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

import de.sciss.lucre.Txn.peer
import de.sciss.lucre.expr.Context
import de.sciss.lucre.impl.ObservableImpl
import de.sciss.lucre.{Disposable, Ident, IdentMap, Obj, Source, synth}
import de.sciss.proc.SoundProcesses.{logTransport => logT}
import de.sciss.proc.{AuralContext, AuralObj, Scheduler, TimeRef, Transport, Universe}
import de.sciss.span.Span

import scala.concurrent.stm.{Ref, TSet}

object TransportImpl {
  def apply[T <: synth.Txn[T]](universe: Universe[T], attr: Context.Attr[T] = Context.emptyAttr[T])
                              (implicit tx: T): Transport[T] = {
    implicit val u: Universe[T] = universe
    val objMap  = tx.newIdentMap[Source[T, Obj[T]]]
    val viewMap = tx.newIdentMap[AuralObj[T]]
    new Impl(objMap, viewMap, attr).init()
  }

  private final class Impl[T <: synth.Txn[T]](objMap : IdentMap[T, Source[T, Obj[T]]],
                                              viewMap: IdentMap[T, AuralObj[T]], attr: Context.Attr[T])
                                       (implicit val universe: Universe[T])
    extends Transport[T] with ObservableImpl[T, Transport.Update[T]] {

    def scheduler: Scheduler[T] = universe.scheduler

    private final class PlayTime(val wallClock0: Long, val pos0: Long) {
      override def toString = s"[pos0 = ${TimeRef.framesAndSecs(pos0)}, time0 = $wallClock0]"

      def isPlaying: Boolean = wallClock0 != Long.MinValue

      def play()(implicit tx: T): PlayTime =
        new PlayTime(wallClock0 = scheduler.time, pos0 = pos0)

      def currentPos(implicit tx: T): Long = if (!isPlaying) pos0 else {
        val wc1   = scheduler.time
        val delta = wc1 - wallClock0
        pos0 + delta
      }

      def stop()(implicit tx: T): PlayTime =
        new PlayTime(wallClock0 = Long.MinValue, pos0 = currentPos)
    }

    // we stupidly need these because identifier-map doesn't have an iterator
    private[this] val objSet      = TSet.empty[Source[T, Obj[T]]]
    private[this] val viewSet     = TSet.empty[AuralObj[T]]
    private[this] val timeBaseRef = Ref(new PlayTime(wallClock0 = Long.MinValue, pos0 = 0L))
    private[this] var obsUniverse: Disposable[T] = _

    def init()(implicit tx: T): this.type = {
      obsUniverse = universe.react { implicit tx => {
        case Universe.AuralStarted(ac)  => auralStartedTx(ac)
        case Universe.AuralStopped()    => auralStoppedTx()
        case _ =>
      }}
      // we don't need to call this, as `objSet` is empty at this point
      // universe.auralContext.foreach(auralStartedTx)
      this
    }

    def views(implicit tx: T): Set[AuralObj[T]] = viewSet.toSet

    def getView    (obj: Obj  [T])(implicit tx: T): Option[AuralObj[T]] = getViewById(obj.id)
    def getViewById(id : Ident[T])(implicit tx: T): Option[AuralObj[T]] = viewMap.get(id)

    def play()(implicit tx: T): Unit = {
      val timeBase0 = timeBaseRef()
      if (timeBase0.isPlaying) return

      val timeBase1 = timeBase0.play()
      timeBaseRef() = timeBase1
      logT.debug(s"play() - $timeBase1")

      playViews()
      fire(Transport.Play(this, timeBase1.pos0))
    }

    private def playViews()(implicit tx: T): Unit = {
      val tr = mkTimeRef()
      logT.debug(s"playViews() - $tr")
      viewSet.foreach(_.run(tr, ()))
    }

    def stop()(implicit tx: T): Unit = {
      val timeBase0 = timeBaseRef()
      if (!timeBase0.isPlaying) return

      val timeBase1 = timeBase0.stop()
      timeBaseRef() = timeBase1
      logT.debug(s"stop() - $timeBase1")

      stopViews()
      fire(Transport.Stop(this, timeBase1.pos0))
    }

    private def stopViews()(implicit tx: T): Unit =
      viewSet.foreach(_.stop())

    def position(implicit tx: T): Long = timeBaseRef().currentPos

    def seek(position: Long)(implicit tx: T): Unit = if (this.position != position) {
      val p = isPlaying
      if (p) stopViews()

      val timeBase1 = new PlayTime(wallClock0 = if (p) scheduler.time else Long.MinValue, pos0 = position)
      timeBaseRef() = timeBase1
      logT.debug(s"seek($position) - $timeBase1")

      if (p) playViews()
      fire(Transport.Seek(this, timeBase1.pos0, isPlaying = p))
    }

    def isPlaying(implicit tx: T): Boolean = timeBaseRef().isPlaying

    def addObject(obj: Obj[T])(implicit tx: T): Unit = {
      logT.debug(s"addObject($obj)")
      val id = obj.id
      if (objMap.contains(id)) throw new IllegalArgumentException(s"Object $obj was already added to transport")
      val objH = tx.newHandle(obj)
      objMap.put(id, objH)
      objSet.add(objH)
      fire(Transport.ObjectAdded(this, obj))

      contextOption.foreach { implicit context =>
        val view = mkView(obj)
        if (isPlaying) view.run(mkTimeRef(), ())
      }
    }

    def removeObject(obj: Obj[T])(implicit tx: T): Unit = {
      logT.debug(s"removeObject($obj)")
      val id    = obj.id
      // we need objH to find the index in objSeq
      val objH  = objMap.get(id) match {
        case Some(res) => res
        case None =>
          Console.err.println(s"Warning: transport - removeObject - not found: $obj")
          return
      }
      objMap.remove(id)
      objSet.remove(objH)
      // note - if server not running, there are no views
      viewMap.get(id).foreach { view =>
        viewMap.remove(id)
        viewSet.remove(view)
        if (isPlaying) view.stop()
        fire(Transport.ViewRemoved(this, view))
      }

      fire(Transport.ObjectRemoved(this, obj))
    }

    private def mkTimeRef()(implicit tx: T) = TimeRef(Span.from(0L), offset = position)

    private def mkView(obj: Obj[T])(implicit tx: T, context: AuralContext[T]): AuralObj[T] = {
      val view = AuralObj(obj, attr)
      viewMap.put(obj.id, view)
      viewSet.add(view)
      fire(Transport.ViewAdded(this, view))
      view
    }

    override def dispose()(implicit tx: T): Unit = {
      obsUniverse.dispose()
      objMap.dispose()
      objSet.foreach { obj =>
        fire(Transport.ObjectRemoved(this, obj()))
      }
      objSet.clear()
      disposeViews()
    }

    private def disposeViews()(implicit tx: T): Unit = {
      viewMap.dispose()
      viewSet.foreach { view =>
        fire(Transport.ViewRemoved(this, view))
        view.dispose()
      }
      viewSet.clear()
    }

    // ---- aural system ----

    def contextOption(implicit tx: T): Option[AuralContext[T]] = universe.auralContext

    private def auralStartedTx(ac: AuralContext[T])(implicit tx: T): Unit = {
      logT.debug(s"transport - aural-system started")
      // fire(AuralStarted(this, auralContext))
      objSet.foreach { objH =>
        val obj = objH()
        mkView(obj)(tx, ac)
      }
      if (isPlaying) playViews()
    }

    private def auralStoppedTx()(implicit tx: T): Unit = {
      logT.debug(s"transport - aural-system stopped")
      disposeViews()
    }
  }
}
