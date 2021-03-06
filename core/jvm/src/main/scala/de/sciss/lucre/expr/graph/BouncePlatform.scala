/*
 *  BouncePlatform.scala
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

import de.sciss.audiofile.AudioFileSpec
import de.sciss.equal.Implicits._
import de.sciss.lucre.Txn.{peer => _peer}
import de.sciss.lucre.expr.graph.Bounce.applyAudioPreferences
import de.sciss.lucre.impl.ObservableImpl
import de.sciss.lucre.{IExpr, synth, Obj => LObj}
import de.sciss.numbers.Implicits._
import de.sciss.proc
import de.sciss.proc.impl.BasicRunnerImpl
import de.sciss.proc.{SoundProcesses, Universe}
import de.sciss.processor.Processor
import de.sciss.span.Span

import java.awt.EventQueue
import java.io.File
import java.net.URI
import scala.concurrent.ExecutionContext
import scala.concurrent.stm.Ref
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

trait BouncePlatform {
  protected final class PeerImpl[T <: synth.Txn[T]](obj : IExpr[T, Seq[Obj]],
                                                  out : IExpr[T, URI],
                                                  spec: IExpr[T, AudioFileSpec],
                                                  span: IExpr[T, Span],
                                                 )
                                                 (implicit val universe: Universe[T]) extends BasicRunnerImpl[T] {

    private[this] val procRef = Ref(Option.empty[Processor[File]])
    private[this] val procRun = Ref(0)

    object progress extends proc.Runner.Progress[T] with ObservableImpl[T, Double] {
      def current(implicit tx: T): Double =
        procRef().fold(0.0)(_.progress)

      def current_=(value: Double)(implicit tx: T): Unit = fire(value)
    }

    def prepare(attr: proc.Runner.Attr[T])(implicit tx: T): Unit = ()  // XXX TODO currently attr unused

    private def disposeProc()(implicit tx: T): Unit = {
      procRef.swap(None).foreach { p =>
        procRun.transform(_ + 1)
        tx.afterCommit {
          p.abort()
        }
      }
    }

    def run()(implicit tx: T): Unit = {
      disposeProc()

      val cfg                 = proc.Bounce.Config[T]()
      val bnc                 = proc.Bounce[T]()
      val specV               = spec.value
      val objV                = obj.value
      val objH                = objV.iterator.flatMap(_.peer[T].map(tx.newHandle(_: LObj[T]))).toList
      cfg.group               = objH
      // Application.applyAudioPreferences(cfg.server, cfg.client, useDevice = false, pickPort = false)
      //      applyAudioPreferences(cfg.server, cfg.client)
      val sCfg                = cfg.server
      sCfg.nrtOutputPath      = out.value.getPath // audioF.path
      sCfg.nrtHeaderFormat    = specV.fileType
      sCfg.nrtSampleFormat    = specV.sampleFormat
      val numPrivate          = 256 // Prefs.audioNumPrivate.getOrElse(Prefs.defaultAudioNumPrivate)
      //      sCfg.sampleRate         = specV.sampleRate.toInt // sampleRate
      cfg.span                = span.value
      val procId              = procRun.transformAndGet(_ + 1)

      def atomic(name: String)(body: T => Unit): Unit =
        SoundProcesses.step[T](name) { implicit tx =>
          val _procRun = procRun()
          // println(s"step($name); ${_procRun} vs $procId")
          if (_procRun === procId) body(tx)
        }

      state = proc.Runner.Preparing

      tx.afterCommit {
        // XXX TODO ugly; we must access preferences on the EDT
        def start(): Unit = {
          try {
            // N.B.: several things are overwritten in `applyAudioPreferences`,
            // therefore be sure to set `sampleRate` etc. after this call
            applyAudioPreferences(cfg.server, cfg.client)
            //            import SoundProcesses.executionContext
            sCfg.inputBusChannels   = 0
            sCfg.outputBusChannels  = 1
            sCfg.audioBusChannels   = (sCfg.outputBusChannels + numPrivate).nextPowerOfTwo
            sCfg.wireBuffers        = math.max(sCfg.wireBuffers, 1024) // possibly higher than default
            sCfg.sampleRate = specV.sampleRate.toInt // sampleRate
            val p           = bnc(cfg.build)
            p.addListener {
              case Processor.Result(_, tr) =>
                atomic("bounce.result") { implicit tx =>
                  procRef() = None
                  state = tr match {
                    case Success(_)   => proc.Runner.Done
                    case Failure(ex)  => proc.Runner.Failed(ex)
                  }
                }

              case Processor.Progress(_, amt) =>
                atomic("bounce.progress") { implicit tx =>
                  progress.current_=(amt)
                }
            }
            p.start()(ExecutionContext.global)  // N.B.: we'll starve if we use the single-threaded SP default here!
            // println("---1")
            atomic("bounce.running") { implicit tx =>
              // println("---2")
              procRef() = Some(p)
              state = proc.Runner.Running
            }
            // println("---3")
          } catch {
            case NonFatal(ex) =>
              // println("---4")
              atomic("bounce.failed") { implicit tx =>
                state = proc.Runner.Failed(ex)
              }
          }
        }

        if (EventQueue.isDispatchThread) start() else
          EventQueue.invokeLater { () =>
            start()
            // println("---7")
          }
      }
    }

    def stop()(implicit tx: T): Unit = {
      disposeProc()
      state = proc.Runner.Stopped
    }

    protected def disposeData()(implicit tx: T): Unit =
      disposeProc()
  }
}
