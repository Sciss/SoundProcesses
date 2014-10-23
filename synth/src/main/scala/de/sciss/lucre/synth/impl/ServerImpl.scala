/*
 *  ServerImpl.scala
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

package de.sciss.lucre.synth
package impl

import scala.concurrent.{ExecutionContext, Future}
import collection.immutable.{IndexedSeq => Vec}
import de.sciss.synth.{Server => SServer, AllocatorExhausted, addToHead, message}
import de.sciss.osc
import de.sciss.synth.{Client => SClient}

object ServerImpl {
  def apply  (peer: SServer): Server          = new OnlineImpl (peer)
  def offline(peer: SServer): Server.Offline  = new OfflineImpl(peer)

  var DEBUG_SIZE = false

  private final case class OnlineImpl(peer: SServer) extends Impl {
    override def toString = peer.toString()

    // ---- side effects ----

    def !(p: osc.Packet): Unit = {
      if (DEBUG_SIZE) checkPacket(p)
      peer ! p
    }

    private def checkPacket(p: osc.Packet): Unit = {
      val sz = p match {
        case b: osc.Bundle  => Server.codec.encodedBundleSize (b)
        case m: osc.Message => Server.codec.encodedMessageSize(m)
      }
      if (sz >= 0x10000) {
        Console.err.println(s"ERROR: Packet size $sz exceeds 64K")
        osc.Packet.printTextOn(p, Server.codec, Console.err)
      }
    }

    def !!(bndl: osc.Bundle): Future[Unit] = {
      val syncMsg = peer.syncMsg()
      val syncID  = syncMsg.id
      val bndlS   = osc.Bundle(bndl.timetag, bndl :+ syncMsg: _*)
      if (DEBUG_SIZE) checkPacket(bndlS)
      peer.!!(bndlS) {
        case message.Synced(`syncID`) =>
      }
    }

    def commit(future: Future[Unit]) = ()  // we don't use these
  }

  private final case class OfflineImpl(peer: SServer) extends Impl with Server.Offline {
    override def toString = s"$peer @offline"


    private val sync = new AnyRef

    var position  = 0L

    private var _bundles      = Vector.empty[osc.Bundle]
    private var _commits      = Vector.empty[Future[Unit]]

    private def time: Double  = position / sampleRate

    def committed(): Future[Unit] = sync.synchronized {
      val futures       = filteredCommits
      _commits          = Vector.empty
      implicit val exec = peer.clientConfig.executionContext
      NodeGraphImpl.reduceFutures(futures)
    }

    def bundles(addDefaultGroup: Boolean): Vec[osc.Bundle] = sync.synchronized {
      val res   = _bundles
      _bundles  = Vector.empty
      val res1  = if (res.isEmpty || !addDefaultGroup) res else {
        val b   = osc.Bundle(res.head.timetag,
          message.GroupNew(message.GroupNew.Data(groupID = 1, addAction = addToHead.id, targetID = 0)))
        b +: res
      }
      res1
    }

    private def addBundle(b: osc.Bundle): Unit = sync.synchronized {
      val b1 = if (b.timetag == osc.Timetag.now) osc.Bundle.secs(time, b: _*) else b
      val sz = Server.codec.encodedBundleSize(b1)
      // SuperCollider versions until 2014 have a hard-coded limit of 8K bundles in NRT!
      // cf. https://github.com/supercollider/supercollider/commit/f3f0f81de4259aa44983f1041589f895c91798a1
      val szOk = sz <= 8192
      if (szOk || b1.length == 1) {
        log(s"addBundle $b1")
        if (!szOk) log("addBundle - bundle exceeds 8k!")
        _bundles :+= b1
      } else {
        val tt = b1.timetag
        b.foreach(p => addBundle(osc.Bundle(tt, p)))
      }
    }

    def !(p: osc.Packet): Unit = {
      val b = p match {
        case m : osc.Message  => osc.Bundle.secs(time, m)
        case b0: osc.Bundle   => b0
      }
      addBundle(b)
    }

    def !!(bndl: osc.Bundle): Future[Unit] = {
      addBundle(bndl)
      Future.successful(())
    }

    // caller must have `sync`
    private def filteredCommits = _commits.filterNot(_.isCompleted)

    def commit(future: Future[Unit]): Unit =
      sync.synchronized {
        _commits = filteredCommits :+ future
      }
  }

  private abstract class Impl extends Server {

    def executionContext: ExecutionContext = peer.clientConfig.executionContext

    private val controlBusAllocator = BlockAllocator("control", peer.config.controlBusChannels)
    private val audioBusAllocator   = BlockAllocator("audio"  , peer.config.audioBusChannels, peer.config.internalBusIndex)
    private val bufferAllocator     = BlockAllocator("buffer" , peer.config.audioBuffers)
    private val nodeAllocator       = NodeIDAllocator(peer.clientConfig.clientID, peer.clientConfig.nodeIDOffset)

    val defaultGroup: Group = Group.wrap(this, peer.defaultGroup) // .default( this )

    def config      : Server .Config = peer.config
    def clientConfig: SClient.Config = peer.clientConfig

    def allocControlBus(numChannels: Int)(implicit tx: Txn): Int = {
      val res = controlBusAllocator.alloc(numChannels)(tx.peer)
      if (res < 0) throw AllocatorExhausted("Control buses exhausted for " + this)
      res
    }

    def allocAudioBus(numChannels: Int)(implicit tx: Txn): Int = {
      val res = audioBusAllocator.alloc(numChannels)(tx.peer)
      if (res < 0) throw AllocatorExhausted("Audio buses exhausted for " + this)
      res
    }

    def freeControlBus(index: Int, numChannels: Int)(implicit tx: Txn): Unit =
      controlBusAllocator.free(index, numChannels)(tx.peer)

    def freeAudioBus(index: Int, numChannels: Int)(implicit tx: Txn): Unit =
      audioBusAllocator.free(index, numChannels)(tx.peer)

    def allocBuffer(numConsecutive: Int)(implicit tx: Txn): Int = {
      val res = bufferAllocator.alloc(numConsecutive)(tx.peer)
      if (res < 0) throw AllocatorExhausted("Buffers exhausted for " + this)
      res
    }

    def freeBuffer(index: Int, numConsecutive: Int)(implicit tx: Txn): Unit =
      bufferAllocator.free(index, numConsecutive)(tx.peer)

    def nextNodeID()(implicit tx: Txn): Int = nodeAllocator.alloc()(tx.peer)
  }
}