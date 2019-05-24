package de.sciss.lucre.expr

import de.sciss.lucre.stm.UndoManager
import de.sciss.lucre.synth.InMemory
import de.sciss.synth.proc.{ExprContext, Universe}

object DelayTest extends App {
  type S = InMemory

  val g = Graph {
    import graph._

    val b = LoadBang()
    b                 ---> PrintLn("Henlo")
    b ---> Delay(2.0) ---> PrintLn("World")
  }

  implicit val system: S = InMemory()

  system.step { implicit tx =>
    implicit val u    : Universe    [S] = Universe.dummy
    implicit val undo : UndoManager [S] = UndoManager()
    implicit val ctx  : Context     [S] = ExprContext()
    g.expand[S].initControl()
  }
}
