/*
 *  ExOps.scala
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

package de.sciss.proc

import java.net.URI

import de.sciss.lucre.Adjunct.HasDefault
import de.sciss.lucre.expr
import de.sciss.lucre.expr.graph.{Ex, Obj, AudioCue => _AudioCue}
import de.sciss.lucre.expr.impl.{ExObjBridgeImpl => Impl}
import de.sciss.lucre.expr.{ExAudioFileSpecOps, ExFileOps}
import de.sciss.proc

import scala.language.implicitConversions

object ExImport extends ExImport
trait ExImport extends expr.ExImport {
  // XXX TODO --- this is all a bit messy

  implicit val codeExAttrBridge       : Obj.Bridge[Code       ] with Obj.CanMake[Code       ] = new Impl(Code       .Obj)
  implicit val colorExAttrBridge      : Obj.Bridge[Color      ] with Obj.CanMake[Color      ] = new Impl(Color      .Obj)
  implicit val curveExAttrBridge      : Obj.Bridge[Curve      ] with Obj.CanMake[Curve      ] = new Impl(CurveObj       )
  implicit val envSegmentExAttrBridge : Obj.Bridge[EnvSegment ] with Obj.CanMake[EnvSegment ] = new Impl(EnvSegment .Obj)
  implicit val fadeSpecExAttrBridge   : Obj.Bridge[FadeSpec   ] with Obj.CanMake[FadeSpec   ] = new Impl(FadeSpec   .Obj)

  implicit def audioCueType: Obj.Bridge[AudioCue] with Obj.CanMake[AudioCue] with HasDefault[AudioCue] =
    _AudioCue.Type

  implicit object audioFileSpecIsValue  extends Ex.Value[AudioFileSpec]
  implicit object colorIsValue          extends Ex.Value[Color]
  implicit object curveIsValue          extends Ex.Value[Curve]

  type AudioCue       = proc              .AudioCue
  type AudioFileSpec  = de.sciss.audiofile.AudioFileSpec
  type Color          = proc              .Color
  type Curve          = de.sciss.synth    .Curve
  type FadeSpec       = proc              .FadeSpec

  /** The general sample-rate used in objects such as `Timeline`, `Grapheme`, `Transport`, `Scheduler`. */
  final val SampleRate  = TimeRef.SampleRate

  implicit def fileOps          (x: Ex[URI          ]): ExFileOps           = new ExFileOps(x)
  implicit def audioFileSpecOps (x: Ex[AudioFileSpec]): ExAudioFileSpecOps  = new ExAudioFileSpecOps(x)

  //  implicit final class procTrigOps(private val x: Trig) extends AnyVal {
//    def delay(time: Ex[Double]): Delay
//  }
}

