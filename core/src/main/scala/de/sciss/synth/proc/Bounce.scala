/*
 *  Bounce.scala
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

package de.sciss.synth.proc

import java.io.File

import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.synth.{Server, Sys}
import de.sciss.processor.ProcessorFactory
import de.sciss.span.Span
import de.sciss.synth.proc.impl.{BounceImpl => Impl}

import scala.collection.immutable.{Iterable => IIterable}
import scala.language.implicitConversions

object Bounce {
  def apply[S <: Sys[S], I <: stm.Sys[I]](implicit cursor: stm.Cursor[S], bridge: S#Tx => I#Tx,
                                          workspace: WorkspaceHandle[S]): Bounce[S] =
    new Impl[S, I]

  private type GroupH[S <: Sys[S]] = IIterable[stm.Source[S#Tx, Obj[S]]]

  sealed trait ConfigLike[S <: Sys[S]] {
    /** The group to transport through the bounce.
      * This parameter is initially unspecified in the builder, and calling the getter will throw an error.
      * This parameter must be specified before generating a `Config` instance.
      */
    def group: GroupH[S]

    /** The span of the timeline to bounce. */
    def span: Span

    /** Configuration of the offline server.
      * It is crucial to specify the NRT bits, i.e.
      * `nrtInputPath` (if used), `nrtOutputPath`,
      * `nrtHeaderFormat` (defaults to AIFF), `nrtSampleFormat` (defaults to Float32),
      * as well as the number of input and output channels
      * `inputBusChannels` and `outputBusChannels`, and finally
      * the sampling rate `sampleRate`.
      *
      * Typically you will not specify the `nrtCommandPath` (NRT OSC file). For debugging purposes this
      * may be set, otherwise a temporary file is automatically generated.
      */
    def server: Server.ConfigLike

    /** An arbitrary function may be provided which is called when the server is initialized (logical time zero).
      * This entry is typically used to set up extra routing synths, master volume, etc.
      */
    def beforePrepare: (S#Tx, Server) => Unit
    def beforePlay   : (S#Tx, Server) => Unit

    /** Whether to run the server in real-time or offline. */
    def realtime: Boolean
  }
  object Config {
    val NoOp = (_: Any, _: Any) => ()

    def apply[S <: Sys[S]]: ConfigBuilder[S] = new ConfigBuilder

    implicit def build[S <: Sys[S]](b: ConfigBuilder[S]): Config[S] = b.build
  }
  sealed trait Config[S <: Sys[S]] extends ConfigLike[S] {
    def server: Server.Config
  }
  final class ConfigBuilder[S <: Sys[S]] private[Bounce] () extends ConfigLike[S] {
    private var _group: GroupH[S] = null
    def group: GroupH[S] = {
      if (_group == null) throw new IllegalStateException("A group has not yet been assigned")
      _group
    }
    def group_=(value: GroupH[S]): Unit = _group = value

    /** The default span is from zero to one second. */
    var span  : Span                    = Span(0L, Timeline.SampleRate.toLong)
    /** The default server configuration is ScalaCollider's
      * default, a block-size of one, no input and one output channel.
      */
    val server: Server.ConfigBuilder    = Server.Config()

    var beforePrepare: (S#Tx, Server) => Unit  = Config.NoOp
    var beforePlay   : (S#Tx, Server) => Unit  = Config.NoOp

    /** The default mode is offline (realtime == `false`) */
    var realtime: Boolean = false

    // some sensible defaults
    server.blockSize          = 1
    server.inputBusChannels   = 0
    server.outputBusChannels  = 1

    def build: Config[S] = ConfigImpl(group = group, span = span, server = server,
      beforePrepare = beforePrepare, beforePlay = beforePlay, realtime = realtime)
  }

  private final case class ConfigImpl[S <: Sys[S]](group: GroupH[S], span: Span, server: Server.Config,
                                                   beforePrepare: (S#Tx, Server) => Unit,
                                                   beforePlay   : (S#Tx, Server) => Unit, realtime: Boolean)
    extends Config[S] {

    override def productPrefix = "Config"
  }

  final case class ServerFailed(code: Int) extends Exception {
    override def toString = s"$productPrefix($code)"
  }
}
trait Bounce[S <: Sys[S]] extends ProcessorFactory {
  type Product  = File
  type Config   = Bounce.Config[S]
  type Repr     = Generic
}