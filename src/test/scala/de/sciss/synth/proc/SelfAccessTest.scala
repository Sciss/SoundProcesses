package de.sciss.synth.proc

import de.sciss.lucre.stm.{Disposable, Durable, InMemory, Cursor, Sys, Writer, TxnSerializer}
import de.sciss.lucre.{DataInput, DataOutput}
import java.util.concurrent.{TimeUnit, Executors, ScheduledExecutorService}
import concurrent.stm.Txn
import de.sciss.lucre.stm.impl.BerkeleyDB
import java.io.File
import de.sciss.confluent.{TemporalObjects, Confluent}

object SelfAccessTest extends App {
//   inMem()
//   dur()
   conf()

   def inMem() {
      implicit val sys = InMemory()
      new SelfAccessTest( sys )
   }

   private def tmpDir() : File = {
      val f = File.createTempFile( "database", "db" )
      f.delete()
      f.mkdir()
      f
   }

   private def durFact() = BerkeleyDB.factory( tmpDir(), createIfNecessary = true )

   def dur() {
      implicit val sys = Durable( durFact() )
      new SelfAccessTest( sys )
   }

   def conf() {
      implicit val sys = Confluent( durFact() )
      TemporalObjects.showConfluentLog = true
      new SelfAccessTest( sys )
   }
}
class SelfAccessTest[ S <: Sys[ S ]]( system: S )( implicit cursor: Cursor[ S ]) {
   lazy val pool : ScheduledExecutorService = Executors.newScheduledThreadPool( 1 ) // > 0 to prevent immediate VM shutdown

   object Counter {
      def apply()( implicit tx: S#Tx, cursor: Cursor[ S ]) : Counter = {
         new Impl {
            val id   = tx.newID()
            val cnt  = tx.newIntVar( id, 0 )
            val play = tx.newBooleanVar( id, init = false )
            val csr  = cursor
            val self = tx.newVar[ Counter ]( id, null )
            self.set( this )
         }
      }

      implicit def serializer( implicit cursor: Cursor[ S ]) : TxnSerializer[ S#Tx, S#Acc, Counter ] = new Ser( cursor )

      private final class Ser( cursor: Cursor[ S ]) extends TxnSerializer[ S#Tx, S#Acc, Counter ] {
         ser =>

         def write( c: Counter, out: DataOutput ) {
            if( c == null ) {
               out.writeUnsignedByte( 0 )
            } else {
               c.write( out )
            }
         }
         def read( in: DataInput, access: S#Acc )( implicit tx: S#Tx ) : Counter = {
            if( in.readUnsignedByte() == 0 ) return null
            new Impl {
               val id      = tx.readID( in, access )
               val cnt     = tx.readIntVar( id, in )
               val play    = tx.readBooleanVar( id, in )
               val csr     = cursor
               val self    = tx.readVar[ Counter ]( id, in )( ser )
            }
         }
      }

      private abstract class Impl
      extends Counter with Runnable {
         me =>

         def id: S#ID
         protected def csr: Cursor[ S ]
         protected def cnt: S#Var[ Int ]
         protected def play: S#Var[ Boolean ]
         protected def self: S#Var[ Counter ]

         override def toString = "Counter" + id

         final def write( out: DataOutput ) {
            out.writeUnsignedByte( 1 )
            id.write( out )
            cnt.write( out )
            play.write( out )
            self.write( out )
         }

         final def dispose()( implicit tx: S#Tx ) {
            id.dispose()
            cnt.dispose()
            play.dispose()
            self.dispose()
         }

         final def run() {
            csr.step { implicit tx =>
               val icke = self.get
               println( "...run " + tx + " -> " + icke )
               icke.step()
            }
         }

         final def step()( implicit tx: S#Tx ) {
            val p = play.get
            println( "Step in " + tx + " found " + p )
            if( p ) {
               cnt.transform( _ + 1 )
               implicit val itx = tx.peer
               Txn.beforeCommit { implicit itx =>
                  val v = value()
                  Txn.afterCommit { _ =>
                     println( "Count of " + me + " = " + v )
                  }
               }
               spawn()
            }
         }

         final def start()( implicit tx: S#Tx ) {
            val wasPlaying = play.get
            if( !wasPlaying ) {
               println( "Setting in " + tx + " play = true " )
               play.set( true )
               spawn()
            }
         }

         final def stop()( implicit tx: S#Tx ) {
            val wasPlaying = play.get
            if( wasPlaying ) {
               play.set( false )
            }
         }

         private def spawn()( implicit tx: S#Tx ) {
            implicit val itx = tx.peer
            Txn.afterCommit { _ =>
               pool.schedule( this, 1, TimeUnit.SECONDS )
            }
         }

         final def value()( implicit tx: S#Tx ) : Int = cnt.get
      }
   }
   sealed trait Counter extends Writer with Disposable[ S#Tx ] {
      def start()( implicit tx: S#Tx ) : Unit
      def stop()( implicit tx: S#Tx ) : Unit
      def value()( implicit tx: S#Tx ) : Int

      // quasi-private
      def step()( implicit tx: S#Tx ) : Unit
   }

   println( "Start" )

   val access = system.root { implicit tx => Counter.apply() }
   cursor.step { implicit tx =>
      access.get.start()
   }

   pool.schedule( new Runnable {
      def run() {
         cursor.step { implicit tx =>
            implicit val itx = tx.peer
            val c = access.get
            val v = c.value()
            Txn.afterCommit { _ =>
               println( "Stop. Last value was " + v )
               pool.shutdown()
               sys.exit( 0 )
            }
            c.stop()
         }
      }
   }, 10, TimeUnit.SECONDS )
}