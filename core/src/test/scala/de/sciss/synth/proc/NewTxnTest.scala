package de.sciss.synth.proc

import de.sciss.synth
import synth.SynthGraph
import de.sciss.lucre.synth.{InMemory, Synth, Server}

object NewTxnTest extends App {
  val sys = InMemory()
  synth.Server.run() { srv =>
    val s = Server(srv)
    srv.dumpOSC()
    //    sys.step { implicit tx =>
    //      NodeGraph.addServer(s)
    //    }

    val x = sys.step { implicit tx =>
      val g = SynthGraph {
        import synth._
        import ugen._
        //            import Ops._
        Out.ar(0, SinOsc.ar(SinOsc.kr(0.1).linexp(-1, 1, 300, 600)) * 0.1)
      }
      Synth.play(g)(s.defaultGroup)
    }

    println("Aqui. " + x)
  }
}