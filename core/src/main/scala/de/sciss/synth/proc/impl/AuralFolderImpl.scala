/*
 *  AuralFolderImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2019 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc.impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Folder, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{AuralContext, AuralObj, TimeRef, Transport}

object AuralFolderImpl {
  def apply[S <: Sys[S]](folder: Folder[S])(implicit tx: S#Tx, context: AuralContext[S]): AuralObj.Folder[S] = {
    val transport = Transport[S](context)
    folder.iterator.foreach(transport.addObject)
    new Impl(tx.newHandle(folder), transport).init(folder)
  }

  private final class Impl[S <: Sys[S]](val objH: stm.Source[S#Tx, Folder[S]],
                                        protected val transport: Transport[S])
    extends AuralFolderLikeImpl[S, Folder[S], AuralObj.Folder[S]]
    with AuralObj.Folder[S] { impl =>

    def tpe: Obj.Type = Folder

    def folder(implicit tx: S#Tx): Folder[S] = objH()

    def mkObserver(ens: Folder[S])(implicit tx: S#Tx): Disposable[S#Tx] =
      ens.changed.react { implicit tx => upd =>
        processFolderUpdate(upd)
      }

    protected def performPlay(timeRef: TimeRef)(implicit tx: S#Tx): Unit =
      startTransport(timeRef.offset)
  }
}
