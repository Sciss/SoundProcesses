/*
 *  Sys.scala
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

package de.sciss.lucre.expr.graph

import de.sciss.lucre.expr.ExElem.{ProductReader, RefMapIn}
import de.sciss.lucre.expr.graph.impl.MappedIExpr
import de.sciss.lucre.expr.{Context, Graph, IAction}
import de.sciss.lucre.synth.AnyTxn
import de.sciss.lucre.{IExpr, ITargets, Txn, synth, Artifact => _Artifact}
import de.sciss.proc
import de.sciss.proc.Universe

import java.net.URI

/** Access to operating system functions. */
object Sys extends SysPlatform {
  /** A shell process. */
  object Process extends ProductReader[Process] {
    /** Creates a new shell process for a given command and arguments.
     * To run the process, use the `run` action. Observe the termination
     * through `done` or `failed`.
     */
    def apply(cmd: Ex[String], args: Ex[Seq[String]] = Nil): Process = Impl(cmd, args)

    override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Process = {
      require (arity == 2 && adj == 0)
      val _cmd  = in.readEx[String]()
      val _args = in.readEx[Seq[String]]()
      Process(_cmd, _args)
    }

    private final val keyDirectory = "directory"

    object Directory extends ProductReader[Directory] {
      override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Directory = {
        require (arity == 1 && adj == 0)
        val _p = in.readProductT[Process]()
        new Directory(_p)
      }
    }
    final case class Directory(p: Process) extends Ex[URI] {
      type Repr[T <: Txn[T]] = IExpr[T, URI]

      override def productPrefix: String = s"Sys$$Process$$Directory" // serialization

      protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
        val valueOpt = ctx.getProperty[Ex[URI]](p, keyDirectory)
        valueOpt.fold(Const(_Artifact.Value.empty).expand[T])(_.expand[T])
      }
    }

    object Output extends ProductReader[Output] {
      override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Output = {
        require (arity == 1 && adj == 0)
        val _p = in.readProductT[Process]()
        new Output(_p)
      }
    }
    final case class Output(p: Process) extends Ex[String] {
      type Repr[T <: Txn[T]] = IExpr[T, String]

      override def productPrefix: String = s"Sys$$Process$$Output" // serialization

      protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
        val px = p.expand[T]
        px.output
      }
    }

    private final case class Impl(cmd: Ex[String], args: Ex[Seq[String]]) extends Process {

      override def productPrefix: String = s"Sys$$Process" // serialization

      type Repr[T <: Txn[T]] = Peer[T]

      def directory: Ex[URI] = Process.Directory(this)

      def directory_=(value: Ex[URI]): Unit = {
        val b = Graph.builder
        b.putProperty(this, keyDirectory, value)
      }

      def output: Ex[String] = Process.Output(this)

      protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] =
        tx match {
          case stx: synth.Txn[_] =>
            // ugly...
            val tup = (ctx, stx).asInstanceOf[(Context[AnyTxn], AnyTxn)]
            mkControlImpl(tup).asInstanceOf[Repr[T]]

          case _ => throw new Exception("Need a SoundProcesses system")
        }

      private def mkControlImpl[T <: synth.Txn[T]](tup: (Context[T], T)): Repr[T] = {
        implicit val ctx: Context[T]  = tup._1
        implicit val tx : T           = tup._2
        import ctx.{cursor, workspace}
        implicit val h  : Universe[T] = Universe()
        val dirOpt = ctx.getProperty[Ex[URI]](this, keyDirectory).map(_.expand[T])
        new ExpandedProcess[T](cmd.expand[T], args.expand[T], dirOpt)(h, ctx.targets)
      }
    }

    trait Peer[T <: Txn[T]] extends proc.Runner[T] {
      def output: IExpr[T, String]
    }
  }

  /** A shell process. */
  trait Process extends Runner {
    type Repr[T <: Txn[T]] <: Process.Peer[T]

    /** The process' current working directory. */
    var directory: Ex[URI]

    def output: Ex[String]

    // var environment: Ex[Map[String, String]]
  }

  object Exit extends ProductReader[Exit] {
    override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Exit = {
      require (arity == 1 && adj == 0)
      val _code = in.readEx[Int]()
      new Exit(_code)
    }
  }
  final case class Exit(code: Ex[Int] = 0) extends Act {
    override def productPrefix: String = s"Sys$$Exit" // serialization

    type Repr[T <: Txn[T]] = IAction[T]

    protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] =
      new ExpandedExit[T](code.expand[T])
  }

  private final class ExpandedProperty[T <: Txn[T]](key: IExpr[T, String], tx0: T)
                                                   (implicit targets: ITargets[T])
    extends MappedIExpr[T, String, Option[String]](key, tx0) {

    protected def mapValue(inValue: String)(implicit tx: T): Option[String] =
      sys.props.get(inValue)
  }

  /** A system property. */
  object Property extends ProductReader[Property] {
    override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Property = {
      require (arity == 1 && adj == 0)
      val _key = in.readEx[String]()
      new Property(_key)
    }
  }
  final case class Property(key: Ex[String]) extends Ex[Option[String]] {
    override def productPrefix: String = s"Sys$$Property" // serialization

    type Repr[T <: Txn[T]] = IExpr[T, Option[String]]

    protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
      import ctx.targets
      new ExpandedProperty[T](key.expand[T], tx)
    }
  }

  private final class ExpandedEnv[T <: Txn[T]](key: IExpr[T, String], tx0: T)
                                                   (implicit targets: ITargets[T])
    extends MappedIExpr[T, String, Option[String]](key, tx0) {

    protected def mapValue(inValue: String)(implicit tx: T): Option[String] =
      sys.env.get(inValue)
  }

  /** An environment variable. */
  object Env extends ProductReader[Env] {
    override def read(in: RefMapIn, key: String, arity: Int, adj: Int): Env = {
      require (arity == 1 && adj == 0)
      val _key = in.readEx[String]()
      new Env(_key)
    }
  }
  final case class Env(key: Ex[String]) extends Ex[Option[String]] {
    override def productPrefix: String = s"Sys$$Env" // serialization

    type Repr[T <: Txn[T]] = IExpr[T, Option[String]]

    protected def mkRepr[T <: Txn[T]](implicit ctx: Context[T], tx: T): Repr[T] = {
      import ctx.targets
      new ExpandedEnv[T](key.expand[T], tx)
    }
  }
}
