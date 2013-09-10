package de.sciss.lucre

import scala.annotation.elidable
import java.util.{Locale, Date}
import elidable.CONFIG
import java.text.SimpleDateFormat

package object synth {
  var showLog       = false
  var showAllocLog  = false

  private lazy val logHeader = new SimpleDateFormat("[d MMM yyyy, HH:mm''ss.SSS] 'synth' - ", Locale.US)

  @elidable(CONFIG) private[synth] def log(what: => String): Unit =
    if (showLog) Console.out.println(logHeader.format(new Date()) + what)

  @elidable(CONFIG) private[synth] def logAlloc(what: => String): Unit =
    if (showAllocLog) Console.out.println(logHeader.format(new Date()) + "block " + what)
}
