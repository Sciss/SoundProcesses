/*
 *  Code.scala
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

package de.sciss.synth.proc

import de.sciss.lucre.event.Targets
import de.sciss.lucre.expr
import de.sciss.lucre.expr.impl.ExprTypeImpl
import de.sciss.lucre.stm.Sys
import de.sciss.serial.{DataInput, DataOutput, ImmutableSerializer, Writable}
import de.sciss.synth
import de.sciss.synth.proc.impl.{CodeImpl => Impl}

import scala.collection.immutable.{IndexedSeq => Vec, Seq => ISeq}
import scala.concurrent.{ExecutionContext, Future, blocking}

object Code {
  final val typeId = 0x20001

  def init(): Unit = {
    Obj.init()
    SynthGraph   .init()
    Action       .init()
  }

  final val UserPackage = "user"

  /** Generates the default package statement. */
  def packagePrelude: String = s"package $UserPackage\n"

  final case class CompilationFailed() extends Exception
  final case class CodeIncomplete   () extends Exception

  object Import {
    sealed trait Selector {
      def sourceString: String
    }
    sealed trait Simple extends Selector
    case object Wildcard extends Simple {
      def sourceString = "_"
    }
    sealed trait Named extends Selector {
      /** Name under which the import is known in this source. */
      def name: String
    }
    final case class Name(name: String) extends Named with Simple {
      def sourceString: String = name
    }
    final case class Rename(from: String, to: String) extends Named {
      def name        : String = to
      def sourceString: String = s"$from => $to"
    }
    final case class Ignore(name: String) extends Selector {
      def sourceString: String = s"$name => _"
    }

    val All: List[Selector] = Wildcard :: Nil
  }
  final case class Import(prefix: String, selectors: List[Import.Selector]) {
    require (selectors.nonEmpty)

    /** The full expression, such as `scala.collection.immutable.{IndexedSeq => Vec}` */
    def expr: String = selectors match {
//      case Nil                            => prefix
      case (single: Import.Simple) :: Nil => s"$prefix.${single.sourceString}"
      case _                              => selectors.iterator.map(_.sourceString).mkString(s"$prefix.{", ", ", "}")
    }

    /** The equivalent source code, such as `import scala.collection.immutable.{IndexedSeq => Vec}` */
    def sourceString: String = s"import $expr"
  }

  implicit def serializer: ImmutableSerializer[Code] = Impl.serializer

  def read(in: DataInput): Code = serializer.read(in)

  def future[A](fun: => A)(implicit compiler: Code.Compiler): Future[A] = Impl.future(fun)

  def registerImports(id: Int, imports: Seq[Import]): Unit = Impl.registerImports(id, imports)

  def getImports(id: Int): Vec[Import] = Impl.getImports(id)

  /** Generates the import statements prelude for a given code object. */
  def importsPrelude(code: Code, indent: Int = 0): String = Impl.importsPrelude(code, indent = indent)

  /** Generates the full prelude of a code object, containing package, imports, and code specific prelude. */
  def fullPrelude(code: Code): String =
    s"${Code.packagePrelude}${Code.importsPrelude(code)}${code.prelude}"

  // ---- type ----

  def apply(id: Int, source: String): Code = Impl(id, source)

  def addType(tpe: Type): Unit = Impl.addType(tpe)

  def getType(id: Int): Code.Type = Impl.getType(id)

  def types: ISeq[Code.Type] = Impl.types

  trait Type {
    def id: Int

    def prefix        : String
    def humanName     : String
    def docBaseSymbol : String

    type Repr <: Code

    private[this] lazy val _init: Unit = Code.addType(this)

    def init(): Unit = _init

    def mkCode(source: String): Repr
  }

  // ---- compiler ----

  def unpackJar(bytes: Array[Byte]): Map[String, Array[Byte]] = Impl.unpackJar(bytes)

  trait Compiler {
    implicit def executionContext: ExecutionContext

    /** Synchronous call to compile a source code consisting of a body which is wrapped in a `Function0` apply method,
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
    def interpret(source: String, print: Boolean, execute: Boolean): Any
  }

  // ---- type: SynthGraph ----

  object SynthGraph extends Type {
    final val id        = 1

    final val prefix    = "Proc"
    final val humanName = "Synth Graph"

    type Repr = SynthGraph

    def docBaseSymbol: String = "de.sciss.synth.ugen"

    def mkCode(source: String): Repr = SynthGraph(source)
  }
  final case class SynthGraph(source: String) extends Code {
    type In     = Unit
    type Out    = synth.SynthGraph

    def tpe: Code.Type = SynthGraph

    def compileBody()(implicit compiler: Code.Compiler): Future[Unit] = {
      import reflect.runtime.universe._
      Impl.compileBody[In, Out, Unit, SynthGraph](this, typeTag[Unit])
    }

    def execute(in: In)(implicit compiler: Code.Compiler): Out =
      synth.SynthGraph {
        import reflect.runtime.universe._
        Impl.compileThunk[Unit](this, typeTag[Unit], execute = true)
      }

    def prelude : String = "object Main {\n"

    def postlude: String = "\n}\n"

    def updateSource(newText: String): SynthGraph = copy(source = newText)
  }

  // ---- type: Action ----

  object Action extends Type {
    final val id = 2

    final val prefix  = "Action"

    def humanName: String = prefix

    type Repr = Action

    def docBaseSymbol: String = s"$pkgAction$$$$Universe"

    def mkCode(source: String): Repr = Action(source)

    private def pkgAction = "de.sciss.synth.proc.Action"
    private val pkgSys    = "de.sciss.lucre.stm"
  }
  final case class Action(source: String) extends Code {
    type In     = String
    type Out    = Array[Byte]

    def tpe: Code.Type = Action

    def compileBody()(implicit compiler: Code.Compiler): Future[Unit] = future(blocking { execute("Unnamed"); () })

    def execute(in: In)(implicit compiler: Code.Compiler): Out = {
      // Impl.execute[In, Out, Action](this, in)
      Impl.compileToJar(in, this, prelude = mkPrelude(in), postlude = postlude)
    }

    def updateSource(newText: String): Action = copy(source = newText)

    private def mkPrelude(name: String): String = {
      import Action.{pkgAction, pkgSys}

      s"""final class $name extends $pkgAction.Body {
         |  def apply[S <: $pkgSys.Sys[S]](universe: $pkgAction.Universe[S])(implicit tx: S#Tx): Unit = {
         |    import universe._
         |""".stripMargin
    }

    def prelude: String = mkPrelude("Main")

    def postlude: String = "\n  }\n}\n"
  }

  // ---- expr ----

  object Obj extends ExprTypeImpl[Code, Obj] {
    import Code.{Obj => Repr}

    def typeId: Int = Code.typeId

    def valueSerializer: ImmutableSerializer[Code] = Code.serializer

    protected def mkConst[S <: Sys[S]](id: S#Id, value: A)(implicit tx: S#Tx): Const[S] =
      new _Const[S](id, value)

    protected def mkVar[S <: Sys[S]](targets: Targets[S], vr: S#Var[_Ex[S]], connect: Boolean)(implicit tx: S#Tx): Var[S] = {
      val res = new _Var[S](targets, vr)
      if (connect) res.connect()
      res
    }

    private final class _Const[S <: Sys[S]](val id: S#Id, val constValue: A)
      extends ConstImpl[S] with Repr[S]

    private final class _Var[S <: Sys[S]](val targets: Targets[S], val ref: S#Var[_Ex[S]])
      extends VarImpl[S] with Repr[S]
  }
  trait Obj[S <: Sys[S]] extends expr.Expr[S, Code]

  type T[I, O] = Code { type In = I; type Out = O }
}
trait Code extends Writable { me =>
  type Self = Code.T[In, Out]

  /** The interfacing input type */
  type In
  /** The interfacing output type */
  type Out

  def tpe: Code.Type

  /** Source code. */
  def source: String

  /** Creates a new code object with updated source code. */
  def updateSource(newText: String): Self

  /** Generic source code prelude wrapping code,
    * containing package, class or object.
    * Should generally end in a newline.
    *
    * Must not include `Code.packagePrelude`.
    * Must not include imports as retrieved by `Code.importsPrelude`.
    */
  def prelude: String

  /** Source code postlude wrapping code,
    * containing for example closing braces.
    * Should generally begin and end in a newline.
    */
  def postlude: String

  /** Compiles the code body without executing it. */
  def compileBody()(implicit compiler: Code.Compiler): Future[Unit]

  /** Compiles and executes the code. Returns the wrapped result. */
  def execute(in: In)(implicit compiler: Code.Compiler): Out // = compile()(in)

  def write(out: DataOutput): Unit = Code.serializer.write(this, out)
}