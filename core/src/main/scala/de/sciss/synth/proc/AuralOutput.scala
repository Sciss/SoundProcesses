/*
 *  AuralOutput.scala
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

import de.sciss.lucre.event.Observable
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.synth.{AudioBus, NodeRef, Sys}
import de.sciss.synth.proc.impl.{AuralOutputImpl => Impl}

object AuralOutput {
  /** Creates a new aural scan view and registers it with the context under `scan.id`. */
  def apply[S <: Sys[S]](view: AuralObj.Proc[S], output: Output[S], bus: AudioBus)
                        (implicit tx: S#Tx, context: AuralContext[S]): AuralOutput.Owned[S] =
    Impl(view = view, output = output, bus = bus)

  trait Owned[S <: Sys[S]] extends AuralOutput[S] {
    def stop()(implicit tx: S#Tx): Unit
    def play(n: NodeRef)(implicit tx: S#Tx): Unit
  }

  sealed trait Update
  case class  Play(n: NodeRef) extends Update
  case object Stop             extends Update
}

trait AuralOutput[S <: Sys[S]] extends Disposable[S#Tx] with Observable[S#Tx, AuralOutput.Update] {
  def view: AuralObj.Proc[S]

  def key : String
  def bus : AudioBus
}
