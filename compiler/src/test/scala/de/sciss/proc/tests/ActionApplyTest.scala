//package de.sciss.proc.tests
//
//import de.sciss.file._
//import de.sciss.lucre.expr.StringObj
//import de.sciss.lucre.stm.store.BerkeleyDB
//import de.sciss.proc.{ActionRaw, Code, Durable, MacroImplicits, SoundProcesses, Workspace}
//
//object ActionApplyTest {
//  import MacroImplicits._
//
//  /** Creates a demo workspace in `~/Documents` that we should be able to verify from Mellite. */
//  def main(args: Array[String]): Unit = {
//    SoundProcesses.init()
//
//    type S      = Durable
//    val dir     = File.createTempIn(userHome / "Documents", suffix = ".mllt", directory = true)
//    val ds      = BerkeleyDB.factory(dir)
//    val ws      = Workspace.Durable.empty(dir, ds)
//    ws.cursor.step { implicit tx =>
//      val a = ActionRaw.apply[S] { universe =>
//        // we're here!
//        val self = universe.self
//        println(s"Hello world. Self = $self - name ${self.attr.$[StringObj]("name").map(_.value)}")
//      }
//      import de.sciss.proc.Implicits._
//      a.name = "my-action"
//      ws.root.addLast(a)
//
//      println("---- Source ----")
//      println(a.attr.$[Code.Obj](ActionRaw.attrSource).map(_.value.source).getOrElse("<not found>"))
//      println("----------------")
//    }
//
//    ws.cursor.step { implicit tx => ws.dispose() }
//
//    sys.exit()
//  }
//}