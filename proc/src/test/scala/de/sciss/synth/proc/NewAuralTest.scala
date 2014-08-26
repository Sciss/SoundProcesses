package de.sciss.synth.proc

import de.sciss.lucre.{bitemp, stm}
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.{Sys, Server}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth
import de.sciss.synth.Curve.{exponential, linear}
import de.sciss.synth.io.AudioFile

import scala.concurrent.stm.Txn
import scala.language.implicitConversions

object NewAuralTest extends App {
  val confluent = true   // currently test4 has a problem with event-variables in confluent

  val name = args.headOption.getOrElse("?")

  if (confluent) {
    type S  = Confluent
    type I  = S#I
    val sys = Confluent(BerkeleyDB.tmp())
    val (_, cursor) = sys.cursorRoot(_ => ())(implicit tx => _ => sys.newCursor())
    new NewAuralTest[S](name)(cursor)

  } else {
    type S  = Durable
    type I  = S#I
    val sys = Durable(BerkeleyDB.tmp())
    val cursor: stm.Cursor[S] = sys
    new NewAuralTest[S](name)(cursor)
  }
}
class NewAuralTest[S <: Sys[S]](name: String)(implicit cursor: stm.Cursor[S]) {
  showAuralLog      = true
  showTransportLog  = true
  // de.sciss.lucre.synth.showLog = true

  val as = AuralSystem()
  cursor.step { implicit tx =>
    as.whenStarted(s => initView(as, s))
    as.start()
  }

  def initView(as: AuralSystem, s: Server): Unit = {
    if (Txn.findCurrent.isDefined) {
      Console.err.println("Damn! I could swear there is no transaction.")
      throw new IllegalStateException()
    }

    s.peer.dumpOSC()
    implicit val context = cursor.step { implicit tx =>
      import WorkspaceHandle.Implicits._
      AuralContext[S](s)
    }
    //////////////////////////////////////////////////////////////////////////////////////
    name match {
      case "--test1"  => test1()
      case "--test2"  => test2()
      case "--test3"  => test3()
      case "--test4"  => test4()
      case "--test5"  => test5()
      case "--test6"  => test6(as)
      case "--test7"  => test7()
      case "--test8"  => test8()
      case "--test9"  => test9()
      case "--test10" => test10()
      case "--test11" => test11()
      case _         =>
        println("WARNING: No option given, using --test10")
        test10()
    }
  }

  def after(secs: Double)(code: S#Tx => Unit): Unit = {
    val t = new Thread {
      override def run(): Unit = {
        Thread.sleep((secs * 1000).toLong)
        cursor.step { implicit tx =>
          code(tx)
        }
      }
    }
    Txn.findCurrent.fold(t.start()) { implicit tx =>
      Txn.afterCommit(_ => t.start())
    }
  }

