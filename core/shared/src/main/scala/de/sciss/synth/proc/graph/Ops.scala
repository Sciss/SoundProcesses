/*
 *  Ops.scala
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

package de.sciss.synth.proc.graph

import de.sciss.synth.GraphFunction

import scala.language.implicitConversions

object Ops {
  /** Allows the construction of attribute controls, for example via `"freq".kr`. */
  implicit def stringToControl(name: String): Attribute.Factory =
    new Attribute.Factory(name)

  /** Allows us to copy ScalaCollider examples into a proc's graph function. */
  def play[A: GraphFunction.Result](thunk: => A): Unit =
    playWith()(thunk)

  /** Allows us to copy ScalaCollider examples into a proc's graph function. */
  def playWith[A](fadeTime: Double = 0.02)(thunk: => A)(implicit result: GraphFunction.Result[A]): Unit =
    result.close(thunk, fadeTime = fadeTime)
}
