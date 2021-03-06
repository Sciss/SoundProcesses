/*
 *  StreamBuffer.scala
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

import de.sciss.lucre.synth.{Buffer, RT, Synth}
import de.sciss.synth.GE
import de.sciss.synth.proc.graph.impl.SendReplyResponder
import de.sciss.{osc, synth}

import scala.annotation.{switch, tailrec}

object StreamBuffer {
  def padSize(interp: Int): Int = (interp: @switch) match {
    case 1 => 0
    case 2 => 1
    case 4 => 4
    case _ => sys.error(s"Illegal interpolation value: $interp")
  }

  def defaultReplyName(key: String): String = s"/$$str_$key"

  /** Creates and returns the phasor; includes the `SendReply` */
  def makeIndex(replyName: String, buf: GE, speed: GE = 1f, padSize: Int = 0, replyId: Int = 0): GE = {
    import synth._
    import ugen._
    //    val bufRate     = speed
    //    val phasorRate  = bufRate / SampleRate.ir
    val phasorRate  = speed
    val bufRate     = speed * SampleRate.ir
    val numFrames   = BufFrames.ir(buf)
    val halfPeriod  = numFrames / (bufRate * 2)
    val phasor      = Phasor.ar(speed = phasorRate, lo = padSize, hi = numFrames - padSize)

    // ---- clock trigger ----

    // for the trigger, k-rate is sufficient
    val phasorK     = A2K.kr(phasor)
    val phasorTrig  = Trig1.kr(phasorK - numFrames/2, ControlDur.ir)
    val clockTrig   = phasorTrig + TDelay.kr(phasorTrig, halfPeriod)
    val position    = PulseCount.kr(clockTrig)

    // println(s"makeUGen($key, $idx, $buf, $numChannels, $speed, $interp)")
    // numFrames.poll(0, "numFrames")
    // position.poll(clockTrig, "count")
    // phasor.poll(2, "phasor")

    SendReply.kr(trig = clockTrig, values = position, msgName = replyName, id = replyId)

    phasor
  }

  def makeUGen(key: String, idx: Int, buf: GE, numChannels: Int, speed: GE, interp: Int): GE = {
    import synth._
    import ugen._
    val diskPad = StreamBuffer.padSize(interp)
    val phasor  = makeIndex(replyName = defaultReplyName(key), replyId = idx, buf = buf, speed = speed,
      padSize = diskPad)
    BufRd.ar(numChannels, buf = buf, index = phasor, loop = 0, interp = interp)
  }
}
/** An object that manages streaming an audio buffer.
 *
 * @param key             the key is used for the `SendReply` messages
 * @param idx             the index in `SendReply`
 * @param synth           the synth to expect the `SendReply` messages to come from
 * @param buf             the buffer to send data to
 * @param fileFrames      the total number of frames in the file
 * @param interpolation   the type of interpolation (1 = none, 2 = linear, 4 = cubic)
 * @param startFrame      the start frame into the file to begin with
 * @param loop            if `true` keeps looping the buffer, if `false` pads reset with zeroes, then stops
 * @param resetFrame      when looping, the reset frame position into the file after each loop begins.
  *                       this should be less than or equal to `startFrame`
 */
