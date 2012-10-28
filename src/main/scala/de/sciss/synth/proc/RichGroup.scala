/*
 *  RichGroup.scala
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

import de.sciss.synth.{addToHead, AddAction, Group}
import ProcTxn.RequiresChange

object RichGroup {
   def apply( server: RichServer ) : RichGroup =
      new RichGroup( server, Group( server.peer ))( initOnline = false )

   def apply( server: RichServer, peer: Group ) : RichGroup = {
      require( server.peer == peer.server )
      new RichGroup( server, peer )( initOnline = false )
   }

   def default( server: RichServer ) : RichGroup =
      new RichGroup( server, server.peer.defaultGroup )( initOnline = true ) // XXX TODO: should go into RichServer
}
final case class RichGroup private( server: RichServer, peer: Group )( initOnline: Boolean )
extends RichNode( initOnline ) {
   override def toString = "RichGroup(" + peer.toString + ")"

   def play( target: RichNode, addAction: AddAction = addToHead )( implicit tx: ProcTxn ) {
      require( target.server == server )

      // XXX THERE IS CURRENTLY A PROBLEM EXHIBITED BY TEST3: BASICALLY --
      // since newMsg is not audible, it might be placed in the first bundle, but then
      // since moveAfterMsg is audible, the target of this group's newMsg might be
      // moved, ending up in moveAfterMsg following the g_new message, leaving this
      // group in the wrong place of the graph.
      //
      // We thus try out a workaround by declaring a group's newMsg also audible...
//      tx.add( group.newMsg( target.node, addAction ), Some( (RequiresChange, isOnline, true) ), false,
//              Map( target.isOnline -> true ))
      tx.add( peer.newMsg( target.peer, addAction ), change = Some( (RequiresChange, isOnline, true) ),
              audible = true, dependencies = Map( target.isOnline -> true ))
   }
}
