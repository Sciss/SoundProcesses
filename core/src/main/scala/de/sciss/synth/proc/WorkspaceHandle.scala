/*
 *  WorkspaceHandle.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc

import de.sciss.lucre.event.{InMemory, Sys}
import de.sciss.lucre.stm.{TxnLike, Disposable}

object WorkspaceHandle {
  object Implicits {
    implicit def dummy[S <: Sys[S]]: WorkspaceHandle[S] = dummyVal.asInstanceOf[DummyImpl[S]]

    private val dummyVal = new DummyImpl[InMemory]

    private class DummyImpl[S <: Sys[S]] extends WorkspaceHandle[S] {
      def addDependent   (dep: Disposable[S#Tx])(implicit tx: TxnLike) = ()
      def removeDependent(dep: Disposable[S#Tx])(implicit tx: TxnLike) = ()
    }
  }
}
trait WorkspaceHandle[S <: Sys[S]] {
  /** Adds a dependent which is disposed just before the workspace is disposed.
    *
    * @param dep  the dependent. This must be an _ephemeral_ object.
    */
  def addDependent   (dep: Disposable[S#Tx])(implicit tx: TxnLike /* S#Tx */): Unit
  def removeDependent(dep: Disposable[S#Tx])(implicit tx: TxnLike /* S#Tx */): Unit
}