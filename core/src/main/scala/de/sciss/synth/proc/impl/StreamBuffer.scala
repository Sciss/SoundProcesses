/*
 *  StreamBuffer.scala
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

package de.sciss.synth.proc
package impl

import de.sciss.{synth, osc}
import scala.annotation.{tailrec, switch}
import de.sciss.lucre.synth.{DynamicUser, Node, Synth, Txn, Buffer}
import de.sciss.synth.GE

import scala.concurrent.stm.Ref

object StreamBuffer {
  def padSize(interp: Int): Int = (interp: @switch) match {
    case 1 => 0
    case 2 => 1
    case 4 => 4
    case _ => sys.error(s"Illegal interpolation value: $interp")
  }

  // via SendReplyAuralContextImpl
  private def replyName(key: String): String = s"/$$str_$key"

  def makeUGen(key: String, idx: Int, buf: GE, numChannels: Int, speed: GE, interp: Int): GE = {
    import synth._
    import ugen._
    val diskPad     = StreamBuffer.padSize(interp)
    //    val bufRate     = speed
    //    val phasorRate  = bufRate / SampleRate.ir
    val phasorRate  = speed
    val bufRate     = speed * SampleRate.ir
    val numFrames   = BufFrames.ir(buf)
    val halfPeriod  = numFrames / (bufRate * 2)
    val phasor      = Phasor.ar(speed = phasorRate, lo = diskPad, hi = numFrames - diskPad)

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

    SendReply.kr(trig = clockTrig, values = position, msgName = replyName(key), id = idx)

    // ---- actual signal ----

    BufRd.ar(numChannels, buf = buf, index = phasor, loop = 0, interp = interp)
  }
}
/** An object that manages streaming an audio buffer.
 *
 * @param key         the key is used for the `SendReply` messages
 * @param idx         the index in `SendReply`
 * @param synth       the synth to expect the `SendReply` messages to come from
 * @param buf         the buffer to send data to
 * @param path        the path of the audio file
 * @param fileFrames  the total number of frames in the file
 * @param interp      the type of interpolation (1 = none, 2 = linear, 4 = cubic)
 * @param startFrame  the start frame into the file to begin with
 * @param loop        if `true` keeps looping the buffer, if `false` pads reset with zeroes, then stops
 * @param resetFrame  when looping, the reset frame position into the file after each loop begins.
  *                   this should be less than or equal to `startFrame`
 */
final class StreamBuffer(key: String, idx: Int, synth: Node, buf: Buffer.Modifiable, path: String, fileFrames: Long,
                         interp: Int, startFrame: Long, loop: Boolean, resetFrame: Long)
  extends DynamicUser {

  // for binary compatibility
  def this(key: String, idx: Int, synth: Synth, buf: Buffer.Modifiable, path: String, fileFrames: Long,
           interp: Int) =
    this(key = key, idx = idx, synth = synth, buf = buf, path = path, fileFrames = fileFrames, interp = interp,
         startFrame = 0L, loop = false, resetFrame = 0L)

  private val bufSizeH  = buf.numFrames/2
  private val diskPad   = StreamBuffer.padSize(interp)
  private val bufSizeHM = bufSizeH - diskPad
  private val replyName = StreamBuffer.replyName(key)
  private val nodeID    = synth.peer.id

  private val trigResp = de.sciss.synth.message.Responder(synth.server.peer) {
    case m @ osc.Message(`replyName`, `nodeID`, `idx`, trigValF: Float) =>
      // println(s"RECEIVED TR $trigValF...")
      // logAural(m.toString)
      val trigVal = trigValF.toInt + 1
      scala.concurrent.stm.atomic { itx =>
        implicit val tx = Txn.wrap(itx)
        val frame = updateBuffer(trigVal)
        if (frame >= fileFrames + bufSizeH) {
          synth.free()
        }
      }
  }

  private def updateBuffer(trigVal: Int)(implicit tx: Txn): Long = {
    val trigEven  = trigVal % 2 == 0
    val bufOff    = if (trigEven) 0 else bufSizeH
    val frame     = trigVal.toLong * bufSizeHM + startFrame + (if (trigEven) 0 else diskPad)
    if (loop)
      updateBufferLoop  (bufOff, frame)
    else
      updateBufferNoLoop(bufOff, frame)
  }

  private def updateBufferNoLoop(bufOff: Int, frame: Long)(implicit tx: Txn): Long = {
    val readSz    = math.max(0, math.min(bufSizeH, fileFrames - frame)).toInt
    val fillSz    = bufSizeH - readSz

    if (fillSz > 0) {
      buf.fill(index = (bufOff + readSz) * buf.numChannels, num = fillSz * buf.numChannels, value = 0f)
    }

    if (readSz > 0) buf.read(
      path            = path,
      fileStartFrame  = frame,
      numFrames       = readSz,
      bufStartFrame   = bufOff
    )

    frame
  }

  private def updateBufferLoop(bufOff: Int, frame0: Long)(implicit tx: Txn): Long = {
    @tailrec def loop(done: Int): Long = {
      val frame1  = frame0 + done
      val frame   = (frame1 - resetFrame) % (fileFrames - resetFrame) + resetFrame  // wrap inside loop span
      val readSz  =  math.min(bufSizeH - done, fileFrames - frame).toInt
      if (readSz > 0) {
        buf.read(
          path            = path,
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

  private val added = Ref(initialValue = false)

  def add()(implicit tx: Txn): Unit = if (!added.swap(true)(tx.peer)) {
    trigResp.add()
    // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.remove()
    } (tx.peer)

    // synth.onEnd(trigResp.remove())

    // initial buffer fills. XXX TODO: fuse both reads into one
    updateBuffer(0)
    updateBuffer(1)
  }

  def remove()(implicit tx: Txn): Unit = if (added.swap(false)(tx.peer)) {
    trigResp.remove()
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.add()
    } (tx.peer)
  }
}
