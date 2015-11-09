/*
 *  AuralNodeImpl.scala
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

package de.sciss.synth.proc.impl

import de.sciss.lucre.stm.TxnLike
import de.sciss.lucre.synth.{DynamicUser, Group, Node, Resource, Synth, Txn}
import de.sciss.synth.proc.{TimeRef, AuralNode}
import de.sciss.synth.{addToHead, ControlSet, addBefore}

import scala.concurrent.stm.Ref

object AuralNodeImpl {
  def apply(timeRef: TimeRef, wallClock: Long, synth: Synth)(implicit tx: Txn): AuralNode.Builder = {
    // XXX TODO -- probably we can throw `users` and `resources` together as disposables
    val res = new Impl(timeRef, wallClock, synth)
    synth.server.addVertex(res)
    res
  }

  /*
   * The possible differentiation of groups for an aural process. The minimum configuration is one main
   * group. If synths need to play before the main process, a pre group will be established, if they need
   * to play after the main process, a post group will be established. If multiple synths participate in
   * the main process, a core group may be established. A back group will hold 'forgotten' synths, once
   * they have been marked to fade out and be removed by themselves.
   */
  private final case class AllGroups(main: Group, pre: Option[Group] = None,
                                     core: Option[Group] = None,
                                     post: Option[Group] = None, back: Option[Group] = None)

  private final class Impl(val timeRef: TimeRef, wallClock: Long, synth: Synth)
    extends AuralNode.Builder {

    import TxnLike.peer

    private[this] val users     = Ref(List.empty[DynamicUser])
    private[this] val resources = Ref(List.empty[Resource   ])

    private[this] val groupsRef = Ref(Option.empty[AllGroups])

    // we only add to `setMap` before `play`, thus does not need to be transactional
    private[this] var setMap    = List.empty[ControlSet]

    override def toString = s"AuralProc($synth)"

    def server = synth.server

    def groupOption(implicit tx: Txn): Option[Group] = groupsRef.get(tx.peer).map(_.main)

    def node(implicit tx: Txn): Node = groupOption.getOrElse(synth)

    def play()(implicit tx: Txn): Unit = {
      // `play` calls `requireOffline`, so we are safe against accidental repeated calls
      val target = server.defaultGroup
      synth.play(target = target, addAction = addToHead, args = setMap.reverse, dependencies = resources().reverse)
      users().reverse.foreach(_.add())
    }

    def shiftTo(newWallClock: Long): TimeRef = {
      val delta = newWallClock - wallClock
      timeRef.shift(delta)
    }

    def group()(implicit tx: Txn): Group =
      groupOption.getOrElse {
        val res = Group(synth, addBefore) // i.e. occupy the same place as before
        group_=(res)
        res
      }

    def group_=(newGroup: Group)(implicit tx: Txn): Unit =
      groupsRef.transform { groupsOpt =>
        val res = groupsOpt.fold {
          val all = AllGroups(main = newGroup)
          synth.moveToHead(newGroup)
          all
        } { all =>
          moveAllTo(all, newGroup)
          all.main.free() // what can you do...?
          all.copy(main = newGroup)
        }
        Some(res)
      }

    @inline private[this] def preGroupOption(implicit tx: Txn): Option[Group] =
      groupsRef.get(tx.peer).flatMap(_.pre)

    def preGroup()(implicit tx: Txn): Group =
      preGroupOption.getOrElse {
        /* val main = */ group() // creates group if necessary
        val all       = groupsRef().get
        val target    = anchorNode()
        val addAction = addBefore
        val res       = Group(target = target, addAction = addAction)
        groupsRef.set(Some(all.copy(pre = Some(res))))
        res
      }

    private[this] def anchorNode()(implicit tx: Txn): Node =
      groupsRef().flatMap(_.core) getOrElse synth

    private[this] def moveAllTo(all: AllGroups, newGroup: Group)(implicit tx: Txn): Unit = {
      val core = anchorNode()
      core.moveToTail(newGroup)
      all.pre .foreach(_.moveBefore(core))
      all.post.foreach(_.moveAfter (core))

      all.back.foreach { g =>
        if (g.isOnline) g.moveToHead(newGroup)
      }
    }

    def dispose()(implicit tx: Txn): Unit = {
      node.free()
      users    .swap(Nil).reverse.foreach(_.dispose())
      resources.swap(Nil).reverse.foreach(_.dispose())
      server.removeVertex(this)
    }

    def addControl(pair: ControlSet)(implicit tx: Txn): Unit = {
      if (synth.isOnline) node.set(pair)
      else setMap ::= pair
    }

    def addUser(user: DynamicUser)(implicit tx: Txn): Unit = {
      users.transform(user :: _)
      if (synth.isOnline) user.add()
    }

    def removeUser(user: DynamicUser )(implicit tx: Txn): Unit =
      users.transform(_.filterNot(_ == user))

    def addResource(resource: Resource)(implicit tx: Txn): Unit =
      resources.transform(resource :: _)

    def removeResource(resource: Resource)(implicit tx: Txn): Unit =
      resources.transform(_.filterNot(_ == resource))
  }
}
