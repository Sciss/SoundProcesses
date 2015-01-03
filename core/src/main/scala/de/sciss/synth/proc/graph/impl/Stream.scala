/*
 *  Stream.scala
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

package de.sciss.synth.proc.graph.impl

import de.sciss.synth
import de.sciss.synth.UGenInLike
import de.sciss.synth.proc.UGenGraphBuilder
import de.sciss.synth.proc.UGenGraphBuilder.Input
import synth._

object Stream {
  /* private[proc] */ def controlName(key: String, idx: Int): String = s"$$str${idx}_$key"

  trait Info extends Stream {
    protected def maxSpeed  = 0.0
    protected def interp    = 0
  }
}
trait Stream extends synth.GE.Lazy {
  protected def makeUGen(numChannels: Int, idx: Int, buf: synth.GE, gain: synth.GE): UGenInLike

  protected def key: String

  // protected def info: UGenGraphBuilder.StreamIn
  protected def maxSpeed: Double
  protected def interp  : Int

  def makeUGens: UGenInLike = {
    val b = UGenGraphBuilder.get
    val interp1       = if (interp == 4) -1 else interp
    val spec          = Input.Stream.Spec(maxSpeed = maxSpeed, interp = interp1)
    val info          = b.requestInput(Input.Stream(key, spec))
    val idx           = if (spec.isEmpty) 0 else info.specs.size - 1
    // val (numCh, idx)  = b.addStreamIn(key, info)
    val ctlName       = Stream.controlName(key, idx)
    val ctl           = ctlName.ir(Seq(0, 0))
    val buf           = ctl \ 0
    val gain          = ctl \ 1
    makeUGen(numChannels = info.numChannels, idx = idx, buf = buf, gain = gain)
  }
}