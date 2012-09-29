package de.sciss.synth
package proc

import de.sciss.lucre.{event => evt, bitemp, stm}
import expr.ExprImplicits
import bitemp.Span
import stm.impl.BerkeleyDB
import java.io.File
import evt.InMemory
import de.sciss.lucre.confluent.reactive.ConfluentReactive

object ScansTest extends App {
   val AURAL = false

//   type S = InMemory // Durable
//   type I = InMemory

   args.headOption match {
      case Some( "--prelim" )    => preliminaryTest()
      case Some( "--mem-scan" )  =>
         implicit val sys = evt.InMemory()
         run[ InMemory, InMemory ]()
      case Some( "--confluent-scan" ) =>
         implicit val sys = ConfluentReactive.tmp()
         run[ ConfluentReactive, stm.InMemory ]()

      case _ =>
         println(
            """
              |Options: --prelim
              |         --mem-scan
              |         --confluent-scan
            """.stripMargin )
         sys.exit( 1 )
   }

//   def makeSys() : InMemory /* Durable */ = {
////      val dir = File.createTempFile( "scans", "db" )
////      dir.delete()
////      Durable( BerkeleyDB.factory( dir ))
//      InMemory()
//   }

   def preliminaryTest() {
      ???
//      val sys  = makeSys()
//      val scan = sys.step { implicit tx =>
//         val _scan = Scan_.Modifiable[ S ]
//         _scan.changed.react { upd =>
//            println( "OBSERVED: " + upd )
//         }
//         _scan
//      }
//
//      sys.step { implicit tx =>
//         scan.add(     0L, Scan_.Mono( 441 ))
//         scan.add( 10000L, Scan_.Mono( 333 ))
//         scan.add(  5000L, Scan_.Synthesis() )
//         val v = Doubles.newVar[ S ]( 666 )
//         scan.add( 15000L, Scan_.Mono( v ))
//         v.set( 777 )
//      }
//
////      println( "\n---step2---\n" )
////
////      sys.step { implicit tx =>
////         val sc2 = Scan_.Modifiable[ S ]
////         val v2 = Longs.newVar[ S ]( 20000L )
////         scan.add( 20000L, Scan_.Embedded( sc2, v2 ))
////         sc2.add( 0L, Scan_.Mono( 888 ))
////         v2.set( 30000L )
////      }
   }

   def run[ S <: evt.Sys[ S ], I <: stm.Sys[ I ]]()( implicit system: S, cursor: stm.Cursor[ S ], bridge: S#Tx => I#Tx ) {
//      implicit val sys = makeSys()
      val imp  = new ExprImplicits[ S ]
      import imp._

      def body( auralSystem: Option[ AuralSystem ]) {
         cursor.step { implicit tx =>
            val group   = ProcGroup_.Modifiable[ S ]
            test( group )
//            transp.playing_=( true )
val transp  = Transport[ S, I ]( group )
            auralSystem.foreach { as => AuralPresentation.run( transp, as )}
            transp.play()
         }
      }

      // ensure index tree 0 is build
      system.root( _ => () )

      if( AURAL ) {
         lazy val as: AuralSystem = AuralSystem().start().whenStarted { _ =>
         //         Thread.sleep( 1000 )
            body( Some( as ))
         }
         as
      } else {
         body( None )
      }

//      Thread.sleep( 1000 )
   }

   def test[ S <: evt.Sys[ S ]]( group: ProcGroup_.Modifiable[ S ])( implicit tx: S#Tx ) {
      SoundProcesses.showLog = true

      val imp  = new ExprImplicits[ S ]
      import imp._

      val p1 = Proc[ S ]
      val p2 = Proc[ S ]

      p1.changed.react( upd => println( "OBSERVED p1 : " + upd ))
      p2.changed.react( upd => println( "OBSERVED p1 : " + upd ))

      val t1 = 1 /* 4 */ * 44100L   // XXX TODO eventually should appear later
      val t2 = 1 * 44100L // XXX TODO must currently be greater than current transport position

      group.add( Span.from( t1 ), p1 )
      group.add( Span.from( t2 ), p2 )

      p1.scans.add( "out" )
      p2.scans.add( "freq" )

      for( s2 <- p2.scans.get( "freq" ); s1 <- p1.scans.get( "out" )) {
         s2.addSink( s1 )
//         s2.add( 0L, Scan_.Embedded( tp1, "out", 0L ))
      }

//      for( s1 <- p1.scans.get( "out" )) {
////         s1.add( 0L, Scan_.Mono( 441 ))
//         s1.add( 0L, Scan_.Synthesis() )  // XXX TODO should be written by the scan.Out itself
//      }

      import ugen._
      import graph.scan

      p1.graph_=( SynthGraph {
         scan( "out" ) := SinOsc.ar( 100 ).linexp( -1, 1, 30, 3000 )
      })

      p2.graph_=( SynthGraph {
         val freq = scan( "freq" ).ar( 333 )
//         freq.poll
         Out.ar( 0, SinOsc.ar( freq ))
      })

//      {
//         implicit val chr = Chronos[ S ]( t1 )
//         p1.playing_=( true )
//      }
//
//      {
//         implicit val chr = Chronos[ S ]( t2 )
//         p2.playing_=( true )
//      }
   }
}
