/*
 *  attribute.scala
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

package de.sciss.synth
package proc
package graph

object attribute {
  private[proc] def controlName(key: String): String = "$attr_"  + key

  private final case class In(key: String, default: Double) extends GE.Lazy with ScalarRated {

    override def productPrefix  = "attribute$In"
    override def toString       = s"""attribute("$key").ir($default)"""

    def makeUGens: UGenInLike = {
      UGenGraph.builder match {
        case b: UGenGraphBuilder[_] =>
          b.addAttributeIn(key)
          val ctlName = controlName(key)
          ctlName.ir(default).expand

        case _ => UGenGraphBuilder.outsideOfContext()
      }
    }
  }
}
final case class attribute(key: String) {
  def ir: GE = ir(0.0)
  def ir(default: Double): GE = attribute.In(key, default)
}