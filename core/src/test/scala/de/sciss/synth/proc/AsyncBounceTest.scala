package de.sciss.synth.proc

import de.sciss.file._
import de.sciss.lucre.artifact.{Artifact, ArtifactLocation}
import de.sciss.lucre.expr
import de.sciss.lucre.synth.InMemory
import de.sciss.synth.io.{AudioFile, AudioFileSpec}
import de.sciss.synth.{freeSelf, ugen, SynthGraph}
import de.sciss.span.Span
import scala.concurrent.ExecutionContext
import de.sciss.processor.Processor
import de.sciss.lucre.stm.store.BerkeleyDB

object AsyncBounceTest {
  // type S = InMemory
  type S = Durable
  type I = S#I

  def main(args: Array[String]): Unit = run()

  def run(): Unit = {
    // implicit val system = InMemory()
    implicit val system = Durable(BerkeleyDB.tmp())

    de.sciss.lucre.synth.showLog = true
    showTransportLog  = true

    def frame(secs: Double): Long = (secs * Timeline.SampleRate).toLong

    println(
      """Expected outcome:
        |
        |XXX TODO
        |""".stripMargin)

    val numFr   = (UGenGraphBuilder.Input.Buffer.AsyncThreshold * 1.5).toInt
    val sr      = 44100.0
    val dur     = numFr.toDouble / sr

    val groupH = system.step { implicit tx =>
      // val expr      = ExprImplicits[S]
      // import ExprImplicits._

      val proc      = Proc[S]
      val peer      = proc // Proc.Elem(proc)
      val obj       = peer // Obj(peer)
      proc.graph() = SynthGraph {
        import ugen._
        val b   = graph.Buffer("foo")
        val sig = PlayBuf.ar(1, b, 1, doneAction = freeSelf)
        Out.ar(0, sig)
      }
      val tmpDir  = File.createTemp("artifacts", deleteOnExit = true, directory = true)
      val tmpF    = tmpDir / "buffer.aif"
      tmpF.deleteOnExit()
      val loc     = ArtifactLocation.newConst[S](tmpDir)
      val artif   = Artifact(loc, tmpF) // .add(tmpF)
      val aSpec   = AudioFileSpec(numChannels = 1, numFrames = numFr, sampleRate = sr)
      val af      = AudioFile.openWrite(tmpF, aSpec)
      val aBuf    = Array(Array.tabulate(numFr) { i =>
        val slow = (i.toFloat *  10 / numFr) % 1.0f
        val fast = (i.toFloat * 100 / numFr) % 1.0f * 2 - 1
        slow * fast
      })
      af.write(aBuf)
      af.close()
      val gr      = Grapheme.Expr.Audio[S](artif, aSpec, 0L, 1.0)
      obj.attr.put("foo", gr)

      val group     = Timeline[S]
      // XXX TODO -- not yet supported: asynchronous objects that begin after the transport position
      // group.add(Span(frame(0.2), frame(0.2 + dur * 0.5)), obj)
      group.add(Span(frame(0.0), frame(0.0 + dur * 0.5)), obj)
      // import ProcGroup.serializer
      tx.newHandle(group)
    }

    import WorkspaceHandle.Implicits._
    val bounce              = Bounce[S, I]
    val bCfg                = Bounce.Config[S]
    bCfg.group              = groupH :: Nil
    bCfg.span               = Span(frame(0.0), frame(dur * 0.5 + 0.4))
    val sCfg                = bCfg.server
    //sCfg.nrtCommandPath = "/Users/hhrutz/Desktop/test.osc"
    sCfg.nrtOutputPath      = File.createTemp("bounce", ".aif", deleteOnExit = false).path
    //sCfg.programPath    = "/Applications/SuperCollider_3.6.5/SuperCollider.app/Contents/Resources/scsynth"

    println(s"Output path:\n${sCfg.nrtOutputPath}")

    // this is default now:
    // sCfg.inputBusChannels   = 0
    sCfg.outputBusChannels  = 1
    sCfg.sampleRate         = sr.toInt

    // this is default now:
    // sCfg.blockSize          = 1       // sample accurate placement of synths

    val process             = bounce(bCfg)
    import ExecutionContext.Implicits.global

    val t = new Thread {
      override def run(): Unit = {
        this.synchronized(this.wait())
        sys.exit(0)
      }
    }
    t.start()

    var lastProg = 0
    process.addListener {
      case prog @ Processor.Progress(_, _) =>
        val p = prog.toInt
        while (lastProg < p) {
          print('#')
          lastProg += 2
        }

      case Processor.Result(_, res) =>
        println(s" $lastProg%")
        println(res)
        t.synchronized(t.notifyAll())
    }
    process.start()
  }
}
