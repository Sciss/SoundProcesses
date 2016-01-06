/*
 *  AsyncResource.scala
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

import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.synth.{NodeRef, Sys}
import de.sciss.processor.Processor

trait AsyncResource[S <: Sys[S]] extends Processor[Any] with Disposable[S#Tx] {
  def install(b: NodeRef.Full[S])(implicit tx: S#Tx): Unit
}