/*
 *  BounceImpl.scala
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

import java.io.{File, RandomAccessFile}
import java.nio.ByteBuffer

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.synth.{Buffer, Server, Synth, Sys, Txn}
import de.sciss.processor.Processor
import de.sciss.processor.impl.ProcessorImpl
import de.sciss.synth.io.{AudioFile, AudioFileType, SampleFormat}
import de.sciss.synth.{Server => SServer, addAfter, SynthGraph, addToTail}
import de.sciss.{osc, synth}

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise, blocking}
import scala.util.Success
import scala.util.control.NonFatal

object BounceImpl {
  var DEBUG = false
}
final class BounceImpl[S <: Sys[S], I <: stm.Sys[I]](implicit cursor: stm.Cursor[S], bridge: S#Tx => I#Tx,
                                                     workspace: WorkspaceHandle[S])
  extends Bounce[S] {

  import BounceImpl.DEBUG

  protected def prepare(config: Config): Prepared = {
    if (config.server.sampleRate <= 0)
      throw new IllegalArgumentException("The sample-rate of the server configuration must be explicitly specified")

    new Impl(config)
  }

  private final class Impl(config: Config) extends ProcessorImpl[Product, Processor[File]]
    with Processor[File] {

    import config.realtime

    private val needsOSCFile  = !realtime && config.server.nrtCommandPath.isEmpty  // we need to generate that file
    private val numChannels   = config.server.outputBusChannels
    private val hasOutputs    = numChannels != 0
    private val needsDummyOut = !realtime && !hasOutputs  // scsynth doesn't allow this. must have 1 dummy channel
    private val needsOutFile  = config.server.nrtOutputPath.isEmpty && hasOutputs // we need to generate

    private var oscFile     : File = null
    private var outFile     : File = null
    private var aural: AuralSystem = null

    private def resultFile = if (needsOutFile) outFile else new File(config.server.nrtOutputPath)

    override protected def cleanUp(): Unit = {
      if (needsOSCFile  && oscFile != null) oscFile.delete()
      if (needsDummyOut && outFile != null) outFile.delete()
      if (aural != null) cursor.step { implicit tx => aural.stop() }
    }

    protected def body(): File = {
      // ---- configuration ----

      // the server config (either directly from input, or updated according to the necessary changes)
      val sCfg = if (needsOSCFile || needsDummyOut || needsOutFile) {
        val b = Server.ConfigBuilder(config.server)
        if (needsOSCFile) {
          val f = File.createTempFile("bounce", ".osc")
          b.nrtCommandPath = f.getCanonicalPath
        }
        if (needsDummyOut) {
          b.nrtHeaderFormat   = AudioFileType.AIFF
          b.nrtSampleFormat   = SampleFormat.Int16
          b.outputBusChannels = 1
        }
        if (needsDummyOut || needsOutFile) {
          outFile = File.createTempFile("bounce", s".${b.nrtHeaderFormat.extension}")
          b.nrtOutputPath = outFile.getCanonicalPath
        }
        b.build
      } else {
        config.server
      }

      if (realtime)
        bodyRealtime(sCfg)
      else
        bodyOffline (sCfg)

      resultFile
    }

    // synchronize via `this`
    private val promiseSync = new AnyRef
    private var promise     = Option.empty[Promise[_]]

    override protected def notifyAborted(): Unit = promiseSync.synchronized {
      promise.foreach(_.tryFailure(Processor.Aborted()))
    }

    private def bodyRealtime(sCfg: Server.Config): Unit = {
      val pServer = Promise[Server]()
      promiseSync.synchronized(promise = Some(pServer))
      val (span, scheduler, transport, __aural) = cursor.step { implicit tx =>
        val _scheduler  = Scheduler[S]
        val _span       = config.span

        val _aural = AuralSystem()
        _aural.addClient(new AuralSystem.Client {
          def auralStarted(s: Server)(implicit tx: Txn): Unit = {
            // config.init.apply(...)
            tx.afterCommit {
              if (DEBUG) s.peer.dumpOSC()
              pServer.trySuccess(s)
            }
          }

          def auralStopped()(implicit tx: Txn): Unit = () // XXX TODO
        })

        val _transport = Transport(_aural, _scheduler)
        config.group.foreach { h =>
          _transport.addObject(h())
        }
        _transport.seek(_span.start)
        if (DEBUG) println(sCfg)
        _aural.start(config = sCfg)
        (_span, _scheduler, _transport, _aural)
      }

      aural = __aural

      val server = Await.result(pServer.future, Duration.Inf)

      if (config.beforePrepare != Bounce.Config.NoOp) cursor.step { implicit tx =>
        config.beforePrepare.apply(tx, server)
      }

      prepare(transport)(state => state == AuralObj.Prepared || state == AuralObj.Stopped)

      lazy val scheduleProgress: (S#Tx) => Unit = { implicit tx: S#Tx =>
        val now = scheduler.time
        if (!isCompleted) {
          if (now < span.stop ) {
            val time = math.min(span.stop, now + (Timeline.SampleRate * 0.1).toLong)
            scheduler.schedule(time)(scheduleProgress)
          }
          tx.afterCommit {
            progress = (now - span.start).toDouble / span.length
          }
        }
      }

      // println("-----------------------------1")

      val p = Promise[Unit]()
      promiseSync.synchronized(promise = Some(p))
      /* val _token = */ cursor.step { implicit tx =>
        // println("-----------------------------2")
        config.beforePlay.apply(tx, server)

        val graph = SynthGraph {
          import synth._
          import ugen._
          val sig     = In.ar(0, numChannels)
          /* val frames  = */ DiskOut.ar("$bnc_disk".ir, sig)
          // sig   .poll(HPZ1.ar(sig).abs, "sig-in")
          // frames.poll(5, "disk-frame")
          val silent = Vector.fill(numChannels)(0)
          ReplaceOut.ar(0, silent)
        }
        //        val gMute = SynthGraph {
        //          import synth._
        //          import ugen._
        //        }
        val buf = Buffer.diskOut(server)(
          path          = resultFile.path,
          fileType      = config.server.nrtHeaderFormat,
          sampleFormat  = config.server.nrtSampleFormat,
          numChannels   = numChannels
        )
        //  (List[ControlSet]("$bnc_disk" -> buf.id), List[Resource](buf))
        val synRec = Synth.play(graph, nameHint = Some("diskout"))(server.defaultGroup, addAction = addToTail,
          args = List("$bnc_disk" -> buf.id), dependencies = buf :: Nil)
        // val synMute = Synth.play(gMute, nameHint = Some("mute"))(synRec, addAction = addAfter)

        transport.play()
        scheduler.schedule(scheduler.time + span.length) { implicit tx =>
          transport.stop()
          synRec  .free()
          // synMute .free()
          buf.dispose()
          tx.afterCommit {
            val syncMsg = server.peer.syncMsg()
            val SyncId  = syncMsg.id
            val futSync = server.peer.!!(syncMsg) {
              case de.sciss.synth.message.Synced(SyncId) =>
            }
            p.tryCompleteWith(futSync)
            // p.tryComplete(Success(()))
          }
        }
        scheduleProgress.apply(tx)
      }

      Await.result(p.future, Duration.Inf)

      cursor.step { implicit tx =>
        transport.dispose()
      }
    }

    private def bodyOffline(sCfg: Server.Config): Unit = {
      // ---- run transport and gather OSC ----

      val server = Server.offline(sCfg)

      val (span, scheduler, transport, __aural) = cursor.step { implicit tx =>
        val _scheduler  = Scheduler.offline[S]
        val _span       = config.span

        val _aural = AuralSystem.offline(server)
        config.beforePrepare.apply(tx, server)
        val _transport = Transport(_aural, _scheduler)
        config.group.foreach { h =>
          _transport.addObject(h())
        }
        _transport.seek(_span.start)
        _transport.play()
        (_span, _scheduler, _transport, _aural)
      }
      aural = __aural

      val srRatio = server.sampleRate / Timeline.SampleRate

      prepare(transport)(state => state == AuralObj.Playing | state == AuralObj.Stopped)

      if (config.beforePlay != Bounce.Config.NoOp) cursor.step { implicit tx =>
        config.beforePlay.apply(tx, server)
      }

      def waitForServer(): Unit = {
        val p = Promise[Unit]()
        promiseSync.synchronized {
          promise = Some(p)
          p.completeWith(server.committed())
        }
        Await.result(p.future, Duration.Inf)
      }

      @tailrec def loop(): Unit = {
        waitForServer()
        val keepPlaying = blocking {
          cursor.step { implicit tx =>
            scheduler.stepTarget match {
              case Some(pos) if pos <= span.length =>
                logTransport(s"stepTarget = $pos")
                server.position = (pos * srRatio + 0.5).toLong
                scheduler.step()
                true

              case _ =>
                if (transport.position < span.stop) {
                  server.position = (span.length * srRatio + 0.5).toLong
                  server !! osc.Bundle.now() // dummy bundle to terminate the OSC file at the right position
                }
                false
            }
          }
        }
        if (keepPlaying) loop()
      }

      loop()
      waitForServer()
      val bundles = server.bundles()

      if (showTransportLog) {
        logTransport("---- BOUNCE: bundles ----")
        bundles.foreach(b => logTransport(b.toString))
      }

      // ---- write OSC file ----

      oscFile = new File(sCfg.nrtCommandPath)
      if (oscFile.exists()) require(oscFile.delete(), s"Could not delete existing OSC file $oscFile")

      // XXX TODO: this should be factored out, probably go into ScalaOSC or ScalaCollider
      blocking {
        val c   = Server.codec
        val sz  = 8192 // bundles.map(_.encodedSize(c)).max
        // logTransport(s"Max bundle size is $sz")
        val raf = new RandomAccessFile(oscFile, "rw")
        try {
          val bb = ByteBuffer.allocate(sz)
          val fch = raf.getChannel
          bundles.foreach { bndl =>
            bndl.encode(c, bb)
            bb.flip()
            raf.writeInt(bb.limit)
            fch.write(bb)
            bb.clear()
          }
        } finally {
          raf.close()
        }
      }

      // ---- run scsynth ----

      val dur = span.length / Timeline.SampleRate

      logTransport("---- BOUNCE: scsynth ----")

      val nrtFut = SServer.renderNRT(dur = dur, config = sCfg)
      nrtFut.start()
      val nrtRes = await(nrtFut)
      if (nrtRes != 0) throw Bounce.ServerFailed(nrtRes)

      cursor.step { implicit tx =>
        transport.dispose()
      }
      // scheduler.dispose()
    }

    private def prepare(transport: Transport[S])(isReady: AuralObj.State => Boolean): Unit = {
      // Tricky business: While handling prepared state is not yet
      // fully solved, especially with collection objects such as
      // Timeline, we at least provide some bounce support for objects
      // that require asynchronous preparation. To do that, we gather
      // all views with state `Preparing` and wait for them to go into
      // either `Playing` or `Stopped`. We go deeply into timelines as
      // well. Finally, we bundle all these futures together and wait
      // for their completion. Then we should be fine advancing the
      // logical clock.
      //
      // This does not work with objects on a timeline that do not
      // overlap with the transport's starting position!
      val prepFutures = cursor.step { implicit tx =>
        // println(s"States = ${transport.views.map(_.state)}")

        def gather(views: Set[AuralObj[S]]): Set[Future[Unit]] = {
          //          views.foreach { obj =>
          //            if (obj.state != AuralObj.Preparing) println(s"- - - - $obj: ${obj.state}")
          //          }
          val set1 = views.collect {
            case obj if obj.state == AuralObj.Preparing =>
              val p = Promise[Unit]()
              obj.react { implicit tx => state => if (isReady(state)) {
                tx.afterCommit(p.tryComplete(Success(())))
              }}
              p.future
          }
          val set2 = views.flatMap {
            case atl: AuralObj.Timeline[S] =>
              val children = atl.views
              // println(s"For timeline: $children")
              gather(children)
            case _ => Set.empty[Future[Unit]]
          }

          set1 ++ set2
        }

        gather(transport.views)
      }
      if (prepFutures.nonEmpty) {
        logTransport(s"waiting for ${prepFutures.size} preparations to complete...")
        val p = Promise[Any]()
        promiseSync.synchronized {
          promise = Some(p)
          p.completeWith(Future.sequence(prepFutures))
        }
        Await.result(p.future, Duration.Inf)
        logTransport("...preparations completed")
      }
    }
  }
}