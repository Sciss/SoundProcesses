/*
 *  KeyMapImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2015 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth
package proc
package impl

import de.sciss.lucre.data.SkipList
import de.sciss.lucre.stm.{Disposable, Sys}
import de.sciss.serial.{DataInput, DataOutput, Serializer, Writable}

object KeyMapImpl {
  trait ValueInfo[S <: Sys[S], Key, Value] {
    // def valueEvent(value: Value): EventLike[S, ValueUpd]

    def keySerializer  : Serializer[S#Tx, S#Acc, Key]
    def valueSerializer: Serializer[S#Tx, S#Acc, Value]
  }

  implicit def entrySerializer[S <: Sys[S], Key, Value](implicit info: ValueInfo[S, Key, Value])
  : Serializer[S#Tx, S#Acc, Entry[S, Key, Value]] = new EntrySer[S, Key, Value]

  private final class EntrySer[S <: Sys[S], Key, Value](implicit info: ValueInfo[S, Key, Value])
    extends Serializer[S#Tx, S#Acc, Entry[S, Key, Value]] {

    def write(e: Entry[S, Key, Value], out: DataOutput): Unit = e.write(out)

    def read(in: DataInput, access: S#Acc)(implicit tx: S#Tx): Entry[S, Key, Value] = {
      val key   = info.keySerializer.read(in, access)
      val value = info.valueSerializer.read(in, access)
      new Entry[S, Key, Value](key, value)
    }
  }

  final class Entry[S <: Sys[S], Key, Value](val key: Key,
                                             val value: Value)(implicit info: ValueInfo[S, Key, Value])
    extends Writable with Disposable[S#Tx] { // extends evti.StandaloneLike[S, (Key, ValueUpd), Entry[S, Key, Value, ValueUpd]] {

//    def connect   ()(implicit tx: S#Tx): Unit = info.valueEvent(value) ---> this
//    def disconnect()(implicit tx: S#Tx): Unit = info.valueEvent(value) -/-> this

    def write(out: DataOutput): Unit = {
      info.keySerializer  .write(key  , out)
      info.valueSerializer.write(value, out)
    }

    def dispose()(implicit tx: S#Tx) = ()

//    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[(Key, ValueUpd)] =
//      pull(info.valueEvent(value)).map(key -> _)
  }
}

/** Common building block for implementing reactive maps where the key is a constant element
  * (that is, it does not require updating such as an `S#ID`).
  *
  * @tparam S         the system used
  * @tparam Key       the type of key, such as `String`
  * @tparam Value     the value type, which has an event attached to it (found via `valueInfo`)
  */
trait KeyMapImpl[S <: Sys[S], Key, Value] {
  // _: evt.VirtualNodeSelector[S] =>
  // _: evt.impl.MappingNode[S] with evt.Publisher[S, ]

  protected type Entry = KeyMapImpl.Entry    [S, Key, Value]
  protected type Info  = KeyMapImpl.ValueInfo[S, Key, Value]

  /** The underlying non-reactive map */
  protected def map: SkipList.Map[S, Key, Entry]

  /** Wrap the given set of added and removed keys in an appropriate update message
    * and dispatch it.
    */
  protected def fire(added: Option[(Key, Value)], removed: Option[(Key, Value)])(implicit tx: S#Tx): Unit

  /** A helper object providing key and value serialization and an event view of the value. */
  protected implicit def valueInfo: Info

  final def get(key: Key)(implicit tx: S#Tx): Option[Value] = map.get(key).map(_.value)

  final def keys(implicit tx: S#Tx): Set[Key] = map.keysIterator.toSet

  final def iterator(implicit tx: S#Tx): Iterator[(Key, Value)] =
    map.iterator.map {
      case (key, entry) => key -> entry.value
    }

  final def add(key: Key, value: Value)(implicit tx: S#Tx): Unit = {
    val n = new KeyMapImpl.Entry[S, Key, Value](key, value)
    val optRemoved: Option[(Key, Value)] = map.add(key -> n).map { oldNode =>
      // this -= oldNode
      key -> oldNode.value
    }
    // this += n
    fire(added = Some(key -> value), removed = optRemoved)
  }

  final def remove(key: Key)(implicit tx: S#Tx): Boolean =
    map.remove(key).exists { oldNode =>
      // this -= oldNode
      fire(added = None, removed = Some(key -> oldNode.value))
      true
    }

  //  @inline private def +=(entry: Entry)(implicit tx: S#Tx): Unit = entry ---> this
  //  @inline private def -=(entry: Entry)(implicit tx: S#Tx): Unit = entry -/-> this

//  final def connect()(implicit tx: S#Tx): Unit =
//    map.iterator.foreach {
//      case (_, node) => this += node
//    }
//
//  final def disconnect()(implicit tx: S#Tx): Unit =
//    map.iterator.foreach {
//      case (_, node) => this -= node
//    }

  //   final protected def foldUpdate( pull: evt.Pull[ S ])( implicit tx: S#Tx ) : Map[ Key, Vec[ ValueUpd ]] = {
  //      pull.parents( this ).foldLeft( Map.empty[ Key, Vec[ ValueUpd ]]) { case (map, sel) =>
  //         val entryEvt = sel.devirtualize[ (Key, ValueUpd), Entry ]( KeyMapImpl.entrySerializer )
  //         pull(entryEvt) match {
  //            case Some( (key, upd) ) => map + (key -> (map.getOrElse( key, Vec.empty ) :+ upd))
  //            case None => map
  //         }
  //      }
  //   }

//  final protected def foldUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Map[Key, ValueUpd] = {
//    pull.parents(this).foldLeft(Map.empty[Key, ValueUpd]) {
//      case (map, sel) =>
//        val entryEvt = sel.devirtualize[(Key, ValueUpd), Entry](KeyMapImpl.entrySerializer)
//        pull(entryEvt) match {
//          case Some((key, upd)) =>
//            assert(!map.contains(key))
//            map + (key -> upd)
//          case None =>
//            map
//        }
//    }
//  }
}
