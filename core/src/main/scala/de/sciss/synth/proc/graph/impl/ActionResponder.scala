/*
 *  ActionResponder.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2020 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc.graph.impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.synth.{Node, Sys, Txn}
import de.sciss.synth.proc.{AuralContext, SoundProcesses}
import de.sciss.synth.{GE, proc}
import de.sciss.{osc, synth}

import scala.collection.immutable.{IndexedSeq => Vec}

object ActionResponder {
  // via SendReply
  private def replyName(key: String): String = s"/$$act_$key"

  def makeUGen(trig: GE, values: Option[GE], key: String): Unit = {
    import synth._
    import ugen._
    // we cannot make values.size zero, because then in the multi-channel expansion,
    // the SendReply collapses :)
    SendReply.kr(trig = trig, values = values.getOrElse(0) /* Vec.empty[GE] */ , msgName = replyName(key), id = 0)
  }

  var DEBUG = false
}
final class ActionResponder[S <: Sys[S]](objH: stm.Source[S#Tx, Obj[S]], key: String, protected val synth: Node)
                                        (implicit context: AuralContext[S])
  extends SendReplyResponder {

  import ActionResponder._

  private[this] val Name    = replyName(key)
  private[this] val NodeId  = synth.peer.id

  protected val body: Body = {
    case osc.Message(Name, NodeId, 0, raw @ _*) =>
      if (DEBUG) println(s"ActionResponder($key, $NodeId) - received trigger")
      // logAural(m.toString)
      val values: Vec[Float] = raw match {
        case rawV: Vec[_] =>
          rawV.collect {
            case f: Float => f
          }

        case _ =>
          raw.iterator.collect {
            case f: Float => f
          }.toIndexedSeq
      }
      import context.universe
      import context.universe.cursor
      SoundProcesses.atomic { implicit tx: S#Tx =>
        val invoker = objH()
        invoker.attr.$[proc.ActionRaw](key).foreach { action =>
          if (DEBUG) println("...and found action")
          val au = proc.Action.Universe[S](action, invoker = Some(invoker),
            value = proc.ActionRaw.FloatVector(values))
          action.execute(au)
        }
      }
  }

  protected def added()(implicit tx: Txn): Unit = ()
}