/*
 *  NodeRefImpl.scala
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

import de.sciss.lucre.synth
import de.sciss.synth.addBefore

import scala.concurrent.stm.Ref

object NodeRefImpl {
  // def apply(n: Node): NodeRef = new Wrap(n)

  def Group(name: String, in0: NodeRef)(implicit tx: Txn): NodeRef.Group = {
    val res = new GroupImpl(name, in0)
    NodeGraph.addNode(res)
    res
  }

  //  private final case class Wrap(n: Node) extends NodeRef {
  //    def node(implicit tx: Txn): Node = n
  //    def server: Server = n.server
  //
  //    override def toString = s"NodeRef($n)"
  //  }

  // dynamically flips between single proc and multiple procs
  // (wrapping them in one common group)
  private final class GroupImpl(name: String, in0: NodeRef) extends NodeRef.Group {
    val server = in0.server

    override def toString = name

    private val instancesRef  = Ref(in0 :: Nil)
    private val nodeRef       = Ref(in0)

    def node(implicit tx: Txn): Node = nodeRef.get(tx.peer).node

    def addInstanceNode(n: NodeRef)(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      val old = instancesRef.getAndTransform(n :: _)
      old match {
        case single :: Nil =>
          val g = synth.Group(single.node, addBefore)
          nodeRef() = g
          single.node.moveToHead(g)
          n     .node.moveToHead(g)

        case _ =>
      }
    }

    def removeInstanceNode(n: NodeRef)(implicit tx: Txn): Boolean = {
      implicit val itx = tx.peer
      val after = instancesRef.transformAndGet(_.filterNot(_ == n))
      after match {
        case single :: Nil =>
          val group = nodeRef.swap(single).node
          single.node.moveBefore(group)
          group.free()
          false

        case Nil  =>
          dispose()
          true

        case _ => false
      }
    }

    def dispose()(implicit tx: Txn): Unit = {
      implicit val itx = tx.peer
      if (instancesRef.swap(Nil).size > 1) {
        val group = nodeRef.swap(null).node
        group.free()
      }
      NodeGraph.removeNode(this)
    }
  }
}