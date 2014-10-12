/*
 *  Code.scala
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

package de.sciss.synth.proc

import de.sciss.lucre.expr.impl.ExprTypeImplA
import de.sciss.serial.{Serializer, Writable, DataInput, DataOutput, ImmutableSerializer}
import impl.{CodeImpl => Impl}
import java.io.File
import scala.concurrent.{ExecutionContext, Future, blocking}
import de.sciss.processor.Processor
import de.sciss.synth
import scala.annotation.switch
import de.sciss.synth.proc
import de.sciss.lucre.{event => evt}
import evt.Sys
import de.sciss.lucre.expr.{Expr => _Expr}
import de.sciss.model
import scala.collection.immutable.{IndexedSeq => Vec}

object Code {
  final val typeID      = 0x20001

  final val UserPackage = "user"

  final case class CompilationFailed() extends Exception
  final case class CodeIncomplete   () extends Exception

  implicit def serializer: ImmutableSerializer[Code] = Impl.serializer

  def read(in: DataInput): Code = serializer.read(in)

  def apply(id: Int, source: String): Code = (id: @switch) match {
    case FileTransform.id => FileTransform(source)
    case SynthGraph   .id => SynthGraph   (source)
    case Action       .id => Action       (source)
  }

  def future[A](fun: => A)(implicit compiler: Code.Compiler): Future[A] = Impl.future(fun)

  def registerImports(id: Int, imports: Seq[String]): Unit = Impl.registerImports(id, imports)

  def getImports(id: Int): Vec[String] = Impl.getImports(id)

  // ---- compiler ----

  def unpackJar(bytes: Array[Byte]): Map[String, Array[Byte]] = Impl.unpackJar(bytes)

  trait Compiler {
    implicit def executionContext: ExecutionContext

    /** Synchronous call to Compile a source code consisting of a body which is wrapped in a `Function0` apply method,
      * returning the raw jar file produced in the compilation.
      *
      * May throw `CompilationFailed` or `CodeIncomplete`
      *
      * @param  source  the completely formatted source code to compile which should contain
      *                 a proper package and class definition. It must contain any
      *                 necessary `import` statements.
      * @return the jar file as byte-array, containing the opaque contents of the source code
      *         (possible the one single class defined)
      */
    def compile(source: String): Array[Byte]

    /** Synchronous call to compile and execute the provided source code.
      *
      * May throw `CompilationFailed` or `CodeIncomplete`
      *
      * @param  source  the completely formatted source code to compile which forms the body
      *                 of an imported object. It must contain any necessary `import` statements.
      * @return the evaluation result, or `()` if there is no result value
      */
    def interpret(source: String, execute: Boolean): Any
  }

  // ---- type: FileTransform ----

  object FileTransform {
    final val id    = 0
    final val name  = "File Transform"
  }
  final case class FileTransform(source: String) extends Code {
    type In     = (File, File, Processor[Any, _] => Unit)
    type Out    = Future[Unit]
    def id      = FileTransform.id

    def compileBody()(implicit compiler: Code.Compiler): Future[Unit] = Impl.compileBody[In, Out, FileTransform](this)

    def execute(in: In)(implicit compiler: Code.Compiler): Out = Impl.execute[In, Out, FileTransform](this, in)

    def contextName = FileTransform.name

    def updateSource(newText: String) = copy(source = newText)
  }

  // ---- type: SynthGraph ----

  object SynthGraph {
    final val id    = 1
    final val name  = "Synth Graph"
  }
  final case class SynthGraph(source: String) extends Code {
    type In     = Unit
    type Out    = synth.SynthGraph
    def id      = SynthGraph.id

    def compileBody()(implicit compiler: Code.Compiler): Future[Unit] = Impl.compileBody[In, Out, SynthGraph](this)

    def execute(in: In)(implicit compiler: Code.Compiler): Out = Impl.execute[In, Out, SynthGraph](this, in)

    def contextName = SynthGraph.name

    def updateSource(newText: String) = copy(source = newText)
  }

  // ---- type: Action ----

  object Action {
    final val id    = 2
    final val name  = "Action"
  }
  final case class Action(source: String) extends Code {
    type In     = String
    type Out    = Array[Byte]
    def id      = Action.id

    def compileBody()(implicit compiler: Code.Compiler): Future[Unit] = future(blocking { execute("Unnamed"); () })

    def execute(in: In)(implicit compiler: Code.Compiler): Out = {
      // Impl.execute[In, Out, Action](this, in)
      Impl.compileToFunction(in, this)
    }

    def contextName = Action.name

    def updateSource(newText: String) = copy(source = newText)

    // def compileToFunction(name: String): Future[Array[Byte]] = Impl.compileToFunction(name, this)
  }

  // ---- expr ----

  object Expr extends ExprTypeImplA[Code] {
    def typeID = Code.typeID

    def readValue(in: DataInput): Code = Code.read(in)
    def writeValue(value: Code, out: DataOutput): Unit = value.write(out)

    protected def readTuple[S <: Sys[S]](cookie: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                        (implicit tx: S#Tx): Code.Expr.ExN[S] = {
      sys.error(s"No tuple operations defined for Code ($cookie)")
    }
  }
  // ---- element ----
  object Elem {
    def apply[S <: Sys[S]](peer: _Expr[S, Code])(implicit tx: S#Tx): Code.Elem[S] = Impl.ElemImpl(peer)

    implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Code.Elem[S]] = Impl.ElemImpl.serializer
  }

  trait Elem[S <: Sys[S]] extends proc.Elem[S] {
    type Peer         = _Expr[S, Code]
    type PeerUpdate   = model.Change[Code]

    def mkCopy()(implicit tx: S#Tx): Elem[S]
  }

  object Obj {
    def unapply[S <: Sys[S]](obj: proc.Obj[S]): Option[Code.Obj[S]] =
      if (obj.elem.isInstanceOf[Code.Elem[S]]) Some(obj.asInstanceOf[Code.Obj[S]])
      else None
  }

  type Obj[S <: Sys[S]] = proc.Obj.T[S, Code.Elem]
}
sealed trait Code extends Writable { me =>
  /** The interfacing input type */
  type In
  /** The interfacing output type */
  type Out

  /** Identifier to distinguish types of code. */
  def id: Int

  /** Source code. */
  def source: String

  def updateSource(newText: String): Code { type In = me.In; type Out = me.Out }

  /** Human readable name. */
  def contextName: String

  /** Compiles the code body without executing it. */
  def compileBody()(implicit compiler: Code.Compiler): Future[Unit]

  /** Compiles and executes the code. Returns the wrapped result. */
  def execute(in: In)(implicit compiler: Code.Compiler): Out // = compile()(in)

  def write(out: DataOutput): Unit = Code.serializer.write(this, out)
}