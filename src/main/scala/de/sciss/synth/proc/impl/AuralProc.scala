/*
 *  AuralProc.scala
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

package de.sciss.synth.proc
package impl

import de.sciss.lucre.{DataInput, DataOutput, stm}
import de.sciss.synth.Synth
import concurrent.stm.TxnLocal
import stm.{InMemory, Durable, Writer}

object AuralProc {
//   implicit object Serializer extends stm.Serializer[ AuralProc ] {
//      def write( v: AuralProc, out: DataOutput ) { v.write( out )}
//      def read( in: DataInput ) : AuralProc = {
//         val name = in.readString()
//         new Impl( name )
//      }
//   }

   def apply( name: String ) : AuralProc = new Impl( name )

   private final class Impl( val name: String ) extends AuralProc {
//      def write( out: DataOutput ) {
//         out.writeString( name )
//      }
   }
}
sealed trait AuralProc /* extends Writer */ {
   def name : String
//   def group : I#Var[ Option[ RichGroup ]]

//   def synth: Durable
}
