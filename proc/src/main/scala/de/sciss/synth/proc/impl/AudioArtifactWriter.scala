/*
 *  AudioArtifactWriter.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package impl

import de.sciss.lucre.synth.{AudioBus, DynamicBusUser, Buffer, Synth, Resource, Txn}
import de.sciss.synth.{addToHead, ControlSetMap, SynthGraph}
import de.sciss.span.Span
import de.sciss.numbers

object AudioArtifactWriter {
  def apply(bus: AudioBus, segm: Grapheme.Segment.Audio, time: Long, sampleRate: Double)
           (implicit tx: Txn): AudioArtifactWriter = {
    val numChannels = segm.numChannels
    val sg  = SynthGraph {
      import de.sciss.synth._
      import ugen._
      val buf = "buf".ir
      val dur = "dur".ir(1)
      val out = "out".kr
      val amp = "amp".kr(1)
      val sig0 = if (segm.value.spec.sampleRate == sampleRate) {
        DiskIn .ar(numChannels, buf)
      } else {
        VDiskIn.ar(numChannels, buf, speed = "speed".ir(1))
      }
      val sig = sig0 * amp
      Line.kr(start = 0, end = 0, dur = dur, doneAction = freeSelf)
      Out.ar(out, sig)
    }
    val synth = Synth(bus.server, sg, nameHint = Some("audio-artifact"))
    val res = new AudioArtifactWriter(synth, bus, segm, time, sampleRate)
    res.britzelAdd()
    res
  }
}
final class AudioArtifactWriter private (synth: Synth, val bus: AudioBus, segm: Grapheme.Segment.Audio, time: Long,
                                         sampleRate: Double)
  extends DynamicBusUser with Resource.Source {

  def resource(implicit tx: Txn) = synth

  def server = synth.server

  def add()(implicit tx: Txn) = ()

  def britzelAdd()(implicit tx: Txn): Unit = {
    val audioVal  = segm.value
    val file      = audioVal.artifact
    val path      = file.getAbsolutePath
    val spec      = audioVal.spec
    val fileFrames= spec.numFrames
    val factor    = spec.sampleRate / sampleRate
    val target    = server.defaultGroup // XXX
    val fStart    = math.max(0L, math.min(fileFrames,
                                          audioVal.offset + ((time - segm.span.start) * factor + 0.5).toLong))
    val fStop     = math.min(fileFrames, segm.span match {
      case Span.HasStop(stop) => fStart + ((stop - time) * factor + 0.5).toLong
      case _ => Long.MaxValue
    })
    val dur       = (fStop - fStart) / spec.sampleRate
    import numbers.Implicits._
    // println(f"AudioArtifactWriter. fStart = $fStart, fStop = $fStop, $dur = $dur%1.3f")
    val bufSize   = (Buffer.defaultCueBufferSize * factor).toInt.nextPowerOfTwo min (server.peer.config.blockSize << 1)
    val rb        = Buffer.diskIn(server)(path, startFrame = fStart, numChannels = bus.numChannels, numFrames = bufSize)
    val args0     = List[ControlSetMap]("buf" -> rb.id, "dur" -> dur, "amp" -> audioVal.gain)
    val args      = if (factor == 1.0) args0 else ("speed" -> factor: ControlSetMap) :: args0

    synth.play(target = target, args = args, addAction = addToHead, dependencies = rb :: Nil)

    synth.onEndTxn { implicit tx =>
      rb   .dispose() // XXX TODO: why was this not in the code before? Is this causing any trouble?
      synth.dispose()
    }

    synth.write(bus -> "out")
  }

  def remove()(implicit tx: Txn): Unit = if (synth.isOnline) synth.free()
}