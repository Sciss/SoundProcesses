/*
 *  CursorHolder.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2020 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.proc.gui.impl

import de.sciss.lucre.{Cursor, Txn}

trait CursorHolder[T <: Txn[T]] {
  protected def cursor: Cursor[T]

  final protected def atomic[A](fun: T => A): A = cursor.step(fun)
}
