/*
 *  StopSelfResponder.scala
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

package de.sciss.synth.proc.graph.impl

import de.sciss.lucre.Cursor
import de.sciss.lucre.synth.{RT, Synth, Txn}
import de.sciss.osc
import de.sciss.proc.{SoundProcesses, ViewBase}
import de.sciss.synth.proc.graph.StopSelf

final class StopSelfResponder[T <: Txn[T]](view: ViewBase[T], protected val synth: Synth)
                                          (implicit cursor: Cursor[T])
  extends SendReplyResponder {

  private[this] val NodeId = synth.peer.id

  protected val body: Body = {
    case osc.Message(StopSelf.replyName, NodeId, 0, _) =>
      SoundProcesses.step[T](s"StopSelfResponder($synth)") { implicit tx: T =>
        view.stop()
      }
  }

  protected def added()(implicit tx: RT): Unit = ()
}
