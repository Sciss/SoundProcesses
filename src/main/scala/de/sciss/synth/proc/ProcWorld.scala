/*
 *  ProcWorld.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2012 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth
package proc

import impl.AuralProc
import concurrent.stm.{TMap, InTxn, TSet, Ref => ScalaRef}

//object ProcWorld {
//// MMM
////   case class Update( procsAdded: ISet[ Proc ], procsRemoved: ISet[ Proc ])
////   type Listener = TxnModel.Listener[ Update ]
//}

class ProcWorld /* MMM extends TxnModel[ ProcWorld.Update ] */ {
//   import ProcWorld._

// EEE
//   private type Topo = Topology[ AuralProc, ProcEdge ]
   val ugenGraphs = ScalaRef( Map.empty[ ProcDemiurg.GraphEquality, RichSynthDef ])
// EEE
//   private val topologyRef = ScalaRef[ Topo ]( Topology.empty )

// MMM
//   protected def fullUpdate( implicit tx: ProcTxn ) = Update( topologyRef().vertices.toSet, Set.empty )
//   protected def emptyUpdate = Update( Set.empty, Set.empty )

// EEE
//   def topology( implicit tx: ProcTxn ) = topologyRef()

   def addProc( p: AuralProc )( implicit tx: ProcTxn ) {
// MMM
//      touch()

// EEE
//      topologyRef.transform( _.addVertex( p ))

// MMM
//      updateRef.transform( u => if( u.procsRemoved.contains( p )) {
//          u.copy( procsRemoved = u.procsRemoved - p )
//      } else {
//          u.copy( procsAdded   = u.procsAdded   + p )
//      })
   }

   def removeProc( p: AuralProc )( implicit tx: ProcTxn ) {
// MMM
//      touch()

// EEE
//      topologyRef.transform( _ removeVertex p )

// MMM
//      updateRef.transform( u => if( u.procsAdded.contains( p )) {
//          u.copy( procsAdded = u.procsAdded - p )
//      } else {
//          u.copy( procsRemoved = u.procsRemoved + p )
//      })
   }

// EEE
//   def addEdge( e: ProcEdge )( implicit tx: ProcTxn ) : Option[ (Topo, Proc, IIdxSeq[ Proc ])] = {
//      val res = topologyRef().addEdge( e )
//      res.foreach( tup => topologyRef.set( tup._1 ))
//      res
//   }
//
//   def removeEdge( e: ProcEdge )( implicit tx: ProcTxn ) {
//      topologyRef.transform( _.removeEdge( e ))
//   }
}

// MMM
//case class ProcDemiurgUpdate( factoriesAdded: ISet[ ProcFactory ], factoriesRemoved: ISet[ ProcFactory ])

object ProcDemiurg /* MMM extends TxnModel[ ProcDemiurgUpdate ] */ {
   demi =>

// MMM
//   type Update    = ProcDemiurgUpdate
//   type Listener  = TxnModel.Listener[ Update ]

   var verbose = false

   private val servers = TSet.empty[ Server ]

   private val uniqueDefID = ScalaRef( 0 )
   private def nextDefID()( implicit tx: InTxn ) : Int = {
      val res = uniqueDefID.get
      uniqueDefID += 1
      res
   }

   def addServer( server: Server )( implicit tx: ProcTxn ) {
      implicit val itx = tx.peer
      if( servers.contains( server )) return
      servers += server
      worlds  += server -> new ProcWorld
   }

   def removeServer( server: Server )( implicit tx: ProcTxn ) {
      implicit val itx = tx.peer
      servers -= server
      worlds  -= server
   }

   // commented out for debugging inspection
   private val worlds = TMap.empty[ Server, ProcWorld ]

// FFF
//   private val factoriesRef = Ref( Set.empty[ ProcFactory ])
//   def factories( implicit tx: ProcTxn ) : Set[ ProcFactory ] = factoriesRef()

// MMM
//   protected def fullUpdate( implicit tx: ProcTxn ) = ProcDemiurgUpdate( factoriesRef(), Set.empty )
//   protected def emptyUpdate = ProcDemiurgUpdate( Set.empty, Set.empty )

// FFF
//   def addFactory( pf: ProcFactory )( implicit tx: ProcTxn ) {
//      touch
//      factoriesRef.transform( _ + pf )
//      updateRef.transform( u => if( u.factoriesRemoved.contains( pf )) {
//          u.copy( factoriesRemoved = u.factoriesRemoved - pf )
//      } else {
//          u.copy( factoriesAdded = u.factoriesAdded + pf )
//      })
//   }
//
//   def removeFactory( pf: ProcFactory )( implicit tx: ProcTxn ) {
//      touch
//      factoriesRef.transform( _ - pf )
//      updateRef.transform( u => if( u.factoriesAdded.contains( pf )) {
//          u.copy( factoriesAdded = u.factoriesAdded - pf )
//      } else {
//          u.copy( factoriesRemoved = u.factoriesRemoved + pf )
//      })
//   }

   def addVertex( e: AuralProc )( implicit tx: ProcTxn ) {
      val world = worlds( e.server )( tx.peer )
      world.addProc( e )
   }

