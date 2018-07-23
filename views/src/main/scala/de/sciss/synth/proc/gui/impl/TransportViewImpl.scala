/*
 *  TransportViewImpl.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2018 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package gui
package impl

import java.awt.event.{ActionEvent, ActionListener, InputEvent}
import javax.swing.event.{ChangeEvent, ChangeListener}
import javax.swing.{AbstractAction, ButtonModel, JComponent, KeyStroke, Timer}

import de.sciss.audiowidgets.{TimelineModel, Transport => GUITransport}
import de.sciss.desktop.Implicits._
import de.sciss.desktop.{FocusType, KeyStrokes}
import de.sciss.lucre.stm.{Cursor, Disposable}
import de.sciss.lucre.swing.deferTx
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.span.Span

import scala.concurrent.stm.Ref
import scala.swing.Swing.HStrut
import scala.swing.event.Key
import scala.swing.{Action, BoxPanel, Component, Orientation, Swing}

object TransportViewImpl {
  def apply[S <: Sys[S]](transport: Transport[S] /* .Realtime[S, Obj.T[S, Proc.Elem], Transport.Proc.Update[S]] */,
                         model: TimelineModel, hasMillis: Boolean, hasLoop: Boolean, hasShortcuts: Boolean)
                        (implicit tx: S#Tx, cursor: Cursor[S]): TransportView[S] = {
    val view    = new Impl(transport, model)
    val srk     = 1000 / TimeRef.SampleRate // transport.sampleRate

    view.observer = transport.react { implicit tx => {
      case Transport.Play(_, time) => view.startedPlaying(time)
      case Transport.Stop(_, time) => view.stoppedPlaying(time)
      case Transport.Seek(_, time, p) =>
        if (p) view.startedPlaying(time) else view.stoppedPlaying(time)
      case _ =>
    }}

    val initPlaying = transport.isPlaying // .playing.value
    val initMillis = (transport.position * srk).toLong
    deferTx {
      view.guiInit(initPlaying, initMillis, hasMillis = hasMillis, hasLoop = hasLoop, hasShortcuts = hasShortcuts)
    }
    view
  }

