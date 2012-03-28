package de.sciss.synth.proc

import de.sciss.lucre.stm.InMemory
import de.sciss.synth.expr
import expr._

// for f***'s sake, `extends App` doesn't work, no method `main` found ???
object FirstTest {
   def main( args: Array[ String ]) { run() }

   def run() {
      type S = InMemory
      implicit val whyOhWhy = Proc.serializer[ S ]

      val cursor: S = InMemory()
      val access = cursor.root { implicit tx => Proc[S]() }

      cursor.step { implicit tx =>
         val p = access.get
         println( "Old name is " + p.name )
         p.name = "Schnuckendorfer"
         println( "New name is " + p.name )
      }
   }
}