abstract class StreamBuffer(key: String, idx: Int, protected val synth: Synth,
                            buf: Buffer.Modifiable, fileFrames: Long,
                            interpolation: Int, startFrame: Long, loop: Boolean, resetFrame: Long)
  extends SendReplyResponder {

  private[this] val bufSizeH  = buf.numFrames/2
  private[this] val diskPad   = StreamBuffer.padSize(interpolation)
  private[this] val bufSizeHM = bufSizeH - diskPad
  private[this] val replyName = StreamBuffer.defaultReplyName(key)
  private[this] val nodeId    = synth.peer.id

  protected def added()(implicit tx: RT): Unit = {
    // initial buffer fills. XXX TODO: fuse both reads into one
    updateBuffer(0)
    updateBuffer(1)
    ()
  }

  protected val body: Body = {
    case osc.Message(`replyName`, `nodeId`, `idx`, trigValF: Float) =>
      // println(s"RECEIVED TR $trigValF...")
      // logAural(m.toString)
      val trigVal = trigValF.toInt + 1
      scala.concurrent.stm.atomic { itx =>
        implicit val tx: RT = RT.wrap(itx)
        val frame = updateBuffer(trigVal)
        if (frame >= fileFrames + bufSizeH) {
          synth.free()
        }
      }
  }

  private def updateBuffer(trigVal: Int)(implicit tx: RT): Long = {
    val trigEven  = trigVal % 2 == 0
    val bufOff    = if (trigEven) 0 else bufSizeH
    val frame     = trigVal.toLong * bufSizeHM + startFrame + (if (trigEven) 0 else diskPad)
    if (loop)
      updateBufferLoop  (bufOff, frame)
    else
      updateBufferNoLoop(bufOff, frame)
  }

  private def updateBufferNoLoop(bufOff: Int, frame: Long)(implicit tx: RT): Long = {
    val readSz    = math.max(0, math.min(bufSizeH, fileFrames - frame)).toInt
    val fillSz    = bufSizeH - readSz

    if (fillSz > 0) {
      buf.fill(index = (bufOff + readSz) * buf.numChannels, num = fillSz * buf.numChannels, value = 0f)
    }

    if (readSz > 0) fillBuf(
      fileStartFrame  = frame,
      numFrames       = readSz,
      bufStartFrame   = bufOff
    )

    frame
  }

  protected def fillBuf(fileStartFrame: Long, numFrames: Int, bufStartFrame: Int)(implicit tx: RT): Unit

  private def updateBufferLoop(bufOff: Int, frame0: Long)(implicit tx: RT): Long = {
    @tailrec def loop(done: Int): Long = {
      val frame1  = frame0 + done
      val frame   = (frame1 - resetFrame) % (fileFrames - resetFrame) + resetFrame  // wrap inside loop span
      val readSz  =  math.min(bufSizeH - done, fileFrames - frame).toInt
      if (readSz > 0) {
        fillBuf(
          fileStartFrame  = frame,
          numFrames       = readSz,
          bufStartFrame   = bufOff + done
        )
        loop(done + readSz)
      } else {
        frame
      }
    }

    loop(0)
  }
}

final class StreamBufferRead(key: String, idx: Int, synth: Synth, buf: Buffer.Modifiable, path: String,
                             fileFrames: Long, interpolation: Int, startFrame: Long, loop: Boolean,
                             resetFrame: Long)
  extends StreamBuffer(key = key, idx = idx, synth = synth, buf = buf, fileFrames = fileFrames,
    interpolation = interpolation, startFrame = startFrame, loop = loop, resetFrame = resetFrame) {

  override protected def fillBuf(fileStartFrame: Long, numFrames: Int, bufStartFrame: Int)(implicit tx: RT): Unit =
    buf.read(
      path            = path,
      fileStartFrame  = fileStartFrame,
      numFrames       = numFrames,
      bufStartFrame   = bufStartFrame
    )
}

//final class StreamBufferSet(key: String, idx: Int, synth: Node, buf: Buffer.Modifiable, uri: URI,
//                            fileFrames: Long, interpolation: Int, startFrame: Long, loop: Boolean,
//                            resetFrame: Long)(implicit exec: ExecutionContext)
//  extends StreamBuffer(key = key, idx = idx, synth = synth, buf = buf, fileFrames = fileFrames,
//    interpolation = interpolation, startFrame = startFrame, loop = loop, resetFrame = resetFrame) {
//
//  private[this] var afFut: Future[AsyncAudioFile] = _
//  private[this] val afBuf = Array.ofDim[Double](buf.numChannels, buf.numFrames)
//  private[this] var current = Option.empty[Future[Any]]
//  private[this] val sync = new AnyRef
//
//  def init()(implicit tx: RT): this.type = {
//    tx.afterCommit {
//      afFut = AudioFile.openReadAsync(uri)
//      sync.synchronized {
//        current = Some(afFut)
//        afFut...
//      }
//    }
//    this
//  }
//
//  override protected def fillBuf(fileStartFrame: Long, numFrames: Int, bufStartFrame: Int)(implicit tx: RT): Unit = {
//    tx.afterCommit {
//      afFut.foreach { af =>
//        af.read(???, 0, len)
//      }
//      buf.setn((bufStartFrame, ???))
//    }
//  }
//}
