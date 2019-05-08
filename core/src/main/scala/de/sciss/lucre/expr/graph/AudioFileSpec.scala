/*
 *  AudioFileSpec.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2019 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.lucre.expr.graph

import de.sciss.lucre.event.ITargets
import de.sciss.lucre.expr.graph.impl.MappedIExpr
import de.sciss.lucre.expr.{Context, IExpr}
import de.sciss.lucre.stm.Sys
import de.sciss.synth.io.AudioFileSpec

object AudioFileSpec {
  private final class NumChannelsExpanded[S <: Sys[S]](in: IExpr[S, AudioFileSpec], tx0: S#Tx)
                                                      (implicit targets: ITargets[S])
    extends MappedIExpr[S, AudioFileSpec, Int](in, tx0) {

    protected def mapValue(inValue: AudioFileSpec): Int = inValue.numChannels
  }

  final case class NumChannels(in: Ex[AudioFileSpec]) extends Ex[Int] {
    override def productPrefix: String = s"AudioFileSpec$$NumChannels" // serialization

    type Repr[S <: Sys[S]] = IExpr[S, Int]

    protected def mkRepr[S <: Sys[S]](implicit ctx: Context[S], tx: S#Tx): Repr[S] = {
      import ctx.targets
      new NumChannelsExpanded(in.expand[S], tx)
    }
  }

  private final class NumFramesExpanded[S <: Sys[S]](in: IExpr[S, AudioFileSpec], tx0: S#Tx)
                                                    (implicit targets: ITargets[S])
    extends MappedIExpr[S, AudioFileSpec, Long](in, tx0) {

    protected def mapValue(inValue: AudioFileSpec): Long = inValue.numFrames
  }

  final case class NumFrames(in: Ex[AudioFileSpec]) extends Ex[Long] {
    override def productPrefix: String = s"AudioFileSpec$$NumFrames" // serialization

    type Repr[S <: Sys[S]] = IExpr[S, Long]

    protected def mkRepr[S <: Sys[S]](implicit ctx: Context[S], tx: S#Tx): Repr[S] = {
      import ctx.targets
      new NumFramesExpanded(in.expand[S], tx)
    }
  }

  private final class SampleRateExpanded[S <: Sys[S]](in: IExpr[S, AudioFileSpec], tx0: S#Tx)
                                                    (implicit targets: ITargets[S])
    extends MappedIExpr[S, AudioFileSpec, Double](in, tx0) {

    protected def mapValue(inValue: AudioFileSpec): Double = inValue.sampleRate
  }

  final case class SampleRate(in: Ex[AudioFileSpec]) extends Ex[Double] {
    override def productPrefix: String = s"AudioFileSpec$$SampleRate" // serialization

    type Repr[S <: Sys[S]] = IExpr[S, Double]

    protected def mkRepr[S <: Sys[S]](implicit ctx: Context[S], tx: S#Tx): Repr[S] = {
      import ctx.targets
      new SampleRateExpanded(in.expand[S], tx)
    }
  }
}