   def removeVertex( e: AuralProc )( implicit tx: ProcTxn ) {
      val world = worlds( e.server )( tx.peer )
      world.removeProc( e )
   }

// EEE
//   def addEdge( e: ProcEdge )( implicit tx: ProcTxn ) { syn.synchronized {
//      val world = worlds( e.sourceVertex.server )
//      val res = world.addEdge( e )
//      if( res.isEmpty ) error( "Could not add edge" )
//
//      val Some( (newTopo, source, affected) ) = res
//      if( verbose ) println( "NEW TOPO = " + newTopo + "; SOURCE = " + source + "; AFFECTED = " + affected )
//      if( affected.isEmpty ) {
//         return
//      }
//
//      val srcGroup   = source.groupOption
//      val tgtGroups  = affected.map( p => (p, p.groupOption) )
//      val isAfter    = source == e.sourceVertex
//
//      def startMoving( g: RichGroup ) {
//         var succ                = g
//         var pred : RichGroup    = null
//         val iter                = tgtGroups.iterator
//         while( iter.hasNext ) {
//            pred = succ
//            val (target, tgtGroup) = iter.next()
//            tgtGroup match {
//               case Some( g2 ) => {
//                  if( isAfter ) {
//                     g2.moveAfter( true, pred )
//                  } else {
//                     g2.moveBefore( true, pred )
//                  }
//                  succ = g2
//               }
//               case None => {
//                  val g2 = RichGroup( Group( target.server ))
//                  g2.play( pred, if( isAfter ) addAfter else addBefore )
//                  target.group = g2
//                  succ = g2
//               }
//            }
//         }
//      }
//
//      srcGroup match {
//         case None => {
//            val g = RichGroup( Group( source.server ))
//            g.play( RichGroup.default( g.server ))
//            source.group = g
//            startMoving( g )
//         }
//         case Some( g ) => startMoving( g )
//      }
//   }}

// EEE
//   def removeEdge( e: ProcEdge )( implicit tx: ProcTxn ) { syn.synchronized {
//      val world = worlds( e.sourceVertex.server )
//      world.removeEdge( e )
//   }}

   private def allCharsOk( name: String ) : Boolean = {
      val len = name.length
      var i = 0; while( i < len ) {
         val c    = name.charAt( i ).toInt
         val ok   = c > 36 && c < 123 || c != 95   // in particular, disallow underscore
         if( !ok ) return false
      i += 1 }
      true
   }

   protected def abbreviate( name: String ) : String = {
      val len = name.length
      if( (len <= 16) && allCharsOk( name )) return name

      val sb = new StringBuffer( 16 )
      var i = 0; while( i < len && sb.length() < 16 ) {
         val c    = name.charAt( i ).toInt
         val ok   = c > 36 && c < 123 || c != 95   // in particular, disallow underscore
         if( ok ) sb.append( c )
      i += 1 }
      sb.toString
   }

   def getSynthDef( server: Server, graph: SynthGraph, nameHint: Option[ String ])( implicit tx: ProcTxn ) : RichSynthDef = {
      getSynthDef( server, graph.expand, nameHint )
   }

   def getSynthDef( server: Server, graph: UGenGraph, nameHint: Option[ String ])( implicit tx: ProcTxn ) : RichSynthDef = {
      implicit val itx = tx.peer
      val w = worlds.get( server ).getOrElse( sys.error( "Trying to access unregistered server " + server ))

      // XXX note: unfortunately we have sideeffects in the expansion, such as
      // includeParam for ProcAudioOutput ... And anyways, we might allow for
      // indeterminate GE.Lazies, thus we need to check for UGenGraph equality,
      // not SynthGraph equality
//      val u = graph.expand

      val equ = new GraphEquality( graph )
      println( "Checking for " + equ.hashCode + "... " )

      w.ugenGraphs.get.get( equ ).getOrElse {
         println( "... not found" )
         val name = abbreviate( nameHint.getOrElse( "proc" )) + "_" + nextDefID()
         val peer = SynthDef( name, graph )
         val rd   = RichSynthDef( server, peer )
         w.ugenGraphs.transform( _ + (equ -> rd) )
         rd
      }
   }

   final class GraphEquality( val graph: UGenGraph ) extends Proxy {
      private def mapUGen( ugen: UGen ) : Any = {
         val inStruct = ugen.inputs.map {
            //         case up: UGenProxy => mapUGen( up.source )
            case ugen: UGen.SingleOut                       => mapUGen( ugen )
            case UGen.OutProxy( source, outputIndex: Int )  => (mapUGen( source ), outputIndex)
            case ctl: ControlUGenOutProxy                   => ctl
            case c: Constant                                => c
         }
         (ugen.name, ugen.rate, ugen.specialIndex, inStruct, ugen.outputRates)
      }

      val self : Any = {
         val uStructs = graph.ugens.map { rich =>
            (mapUGen( rich.ugen ), rich.inputSpecs)
         }

         (graph.constants, graph.controlValues, graph.controlNames, uStructs)
      }

      override val hashCode: Int = self.hashCode   // make it a val
   }
}