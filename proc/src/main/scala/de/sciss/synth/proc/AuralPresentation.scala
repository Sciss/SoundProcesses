/*
 *  AuralPresentation.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2014 Hanns Holger Rutz. All rights reserved.
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

import de.sciss.lucre.stm
import stm.Disposable
import impl.{AuralPresentationImpl => Impl}
import de.sciss.lucre.synth.{Sys, Group}

object AuralPresentation {
  // ---- implementation forwards ----

  def run[S <: Sys[S]](transport: ProcTransport[S], aural: AuralSystem): AuralPresentation[S] =
    Impl.run[S](transport, aural)

  def runTx[S <: Sys[S]](transport: ProcTransport[S], aural: AuralSystem)(implicit tx: S#Tx): AuralPresentation[S] =
    Impl.runTx[S](transport, aural)

  private[proc] trait Running[S <: Sys[S]] {
    /** Queries the number of channel associated with a scanned input.
      * Throws a control throwable when no value can be determined, making
      * the ugen graph builder mark the querying graph element as incomplete
      * (missing information).
      *
      * @param timed        the process whose graph is currently built
      * @param time         the time at which to query the scan
      * @param key          the scan key
      * @param numChannels  a given number of channels if `>= 0`, or `-1` to accept whatever the scan in provides
      *
      * @return             the number of channels for the scan input at the given time
      */
    def scanInNumChannels(timed: TimedProc[S], time: Long, key: String, numChannels: Int)(implicit tx: S#Tx): Int
  }

  final private[proc] case class MissingInfo[S <: Sys[S]](source: TimedProc[S], key: String) extends Throwable
}
trait AuralPresentation[S <: Sys[S]] extends Disposable[S#Tx] {
  def group(implicit tx: S#Tx): Option[Group]

  def stopAll(implicit tx: S#Tx): Unit
}
