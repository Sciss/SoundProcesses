/*
 *  UGenGraphBuilder.scala
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

import de.sciss.lucre.Txn
import de.sciss.lucre.synth.Server
import de.sciss.proc.impl.{UGenGraphBuilderImpl => Impl}
import de.sciss.synth.{NestedUGenGraphBuilder, UGenGraph}
import de.sciss.synth.proc.graph

import scala.util.control.ControlThrowable

object UGenGraphBuilder {
  def get: UGenGraphBuilder = UGenGraph.builder match {
    case b: UGenGraphBuilder => b
    case _ => throw new IllegalStateException("Expansion out of context")
  }

  /** An exception thrown when during incremental build an input is required for which the underlying source
    * cannot yet be determined.
    *
    * This can be a case class because it is used only within the same transaction,
    * and thereby the `timed` argument does not become stale.
    */
  final case class MissingIn(input: Key /* Input */) extends ControlThrowable

  /** '''Note''': The resulting object is mutable, therefore must not be shared across threads and also must be
    * created and consumed within the same transaction. That is to say, to be transactionally safe, it may only
    * be stored in a `TxnLocal`, but not a full STM ref.
    */
  def apply[T <: Txn[T]](context: Context[T], proc: Proc[T])
                        (implicit tx: T): State[T] = Impl(context, proc)

  def init[T <: Txn[T]](proc: Proc[T])(implicit tx: T): Incomplete[T] = Impl.init(proc)

  case class ScanIn(numChannels: Int, fixed: Boolean)

  trait Context[T <: Txn[T]] {
    def server: Server

    def requestInput[Res <: Value](req: Input[Res], io: Requester[T])(implicit tx: T): Res
  }

  trait IO[T <: Txn[T]] {
    def acceptedInputs: Map[Key, Map[Input[_], Value]]

    /** Current set of used outputs (scan keys to number of channels).
      * This is guaranteed to only grow during incremental building, never shrink.
      */
    def outputs: Map[String, Int]
  }

  trait Requester[T <: Txn[T]] extends IO[T] {
    /** Asks for a unique (monotonously increasing) number that can be used
      * to created a unique control name, for example.
      */
    def allocUniqueId(): Int
  }

  sealed trait State[T <: Txn[T]] extends IO[T] {
    def rejectedInputs: Set[Key]

    def isComplete: Boolean
  }

  trait Incomplete[T <: Txn[T]] extends State[T] {
    def retry(context: Context[T])(implicit tx: T): State[T]

    final def isComplete = false
  }

  trait Complete[T <: Txn[T]] extends State[T] {
    def result: NestedUGenGraphBuilder.Result

    final def isComplete = true
    // final def missingIns = Set.empty[String]

    final def rejectedInputs = Set.empty[UGenGraphBuilder.Key]
  }

  // --------------------------------------------

  /** A pure marker trait to rule out some type errors. */
  trait Key
  /** A scalar value found in the attribute map. */
  final case class AttributeKey(name: String) extends Key
