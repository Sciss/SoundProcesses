/*
 *  UGenGraphBuilderImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package impl

import de.sciss.synth.impl.BasicUGenGraphBuilder
import collection.immutable.{IndexedSeq => IIdxSeq, Set => ISet}
import de.sciss.synth.{UGenGraph, Lazy, SynthGraph}
import de.sciss.synth.ugen.ControlProxyLike

private[proc] object UGenGraphBuilderImpl {
  def apply[S <: Sys[S]](aural: AuralPresentation.Running[S], timed: TimedProc[S], time: Long)
                        (implicit tx: S#Tx): UGenGraphBuilder[S] =
    new Impl(aural, timed, time, timed.value.graph.value, tx)

  private final class Impl[S <: Sys[S]](aural: AuralPresentation.Running[S],
                                        val timed: TimedProc[S], val time: Long, g: SynthGraph, val tx: S#Tx)
    extends BasicUGenGraphBuilder with UGenGraphBuilder[S] {
    builder =>

    import UGenGraphBuilder._

    override def toString = "proc.UGenGraph.Builder@" + hashCode.toHexString

    private var remaining: IIdxSeq[Lazy]                  = g.sources
    private var controlProxies: ISet[ControlProxyLike[_]] = g.controlProxies

    var scanOuts    = Map.empty[String, Int]
    var scanIns     = Map.empty[String, Int]
    var missingIns  = Set.empty[MissingIn[S]]
    var attributeIns= Set.empty[String]

    def addScanIn(key: String): Int = {
      val res = aural.scanInNumChannels(timed, time, key)(tx)
      scanIns += key -> res
      res
    }

    def addScanOut(key: String, numChannels: Int) {
      scanOuts.get(key) match {
        case Some(prevChans) =>
          require(numChannels == prevChans, "Cannot write multiple times to the same scan (" + key +
            ") using different number of channels (" + prevChans + ", " + numChannels + ")")
        case _ =>
          scanOuts += key -> numChannels
      }
    }

    def addAttributeIn(key: String) { attributeIns += key }

    def tryBuild(): Boolean = UGenGraph.use(this) {
      var missingElems  = Vector.empty[Lazy]
      missingIns        = Set.empty
      var someSucceeded = false
      while (remaining.nonEmpty) {
        val g = SynthGraph {
          remaining.foreach { elem =>
            // save rollback information -- not very elegant; should figure out how scala-stm nesting works
            val savedSourceMap      = sourceMap
            val savedControlNames   = controlNames
            val savedControlValues  = controlValues
            val savedUGens          = ugens
            val savedScanOuts       = scanOuts
            val savedScanIns        = scanIns
            val savedAttrs          = attributeIns
            try {
              elem.force(builder)
              someSucceeded = true
            } catch {
              case miss @ MissingIn(_) =>
                sourceMap           = savedSourceMap
                controlNames        = savedControlNames
                controlValues       = savedControlValues
                ugens               = savedUGens
                scanOuts            = savedScanOuts
                scanIns             = savedScanIns
                attributeIns        = savedAttrs
                missingElems      :+= elem
                missingIns         += miss.asInstanceOf[MissingIn[S]] // XXX TODO yukk
            }
          }
        }
        if (g.nonEmpty) {
          remaining        = g.sources
          controlProxies ++= g.controlProxies
        } else {
          remaining = Vector.empty
        }
      }

      if (missingElems.isEmpty) {
        true // Finished // ( build( controlProxies ))
      } else {
        remaining = missingElems
        false // Partial // ( missingIns, advanced = someSucceeded )
      }
    }

    def isComplete = remaining.isEmpty

    def finish: UGenGraph = UGenGraph.use(this) {
      require(isComplete)
      build(controlProxies)
    }
  }
}