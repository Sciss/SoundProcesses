/*
 *  UGenGraphBuilderImpl.scala
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

package de.sciss.proc.impl

import de.sciss.lucre.Txn
import de.sciss.lucre.synth.Server
import de.sciss.proc.SoundProcesses.logAural
import de.sciss.proc.{Proc, UGenGraphBuilder => UGB}
import de.sciss.synth.NestedUGenGraphBuilder.ExpIfCase
import de.sciss.synth.{NestedUGenGraphBuilder, SynthGraph}

object UGenGraphBuilderImpl {
  import UGB.{Complete, Context, Incomplete, MissingIn, State}

  /** '''Note''': The resulting object is mutable, therefore must not be shared across threads and also must be
    * created and consumed within the same transaction. That is to say, to be transactionally safe, it may only
    * be stored in a `TxnLocal`, but not a full STM ref.
    */
  def apply[T <: Txn[T]](context: Context[T], proc: Proc[T])
                        (implicit tx: T): State[T] = {
    val in = init(proc)
    in.retry(context)
  }

  def init[T <: Txn[T]](proc: Proc[T])
                       (implicit tx: T): Incomplete[T] = {
    val g   = proc.graph.value
    val in  = new IncompleteImpl[T](g, Set.empty)
    in
  }

  // ---- impl ----

  private final class IncompleteImpl[T <: Txn[T]](g: SynthGraph, val rejectedInputs: Set[UGB.Key])
    extends Incomplete[T] {

    override def toString = s"UGenGraphBuilder.Incomplete@${hashCode.toHexString}"

    def acceptedInputs  = Map.empty[UGB.Key, Map[UGB.Input[_], UGB.Value]]
    def outputs         = Map.empty[String, Int]

    def retry(context: Context[T])(implicit tx: T): State[T] =
      new OuterImpl[T](context, tx).tryBuild(g)
  }

  private final class CompleteImpl[T <: Txn[T]](val result: NestedUGenGraphBuilder.Result,
      val outputs       : Map[String, Int],
      val acceptedInputs: Map[UGB.Key, Map[UGB.Input[_], UGB.Value]]
   ) extends Complete[T] {

    override def toString = s"UGenGraphBuilder.Complete@${hashCode.toHexString}"
  }

  private final class OuterImpl[T <: Txn[T]](protected val context: Context[T],
                                             protected val tx: T)
    extends NestedUGenGraphBuilder.Outer with Impl[T] with UGB.Requester[T] {

    private[this] var uniqueId = 0

    /*override*/ var acceptedInputs = Map.empty[UGB.Key, Map[UGB.Input[_], UGB.Value]]
    /*override*/ var outputs        = Map.empty[String, Int]

    override protected def outerImpl: OuterImpl[T] = this

    override def allocUniqueId(): Int = {
      val res = uniqueId
      uniqueId += 1
      res
    }

    def tryBuild(g: SynthGraph): State[T] =
      try {
        val result = build(g)
        new CompleteImpl[T](result, outputs = outputs, acceptedInputs = acceptedInputs)
      } catch {
        case MissingIn(rejected) =>
          new IncompleteImpl[T](g, Set(rejected))
      }

    override def requestInput[Res <: UGB.Value](req: UGB.Input[Res]): Res = {
      // we pass in `this` and not `in`, because that way the context
      // can find accepted inputs that have been added during the current build cycle!
      val res   = context.requestInput[Res](req, this)(tx)  // IntelliJ highlight bug
      val key   = req.key
      val map0  = acceptedInputs.getOrElse(key, Map.empty)
      val map1  = map0 + (req -> res)
      acceptedInputs += key -> map1
      logAural.debug(s"acceptedInputs += ${req.key} -> $res")
      res
    }

    override def addOutput(key: String, numChannels: Int): Unit =
      outputs.get(key).fold {
        outputs += key -> numChannels
      } { prevChannels =>
        if (numChannels != prevChannels) {
          val s1 = s"Cannot write multiple times to the same scan ($key)"
          val s2 = s"using different number of channels ($prevChannels, $numChannels)"
          sys.error(s"$s1 $s2")
        }
      }
  }

  private final class InnerImpl[T <: Txn[T]](protected val childId: Int,
                                             protected val thisExpIfCase: Option[ExpIfCase],
                                             protected val outerImpl: OuterImpl[T],
                                             protected val parent: NestedUGenGraphBuilder.Basic,
                                             protected val name: String,
                                             protected val context: Context[T],
                                             protected val tx: T)
    extends NestedUGenGraphBuilder.Inner with Impl[T] {

    override def requestInput[Res <: UGB.Value](req: UGB.Input[Res]): Res =
      outerImpl.requestInput[Res](req)

    override def addOutput(key: String, numChannels: Int): Unit =
      outerImpl.addOutput(key, numChannels)
  }

  private trait Impl[T <: Txn[T]]
    extends NestedUGenGraphBuilder.Basic with UGB /*with UGB.Requester[T]*/ {
    builder =>

    // ---- abstract ----

    protected def context: Context[T]
    protected def outerImpl: OuterImpl[T]
    protected def tx: T

    // ---- impl ----

    override def toString = s"UGenGraphBuilder.Incomplete@${hashCode.toHexString} (active)"

    protected final def mkInner(childId: Int, thisExpIfCase: Option[ExpIfCase], parent: NestedUGenGraphBuilder.Basic,
                          name: String): NestedUGenGraphBuilder.Inner =
      new InnerImpl(childId = childId, thisExpIfCase = thisExpIfCase, parent = parent, outerImpl = outerImpl,
        name = name, context = context, tx = tx)

    final def server: Server = context.server

//    final def retry(context: Context[T])(implicit tx: T): State[T] =
//      throw new IllegalStateException("Cannot retry an ongoing build")
  }
}