//  /** A entry in a proc's scan map. */
//  final case class InputKey    (name: String) extends Key
  //  /** A buffer source found in the attribute map. */
  //  final case class BufferKey(name: String) extends Key

  // final case class NumChannels(value: Int) extends UGenGraphBuilder.Value

  /** A pure marker trait to rule out some type errors. */
  trait Value {
    def async: Boolean
  }
  case object Unit extends Value {
    final val async = false
  }
  type Unit = Unit.type

  object Input {
    object Stream {
      def EmptySpec: Spec = Spec(0.0, 0)

      final case class Spec(maxSpeed: Double, interp: Int) {
        /** Empty indicates that the stream is solely used for information
          * purposes such as `BufChannels`.
          */
        def isEmpty: Boolean = interp == 0

        /** Native indicates that the stream will be transported by the UGen
          * itself, i.e. via `DiskIn` or `VDiskIn`.
          */
        def isNative: Boolean = interp == -1

        override def productPrefix = "Input.Stream.Spec"

        override def toString = f"$productPrefix(maxSpeed = $maxSpeed%1.1f, interp = $interp)"
      }
      final case class Value(numChannels: Int, sampleRate: Double, specs: List[Spec]) extends UGenGraphBuilder.Value {
        override def productPrefix = "Input.Stream.Value"
        override def toString = s"$productPrefix(numChannels = $numChannels, spec = ${specs.mkString("[", ",", "]")})"
        def async = false
      }
    }
    final case class Stream(name: String, spec: Stream.Spec) extends Input[Stream.Value] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.Stream"
    }

    object DiskOut {
      final case class Value(numChannels: Int) extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.DiskOut.Value"
        override def toString = s"$productPrefix(numChannels = $numChannels)"
      }
    }
    final case class DiskOut(name: String, numChannels: Int) extends Input[DiskOut.Value] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.DiskOut"
    }

    object Scalar {
      final case class Value(numChannels: Int) extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.Scalar.Value"
        override def toString = s"$productPrefix(numChannels = $numChannels)"
      }
    }
    /** Specifies access to a scalar attribute as a control signal.
      *
      * @param name                 name (key) of the attribute
      * @param requiredNumChannels  the required number of channels or `-1` if no specific requirement
      * @param defaultNumChannels   the default  number of channels or `-1` if no default provided
      */
    final case class Scalar(name: String, requiredNumChannels: Int, defaultNumChannels: Int) 
      extends Input[Scalar.Value] {
      
      type Key    = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.Scalar"
    }

    object Attribute {
      final case class Value(peer: Option[Any]) extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.Attribute.Value"
      }
    }
    /** Specifies access to a an attribute's value at build time.
      *
      * @param name   name (key) of the attribute
      */
    final case class Attribute(name: String) extends Input[Attribute.Value] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.Attribute"
    }

    object Buffer {
      /** Maximum number of samples (channels multiplied by frames)
        * prepared on-the-fly. If the number of samples exceeds this
        * value, use asynchronous preparation.
        */
      final val AsyncThreshold = 65536

      final case class Value(numFrames: Long, numChannels: Int, async: Boolean) extends UGenGraphBuilder.Value {
        override def productPrefix = "Input.Buffer.Value"
        override def toString = s"$productPrefix(numFrames = $numFrames, numChannels = $numChannels, async = $async)"

        def numSamples: Long = numFrames * numChannels
      }
    }
    /** Specifies access to a random access buffer.
      *
      * @param name         name (key) of the attribute referring to an object that
      *                     can be buffered (e.g. audio grapheme)
      */
    final case class Buffer(name: String) extends Input[Buffer.Value] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.Buffer"
    }

    /** Specifies access to an empty buffer that will be
      * written to disk when the encompassing graph finishes.
      */
    final case class BufferOut(artifact: String, action: String, numFrames: Int, numChannels: Int)
      extends Input[Unit] with Key {

      type Key = BufferOut

      def key: Key = this

      override def productPrefix = "Input.BufferOut"
    }

    object BufferGen {
      final case class Value(id: Int) extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.BufferGen.Value"
      }
    }
    /** Specifies access to an buffer filled by a generator function. */
    final case class BufferGen(cmd: graph.BufferGen.Command, numFrames: Int, numChannels: Int)
      extends Input[BufferGen.Value] with Key {

      type Key = BufferGen

      def key: Key = this

      override def productPrefix = "Input.BufferGen"
    }

    object Action {
      case object Value extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.Action.Value"
      }
    }
    /** Specifies access to an action.
      *
      * @param name   name (key) of the attribute referring to an action
      */
    final case class Action(name: String) extends Input[Action.Value.type] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.Action"
    }

    object MkValue {
      final case class Value(defined: Boolean) extends UGenGraphBuilder.Value {
        def async = false
        override def productPrefix = "Input.MkValue.Value"
      }
    }
    /** Specifies access to `expr.Var`.
      *
      * @param name   name (key) of the attribute referring to a variable
      */
    final case class MkValue(name: String) extends Input[MkValue.Value] {
      type Key = AttributeKey

      def key: Key = AttributeKey(name)

      override def productPrefix = "Input.MkValue"
    }

    case object StopSelf extends Input[Unit] with Key {
      type Key = StopSelf.type

      def key: Key = this

      override def productPrefix = "Input.StopSelf"
    }
  }
  trait Input[V <: Value] {
    type Key <: UGenGraphBuilder.Key

    def key: Key
  }
}
trait UGenGraphBuilder extends NestedUGenGraphBuilder {
  import UGenGraphBuilder._

  def server: Server

  /** Called by graph elements during their expansion, this method forwards a request
    * for input specifications to the `UGenGraphBuilder.Context`. The context should
    * examine the input and return an appropriate value of type `input.Value` that
    * will then be stored under `input.key` in the `acceptedInputs` map of the builder
    * state.
    *
    * Note that the builder will not check whether an entry with the key already exists
    * in the map or not. It is the responsibility of the context to react appropriately
    * to repeated calls with the same input key. For example, the same attribute key
    * for a streaming operation may be used multiple times, perhaps with different
    * streaming speeds.
    *
    * If an input resource is not ready, the context should throw a `MissingIn` exception.
    * The builder will catch that exception and add the key to `rejectedInputs` instead
    * of `acceptedInputs` instead.
    */
  def requestInput[Res <: UGenGraphBuilder.Value](input: Input[Res]): Res

  /** This method should only be invoked by the `graph.scan.Elem` instances. It declares a scan output along
    * with the number of channels written to it.
    */
  def addOutput(key: String, numChannels: Int): scala.Unit
}