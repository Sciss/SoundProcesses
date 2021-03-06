/*
 *  BasicAuralObjImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2021 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.proc.impl

import de.sciss.lucre.Txn
import de.sciss.proc.AuralObj

trait BasicAuralObjImpl[T <: Txn[T]] extends AuralObj[T] with BasicViewBaseImpl[T]