/*
 *  AuralTimelineAttribute.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2015 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package impl

import de.sciss.lucre.bitemp.BiGroup
import de.sciss.lucre.data.SkipOctree
import de.sciss.lucre.geom.LongSpace
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, TxnLike, IdentifierMap, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.AuralAttribute.{Factory, Observer}

import scala.annotation.tailrec
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object AuralTimelineAttribute extends Factory {
  import AuralTimelineBase.spanToPoint

  type Repr[S <: stm.Sys[S]] = Timeline[S]

  private type Leaf[S <: Sys[S]] = AuralTimelineBase.Leaf[S, AuralAttribute[S]]

  def typeID = Timeline.typeID

  def apply[S <: Sys[S]](key: String, timeline: Timeline[S], observer: Observer[S])
                        (implicit tx: S#Tx, context: AuralContext[S]): AuralAttribute[S] = {
    val system  = tx.system
    val res     = prepare[S, system.I](key, timeline, observer, system)
    res.init(timeline)
  }

  private def prepare[S <: Sys[S], I1 <: stm.Sys[I1]](key: String, value: Timeline[S],
                                                      observer: Observer[S], system: S { type I = I1 })
                   (implicit tx: S#Tx, context: AuralContext[S]): AuralTimelineAttribute[S, I1] = {
    implicit val iSys     = system.inMemoryTx _
    implicit val itx      = iSys(tx)
    implicit val pointView = (l: Leaf[S], tx: I1#Tx) => spanToPoint(l._1)
    implicit val dummyKeySer = DummySerializerFactory[system.I].dummySerializer[Leaf[S]]
    val tree = SkipOctree.empty[I1, LongSpace.TwoDim, Leaf[S]](BiGroup.MaxSquare)

    val viewMap = tx.newInMemoryIDMap[AuralAttribute[S]]
    new AuralTimelineAttribute(key, tx.newHandle(value), observer, tree, viewMap)
  }
}
final class AuralTimelineAttribute[S <: Sys[S], I <: stm.Sys[I]](val key: String,
         val obj: stm.Source[S#Tx, Timeline[S]],
         observer: Observer[S],
         protected val tree: SkipOctree[I, LongSpace.TwoDim, AuralTimelineAttribute.Leaf[S]],
         protected val viewMap: IdentifierMap[S#ID, S#Tx, AuralAttribute[S]])
        (implicit protected val context: AuralContext[S], protected val iSys: S#Tx => I#Tx)
  extends AuralTimelineBase[S, I, AuralAttribute.Target[S], AuralAttribute[S]]
  with AuralAttribute[S]
  with Observer[S] {
  attr =>

  import TxnLike.peer

  type Elem = AuralAttribute[S]

  // we sample the first encountered objects for which temporary views
  // have to built in order to get the number-of-channels. these
  // temporary views are here and must be disposed with the parent view.
  private[this] val prefChansElemRef  = Ref[Vec[Elem]](Vector.empty)
  private[this] val prefChansNumRef   = Ref(-2)   // -2 = cache invalid. across contents of `prefChansElemRef`

  protected def makeView(obj: Obj[S])(implicit tx: S#Tx): Elem = AuralAttribute(key, obj, attr)

  protected def viewAdded  (timed: S#ID, view: Elem)(implicit tx: S#Tx): Unit = ()
  protected def viewRemoved(             view: Elem)(implicit tx: S#Tx): Unit = ()

  def preferredNumChannels(implicit tx: S#Tx): Int = {
    val cache = prefChansNumRef()
    if (cache > -2) {
      // println(s"preferredNumChannels - cached: $cache")
      return cache
    }

    val timeline  = obj()
    val time0     = timeline.firstEvent.getOrElse(-1L)
    if (time0 < 0L) {
      // println(s"preferredNumChannels - empty: -1")
      return -1
    }

    val entries = timeline.intersect(time0)
    if (entries.isEmpty) {
      // println(s"preferredNumChannels - empty: -1")
      return -1
    }

    val elems = entries.flatMap(_._2.map(_.value)).toVector
    val views = elems.map(makeView)
    prefChansElemRef.swap(views).foreach(_.dispose())

    @tailrec
    def loop(views: Vector[Elem], res: Int): Int = views match {
      case head +: tail =>
        val ch = head.preferredNumChannels
        // if there is any child with `-1` (undefined), we have to return
        if (ch == -1) ch else loop(tail, math.max(res, ch))
      case _ => res
    }

    val res = loop(views, -1)
    prefChansNumRef() = res
    // println(s"preferredNumChannels - ${views.size} elems yield new: $res")
    res
  }

  // if cache is affected, simply forward, so that cache is rebuilt.
  def attrNumChannelsChanged(attr: Elem)(implicit tx: S#Tx): Unit =
    if (prefChansElemRef().contains(attr)) {  // then invalidate, otherwise ignore (what can we do?)
      prefChansNumRef() = -2
      observer.attrNumChannelsChanged(this)
    }

  override def dispose()(implicit tx: S#Tx): Unit = {
    super.dispose()
    prefChansElemRef.swap(Vector.empty).foreach(_.dispose())
  }
}