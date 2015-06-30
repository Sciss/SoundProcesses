/*
 *  Server.scala
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

package de.sciss.lucre.synth

import de.sciss.topology.Topology

import scala.concurrent.stm.{Ref, InTxn}
import scala.concurrent.{ExecutionContext, Future}
import collection.immutable.{IndexedSeq => Vec}
import language.implicitConversions
import de.sciss.synth.{Server => SServer, Client => SClient, UGenGraph}
import impl.{ServerImpl => Impl}
import de.sciss.osc

import scala.util.Try

object Server {
  def apply(peer: SServer): Server = Impl(peer)

  def offline(config: Config, client: SClient.Config = SClient.Config()): Offline = {
    val peer = SServer.dummy(name = "offline", config = config, clientConfig = client)
    Impl.offline(peer)
  }

  val codec: osc.PacketCodec = osc.PacketCodec().scsynth().build

  implicit def defaultGroup(server: Server): Group = server.defaultGroup

  trait Offline extends Server {
    /** Returns a future of ongoing message sending.
      * __Note__: Calling this method will clear the futures held by the server.
      */
    def committed(): Future[Unit]

    /** The current frame position in the OSC file. The user should
      * increment this according to a reference transport, so that
      * messages are queued in the correct position. */
    var position: Long

    /** Logically closes the offline server and returns a list of all the bundles collected so far.
      * __Note__: Calling this method will clear the bundles held by the server.
      *
      * @param  addDefaultGroup if `true`, prepends a `/g_new` message for the standard default group
        *                       (node id 1) to the returned bundles
      */
    def bundles(addDefaultGroup: Boolean = true): Vec[osc.Bundle]
  }

  val  Config         = SServer.Config
  type Config         = SServer.Config
  val  ConfigBuilder  = SServer.ConfigBuilder
  type ConfigBuilder  = SServer.ConfigBuilder
  type ConfigLike     = SServer.ConfigLike
}

trait Server {
  def peer: SServer

  final def sampleRate: Double = peer.sampleRate

  def isRealtime   : Boolean
  def isLocal      : Boolean
  def maxPacketSize: Int

  implicit def executionContext: ExecutionContext

  // ---- transactional methods ----

  def nextNodeID()(implicit tx: Txn): Int

  def allocControlBus(numChannels   : Int    )(implicit tx: Txn): Int
  def allocAudioBus  (numChannels   : Int    )(implicit tx: Txn): Int
  def allocBuffer    (numConsecutive: Int = 1)(implicit tx: Txn): Int

  def freeControlBus(index: Int, numChannels   : Int    )(implicit tx: Txn): Unit
  def freeAudioBus  (index: Int, numChannels   : Int    )(implicit tx: Txn): Unit
  def freeBuffer    (index: Int, numConsecutive: Int = 1)(implicit tx: Txn): Unit

  def defaultGroup: Group

  def config      : Server .Config
  def clientConfig: SClient.Config

  // ---- side effects methods ----

  /** Sends out a packet immediately without synchronization */
  def ! (p: osc.Packet): Unit

  /** Sends out a packet with an added sync message. The returned future is completed with the
    * sync message's reply having arrived. */
  def !! (b: osc.Bundle): Future[Unit]

  /** Signalizes that no more messages are sent from the currently committing transaction.
    * The offline server collects these futures, in order to allow an outside process
    * to eventually wait for these to be completed, before closing the OSC file and carrying on.
    * The realtime server just ignores these futures.
    */
  def commit(future: Future[Unit]): Unit

  // ------------ former NodeGraph ------------

  def addVertex   (node: NodeRef)(implicit tx: Txn): Unit
  def removeVertex(node: NodeRef)(implicit tx: Txn): Unit

  def addEdge   (edge: NodeRef.Edge)(implicit tx: Txn): Try[(Topology[NodeRef, NodeRef.Edge], Option[Topology.Move[NodeRef]])]
  def removeEdge(edge: NodeRef.Edge)(implicit tx: Txn): Unit

  private[synth] def send(bundles: Txn.Bundles): Future[Unit]

  private[synth] def messageTimeStamp: Ref[Int]

  /** Acquires a synth def to be used in this transaction on the server.
    * If the ugen graph was already sent to the server, it returns the
    * corresponding synth def, and no `recv()` is necessary. The internal
    * use counter is increment. Otherwise produces a fresh synth def and
    * stores it in the cache.
    *
    * If the number of synth defs on the server would exceed the maximum,
    * a number of synth-def disposals are issued for those that have a
    * use count of zero.
    */
  def acquireSynthDef(graph: UGenGraph, nameHint: Option[String])(implicit tx: Txn): SynthDef

  //  /** Releases a synth def on the server. Decrements the cache use count,
  //    * and if it reaches zero, lazily purges the def on the server as soon
  //    * as more slots are required.
  //    */
  //  def releaseSynthDef(sd: SynthDef)(implicit tx: Txn): Unit

  /** Queries the current topology */
  def topology(implicit tx: Txn): Topology[NodeRef, NodeRef.Edge]

  def mkSynthDefName(nameHint: Option[String])(implicit tx: Txn): String
}