//  private final class Foo(m: ButtonModel, dir: Int) extends ChangeListener {
//    var pressed = false
//
//    def stateChanged(e: ChangeEvent): Unit = {
//      val p = m.isPressed
//      if (p != pressed) {
//        pressed = p
//        if (p) {
//          //println( "-restart" )
//          cueDirection = dir
//          cueTimer.restart()
//        } else {
//          //println( "-stop" )
//          cueTimer.stop()
//        }
//      }
//    }
//  }

  // (0 until 20).map(i => (math.sin(i.linlin(0, 20, 0, math.Pi)) * 7.20).round).scanLeft(10.0) { case (sum, i) => sum + i }
  private final val cueSteps = Array[Float](
      0.010f, 0.010f, 0.011f, 0.013f, 0.016f, 0.020f, 0.025f, 0.031f, 0.037f, 0.044f,
      0.051f, 0.058f, 0.065f, 0.072f, 0.078f, 0.084f, 0.089f, 0.093f, 0.096f, 0.098f, 0.099f)

  private final class Impl[S <: Sys[S]](val transport: Transport[S] /* .Realtime[S, Obj.T[S, Proc.Elem], Transport.Proc.Update[S]] */,
                                        val timelineModel: TimelineModel)
                                       (implicit protected val cursor: Cursor[S])
    extends TransportView[S] with ComponentHolder[Component] with CursorHolder[S] {

    type C = Component

    var observer: Disposable[S#Tx] = _

    private[this] val modOpt  = timelineModel.modifiableOption

    private[this] var playTimer   : Timer = _
    private[this] var cueTimer    : Timer = _
    private[this] var cueDirection  = 1
    private[this] var cueCount      = 0
    private[this] var cueSelect     = false

    private[this] var timerFrame  = 0L
    private[this] var timerSys    = 0L
    private[this] val srm         = 0.001 * TimeRef.SampleRate // transport.sampleRate

    private[this] var transportStrip: Component with GUITransport.ButtonStrip = _

    private[this] val loopSpan  = Ref[Span.SpanOrVoid](Span.Void)
    private[this] val loopToken = Ref(-1)

    import GUITransport.{FastForward, GoToBegin, Loop, Play, Rewind, Stop}

    private final class ActionCue(elem: GUITransport.Element, key: Key.Value, onOff: Boolean, select: Boolean)
      extends AbstractAction(elem.toString) { action =>

//      private[this] var lastWhen: Long = 0L

      private[this] val b = transportStrip.button(elem).get

      b.peer.registerKeyboardAction(this, s"${if (onOff) "start" else "stop"}-${elem.toString}",
        KeyStroke.getKeyStroke(key.id, if (select) InputEvent.SHIFT_MASK else 0, !onOff),
        JComponent.WHEN_IN_FOCUSED_WINDOW)

      private def perform(): Unit = {
        val bm = b.peer.getModel
        cueSelect = select
        if (bm.isPressed != onOff) bm.setPressed(onOff)
        if (bm.isArmed   != onOff) bm.setArmed  (onOff)
      }

      def actionPerformed(e: ActionEvent): Unit = {
        // println(s"actionPerformed ${elem.toString}; onOff = $onOff")
//        lastWhen = e.getWhen
        perform()
      }
    }

    private final class CueListener(elem: GUITransport.Element, dir: Int)
      extends ChangeListener {

      private[this] val m: ButtonModel  = transportStrip.button(elem).get.peer.getModel
      private[this] var pressed         = false
      private[this] var wasPlaying      = false

      m.addChangeListener(this)

      def stateChanged(e: ChangeEvent): Unit = {
        val p = m.isPressed
        if (p != pressed) {
          pressed = p
          if (p) {
            cueDirection = dir
            wasPlaying   = transportStrip.button(Play).exists(_.selected)
            // start at higher cue speed if direction is
            // forward and transport was playing,
            // because otherwise it appears sluggish
            cueCount     = if (wasPlaying /* && dir > 0 */) 10 else 0
            if (wasPlaying) stop()
            cueTimer.restart()
          } else {
            cueTimer.stop()
            if (wasPlaying) play()
          }
        }
      }
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      observer.dispose()
      cancelLoop()
      deferTx {
        playTimer.stop()
        cueTimer .stop()
      }
    }

    private def cancelLoop()(implicit tx: S#Tx): Unit =
      transport.scheduler.cancel(loopToken.swap(-1)(tx.peer))

    def startedPlaying(time: Long)(implicit tx: S#Tx): Unit = {
      checkLoop()
      deferTx {
        playTimer.stop()
        cueTimer .stop()
        timerFrame  = time
        timerSys    = System.currentTimeMillis()
        playTimer.start()
        transportStrip.button(Play).foreach(_.selected = true )
        transportStrip.button(Stop).foreach(_.selected = false)
      }
    }

    def stoppedPlaying(time: Long)(implicit tx: S#Tx): Unit = {
      cancelLoop()
      deferTx {
        playTimer.stop()
        // cueTimer .stop()
        modOpt.foreach(_.position = time) // XXX TODO if Cursor follows play-head
        transportStrip.button(Play).foreach(_.selected = false)
        transportStrip.button(Stop).foreach(_.selected = true )
      }
    }

    private def rtz(): Unit = {
      stop()
      modOpt.foreach { mod =>
        val start     = mod.bounds.start
        mod.position  = start
        mod.visible   = Span(start, start + mod.visible.length)
      }
    }

    private def playOrStop(): Unit = {
      // we have both visual feedback
      // and transactional control here,
      // because we want to see the buttons
      // depressed, but we also want zero
      // latency in taking action.
      val isPlaying =
        (for {
          ggStop <- transportStrip.button(Stop)
          ggPlay <- transportStrip.button(Play)
        } yield {
          val _isPlaying = ggPlay.selected
          val but = if (_isPlaying) ggStop else ggPlay
          but.doClick()
          _isPlaying
        }).getOrElse(false)

      atomic { implicit tx =>
        if (isPlaying)
          transport.stop()
        else
          playTxn()
      }
    }

    private def stop(): Unit = atomic { implicit tx =>
      transport.stop()
    }

    private def play(): Unit = atomic { implicit tx =>
      playTxn()
    }

    private def playTxn(pos: Long = timelineModel.position)(implicit tx: S#Tx): Unit =
      if (!transport.isPlaying) {
//        transport.stop()
        transport.seek(pos  )
        transport.play()
      }

    private def toggleLoop(): Unit = transportStrip.button(Loop).foreach { ggLoop =>
      val wasLooping  = ggLoop.selected
      val sel         = if (wasLooping) Span.Void else timelineModel.selection
      val isLooping   = sel.nonEmpty
      ggLoop.selected = isLooping
      atomic { implicit tx =>
        loopSpan.set(sel)(tx.peer)
        if (transport.isPlaying) {
          cancelLoop()
          checkLoop()
        }
      }
    }

    private def checkLoop()(implicit tx: S#Tx): Unit = {
      val pos       = transport.position
      val loopStop  = loopSpan.get(tx.peer) match { case hs: Span.HasStop => hs.stop; case _ => Long.MinValue }
      if (loopStop > pos) {
        val sched   = transport.scheduler
        val time    = sched.time + (loopStop - pos)
        val token   = sched.schedule(time) { implicit tx => loopEndReached() }
        val old     = loopToken.swap(token)(tx.peer)
        sched.cancel(old)
      }
    }

    private def loopEndReached()(implicit tx: S#Tx): Unit = loopSpan.get(tx.peer) match {
      case hs: Span.HasStart => playTxn(hs.start)
      case _ =>
    }

    def guiInit(initPlaying: Boolean, initMillis: Long, hasMillis: Boolean, hasLoop: Boolean,
                hasShortcuts: Boolean): Unit = {
      val timeDisplay = TimeDisplay(timelineModel, hasMillis = hasMillis)

      val actions0 = Vector(
        GoToBegin   { rtz() },
        Rewind      { () },     // handled below
        Stop        { stop() },
        Play        { play() },
        FastForward { () }      // handled below
      )
      val actions1 = if (hasLoop) actions0 :+ Loop { toggleLoop() } else actions0
      transportStrip = GUITransport.makeButtonStrip(actions1)
      val initPressed = if (initPlaying) Play else Stop
      transportStrip.button(initPressed).foreach(_.selected = true)

      val transportPane = new BoxPanel(Orientation.Horizontal) {
        contents += timeDisplay.component
        contents += HStrut(8)
        contents += transportStrip
      }

      def addClickAction(name: String, key: Key.Value, elem: GUITransport.Element): Unit =
        transportPane.addAction(name, focus = FocusType.Window, action = new Action(name) {
          accelerator = Some(KeyStrokes.plain + key)
          enabled     = modOpt.isDefined
          def apply(): Unit =
            transportStrip.button(elem).foreach(_.doClick())
        })

      if (hasShortcuts) {
        transportPane.addAction("play-stop", focus = FocusType.Window, action = new Action("play-stop") {
          accelerator = Some(KeyStrokes.plain + Key.Space)
          def apply(): Unit = playOrStop()
        })
        addClickAction("rtz" , Key.Enter, GoToBegin)
        addClickAction("loop", Key.Slash, Loop     )

        mkCue(Rewind      , Key.OpenBracket )
        mkCue(FastForward , Key.CloseBracket)
      }

      playTimer = new javax.swing.Timer(27 /* 47 */,
        Swing.ActionListener(modOpt.fold((_: ActionEvent) => ()) { mod => (_: ActionEvent) =>
          val elapsed = ((System.currentTimeMillis() - timerSys) * srm).toLong
          mod.position = timerFrame + elapsed
        })
      )

      cueTimer = new javax.swing.Timer(25 /* 63 */, new ActionListener {
        def actionPerformed(e: ActionEvent): Unit = modOpt.foreach { mod =>
          val isPlaying = atomic { implicit tx => transport.isPlaying }
          if (!isPlaying) {
            val cc = cueCount
            cueCount = cc + 1
            val inc =
//              if (cc < 14) {
//                math.max(1, cc - 4) * 10
              if (cc < 21) {
                cueSteps(cc)
              } else if (cc < 210 /* 209 */) {
                0.1f
              } else {
                0.5f
              }
            val d         = (TimeRef.SampleRate * inc * cueDirection).toLong
            val posOld    = mod.position
            val posNew    = posOld + d
            mod.position  = posNew
            if (cueSelect) {
              def mkSpan(a: Long, b: Long) = Span(math.min(a, b), math.max(a, b))

              val selNew = mod.selection match {
                case Span.Void => mkSpan(posOld, posNew)
                case Span(start, stop) =>
                  if (math.abs(posNew - start) < math.abs(posNew - stop))
                    mkSpan(stop, posNew)
                  else
                    mkSpan(start, posNew)
              }
              mod.selection = selNew
            }
          }
        }
      })

      new CueListener(Rewind     , -1)
      new CueListener(FastForward, +1)

      component = transportPane
    }

    private def mkCue(elem: GUITransport.Element, key: Key.Value): Unit = {
      new ActionCue(elem, key, onOff = false, select = false)
      new ActionCue(elem, key, onOff = true , select = false)
      new ActionCue(elem, key, onOff = false, select = true )
      new ActionCue(elem, key, onOff = true , select = true )
    }
  }
}