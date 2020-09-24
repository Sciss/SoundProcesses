/*
 *  ControlValuesView.scala
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

package de.sciss.synth.proc

import de.sciss.lucre.{Observable, Source, Txn}
import de.sciss.synth.ugen.ControlValues

trait ControlValuesView[T <: Txn[T]]
  extends Observable[T, Option[ControlValues]] with Source[T, Option[ControlValues]]
