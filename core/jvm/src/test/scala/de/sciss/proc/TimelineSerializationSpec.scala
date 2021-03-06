package de.sciss.proc

import de.sciss.lucre.store.BerkeleyDB
import de.sciss.lucre.{Folder, Obj}
import de.sciss.proc.Implicits._
import de.sciss.span.Span
import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers

/*
 To test only this suite:

 testOnly de.sciss.proc.TimelineSerializationSpec

 */
class TimelineSerializationSpec extends FixtureAnyFlatSpec with Matchers {
  type S            = Durable
  type T            = Durable.Txn
  type FixtureParam = Durable

  SoundProcesses.init()

  final def withFixture(test: OneArgTest): Outcome = {
    val system = Durable(BerkeleyDB.tmp())
    try {
      test(system)
    }
    finally {
      system.close()
    }
  }

  "Timeline" should "serialize and deserialize" in { system =>
    val tH = system.step { implicit tx =>
      val t = Timeline[T]()
      val p = Proc[T]()
      p.name = "Schoko"
      assert(p.name === "Schoko")
      t.add(Span(0L, 10000L), p)
      t.name = "Britzel"
      tx.newHandle(t)
    }

    val oH = system.step { implicit tx =>
      val t = tH()  // uses direct serializer
      val objects = t.intersect(0L).toList.flatMap(_._2.map(_.value))
      assert(objects.map(_.name) === List("Schoko"))
      tx.newHandle(t: Obj[T])
    }

    system.step { implicit tx =>
      val o = oH()  // uses Obj serializer
      assert(o.name === "Britzel")
    }

    val fH = system.step { implicit tx =>
      val t = tH()
      val f = Folder[T]()
      f.addLast(t)
      tx.newHandle(f)
    }

    system.step { implicit tx =>
      val f = fH()
      val o = f.last
      assert(o.isInstanceOf[Timeline[T]])
      val t = o.asInstanceOf[Timeline[T]]
      val objects = t.intersect(0L).toList.flatMap(_._2.map(_.value))
      assert(objects.map(_.name) === List("Schoko"))
    }
  }
}
