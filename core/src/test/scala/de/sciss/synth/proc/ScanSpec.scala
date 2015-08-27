package de.sciss
package synth
package proc

import de.sciss.lucre.expr.{DoubleObj, LongObj}
import de.sciss.synth.Curve.linear

import scala.collection.immutable.{IndexedSeq => Vec}

/*
 To run only this suite:

 test-only de.sciss.synth.proc.ScanSpec

 */
class ScanSpec extends ConfluentEventSpec {

  // lucre.event    .showLog = true
  // lucre.confluent.showLog = true

  ignore /* "Proc" */ should "notify observers about all relevant events" in { system =>
    val obs = new Observation
    val ph = system.step { implicit tx =>
      val p = Proc[S]
      p.changed.react(obs.register)
      val res = tx.newHandle(p)(Proc.serializer[S])
      obs.assertEmpty()
      res
    }

    val grH = system.step { implicit tx =>
      val p = ph()
      val scan1 = p.inputs.add   ("amp")
      val scan2 = p.inputs.add   ("freq")
      p.inputs.remove("amp")
      val gr = Grapheme[S](1)
      // p.graphemes.add( "test", gr )
      // p.graphemes.remove( "test" )
      // p.graphemes.add( "gr", gr )

      // val gr2 = Grapheme.Modifiable[S]
      obs.assertEquals(
        Proc.Update[S](p, Vec(Proc.InputAdded  ("amp" , scan1))),
        Proc.Update[S](p, Vec(Proc.InputAdded  ("freq", scan2))),
        Proc.Update[S](p, Vec(Proc.InputRemoved("amp" , scan1)))
        // Proc.Update( p, Vec( Proc.AssociationAdded( Proc.GraphemeKey( "test" )))),
        // Proc.Update( p, Vec( Proc.AssociationRemoved( Proc.GraphemeKey( "test" )))),
        // Proc.Update( p, Vec( Proc.AssociationAdded( Proc.GraphemeKey( "gr" ))))
      )
      obs.clear()

      // p.graphemes.add( "gr", gr2 )
      // gr.dispose()
      // obs.assertEquals(
      //    Proc.Update( p, Vec( Proc.AssociationRemoved( Proc.GraphemeKey( "gr" )),
      //                             Proc.AssociationAdded(   Proc.GraphemeKey( "gr" ))))
      // )

      tx.newHandle(gr)
    }

    def curve(amp: DoubleObj[S], shape: Curve = linear)(implicit tx: S#Tx) =
      Grapheme.Expr.Curve(amp -> shape)

    system.step { implicit tx =>
      val p = ph()
      // val Some( Grapheme.Modifiable( gr )) = p.graphemes.get( "gr" )
      val gr = grH()
      val Some(scan) = p.inputs.get("freq")

      gr.add((0L: LongObj[S]) -> (curve(1234.0): Grapheme.Expr[S])) // should be observed only directly through proc (but not scan)
      obs.assertEquals()
      //    Proc.Update( p, Vec( Proc.GraphemeChange( "gr",
      //       Grapheme.Update( gr, Vec( Grapheme.Segment.Const( Span.from( 0L ), Vec( 1234.0 ))))
      //    )))
      // )
      obs.clear()

      val grSource = Scan.Link.Grapheme(gr)
      scan.add(grSource) // should be observed
      obs.assertEquals(
//        Proc.Update(p, Vec(
//          Proc.InputChange("freq", scan, Vec(Scan.Added(grSource)))
//        ))
      )
      obs.clear()

      gr.add((2000L: LongObj[S]) -> (curve(5678.0): Grapheme.Expr[S])) // ...
      obs.assertEquals(
//        Proc.Update(p,
//          Vec(Proc.InputChange("freq", scan, Vec(
////          Scan.GraphemeChange(gr, Vec(Grapheme.Segment.Curve(Span(0L, 2000L), Vec((1234.0, 5678.0, linear))),
////            Grapheme.Segment.Const(Span.from(2000L), Vec(5678.0)))
////          )
//          )))
//        )
      )
      obs.clear()

      scan.remove(grSource)
      val timeVar = LongObj  .newVar[S](3000L)
      val ampVar  = DoubleObj.newVar[S](9876.0)
      gr.add(timeVar -> curve(ampVar)) // should not be observed
      val grSourceNew = Scan.Link.Grapheme(gr)
      scan.add(grSourceNew) // should be observed
      obs.assertEquals(
//        Proc.Update(p, Vec(Proc.InputChange("freq", scan, Vec(Scan.Removed(grSource   ))))),
//        Proc.Update(p, Vec(Proc.InputChange("freq", scan, Vec(Scan.Added  (grSourceNew)))))
      )
      obs.clear()

      //lucre.event.showLog = true
      timeVar() = 4000L // ...
      //lucre.event.showLog = false
      obs.assertEquals(
//        Proc.Update(p, Vec(Proc.InputChange("freq", scan, Vec(
////          Scan.GraphemeChange(
////            gr, Vec(Grapheme.Segment.Curve(Span(2000L, 4000L), Vec((5678.0, 9876.0, linear))),
////              Grapheme.Segment.Const(Span.from(4000L), Vec(9876.0)))
////          )
//        ))))
      )
      obs.clear()

      ampVar() = 5432.0 // ...
      obs.assertEquals(
//        Proc.Update(p, Vec(Proc.InputChange("freq", scan, Vec(
////          Scan.GraphemeChange(
////            gr, Vec(Grapheme.Segment.Curve(Span(2000L, 4000L), Vec((5678.0, 5432.0, linear))),
////              Grapheme.Segment.Const(Span.from(4000L), Vec(5432.0)))
////          )
//        ))))
      )
      obs.clear()
    }
  }
}