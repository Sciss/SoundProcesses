/*
 *  AuralOutputAttribute.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2016 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc.impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, TxnLike}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.AuralAttribute.{Factory, Observer, Target}
import de.sciss.synth.proc.AuxContext
import de.sciss.synth.proc.AuralView.{Playing, Prepared, Stopped}
import de.sciss.synth.proc.{AuralAttribute, AuralContext, AuralOutput, Output, TimeRef}

import scala.concurrent.stm.Ref

// ------------------- Output ------------------- 

object AuralOutputAttribute extends Factory {
  type Repr[S <: stm.Sys[S]] = Output[S]

  def typeID = Output.typeID

  def apply[S <: Sys[S]](key: String, value: Output[S], observer: Observer[S])
                        (implicit tx: S#Tx, context: AuralContext[S]): AuralAttribute[S] =
    new AuralOutputAttribute(key, tx.newHandle(value), observer).init(value)
}
final class AuralOutputAttribute[S <: Sys[S]](val key: String, val obj: stm.Source[S#Tx, Output[S]],
                                              observer: Observer[S])
                                             (implicit context: AuralContext[S])
  extends AuralAttributeImpl[S] { attr =>

  override def toString = s"AuralOutputAttribute($key)@${hashCode.toHexString}"

  import TxnLike.peer

  def typeID = Output.typeID

  private[this] val auralRef  = Ref(Option.empty[AuralOutput[S]])
  private[this] var obs: Disposable[S#Tx] = _
  private[this] val playRef   = Ref(Option.empty[Target[S]])
  private[this] val aObsRef   = Ref(Option.empty[Disposable[S#Tx]])

  def targetOption(implicit tx: S#Tx): Option[Target[S]] = playRef()

  def preferredNumChannels(implicit tx: S#Tx): Int =
    auralRef().fold(-1)(_.bus.numChannels)

  def init(output: Output[S])(implicit tx: S#Tx): this.type = {
    val id  = output.id // idH()
    obs = context.observeAux[AuralOutput[S]](id) { implicit tx => {
      case AuxContext.Added(_, auralOutput) =>
        auralSeen(auralOutput)
        playRef().foreach(update(_, auralOutput))
        observer.attrNumChannelsChanged(this)
      case AuxContext.Removed(_) => // XXX TODO: ignore?
    }}
    context.getAux[AuralOutput[S]](id).foreach(auralSeen)
    this
  }

  private[this] def auralSeen(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    auralRef() = Some(auralOutput)
    val aObs = auralOutput.react { implicit tx => {
      case AuralOutput.Play(n) =>
        playRef().foreach(update(_, auralOutput))
      case AuralOutput.Stop => // XXX TODO: ignore?
    }}
    aObsRef.swap(Some(aObs)).foreach(_.dispose())
    playRef().foreach(update(_, auralOutput))
  }

  def prepare(timeRef: TimeRef.Option)(implicit tx: S#Tx): Unit = state = Prepared

  def play(timeRef: TimeRef.Option, target: Target[S])(implicit tx: S#Tx): Unit /* Instance */ = {
    require (playRef.swap(Some(target)).isEmpty)
    // target.add(this)
    auralRef().foreach(update(target, _))
    state = Playing
  }

  def stop()(implicit tx: S#Tx): Unit = {
    stopNoFire()
    state = Stopped
  }

  private[this] def stopNoFire()(implicit tx: S#Tx): Unit = {
    playRef.swap(None).foreach(_.remove(this))
  }

  private[this] def update(target: Target[S], audioOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    val nodeRefOpt = audioOutput.view.nodeOption
    nodeRefOpt.foreach { nodeRef =>
      target.put(this, AuralAttribute.Stream(nodeRef, audioOutput.bus))
    }
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    stopNoFire()
    auralRef.set(None)
    aObsRef.swap(None).foreach(_.dispose())
    obs.dispose()
  }
}
  