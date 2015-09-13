/*
 *  CompilerImpl.scala
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

package de.sciss.synth.proc
package impl

import java.io.{BufferedInputStream, FileOutputStream, BufferedOutputStream, File}
import java.util.concurrent.Executors

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.tools.nsc
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.{ConsoleWriter, NewLinePrintWriter}
import scala.tools.nsc.interpreter.{Results, IMain}

object CompilerImpl {
  def apply(): Code.Compiler = new Impl({
    val cSet = new nsc.Settings()
    cSet.classpath.value += File.pathSeparator + sys.props("java.class.path")
    val c = new IMainImpl(cSet)
    c.initializeSynchronous()
    c
  })

  private final class Impl(intp0: => IMainImpl) extends Code.Compiler {
    private lazy val intp = intp0

    override def toString = s"Compiler@${hashCode().toHexString}"

    // note: the Scala compiler is _not_ reentrant!!
    implicit val executionContext: ExecutionContextExecutor =
      ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

    def compile(source: String): Array[Byte] = {
      val compiler = intp.global  // we re-use the intp compiler -- no problem, right?
      intp.reset()
      compiler.reporter.reset()
      val f     = File.createTempFile("temp", ".scala")
      val out   = new BufferedOutputStream(new FileOutputStream(f))
      out.write(source.getBytes("UTF-8"))
      out.flush(); out.close()
      val run   = new compiler.Run()
      run.compile(List(f.getPath))
      f.delete()

      if (compiler.reporter.hasErrors) throw new Code.CompilationFailed()

      // NB -- we could eventually use Scala-version specific sources in sbt
      val d0    = intp.virtualDirectory // method deprecated in Scala 2.11, but necessary for Scala 2.10
      // intp.replOutput.dir

      packJar(d0)
    }

    // cf. http://stackoverflow.com/questions/1281229/how-to-use-jaroutputstream-to-create-a-jar-file
    private def packJar(base: AbstractFile): Array[Byte] = {
      import java.util.jar._

      val mf = new Manifest
      mf.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
      val bs    = new java.io.ByteArrayOutputStream
      val out   = new JarOutputStream(bs, mf)

      def add(prefix: String, f: AbstractFile): Unit = {
        val name0 = prefix + f.name // f.getName
        val name  = if (f.isDirectory) name0 + "/" else name0
        val entry = new JarEntry(name)
        entry.setTime(f.lastModified /* () */)
        // if (f.isFile) entry.setSize(f.length())
        out.putNextEntry(entry)
        if (!f.isDirectory /* f.isFile */) {
          val in = new BufferedInputStream(f.input /* new FileInputStream(f) */)
          try {
            val buf = new Array[Byte](1024)
            @tailrec def loop(): Unit = {
              val count = in.read(buf)
              if (count >= 0) {
                out.write(buf, 0, count)
                loop()
              }
            }
            loop()
          } finally {
            in.close()
          }
        }
        out.closeEntry()
        if (f.isDirectory) f /* .listFiles */ .foreach(add(name, _))
      }

      base /* .listFiles() */.foreach(add("", _))
      out.close()
      bs.toByteArray
    }

    def interpret(source: String, execute: Boolean): Any = {
      intp.reset()
      val th  = Thread.currentThread()
      val cl  = th.getContextClassLoader
      // work-around for SI-8521 (Scala 2.11.0)
      val res = try {
        intp.interpret(source)
      } finally {
        th.setContextClassLoader(cl)
      }

      // commented out to chase ClassNotFoundException
      // i.reset()
      res match {
        case Results.Success =>
          if (/* aTpe == "Unit" || */ !execute) () else {
            val n = intp.mostRecentVar
            intp.valueOfTerm(n).getOrElse(sys.error(s"No value for term $n"))
          }

        case Results.Error      => throw Code.CompilationFailed()
        case Results.Incomplete => throw Code.CodeIncomplete()
      }
    }
  }

  private final class IMainImpl(cSet: nsc.Settings)
    extends IMain(cSet, new NewLinePrintWriter(new ConsoleWriter, autoFlush = true)) {

    override protected def parentClassLoader = CompilerImpl.getClass.getClassLoader
  }
}
