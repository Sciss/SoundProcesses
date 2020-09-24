/*
 *  Proc.scala
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

package de.sciss.lucre.expr.graph

import de.sciss.file._
import de.sciss.lucre.{IExpr, ITargets, Source, StringObj, Txn, Sys}
import de.sciss.lucre.expr.graph.impl.{ExpandedObjMakeImpl, ObjImplBase}
import de.sciss.lucre.expr.{Context, IAction}
import de.sciss.synth.proc
import de.sciss.synth.proc.{ObjKeys, SynthGraphObj, AudioCue => _AudioCue}

object Proc {
  def apply(): Ex[Proc] with Obj.Make = Apply()

  object Tape {
    def apply(cue: Ex[_AudioCue]): Ex[Proc] with Obj.Make = TapeImpl(cue)

    private final case class TapeImpl(cue: Ex[_AudioCue]) extends Ex[Proc] with Act with Obj.Make {
      override def productPrefix: String = s"Proc$$Tape" // serialization

      type Repr[T <: Txn[T]] = IExpr[T, Proc] with IAction[T]

      def make: Act = this

      protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
        import ctx.targets
        new TapeExpanded(cue.expand[T])
      }
    }
  }

  private final class TapeExpanded[T <: Txn[T]](cue: IExpr[T, _AudioCue])(implicit targets: ITargets[T])
    extends ExpandedObjMakeImpl[T, Proc] {

    protected def empty: Proc = Empty

    protected def make()(implicit tx: T): Proc = {
      val peer    = proc.Proc[T]()
      val a       = peer.attr
      val cueV    = cue.value
      val name    = StringObj     .newVar[T](cueV.artifact.base)
      val cueObj  = _AudioCue.Obj .newVar[T](cueV)
      a.put(ObjKeys   .attrName   , name)
      a.put(proc.Proc .graphAudio , cueObj)
      peer.graph() = SynthGraphObj.tape
      a.put(proc.Proc.attrSource, SynthGraphObj.tapeSource)
      peer.outputs.add(proc.Proc.mainOut)
      new Impl(tx.newHandle(peer), tx.system)
    }
  }

  private[lucre] def wrap[T <: Txn[T]](peer: Source[T, proc.Proc[T]], system: Sys): Proc =
    new Impl[T](peer, system)

  private final class Impl[T <: Txn[T]](in: Source[T, proc.Proc[T]], system: Sys)
    extends ObjImplBase[T, proc.Proc](in, system) with Proc {

    override type Peer[~ <: Txn[~]] = proc.Proc[~]
  }

  private[lucre] object Empty extends Proc {
    private[lucre] def peer[T <: Txn[T]](implicit tx: T): Option[Peer[T]] = None
  }

  private final class ApplyExpanded[T <: Txn[T]](implicit targets: ITargets[T])
    extends ExpandedObjMakeImpl[T, Proc] {

    protected def empty: Proc = Empty

    protected def make()(implicit tx: T): Proc = {
      val peer = proc.Proc[T]()
      new Impl(tx.newHandle(peer), tx.system)
    }
  }

  private final case class Apply() extends Ex[Proc] with Act with Obj.Make {
    override def productPrefix: String = "Proc" // serialization

    type Repr[T <: Txn[T]] = IExpr[T, Proc] with IAction[T]

    def make: Act = this

    protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
      import ctx.targets
      new ApplyExpanded[T]
    }
  }
}
trait Proc extends Obj {
  type Peer[~ <: Txn[~]] = proc.Proc[~]
}