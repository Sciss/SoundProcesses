package de.sciss
package synth
package proc

import de.sciss.lucre.expr

import collection.immutable.{IndexedSeq => Vec}
import span.Span
import de.sciss.synth.Curve.{parametric, step, welch, sine, exponential, linear}

/*
  To run only this suite:
  
  test-only de.sciss.synth.proc.GraphemeSpec

 */
class GraphemeSpec extends ConfluentEventSpec {
  // import imp._
  import ExprImplicits._

  import Grapheme.{Value, Modifiable, Update, Segment, Expr, TimedElem}

  ignore /* "Grapheme" */ should "notify observers about all relevant events" in { system =>
    val obs = new Observation
    val (gH1, gH2) = system.step { implicit tx =>
      val g1  = Grapheme[S](1)
      val g2  = Grapheme[S](2)
      g1.changed.react(obs.register)
      g2.changed.react(obs.register)
      val res1 = tx.newHandle(g1)(Modifiable.serializer[S])
      val res2 = tx.newHandle(g2)(Modifiable.serializer[S])
      obs.assertEmpty()
      (res1, res2)
    }

    type LE = expr.Expr[S, Long]
    type GE = Grapheme.Expr[S]
    import expr.Ops._

    val (e1, e2, e3, e4, e5) = system.step { implicit tx =>
      (??? : TimedElem[S], // RRR (    0L: LE) -> (Value.Curve(441.0 -> linear): GE)               : TimedElem[S],
        ??? : TimedElem[S], // RRR (10000L: LE) -> (Value.Curve(882.0 -> exponential): GE)          : TimedElem[S],
        ??? : TimedElem[S], // RRR (20000L: LE) -> (Value.Curve(123.4 -> sine, 567.8 -> sine): GE)  : TimedElem[S],
        ??? : TimedElem[S], // RRR (30000L: LE) -> (Value.Curve(987.6 -> welch, 543.2 -> step): GE) : TimedElem[S],
        ??? : TimedElem[S] // RRR (20000L: LE) -> (Value.Curve(500.0 -> parametric(-4f)): GE)      : TimedElem[S]
      )
    }

    // adding constants
    system.step { implicit tx =>
      val g1 = gH1()
      val g2 = gH2()

      g1.add(e1)
      obs.assertEquals(
        Update(g1, Vec(Segment.Const(Span.from(0L), Vec(441.0))))
      )
      obs.clear()

      g1.add(e2)
      val s0_10000 = Segment.Curve(Span(0L, 10000L), Vec((441.0, 882.0, exponential)))
      obs.assertEquals(
        Update(g1, Vec(s0_10000,
          Segment.Const(Span.from(10000L), Vec(882.0))))
      )
      obs.clear()

      g2.add(e3)
      obs.assertEquals(
        Update(g2, Vec(
          // Segment.Const(Span(10000L, 20000L), Vec(882.0)), // no curve if channel mismatch
          Segment.Const(Span.from(20000L)   , Vec(123.4, 567.8))))
      )
      obs.clear()

      g2.add(e4)
      obs.assertEquals(
        Update(g2, Vec(Segment.Curve(Span(20000L, 30000L), Vec((123.4, 987.6, welch), (567.8, 543.2, step))),
          Segment.Const(Span.from(30000L), Vec(987.6, 543.2))))
      )
      obs.clear()

      // NOT: override a stereo signal with a mono signal
      g1.add(e5)
      val s1 = Segment.Curve(Span(10000L, 20000L), Vec((882.0, 500.0, parametric(-4f))))
      obs.assertEquals(
        Update(g1, Vec(s1,
          // Segment.Const(Span(20000L, 30000L), Vec(500.0))
          Segment.Const(Span.From(20000L), Vec(500.0))
        ))
      )
      obs.clear()

      assert(  g1.segment(    -1L ) === None )
      assert(  g1.segment(     0L ) === Some( s0_10000 ))
      assert(  g1.segment(  9999L ) === Some( s0_10000 ))
      // assert( (g.segment( 10000L ) === Some( s0_10000 )) /* .isDefined */ )
      assert(g1.segment(10000L) === Some(s1))

      assert(g1.debugList() === List(
        s0_10000,
        Segment.Curve(Span(10000L, 20000L), Vec((882.0, 500.0, parametric(-4f)))),
        Segment.Const(Span.From(20000L), Vec(500.0))
      ))

      assert(g2.debugList() === List(
        Segment.Curve(Span(20000L, 30000L), Vec((123.4, 987.6, welch), (567.8, 543.2, step))),
        Segment.Const(Span.from(30000L), Vec(987.6, 543.2))
      ))
    }

    // removals
    system.step { implicit tx =>
      val g1 = gH1()
      val g2 = gH2()
      //         println( g.debugList() )
      assert(!g1.remove(e3)) // assert it was not found
      assert( g2.remove(e3)) // assert it was found
      // obs.assertEmpty() // ... but it was hidden
      obs.assertEquals(
        Update(g2, Vec(Segment.Undefined(Span(20000L, 30000L))))
      )

      ??? // RRR assert(!g1.remove((e5.key.value - 1) -> e5.value)) // assert it was not found
      assert(g1.remove(e5)) // assert it was found
      obs.assertEquals(
        Update(g1, Vec(Segment.Const(Span(10000L, 30000L), Vec(882.0))))
      )
      obs.clear()

      // removing first element should dispatch an undefined segment
      g1.remove(e1)
      obs.assertEquals(
        Update(g1, Vec(Segment.Undefined(Span(0L, 10000L))))
      )
      obs.clear()

      g1.remove(e4)
      obs.assertEquals(
        Update(g1, Vec(Segment.Const(Span.from(10000L), Vec(882.0))))
      )
      obs.clear()

      g1.remove(e2)
      obs.assertEquals(
        Update(g1, Vec(Segment.Undefined(Span.from(10000L))))
      )
      obs.clear()

      assert(g1.debugList() === Nil)
    }

    import expr.Ops._

    // ok, now test with non-constant expressions
    system.step { implicit tx =>
      val g1      = gH1()
      val time1   = lucre.expr.Long  .newVar[S](0L)
      val mag1    = lucre.expr.Double.newVar[S](1234.5)
      val value1  = Expr.Curve(mag1 -> linear)
      val elem1: TimedElem[S] = time1 -> value1

      val time2   = lucre.expr.Long  .newVar[S](10000L)
      val mag2    = lucre.expr.Double.newVar[S](6789.0)
      val value2  = Expr.Curve(mag2 -> linear)
      val elem2: TimedElem[S] = time2 -> value2

      val time3   = time2 + 1000L
      val mag3    = mag1 + 1000.0
      val value3  = Expr.Curve(mag3 -> linear)
      val elem3: TimedElem[S] = time3 -> value3

      g1.add(elem1)
      g1.add(elem2)
      g1.add(elem3)

      obs.assertEquals(
        Update(g1, Vec(
          Segment.Const(Span.from(0L), Vector(1234.5))
        )),
        Update(g1, Vec(
          Segment.Curve(Span(0L, 10000L), Vector((1234.5, 6789.0, linear))),
          Segment.Const(Span.from(10000L), Vector(6789.0))
        )),
        Update(g1, Vec(
          Segment.Curve(Span(10000L, 11000L), Vector((6789.0, 2234.5, linear))),
          Segment.Const(Span.from(11000L), Vector(2234.5))
        ))
      )
      obs.clear()

      time1() = 2000L
      obs.assertEquals(
        Update(g1, Vec(
          Segment.Undefined(Span(0L, 2000L)),
          Segment.Curve(Span(2000L, 10000L), Vector((1234.5, 6789.0, linear)))
        ))
      )
      //         obs.print()
      obs.clear()

      mag1() = 666.6
      obs.assertEquals(
        Update(g1, Vec(
          Segment.Curve(Span(2000L, 10000L), Vector((666.6, 6789.0, linear))),
          Segment.Curve(Span(10000L, 11000L), Vector((6789.0, 1666.6, linear))),
          Segment.Const(Span.from(11000L), Vector(1666.6))
        ))
      )
      obs.clear()

      time2() = 11000L
      obs.assertEquals(
        Update(g1, Vec(
          Segment.Curve(Span(2000L, 11000L), Vector((666.6, 6789.0, linear))),
          Segment.Curve(Span(11000L, 12000L), Vector((6789.0, 1666.6, linear))),
          Segment.Const(Span.from(12000L), Vector(1666.6))
        ))
      )
      obs.clear()
    }
  }
}
