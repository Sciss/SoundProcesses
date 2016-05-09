/*
 *  AuralScheduledBase.scala
 *  (SoundProcesses)
 *
 *  Copyright (c) 2010-2016 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth.proc
package impl

import de.sciss.lucre.bitemp.impl.BiGroupImpl
import de.sciss.lucre.event.impl.ObservableImpl
import de.sciss.lucre.geom.LongPoint2D
import de.sciss.lucre.stm.{Disposable, Obj, TxnLike}
import de.sciss.lucre.synth.Sys
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.AuralView.{Playing, Prepared, Preparing, Stopped}
import de.sciss.synth.proc.{logAural => logA}

import scala.concurrent.stm.Ref

object AuralScheduledBase {
  final         val LOOK_AHEAD      = (1.0 * TimeRef.SampleRate).toLong  // one second. XXX TODO -- make configurable
  private final val PREP_FRAMES     = (0.5 * TimeRef.SampleRate).toLong  // XXX TODO -- make configurable
  private final val LOOK_STOP       = LOOK_AHEAD + PREP_FRAMES

  private val EmptyScheduled = new Scheduled(-1, Long.MaxValue)

  final class Scheduled(val token: Int, val offset: Long) {
    override def toString = s"[token = $token, offset = ${TimeRef.framesAndSecs(offset)}]"
    def isEmpty: Boolean = token == -1
  }

  @inline
  def spanToPoint(span: SpanLike): LongPoint2D = BiGroupImpl.spanToPoint(span)
}
trait AuralScheduledBase[S <: Sys[S], Target, Elem <: AuralView[S, Target]]
  extends ObservableImpl[S, AuralView.State] { impl =>

  import TxnLike.peer

  // ---- abstract ----

  implicit protected val context: AuralContext[S]

  /** Called during preparation of armed elements. This
    * happens either during initial `prepare` or during grid-events.
    * Given the `prepareSpan`, the sub-class should
    *
    * - find the elements using an `intersect`
    * - for each build a view and store it somewhere
    * - for each view call `prepareChild`
    * - accumulate the results of `prepareChild` into a `Map` that is returned.
    *
    * The map will become part of `IPreparing`. (NOT: The returned `Boolean` indicates
    * if elements were found (`true`) or not (`false`)).
    *
    * @param initial  if `true` this is an initial preparation which means the method
    *                 must include views that start before `prepareSpan` if their span
    *                 overlaps with `prepareSpan`. If `false` this is a follow up from
    *                 `gridReached` and the search must be restricted to views that
    *                 start no earlier than `prepareSpan`.
    */
  protected def processPrepare(prepareSpan: Span, timeRef: TimeRef, initial: Boolean)
                              (implicit tx: S#Tx): Iterator[PrepareResult]

  protected type PrepareResult = (ViewID, SpanLike, Obj[S])

  /** Called during `play`. Sub-classes should intersect
    * the current elements and for each of them call `playView`.
    */
  protected def processPlay(timeRef: TimeRef, target: Target)(implicit tx: S#Tx): Unit

  /** Called when a next interesting frame has been reached.
    * The method should look for and invoke the events such as
    * starting or stopping a view.
    */
  protected def processEvent(play: IPlaying, timeRef: TimeRef)(implicit tx: S#Tx): Unit

  /** Report the next interesting frame greater than the given frame for which
    * `eventReached` (internal) and `processEvent` will be called.
    * If no such event exists, the method must return `Long.MaxValue`.
    */
  protected def viewEventAfter(offset: Long)(implicit tx: S#Tx): Long

  /** Report the next interesting frame greater than the given frame for which
    * `gridReached` (internal) and `processPrepare` will be called.
    * If no such event exists, the method must return `Long.MaxValue`.
    */
  protected def modelEventAfter(offset: Long)(implicit tx: S#Tx): Long

  /** An opaque type passed into `playView` that may be used by an overriding implementation.
    * Otherwise it may simply be set to `Unit`.
    */
  protected type ViewID

  protected type ElemHandle

  protected def elemFromHandle(h: ElemHandle): Elem

  protected def mkView(vid: ViewID, span: SpanLike, obj: Obj[S])(implicit tx: S#Tx): ElemHandle

  protected def checkReschedule(h: ElemHandle, currentOffset: Long, oldTarget: Long, elemPlays: Boolean)
                               (implicit tx: S#Tx): Boolean

  /** Should be called from `processPlay`. It calls `play` on the view
    * and adds it to the list of playing views.
    * Note: `timeRef` must already have been updated through
    * appropriate intersection.
    *
    * Sub-classes may override this if they call `super.playView`
    */
  protected def playView(h: ElemHandle, timeRef: TimeRef.Option, target: Target)(implicit tx: S#Tx): Unit

  /** Should be called from `processEvent` for views that should be
    * stopped and disposed. The caller is responsible for removing
    * the view also from a view-tree if such structure is maintained.
    * NOT: This method ends by calling `viewRemoved`.
    */
  protected def stopView(h: ElemHandle)(implicit tx: S#Tx): Unit

  /** Stops and disposes all currently playing views. */
  protected def stopViews()(implicit tx: S#Tx): Unit

  // ---- impl ----

  import AuralScheduledBase.{EmptyScheduled, LOOK_AHEAD, LOOK_STOP, PREP_FRAMES, Scheduled}

  protected sealed trait InternalState extends Disposable[S#Tx] {
    def external: AuralView.State
  }

  protected case object IStopped extends InternalState {
    def dispose()(implicit tx: S#Tx): Unit = ()
    def external = Stopped
  }

  protected sealed trait ITimedState extends InternalState {
    def timeRef: TimeRef.Option
  }

  protected final class IPreparing(val map: Map[Elem, Disposable[S#Tx]], val timeRef: TimeRef)
    extends ITimedState {

    def copy(map: Map[Elem, Disposable[S#Tx]]): IPreparing = new IPreparing(map, timeRef)

    override def toString = s"IPreparing($map, $timeRef)"

    def dispose()(implicit tx: S#Tx): Unit = map.foreach(_._2.dispose())

    def external = if (map.isEmpty) Prepared else Preparing
  }

  protected final class IPlaying(val wallClock: Long, val timeRef: TimeRef, val target: Target)
    extends ITimedState {

    override def toString = s"IPlaying($wallClock, $timeRef, $target)"

    def shiftTo(newWallClock: Long): TimeRef = timeRef.shift(newWallClock - wallClock)

    def dispose()(implicit tx: S#Tx): Unit = ()

    def external = Playing
  }

  import context.{scheduler => sched}

  private[this] val internalRef       = Ref[InternalState](IStopped)
  private[this] val prepareSpanRef    = Ref(Span(0L, 0L))

  private[this] val schedEvtToken     = Ref(EmptyScheduled)
  private[this] val schedGridToken    = Ref(EmptyScheduled)

  final def state(implicit tx: S#Tx): AuralView.State = internalRef().external

  protected final def internalState(implicit tx: S#Tx): InternalState = internalRef()
  protected final def internalState_=(value: InternalState)(implicit tx: S#Tx): Unit = internalRef() = value

  final def targetOption(implicit tx: S#Tx): Option[Target] = internalState match {
    case p: IPlaying  => Some(p.target)
    case _            => None
  }

  // ---- utility methods for sub-types ----

  /* Should be called from `processPrepare`. If the child can be instantly
   * prepared, `None` is returned. Otherwise the child is observed until it
   * is prepared and the method returns `Some(elem, observer)` that must
   * be passed back from `processPrepare` (they'll show up in `IPreparing`).
   */
  private def prepareChild(childView: Elem, childTime: TimeRef.Option, observer: Boolean)
                          (implicit tx: S#Tx): Option[(Elem, Disposable[S#Tx])] = {
    logA(s"scheduled - prepare $childView - $childTime")
    childView.prepare(childTime)
    if (!observer || childView.state == Prepared) None else {
      val childObs = childView.react { implicit tx => {
        case Prepared => childPreparedOrRemoved(childView)
        case _        =>
      }}
      Some(childView -> childObs)
    }
  }

  // called by `prepareChild` for each child view when it becomes ready
  protected final def childPreparedOrRemoved(childView: Elem)(implicit tx: S#Tx): Unit =
    internalState match {
      case prep: IPreparing =>
        prep.map.get(childView).foreach { obs =>
          obs.dispose()
          val map1      = prep.map - childView
          val prep1     = prep.copy(map = map1)
          internalRef() = prep1
          val st = prep1.external
          if (st == Prepared) fire(Prepared)
        }
      case _ =>
    }

  // ----

  final def prepare(timeRef: TimeRef.Option)(implicit tx: S#Tx): Unit = {
    if (state != Stopped) return
    val tForce    = timeRef.force
    val newState  = prepareNoFire(tForce)
    fire(newState.external)
  }

  /* Makes sure no more nodes are playing, then calls `processPrepare`.
   * Finally sets internal state to `IPreparing` and updates `prepareSpanRef`.
   */
  private def prepareNoFire(timeRef: TimeRef)(implicit tx: S#Tx): InternalState = {
    val startFrame    = timeRef.offset
    val stopFrame     = startFrame + LOOK_STOP
    val prepareSpan   = Span(startFrame, stopFrame)

    // this can happen if `play` is called with a
    // different `timeRef` from what was previously prepared
    /* TTT if (!tree.isEmpty(iSys(tx))) */ stopViewsAndCancelSchedule()

    val it = processPrepare(prepareSpan, timeRef, initial = true)
    val async = it.flatMap { case (vid, span, obj) =>
      val (_, prep) = mkViewAndPrepare(timeRef, vid, span, obj, observer = true)
      prep
    } .toMap

    val st            = new IPreparing(async, timeRef)
    internalRef   ()  = st
    prepareSpanRef()  = prepareSpan
    st
  }

  protected final def scheduledEvent()(implicit tx: S#Tx): Scheduled = schedEvtToken ()
  protected final def scheduledGrid ()(implicit tx: S#Tx): Scheduled = schedGridToken()

  /** Note: the prepare span will always start from current-frame and have
    * a duration of at least `LOOK_STOP`. I.e. during playback it contains the current play position.
    */
  protected final def prepareSpan()(implicit tx: S#Tx): Span = prepareSpanRef()

  /** Ensures state is consistent, then checks preparation of children.
    * If all is good, sets internal state to `IPlaying` and calls `processPlay`.
    * Next instructs scheduler and sets external state to `Playing`.
    */
  final def play(timeRef: TimeRef.Option, target: Target)(implicit tx: S#Tx): Unit = {
    val st = state
    if (st == Playing) return

    val tForce    = timeRef.force
    val offset    = timeRef.offset

    if (st == Stopped || prepareSpanRef() != Span(offset, offset + LOOK_STOP)) prepareNoFire(tForce)

    val st1 = new IPlaying(sched.time, tForce, target)
    internalRef.swap(st1).dispose()

    processPlay(tForce, target)
    scheduleNextEvent(offset)
    scheduleNextGrid (offset)

    fire(Playing)
  }

  /** Calls `eventAfter` to determine the next interesting frame. If that
    * frame exists, schedules the execution of `eventReached`.
    */
  private def scheduleNextEvent(currentOffset: Long)(implicit tx: S#Tx): Unit = {
    val targetOffset = viewEventAfter(currentOffset)
    val token = if (targetOffset == Long.MaxValue) -1 else {
      import TimeRef.{framesAndSecs => fas}
      logA(s"scheduled - scheduleNextEvent(${fas(currentOffset)}) -> ${fas(targetOffset)}")
      val targetTime = sched.time + (targetOffset - currentOffset)
      sched.schedule(targetTime) { implicit tx =>
        eventReached(offset = targetOffset)
      }
    }
    val oldSched = schedEvtToken.swap(new Scheduled(token, targetOffset))
    if (oldSched.token != -1) sched.cancel(oldSched.token)
  }

  /* Processes next interesting event that has been reached. If the internal
   * state is playing, calls `processEvent` followed by `scheduleNextEvent`.
   */
  private def eventReached(offset: Long)(implicit tx: S#Tx): Unit = {
    import TimeRef.{framesAndSecs => fas}
    logA(s"scheduled - eventReached(${fas(offset)})")
    internalState match {
      case play: IPlaying =>
        val tr0 = play.timeRef
        val tr  = tr0.updateOffset(offset)
        processEvent(play, tr)
        scheduleNextEvent(offset)
      case _ =>
    }
  }

  /* Schedules ahead `STEP_GRID` frames to execute `gridReached`. */
  private def scheduleNextGrid(currentOffset: Long)(implicit tx: S#Tx): Unit = {
    val modelOffset = modelEventAfter(currentOffset + LOOK_STOP - 1)
    scheduleGrid(currentOffset = currentOffset, modelOffset = modelOffset)
  }

  /* `modelFrame` may be `Long.MaxValue` in which case the old schedule is simply cancelled. */
  private def scheduleGrid(currentOffset: Long, modelOffset: Long)(implicit tx: S#Tx): Unit = {
    val targetOffset  = if (modelOffset == Long.MaxValue) Long.MaxValue else modelOffset - LOOK_AHEAD
    val token         = if (targetOffset == Long.MaxValue) -1 else {
      val targetTime = sched.time + (targetOffset - currentOffset)
      import TimeRef.{framesAndSecs => fas}
      logA(s"scheduled - scheduleGrid(${fas(currentOffset)}, ${fas(modelOffset)}) -> ${fas(targetOffset)}")
      sched.schedule(targetTime) { implicit tx =>
        gridReached(offset = targetOffset)
      }
    }
    val oldSched = schedGridToken.swap(new Scheduled(token, targetOffset))
    if (oldSched.token != -1) sched.cancel(oldSched.token)
  }

  /* If internal state is playing, calls `processPrepare` with the new prepare-span.
   * That is `LOOK_AHEAD` ahead of the `frame` we stopped at.
   */
  private def gridReached(offset: Long)(implicit tx: S#Tx): Unit = {
    import TimeRef.{framesAndSecs => fas}
    logA(s"scheduled - gridReached(${fas(offset)})")
    internalState match {
      case play: IPlaying =>
        val startFrame      = offset      + LOOK_AHEAD
        val stopFrame       = startFrame  + PREP_FRAMES
        val prepareSpan     = Span(offset, stopFrame)
        prepareSpanRef()    = prepareSpan
        val searchSpan      = Span(startFrame, stopFrame)
        val timeRef         = play.shiftTo(sched.time)
        val it              = processPrepare(searchSpan, timeRef, initial = false)
        val reschedule      = it.nonEmpty

        it.foreach { case (vid, span, obj) =>
          mkViewAndPrepare(timeRef, vid, span, obj, observer = false)
        }

        // XXX TODO -- a refinement could look for eventAfter,
        // however then we need additional fiddling around in
        // `elemAdded` and `elemRemoved`...
        scheduleNextGrid(offset)
        if (reschedule) {
          logA("...reschedule")
          scheduleNextEvent(offset)
        }

      case _ =>
    }
  }

  final def stop()(implicit tx: S#Tx): Unit = if (state != Stopped) {
    stopViewsAndCancelSchedule()
    fire(Stopped)
  }

  /** Sub-classes may override this and call `super.dispose()`
    * if they wish to free additional observers, e.g. the
    * timeline or grapheme observer.
    */
  def dispose()(implicit tx: S#Tx): Unit =
    stopViewsAndCancelSchedule()

  /* Stops playing views and disposes them. Cancels scheduled actions.
   * Then calls `clearViewsTree`. Puts internal state to `IStopped`.
   */
  private def stopViewsAndCancelSchedule()(implicit tx: S#Tx): Unit = {
    internalRef.swap(IStopped).dispose()
    stopViews()
    sched.cancel(schedEvtToken ().token)
    sched.cancel(schedGridToken().token)
  }

  // ---- helper methods for elemAdded ----

  protected final def elemAdded(vid: ViewID, span: SpanLike, obj: Obj[S])
                               (implicit tx: S#Tx): Unit = internalState match {
      case prep: IPreparing => elemAddedPrepare(prep, vid, span, obj)
      case play: IPlaying   => elemAddedPlay   (play, vid, span, obj)
      case       IStopped   =>
    }

  protected final def elemRemoved(h: ElemHandle, elemPlays: Boolean)(implicit tx: S#Tx): Unit = {
    // val elemPlays = playingRef.contains(h)
    // remove view for the element from tree and map
    stopView(h)
    internalState match {
      case _: IPreparing =>
        val childView = elemFromHandle(h)
        childPreparedOrRemoved(childView) // might change state to `Prepared`

      case play: IPlaying =>
        // calculate current frame
        val timeRef       = play.shiftTo(sched.time)
        val currentOffset = timeRef.offset

        // re-validate the next scheduling position
        val oldSched    = scheduledEvent()
        val oldTarget   = oldSched.offset
        val reschedule  = checkReschedule(h, currentOffset = currentOffset, oldTarget = oldTarget,
                                             elemPlays = elemPlays)
        if (reschedule) {
          logA("...reschedule")
          scheduleNextEvent(currentOffset)
        }

      case _ =>
    }
  }

  private def elemAddedPrepare(prep: IPreparing, vid: ViewID, span: SpanLike, obj: Obj[S])
                              (implicit tx: S#Tx): Unit = {
    val prepS     = prepareSpan()
    if (!span.overlaps(prepS)) return

    val (childView, prepOpt) = mkViewAndPrepare(prep.timeRef, vid, span, obj, observer = true)
    prepOpt.foreach { case (_, childObs) =>
      val map1      = prep.map + (childView -> childObs)
      val prep1     = prep.copy(map = map1)
      internalState = prep1
      val st0       = prep.external
      if (st0 == Prepared) fire(Preparing)
    }
  }

  private final def mkViewAndPrepare(timeRef: TimeRef, vid: ViewID, span: SpanLike, obj: Obj[S], observer: Boolean)
                                    (implicit tx: S#Tx): (Elem, Option[(Elem, Disposable[S#Tx])]) = {
    val childTime = timeRef.child(span)
    val h         = mkView(vid, span, obj)
    val view      = elemFromHandle(h)
    val prep      = prepareChild(view, childTime, observer = observer)
    (view, prep)
  }

  private final def elemAddedPlay(play: IPlaying, vid: ViewID, span: SpanLike, obj: Obj[S])
                                 (implicit tx: S#Tx): Unit = {
    import Implicits.SpanComparisons

    // calculate current frame
    val timeRef       = play.shiftTo(sched.time)

    // if we're playing and the element span intersects contains
    // the current frame, play that new element
    val currentOffset = timeRef.offset
    val elemPlays     = span.contains(currentOffset)
    val elemPrepares  = !elemPlays && (span stopsAfter currentOffset) && (span startsBefore (currentOffset + LOOK_STOP))

    // re-validate the next scheduling position
    val schedEvt    = if (elemPlays) {
      // reschedule if the span has a stop and elem.stop < oldTarget
      span stopsBefore scheduledEvent().offset
    } else {
      // reschedule if the span has a start and that start is greater than the current frame,
      // and elem.start < oldTarget
      (span startsAfter currentOffset) && (span startsBefore scheduledEvent().offset)
    }

    val schedGrid = !elemPlays && !elemPrepares && (span startsAfter currentOffset) && {
      val oldGrid = scheduledGrid()
      oldGrid.isEmpty || (span startsBefore (oldGrid.offset + LOOK_AHEAD))
    }

    // react accordingly

    if (elemPlays) {
      val childTime = timeRef.child(span)
      val childView = mkView(vid, span, obj)
      playView(childView, childTime, play.target)
    }

    if (elemPrepares) {
      mkViewAndPrepare(timeRef, vid, span, obj, observer = false)
    }

    if (schedEvt) {
      logA("...reschedule event")
      scheduleNextEvent(currentOffset)
    }

    if (schedGrid) {
      logA("...reschedule grid")
      val Span.HasStart(modelFrame) = span
      scheduleGrid(currentOffset = currentOffset, modelOffset = modelFrame)
    }
  }
}