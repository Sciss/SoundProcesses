/*
 *  AudioBusMeterImpl.scala
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

package de.sciss.proc.gui.impl

import de.sciss.audiowidgets.PeakMeter
import de.sciss.lucre.swing.LucreSwing.{defer, deferTx}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.{RT, Synth}
import de.sciss.osc.Message
import de.sciss.synth
import de.sciss.synth.Ops.stringToControl
import de.sciss.proc.gui.AudioBusMeter
import de.sciss.synth.{SynthGraph, message}

import scala.collection.immutable.{Seq => ISeq}
import scala.concurrent.stm.Ref
import scala.swing.{BoxPanel, Component, Orientation}

final class AudioBusMeterImpl(val strips: ISeq[AudioBusMeter.Strip])
  extends AudioBusMeter with ComponentHolder[Component] {

  private[this] val ref = Ref(ISeq.empty[Synth])
  private[this] var meters: Array[PeakMeter] = _

  def dispose()(implicit tx: RT): Unit = {
    disposeRef(ref.swap(Nil)(tx.peer))
    deferTx {
      meters.foreach(_.dispose())
    }
  }

  @inline private[this] def disposeRef(synths: ISeq[Synth])(implicit tx: RT): Unit =
    synths.foreach(_.dispose())

  private[this] def guiInit(): Unit = {
    meters = strips.iterator.map { strip =>
      val meter           = new PeakMeter
      meter.numChannels   = strip.bus.numChannels
      meter.caption       = true
      meter.borderVisible = true
      meter
    } .toArray
    component = new BoxPanel(Orientation.Horizontal) {
      contents ++= meters
    }
  }

  def init()(implicit tx: RT): Unit = {
    deferTx(guiInit())

    // group to send out bundles per server
    val synths = strips.zipWithIndex.map { case (strip, stripIdx) =>
      import strip.{addAction, bus, target}

      val graph = SynthGraph {
        import synth._
        import ugen._
        val sig   = In.ar("bus".ir, bus.numChannels)
        val tr    = Impulse.kr(20)
        val peak  = Peak.kr(sig, tr)
        val rms   = A2K.kr(Lag.ar(sig.squared, 0.1))
        SendReply.kr(tr, Flatten(Zip(peak, rms)), "/$meter")
      }

      val syn = Synth.play(graph, nameHint = Some("meter"))(target = target, addAction = addAction)
      syn.read(bus -> "bus")

      val SynId = syn.peer.id
      val resp = message.Responder.add(syn.server.peer) {
        case Message("/$meter", SynId, _, vals @ _*) =>
          val pairs = vals.asInstanceOf[Seq[Float]].toIndexedSeq
          val time  = System.currentTimeMillis()
          defer {
            val meter = meters(stripIdx)
            meter.update(pairs, 0, time)
            ()
          }
      }
      scala.concurrent.stm.Txn.afterRollback(_ => resp.remove())(tx.peer)
      syn.onEnd(resp.remove())
      syn
    }
    disposeRef(ref.swap(synths)(tx.peer))
  }
}