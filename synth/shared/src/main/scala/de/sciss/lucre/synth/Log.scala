///*
// *  Log.scala
// *  (SoundProcesses)
// *
// *  Copyright (c) 2010-2021 Hanns Holger Rutz. All rights reserved.
// *
// *	This software is published under the GNU Affero General Public License v3+
// *
// *
// *  For further information, please contact Hanns Holger Rutz at
// *  contact@sciss.de
// */
//
//package de.sciss.lucre.synth
//
//import scala.annotation.elidable
//import java.util.{Locale, Date}
//import elidable.CONFIG
//import java.text.SimpleDateFormat
//
//object Log {
//
//  var showLog       = false
//  var showAllocLog  = false
//
//  private lazy val logHeader = new SimpleDateFormat("[d MMM yyyy, HH:mm''ss.SSS] 'synth' - ", Locale.US)
//
//  @elidable(CONFIG) private[synth] def log(what: => String): Unit =
//    if (showLog) Console.out.println(logHeader.format(new Date()) + what)
//
//  @elidable(CONFIG) private[synth] def logAlloc(what: => String): Unit =
//    if (showAllocLog) Console.out.println(logHeader.format(new Date()) + "block " + what)
//}
