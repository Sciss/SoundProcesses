/*
 *  InMemoryImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2018 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.lucre.synth
package impl

import de.sciss.lucre.event.impl.ReactionMapImpl
import de.sciss.lucre.stm

import scala.concurrent.stm.InTxn

object InMemoryImpl {
  def apply(): InMemory = new System

  private final class TxnImpl(val system: InMemory, val systemTimeNanos: Long, val peer: InTxn)
    extends stm.impl.InMemoryImpl.TxnMixin[InMemory]
    with TxnFullImpl[InMemory] with InMemory.Txn {

    override def toString = s"proc.InMemory#Tx@${hashCode.toHexString}"

    def inMemory: InMemory#Tx = this
  }

  private final class System
    extends stm.impl.InMemoryImpl.Mixin[InMemory]
    with InMemory with ReactionMapImpl.Mixin[InMemory] {

    type S = InMemory

    def inMemory: I = this
    def inMemoryTx(tx: Tx): Tx = tx

    def wrap(peer: InTxn, systemTimeNanos: Long): S#Tx =
      new TxnImpl(this, systemTimeNanos, peer)

    override def toString = s"proc.InMemory@${hashCode.toHexString}"
  }
}