  def quit()(implicit tx: S#Tx): Unit =
    tx.afterCommit {
      Thread.sleep(1000)  // have to wait a bit for scsynth to quit
      scala.sys.exit()
    }

  import synth._
  import ugen._

  def procV(graph: => Unit)(implicit tx: S#Tx, context: AuralContext[S]): AuralObj.Proc[S] = {
    val pObj  = proc(graph)
    val _view = AuralObj.Proc(pObj)
    _view
  }

  def proc(graph: => Unit)(implicit tx: S#Tx): Proc.Obj[S] = {
    val p = Proc[S]
    val g = SynthGraph {
      graph
    }
    p.graph() = SynthGraphs.newConst[S](g)
    Obj(Proc.Elem(p))
  }

  def timelineV()(implicit tx: S#Tx, context: AuralContext[S]): AuralObj.Timeline[S] = {
    val tlObj = timeline()
    val _view = AuralObj.Timeline(tlObj)
    _view
  }

  def timeline()(implicit tx: S#Tx): Timeline.Obj[S] = {
    val tl    = Timeline[S]
    Obj(Timeline.Elem(tl))
  }

  def frame(secs: Double): Long = (secs * Timeline.SampleRate).toLong

  def putDouble(proc: Proc.Obj[S], key: String, value: Double)(implicit tx: S#Tx): Unit = {
    val imp = ExprImplicits[S]
    import imp._
    proc.attr.put(key, Obj(DoubleElem(value)))
  }

  def stopAndQuit(delay: Double = 4.0): Unit =
    after(delay) { implicit tx =>
      as.stop()
      quit()
    }

  def addScan(proc: Proc.Obj[S], key: String)(implicit tx: S#Tx): Scan[S] = {
    proc.elem.peer.scans.add(key)
  }

  implicit class ScanOps(val `this`: Scan[S]) /* extends AnyVal */ {
    def ~> (that: Scan[S])(implicit tx: S#Tx): Unit =
      `this`.addSink(Scan.Link.Scan(that))
  }

  implicit def timeRange(in: (Double, Double)): Span = {
    val start = (in._1 * Timeline.SampleRate).toLong
    val stop  = (in._2 * Timeline.SampleRate).toLong
    Span(start, stop)
  }

  implicit class TimelineOps(tl: Timeline.Obj[S]) /* extends AnyVal */ {
    def += (span: SpanLike, obj: Obj[S])(implicit tx: S#Tx): Unit = {
      val tlm = tl.elem.peer.modifiableOption.get  // yo
      tlm.add(bitemp.SpanLike.newConst(span), obj)
    }

    def -= (span: SpanLike, obj: Obj[S])(implicit tx: S#Tx): Unit = {
      val tlm = tl.elem.peer.modifiableOption.get  // yo
      val res = tlm.remove(bitemp.SpanLike.newConst(span), obj)
      if (!res) Console.err.println(s"Warning: object $obj at $span not found in timeline")
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////

  ////////////////////////////////////////////////////////////////////////////////////// 11

  def test11()(implicit context: AuralContext[S]): Unit = {
    println("----test10----")
    println(
      """
        |Expected behaviour:
        |A buffer is loaded and played fast forward and backward.
        |
        |""".stripMargin)

    val imp     = ExprImplicits[S]
    import imp._

    val procAural = cursor.step { implicit tx =>
      import de.sciss.file._
      val f       = userHome / "Music" / "tapes" / "MetallScheibe5TestN.aif"
      val spec    = AudioFile.readSpec(f)
      println(spec)
      // val vAudio  = Grapheme.Value.Audio(f, spec, offset = 0L, gain = 2.0)

      val _proc = proc {
        val buf   = graph.Buffer("metal")
        val speed = LFPulse.ar(BufDur.kr(buf).reciprocal).linlin(0, 1, -4, 4)
        val sig0  = PlayBuf.ar(numChannels = spec.numChannels, buf = buf, speed = speed, loop = 1)
        val sig   = Pan2.ar(sig0)
        Out.ar(0, sig)
      }
      val loc     = ArtifactLocation[S](f.parent)
      val artif   = loc.add(f)
      val oAudio  = Grapheme.Expr.Audio(artif, spec, offset = 0L, gain = 2.0)

      _proc.attr.put("metal", Obj(AudioGraphemeElem(oAudio)))

      AuralObj(_proc)
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      procAural.play()

      after(20.0) { implicit tx =>
        procAural.stop()
        stopAndQuit(1.0)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 10

  def test10()(implicit context: AuralContext[S]): Unit = {
    println("----test10----")
    println(
      """
        |Expected behaviour:
        |A tape proc is fed through a global spatialization proc.
        |It is heard after 1s, beginning with the text "So I took a turn...".
        |The sound is panned rapidly between left and right stereo channel.
        |After 1s the sound is muted (on the tape), after another 1s unmuted,
        |then the same happens, but the mute is engaged with the global proc.
        |
        |""".stripMargin)

    val imp     = ExprImplicits[S]
    import imp._

    val (tr, proc1H, proc2H) = cursor.step { implicit tx =>
      val p     = Proc[S]
      val g     = SynthGraphs.tape[S]
      p.graph() = g
      val _proc1 = Obj(Proc.Elem(p))

      val sAudio = addScan(_proc1, "sig")
      import de.sciss.file._
      val f       = userHome / "Music" / "tapes" / "machinaecoelestis.aif"
      val spec    = AudioFile.readSpec(f)
      println(spec)
      val aOff    = ((5 * 60 + 14) * spec.sampleRate).toLong  // "So I took a turn..."
      val vAudio  = Grapheme.Value.Audio(f, spec, offset = aOff, gain = 2.0)
      val gAudio  = Grapheme[S](spec.numChannels)
      gAudio.add(0L -> vAudio) // ... çoit trop complexe ...
      sAudio.addSource(Scan.Link.Grapheme(gAudio))

      val _proc2 = proc {
        val in  = graph.ScanIn("in")
        val m   = graph.Attribute.kr("mute", 0)
        val sig0 = in * (1 - m)
        val pos  = LFTri.ar(4)
        val sig  = Balance2.ar(sig0 \ 0, sig0 \ 1, pos)
        Out.ar(0, sig)
      }

      addScan(_proc1, "out") ~> addScan(_proc2, "in")

      val _tl = timeline()
      _tl += (1.0 -> 8.0, _proc1)
      // _tl += (1.0 -> 6.0, _proc2)
      _tl += (Span.all, _proc2)
      val _tr = Transport(as)
      _tr.addObject(_tl)

      (_tr, tx.newHandle(_proc1), tx.newHandle(_proc2))
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      tr.play()

      after(2.0) { implicit tx =>
        println("--mute tape--")
        val p1 = proc1H()
        p1.attr.put(ObjKeys.attrMute, Obj(BooleanElem(true)))

        after(1.0) { implicit tx =>
          println("--unmute tape--")
          val p1 = proc1H()
          p1.attr.put(ObjKeys.attrMute, Obj(BooleanElem(false)))

          after(1.0) { implicit tx =>
            println("--mute main--")
            val p2 = proc2H()
            p2.attr.put(ObjKeys.attrMute, Obj(BooleanElem(true)))

            after(1.0) { implicit tx =>
              println("--unmute main--")
              val p2 = proc2H()
              p2.attr.put(ObjKeys.attrMute, Obj(BooleanElem(false)))

              after(2.0) { implicit tx =>
                tr.stop()
                stopAndQuit(1.0)
              }
            }
          }
        }
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 9

  def test9()(implicit context: AuralContext[S]): Unit = {
    println("----test9----")
    println(
      """
        |Expected behaviour:
        |After 2s, a pink noise is linearly faded in for 4s,
        |after a sustain phase of 1s, it is exponentially faded
        |out for 3s. Then transport jumps back into the proc,
        |beginning the fade-in halfway through from -6 dB to 0 dB
        |in 2s.
        |
        |""".stripMargin)

    val tr = cursor.step { implicit tx =>
      val _proc1 = proc {
        val noise = PinkNoise.ar
        val env   = graph.FadeInOut.ar("fadeIn", "fadeOut")
        val sig   = noise * env
        Out.ar(0, sig)
      }

      import de.sciss.synth._
      val imp = ExprImplicits[S]
      import imp._

      val fadeExprIn  = FadeSpec.Expr[S](frame(4.0), linear, 0.0)
      val fadeExprOut = FadeSpec.Expr[S](frame(3.0), exponential, -40.0.dbamp)
      _proc1.attr.put("fadeIn" , Obj(FadeSpec.Elem(fadeExprIn )))
      _proc1.attr.put("fadeOut", Obj(FadeSpec.Elem(fadeExprOut)))

      val _tl = timeline()
      _tl += (2.0 -> 10.0, _proc1)
      val _tr = Transport(as)
      _tr.addObject(_tl)
      _tr
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      tr.play()

      after(11.0) { implicit tx =>
        println("--issue seek--")
        tr.seek(frame(4.0))
        stopAndQuit(6.0)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 8

  def test8()(implicit context: AuralContext[S]): Unit = {
    println("----test8----")
    println(
      """
        |Expected behaviour:
        |Two procs are connected to each other and placed
        |on a timeline. The filter exists from the beginning,
        |but the generator only joins after 2s. Sound
        |should only be heard after 2s, The filter
        |synth is started immediately with zero input as observable
        |from the poll output (because the input's number of channels
        |is known). At 4s the generator is removed
        |again. The filter keeps playing but with zero
        |input.
        |
        |There is currently a glitch when n_mapan is undone,
        |resulting in a short burst of buffer repetition at 4s
        |before the filter input becomes actually zero.
        |
        |""".stripMargin)

    val tl = cursor.step { implicit tx =>
      val _proc1 = proc {
        val sig = graph.ScanIn("in")
        (sig \ 0).poll(1, "ping-1")
        Out.ar(0, sig)
      }

      val _proc2 = proc {
        val sig = PinkNoise.ar(Seq(0.5, 0.5))
        (sig \ 0).poll(1, "ping-2")
        graph.ScanOut("out", sig)
      }

      println("--mk timeline--")

      val _tl = timelineV()
      val tlObj = _tl.obj()
      tlObj += (0.0 -> 10.0, _proc1)
      // the problem occurs (occurred! now it's fixed) when we add the scan _before_ creating
      // adding _proc2. like here:
      println("--add scan--")
      addScan(_proc2, "out") ~> addScan(_proc1, "in")
      println("--add proc2--")
      tlObj += (2.0 ->  4.0, _proc2)
      println("--alright--")
      _tl
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      tl.play()

      after(6.0) { implicit tx =>
        tl.stop()
        stopAndQuit(2.0)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 7

  def test7()(implicit context: AuralContext[S]): Unit = {
    println("----test7----")
    println(
      """
        |Expected behaviour:
        |Two procs, a generator and filter, are connected,
        |however the filter's scan input refers to an non-existing
        |key, thus nothing is heard. After 2s, the filter's
        |graph function is exchanged for one that refers to the
        |correct scan key, making a pink noise filtered at 555 Hz
        |be heard.
        |
        |""".stripMargin)

    cursor.step { implicit tx =>
      val _view1 = procV {
        val in   = graph.ScanIn("foo")
        val sig  = Resonz.ar(in, 777, 0.1) * 10
        Out.ar(0, sig)
      }

      val _view2 = procV {
        graph.ScanOut("out", PinkNoise.ar(Seq(0.5, 0.5)))
      }

      val scanOut = addScan(_view2.obj(), "out")
      val scanBar = addScan(_view1.obj(), "bar")

      scanOut ~> scanBar

      println("--issue play--")
      _view1.play()
      _view2.play()

      after(2.0) { implicit tx =>
        println("--issue graph change--")
        val pObj = _view1.obj()
        val newGraph = SynthGraph {
          val in   = graph.ScanIn("bar")
          val sig  = Resonz.ar(in, 555, 0.1) * 10
          Out.ar(0, sig)
        }
        pObj.elem.peer.graph() = SynthGraphs.newConst[S](newGraph)

        stopAndQuit()
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 6

  def test6(as: AuralSystem): Unit = {
    println("----test6----")
    println(
      """
        |Expected behaviour:
        |A transport takes care both of a timeline and an individual
        |proc. A dust ugen is placed on the timeline at 10s for 3s, but the
        |transport is started at 8s, thus after 2s the dust is heard.
        |The individual proc is a brownian noise. After the dust
        |has stopped playing, the transport seeks back to 10s, playing
        |again the full 3s of the dust.
        |
        |""".stripMargin)

    val tr = cursor.step { implicit tx =>
      val _proc1 = proc {
        // val sig = GrayNoise.ar(Seq(0.25, 0.25))
        val sig = Dust.ar(1000) * 0.66667
        Out.ar(0, sig)
      }

      val _proc2 = proc {
        val sig = BrownNoise.ar(Seq(0.125, 0.125))
        Out.ar(0, sig)
      }

      val _tl = timeline()

      _tl += (10.0 -> 13.0, _proc1)

      val _tr = Transport(as)
      _tr.addObject(_tl)
      _tr.addObject(_proc2)
      _tr.seek(frame(8.0))
      println("--issue play at 8s--")
      _tr.play()
      _tr
    }

    after(5.0) { implicit tx =>
      println("--issue seek to 10s--")
      tr.seek(frame(10.0))

      after(4.0) { implicit tx =>
        println("--issue stop--")
        tr.stop()
        stopAndQuit()
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 5

  def test5()(implicit context: AuralContext[S]): Unit = {
    println("----test5----")
    println(
      """
        |Expected behaviour:
        |Two sine generators are placed on a timeline
        |Between 1 and 3 seconds, a sine of 441 Hz is heard on the left  channel.
        |Between 2 and 4 seconds, a sine of 666 Hz is heard on the right channel.
        |After 5 seconds, transport stops. One second later it restarts
        |at 3.5s, thus playing the 666 Hz sine for 0.5s. After 5.5 seconds,
        |a sweep is added with a span that has already started, making the
        |sweep start at roughly 40% of the ramp (pos = 0.4) or 1 kHz. A pink noise
        |is dynamically added to the timeline and should be heard 0.5s after
        |the sweep. The pink noise is removed after another 0.5s, leaving
        |only the sweep to finish playing.
        |
        |""".stripMargin)

    val tl = cursor.step { implicit tx =>
      def mkProc() = procV {
        val freq = graph.Attribute.ir("freq", 441)
        val pan  = graph.Attribute.ir("pan")
        val sig  = Pan2.ar(SinOsc.ar(freq) * 0.2, pan)
        Out.ar(0, sig)
      }

      val _view1 = mkProc()
      putDouble(_view1.obj(), "pan", -1)
      val _view2 = mkProc()
      putDouble(_view2.obj(), "freq", 666)
      putDouble(_view2.obj(), "pan", 1)

      val _tl   = timelineV()
      val tlObj = _tl.obj()
      tlObj += (1.0 -> 3.0, _view1.obj())
      tlObj += (2.0 -> 4.0, _view2.obj())
      val it = tlObj.elem.peer.debugList
      println("--debug print--")
      println(it)
      _tl
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      tl.play()
    }

    after(5.0) { implicit tx =>
      println("--issue stop--")
      tl.stop()

      after(1.0) { implicit tx =>
        println("--issue play from 3.5s--")
        tl.play(TimeRef(Span.from(0L), frame(3.5)))

        after(2.0) { implicit tx =>
          println("--insert at 5.5s--")
          val tlObj = tl.obj()
          val _view3 = procV {
            val dur  = graph.Duration.ir
            val off  = graph.Offset  .ir
            val pos  = Line.ar(off / dur, 1, dur - off)
            pos.poll(8, "pos")
            val freq = pos.linexp(0, 1, 400, 4000)
            val sig  = Pan2.ar(SinOsc.ar(freq) * 0.2)
            Out.ar(0, sig)
          }

          tlObj += (3.5 -> 8.5, _view3.obj())

          val _view4 = procV {
            val sig  = PinkNoise.ar(0.5)
            Out.ar(1, sig)
          }

          tlObj += (6.0 -> 7.5, _view4.obj())

          after(1.0) { implicit tx =>
            val tlObj = tl.obj()
            println("--kill your idol at 6.5s--")
            tlObj -= (6.0 -> 7.5, _view4.obj())

            stopAndQuit(3.0)
          }
        }
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 4

  def test4()(implicit context: AuralContext[S]): Unit = {
    println("----test4----")
    println(
      """
        |Expected behaviour:
        |A filtered pink noise with resonance frequency 666 Hz is heard
        |after issuing 'play1'. A second later, the frequency changes to 999 Hz.
        |
        |""".stripMargin)

    val (view1, view2) = cursor.step { implicit tx =>
      val _view1 = procV {
        val amp   = graph.Attribute.ir("amp")
        val noise = PinkNoise.ar(Seq(amp, amp))
        graph.ScanOut("out", noise)
      }
      _view1.react { implicit tx => upd => println(s"Observed: $upd") }
      val proc1 = _view1.obj()
      putDouble(proc1, "amp", 0.5)

      val _view2 = procV {
        val freq  = graph.Attribute.kr("freq", 440)
        val in    = graph.ScanIn("in")
        Out.ar(0, Resonz.ar(in, freq, 0.1) * 10)
      }
      val proc2 = _view2.obj()
      putDouble(proc2, "freq", 666)

      (_view1, _view2)
    }

    cursor.step { implicit tx =>
      println("--issue play2--")
      view2.play()
      val proc1   = view1.obj()
      val proc2   = view2.obj()
      //      val test = de.sciss.lucre.event.Peek.targets(proc2)
      //      println(s"---1, num-children is ${test.size}")
      // reversed steps
      val scanIn  = addScan(proc2, "in" )
      val scanOut = addScan(proc1, "out")
      scanOut ~> scanIn
    }

    after(2.0) { implicit tx =>
      println("--issue play1--")
      view1.play()
      //      val proc2 = view2.obj()
      //      val test = de.sciss.lucre.event.Peek.targets(proc2)
      //      println(s"---2, num-children is ${test.size}")

      after(1.0) { implicit tx =>
        val proc2b = view2.obj()
        println("--adjust attribute--")
        // val test1 = de.sciss.lucre.event.Peek.targets(proc2b)
        // println(s"---3, num-children is ${test1.size}")
        putDouble(proc2b, "freq", 999)

        stopAndQuit()
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 3

  def test3()(implicit context: AuralContext[S]): Unit = {
    println("----test3----")
    println(
      """
        |Expected behaviour:
        |A filtered pink noise with resonance frequency 666 Hz is heard
        |after issuing 'play2'.
        |
        |""".stripMargin)

    val (view1, view2) = cursor.step { implicit tx =>
      val _view1 = procV {
        val amp   = graph.Attribute.ir("amp")
        val noise = PinkNoise.ar(Seq(amp, amp))
        graph.ScanOut("out", noise)
      }
      _view1.react { implicit tx => upd => println(s"Observed: $upd") }
      val proc1 = _view1.obj()
      putDouble(proc1, "amp", 0.5)

      val _view2 = procV {
        val freq  = graph.Attribute.ir("freq", 440)
        val in    = graph.ScanIn("in")
        Out.ar(0, Resonz.ar(in, freq, 0.1) * 10)
      }
      val proc2 = _view2.obj()
      putDouble(proc2, "freq", 666)

      (_view1, _view2)
    }

    cursor.step { implicit tx =>
      println("--issue play1--")
      view1.play()
      val proc1   = view1.obj()
      val proc2   = view2.obj()
      val scanOut = addScan(proc1, "out")
      val scanIn  = addScan(proc2, "in" )
      scanOut ~> scanIn
//      println("--issue play2--")
//      view2.play()
    }

    after(2.0) { implicit tx =>
      println("--issue play2--")
      view2.play()

      stopAndQuit()
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////// 2

  def test2()(implicit context: AuralContext[S]): Unit = {
    println("----test2----")
    println(
      """
        |Expected behaviour:
        |A filtered pink noise with resonance frequency 666 Hz is heard.
        |
        |""".stripMargin)

    val (view1, view2) = cursor.step { implicit tx =>
      val _view1 = procV {
        val amp   = graph.Attribute.ir("amp")
        val noise = PinkNoise.ar(Seq(amp, amp))
        graph.ScanOut("out", noise)
      }
      import Implicits._
      _view1.react { implicit tx => upd => println(s"Observed: $upd") }
      val proc1 = _view1.obj()
      proc1.attr.name = "pink"
      putDouble(proc1, "amp", 0.5)

      val _view2 = procV {
        val freq  = graph.Attribute.ir("freq", 440)
        val in    = graph.ScanIn("in")
        Out.ar(0, Resonz.ar(in, freq, 0.1) * 10)
      }
      val proc2 = _view2.obj()
      proc2.attr.name = "filter"
      putDouble(proc2, "freq", 666)

      (_view1, _view2)
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      view1.play()
      view2.play()
      val proc1   = view1.obj()
      val proc2   = view2.obj()
      val scanOut = addScan(proc1, "out")
      val scanIn  = addScan(proc2, "in" )
      scanOut ~> scanIn
    }

    stopAndQuit()
  }

  ////////////////////////////////////////////////////////////////////////////////////// 1

  def test1()(implicit context: AuralContext[S]): Unit = {
    println("----test1----")
    println(
      """
        |Expected behaviour:
        |A pink noise is heard after the 'play' is issued.
        |
        |""".stripMargin)

    val view = cursor.step { implicit tx =>
      val _view = procV {
        Out.ar(0, PinkNoise.ar(Seq(0.5, 0.5)))
      }
      _view.react { implicit tx => upd => println(s"Observed: $upd") }
      _view
    }

    cursor.step { implicit tx =>
      println("--issue play--")
      view.play()
    }

    stopAndQuit()
  }
}