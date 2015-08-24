/*
 *  FadeSpec.scala
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

package de.sciss
package synth
package proc

import de.sciss.lucre.event.{Pull, Targets}
import de.sciss.lucre.expr.{Expr => _Expr, DoubleObj, LongObj, Type}
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.{stm, expr}
import de.sciss.serial.{DataInput, DataOutput, ImmutableSerializer}
import de.sciss.synth.Curve.linear
import de.sciss.{model => m}

object FadeSpec extends Obj.Type {
  final val typeID = 14

  override def init(): Unit = {
    super.init()
    Obj  .init()
  }

  private final val COOKIE = 0x4664 // 'Fd'

  implicit object serializer extends ImmutableSerializer[FadeSpec] {
    def write(v: FadeSpec, out: DataOutput): Unit = {
      import v._
      out.writeShort(COOKIE)
      out.writeLong (numFrames)
      Curve.serializer.write(curve, out)
      out.writeFloat(floor)
    }

    def read(in: DataInput): FadeSpec = {
      val cookie = in.readShort()
      require(cookie == COOKIE, s"Unexpected cookie $cookie, expected $COOKIE")
      val numFrames = in.readLong()
      val curve     = Curve.serializer.read(in)
      val floor     = in.readFloat()
      FadeSpec(numFrames = numFrames, curve = curve, floor = floor)
    }
  }

  object Obj extends expr.impl.ExprTypeImpl[FadeSpec, FadeSpec.Obj] {
    def typeID = FadeSpec.typeID

    import FadeSpec.{Obj => Repr}

    override def init(): Unit = {
      super.init()
      registerExtension(Apply)
    }

    protected def mkConst[S <: Sys[S]](id: S#ID, value: A)(implicit tx: S#Tx): Const[S] =
      new _Const[S](id, value)

    protected def mkVar[S <: Sys[S]](targets: Targets[S], vr: S#Var[Ex[S]])(implicit tx: S#Tx): Var[S] =
      new _Var[S](targets, vr)

    private[this] final class _Const[S <: Sys[S]](val id: S#ID, val constValue: A)
      extends ConstImpl[S] with Repr[S]

    private[this] final class _Var[S <: Sys[S]](val targets: Targets[S], val ref: S#Var[Ex[S]])
      extends VarImpl[S] with Repr[S]

    // 0 reserved for variables
    private final val elemCookie = 1

    //    def longType    : BiType[Long    ] = Longs
    //    def spanLikeType: BiType[SpanLike] = SpanLikes

    def valueSerializer: ImmutableSerializer[FadeSpec] = FadeSpec.serializer

//    def readValue (                 in : DataInput ): FadeSpec  = FadeSpec.serializer.read (       in )
//    def writeValue(value: FadeSpec, out: DataOutput): Unit      = FadeSpec.serializer.write(value, out)

    def apply[S <: Sys[S]](numFrames: LongObj[S], shape: CurveObj[S], floor: DoubleObj[S])
                              (implicit tx: S#Tx): Obj[S] = {
      val targets = Targets[S]
      new Apply(targets, numFrames, shape, floor).connect()
    }

    def unapply[S <: Sys[S]](expr: Obj[S]): Option[(LongObj[S], CurveObj[S], DoubleObj[S])] =
      expr match {
        case impl: Apply[S] => Some((impl.numFrames, impl.shape, impl.floor))
        case _ => None
      }

    private object Apply extends Type.Extension1[Obj] {
      def readExtension[S <: Sys[S]](opID: Int, in: DataInput, access: S#Acc, targets: Targets[S])
                                    (implicit tx: S#Tx): Obj[S] = {
        val numFrames = LongObj  .read(in, access)
        val shape     = CurveObj .read(in, access)
        val floor     = DoubleObj.read(in, access)
        new Apply(targets, numFrames, shape, floor)
      }

      def name: String = "Apply"

      val opHi: Int = 0
      val opLo: Int = 0
    }
    private final class Apply[S <: Sys[S]](protected val targets: Targets[S],
                                              val numFrames: LongObj[S],
                                              val shape: CurveObj[S],
                                              val floor: DoubleObj[S])
      extends lucre.expr.impl.NodeImpl[S, FadeSpec] with Obj[S] {

      def tpe: stm.Obj.Type = FadeSpec

      def value(implicit tx: S#Tx): FadeSpec = FadeSpec(numFrames.value, shape.value, floor.value.toFloat)

      object changed extends Changed {
        def pullUpdate(pull: Pull[S])(implicit tx: S#Tx): Option[m.Change[FadeSpec]] = {
          val framesEvt = numFrames.changed
          val framesChO = if (pull.contains(framesEvt)) pull(framesEvt) else None
          val shapeEvt  = shape.changed
          val shapeChO  = if (pull.contains(shapeEvt )) pull(shapeEvt ) else None
          val floorEvt  = floor.changed
          val floorChO  = if (pull.contains(floorEvt )) pull(floorEvt ) else None

          if (framesChO.isEmpty && shapeChO.isEmpty && floorChO.isEmpty) return None

          val framesCh = framesChO.getOrElse {
            val framesV = numFrames.value
            m.Change(framesV, framesV)
          }

          val shapeCh = shapeChO.getOrElse {
            val shapeV = shape.value
            m.Change(shapeV, shapeV)
          }

          val floorCh = floorChO.getOrElse {
            val floorV = floor.value
            m.Change(floorV, floorV)
          }

          val before  = FadeSpec(framesCh.before, shapeCh.before, floorCh.before.toFloat)
          val now     = FadeSpec(framesCh.now,    shapeCh.now,    floorCh.now   .toFloat)

          Some(m.Change(before, now))
        }
      }

      protected def disposeData()(implicit tx: S#Tx): Unit = disconnect()

      protected def writeData(out: DataOutput): Unit = {
        out.writeByte(elemCookie)
        numFrames.write(out)
        shape    .write(out)
        floor    .write(out)
      }

      def connect()(implicit tx: S#Tx): this.type = {
        numFrames.changed ---> changed
        shape    .changed ---> changed
        floor    .changed ---> changed
        this
      }

      private[this] def disconnect()(implicit tx: S#Tx): Unit = {
        numFrames.changed -/-> changed
        shape    .changed -/-> changed
        floor    .changed -/-> changed
      }
    }
  }
  trait Obj[S <: Sys[S]] extends _Expr[S, FadeSpec]

  override def readIdentifiedObj[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Obj[S] =
    Obj.readIdentifiedObj(in, access)
}
final case class FadeSpec(numFrames: Long, curve: Curve = linear, floor: Float = 0f)
