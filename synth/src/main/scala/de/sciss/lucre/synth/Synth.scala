/*
 *  Synth.scala
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

package de.sciss.lucre
package synth

import collection.immutable.{Seq => ISeq}
import de.sciss.synth.{Synth => SSynth, SynthDef => SSynthDef, UGenGraph, addToHead, AddAction, ControlSet, SynthGraph}
import impl.{SynthImpl => Impl}

object Synth {
  def apply(server: Server, graph: SynthGraph, nameHint: Option[String] = None)(implicit tx: Txn): Synth = {
    val df  = NodeGraph.acquireSynthDef(server, graph, nameHint)
    val res = create(df)
    releaseDefOnEnd(res)
    res
  }

  private def releaseDefOnEnd(x: Synth)(implicit tx: Txn): Unit =
    x.onEndTxn { implicit tx =>
      NodeGraph.releaseSynthDef(x.definition)
    }

  def play(graph: SynthGraph, nameHint: Option[String] = None)
          (target: Node, args: ISeq[ControlSet] = Nil, addAction: AddAction = addToHead,
            dependencies: List[Resource] = Nil)(implicit tx: Txn): Synth = {
    val res = apply(target.server, graph, nameHint)
    res.play(target, args, addAction, dependencies)
    res
  }

  /** Like `play` but does not memoize synth def. */
  def playOnce(graph: SynthGraph, nameHint: Option[String] = None)
          (target: Node, args: ISeq[ControlSet] = Nil, addAction: AddAction = addToHead,
           dependencies: List[Resource] = Nil)(implicit tx: Txn): Synth = {

    // XXX TODO - DRY - NodeGraphImpl
    val name    = impl.NodeGraphImpl.mkName(nameHint)(tx.peer)
    val uGraph  = graph.expand(de.sciss.synth.impl.DefaultUGenGraphBuilderFactory)
    val peer    = SSynthDef(name, uGraph)
    val server  = target.server
    val rd      = impl.SynthDefImpl(server, peer) // (bytes)
    rd.recv()

    val res = create(rd)
    res.play(target, args, addAction, dependencies)

    rd.dispose()  // free it immediately

    res
  }

  /* private[synth] */ def expanded(server: Server, graph: UGenGraph, nameHint: Option[String] = None)
                            (implicit tx: Txn): Synth = {
    val df = NodeGraph.acquireSynthDef(server, graph, nameHint)
    val res = create(df)
    releaseDefOnEnd(res)
    res
  }

  private def create(df: SynthDef)(implicit tx: Txn): Synth = {
    val server  = df.server
    val nodeID  = server.nextNodeID()
    new Impl(SSynth(server.peer, nodeID), df)
  }
}

trait Synth extends Node {
  def peer: SSynth

  def definition: SynthDef

  /* private[synth] */ def play(target: Node, args: ISeq[ControlSet], addAction: AddAction, dependencies: List[Resource])
                         (implicit tx: Txn): Unit
}