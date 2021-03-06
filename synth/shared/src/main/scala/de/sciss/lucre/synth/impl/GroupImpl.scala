/*
 *  GroupImpl.scala
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

package de.sciss.lucre.synth
package impl

import de.sciss.synth.{AddAction, ControlSet, Group => SGroup}

import scala.collection.immutable.Seq

final case class GroupImpl(server: Server, peer: SGroup)(override protected val online0: Boolean)
  extends NodeImpl with Group {

  override def toString = s"Group($peer)"

  def play(target: Node, addAction: AddAction)(implicit tx: RT): Unit =
    play(target = target, addAction = addAction, args = Nil, dependencies = Nil)

  def play(target: Node, args: Seq[ControlSet], addAction: AddAction, dependencies: List[Resource])
          (implicit tx: RT): Unit = {
    requireOffline()
    require(target.isOnline        , s"Target $target must be running")
    require(target.server == server, s"Target $target must be using the same server")

    tx.addMessage(this, peer.newMsg(target.peer, addAction), dependencies = target :: dependencies)
    setOnline(value = true)
    if (args.nonEmpty) set(args: _*)
  }

  def freeAll()(implicit tx: RT): Unit = {
    requireOnline()
    tx.addMessage(this, peer.freeAllMsg)
  }
}