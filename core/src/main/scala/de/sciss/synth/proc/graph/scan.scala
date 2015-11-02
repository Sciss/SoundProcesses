/*
 *  scan.scala
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

package de.sciss.synth
package proc
package graph

import de.sciss.synth.proc.UGenGraphBuilder.Input

import collection.immutable.{IndexedSeq => Vec}
import de.sciss.synth.ugen.UGenInGroup

object ScanIn {
  /* private[proc] */ def controlName (key: String): String =
    Attribute.controlName(key) // s"$$i_$key"

  sealed trait Like extends GE.Lazy with AudioRated {
    protected def key: String

    protected def fixed: Int

    final def makeUGens: UGenInLike = {
      val b = UGenGraphBuilder.get
      val numCh   = b.requestInput(Input.Attribute(
        name                = key,
        requiredNumChannels = fixed,
        defaultNumChannels  = -1)).numChannels
      val ctlName = controlName(key)
      mkUGen(ctlName, numCh)
    }

    protected def mkUGen(ctlName: String, numCh: Int): UGenInLike
  }

  def apply(): ScanIn = apply("in")
  // def apply(key: String): ScanIn = new ScanIn(key)

  //  final case class InFix(key: String, numChannels: Int)
  //    extends InLike {
  //
  //    override def toString = s"""scan.InFix("$key", $numChannels)"""
  //
  //    override def productPrefix = "scan$InFix"
  //
  //    protected def mkUGen(ctlName: String, numCh: Int): UGenInLike =
  //      ugen.In.ar(ctlName.kr, numCh)
  //  }
}
final case class ScanIn(key: String /*, default: Double = 0.0 */)
  extends ScanIn.Like {

  protected def fixed = -1

  protected def mkUGen(ctlName: String, numCh: Int): UGenInLike =
    if (numCh == 1) {
      ctlName.ar(0.0f).expand
    } else if (numCh > 1) {
      ctlName.ar(Vector.fill(numCh)(0.0f)).expand
    } else {
      UGenInGroup.empty
    }
}
object ScanOut {
  /* private[proc] */ def controlName(key: String): String = s"$$o_$key"

  def apply(in: GE): ScanOut = new ScanOut("out", in)
}
final case class ScanOut(key: String, in: GE)
  extends UGenSource.ZeroOut with WritesBus {

  protected def makeUGens: Unit = {
    val bus = ScanOut.controlName(key).kr
    unwrap(Vector(bus.expand) ++ in.expand.outputs)
  }

  // first arg: bus control, remaining args: signal to write; thus numChannels = _args.size - 1
  protected def makeUGen(_args: Vec[UGenIn]): Unit = {
    val busArg      = _args.head
    val sigArg      = _args.tail
    val numChannels = sigArg.size
    val b = UGenGraphBuilder.get
    b.addOutput(key, numChannels)
    val sigArgAr = sigArg.map { ui =>
      if (ui.rate == audio) ui else UGen.SingleOut("K2A", audio, Vector(ui))
    }
    UGen.ZeroOut("Out", audio, busArg +: sigArgAr, isIndividual = true)
  }
}

object ScanInFix {
  def apply(numChannels: Int): ScanInFix = apply("in", numChannels)
}
/** Like `ScanIn` but with a predetermined number of channels. */
final case class ScanInFix(key: String, numChannels: Int)
  extends ScanIn.Like {

  protected def fixed = numChannels

  protected def mkUGen(ctlName: String, numCh: Int): UGenInLike =
    if (numCh == 1) {
      ctlName.ar(0.0f).expand
    } else if (numCh > 1) {
      ctlName.ar(Vector.fill(numCh)(0.0f)).expand
    } else {
      UGenInGroup.empty
    }
}
