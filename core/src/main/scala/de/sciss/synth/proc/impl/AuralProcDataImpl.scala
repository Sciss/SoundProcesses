/*
 *  AuralProcDataImpl.scala
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
package impl

import de.sciss.file._
import de.sciss.lucre.artifact.Artifact
import de.sciss.lucre.expr.{BooleanObj, DoubleObj, DoubleVector, IntObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.synth.{AudioBus, Buffer, Bus, BusNodeSetter, NodeRef, Sys}
import de.sciss.model.Change
import de.sciss.numbers
import de.sciss.synth.Curve.parametric
import de.sciss.synth.io.AudioFileType
import de.sciss.synth.proc.AuralObj.{Playing, ProcData}
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.UGenGraphBuilder.{Complete, Incomplete, MissingIn}
import de.sciss.synth.proc.graph.impl.ActionResponder
import de.sciss.synth.proc.{UGenGraphBuilder => UGB, logAural => logA}
import de.sciss.synth.{ControlSet, SynthGraph}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TMap, TSet, TxnLocal}

object AuralProcDataImpl {
  def apply[S <: Sys[S]](proc: Proc[S])
                        (implicit tx: S#Tx, context: AuralContext[S]): AuralObj.ProcData[S] =
    context.acquire[AuralObj.ProcData[S]](proc)(new Impl[S].init(proc))

  class Impl[S <: Sys[S]](implicit val context: AuralContext[S])
    extends ProcData[S] with UGB.Context[S] {

    import context.server

    private val stateRef      = Ref.make[UGB.State[S]]()

    // running main synths
    private val nodeRef       = Ref(Option.empty[NodeRef.Group])

    // running attribute inputs
    private val attrMap       = TMap.empty[String, Disposable[S#Tx]]

    private val scanBuses     = TMap.empty[String, AudioBus]
// SCAN
//    private val scanInViews   = TMap.empty[String, AuralScan.Owned[S]]
    private val scanOutViews  = TMap.empty[String, AuralOutput.Owned[S]]
    private val procViews     = TSet.empty[AuralObj.Proc[S]]

    private val procLoc       = TxnLocal[Proc[S]]() // cache-only purpose

    private var observers     = List.empty[Disposable[S#Tx]]

    private var _obj: stm.Source[S#Tx, Proc[S]] = _

    final def obj = _obj

    override def toString = s"AuralObj.ProcData@${hashCode().toHexString}"

    /** Sub-classes may override this if invoking the super-method. */
    def init(proc: Proc[S])(implicit tx: S#Tx): this.type = {
      _obj = tx.newHandle(proc)
      val ugenInit = UGB.init(proc)
      stateRef.set(ugenInit)(tx.peer)

      observers ::= proc.changed.react { implicit tx => upd =>
        upd.changes.foreach {
          case Proc.GraphChange(Change(_, newGraph))  => newSynthGraph(newGraph)
// SCAN
//          case Proc.InputAdded   (key, scan)          => scanInAdded (key, scan)
          case Proc.OutputAdded  (scan)          => scanOutAdded(scan)
// SCAN
//          case Proc.InputRemoved (key, scan)          => scanRemoved (key, scan)
          case Proc.OutputRemoved(scan)          => scanOutRemoved(scan)
// ELEM
// case Proc.InputChange  (key, scan, sCh)     => scanInChange(key, scan, sCh)
// case Proc.OutputChange (key, scan, sCh)     => // nada
        }
      }
      val attr = proc.attr
      observers ::= attr.changed.react { implicit tx => upd => upd.changes.foreach {
        case Obj.AttrAdded  (key, value) => attrAdded  (key, value)
        case Obj.AttrRemoved(key, value) => attrRemoved(key, value)
      }}

//      // XXX TODO -- should filter only relevant values
//      attr.iterator.foreach { case (key, value) =>
//        mkAttrObserver(key, value)
//      }

      tryBuild()
      this
    }

    final def nodeOption(implicit tx: S#Tx): Option[NodeRef] = nodeRef.get(tx.peer)

// SCAN
//    final def getScanIn(key: String)(implicit tx: S#Tx): Option[Either[AudioBus, AuralScan[S]]] =
//      getScan(key, scanInViews)
//
//    final def getScanOut(key: String)(implicit tx: S#Tx): Option[Either[AudioBus, AuralScan[S]]] =
//      getScan(key, scanOutViews)

// SCAN
//    private[this] def getScan(key: String, views: TMap[String, AuralScan.Owned[S]])
//                             (implicit tx: S#Tx): Option[Either[AudioBus, AuralScan[S]]] = {
//      implicit val itx = tx.peer
//      views.get(key).fold[Option[Either[AudioBus, AuralScan[S]]]] {
//        scanBuses.get(key).map(Left(_))
//      } { v =>
//        Some(Right(v))
//      }
//    }

    private def playScans(n: NodeRef)(implicit tx: S#Tx): Unit = {
      logA(s"playScans ${procCached()}")

// SCAN
//      scanInViews.foreach { case (_, view) =>
//        view.play(n)
//      }(tx.peer)
//
//      scanOutViews.foreach { case (_, view) =>
//        view.play(n)
//      }(tx.peer)
    }

    private def newSynthGraph(g: SynthGraph)(implicit tx: S#Tx): Unit = {
      logA(s"newSynthGraph ${procCached()}")
      implicit val itx = tx.peer

      // stop and dispose all
      procViews.foreach { view =>
        if (view.state == AuralObj.Playing) view.stopForRebuild()
      }
      disposeNodeRefAndScans()
      disposeAttrMap()

      // then try to rebuild the stuff
      val ugenInit = UGB.init(procCached())
      stateRef() = ugenInit
      tryBuild() // this will re-start the temporarily stopped views if possible
    }

    // ---- scan events ----

// SCAN
//    private def scanInAdded(key: String, scan: Scan[S])(implicit tx: S#Tx): Unit = {
//      logA(s"scanInAdded  to   ${procCached()} ($key)")
//      testInScan (key, scan)
//    }

    private def scanOutAdded(scan: Output[S])(implicit tx: S#Tx): Unit = {
      logA(s"scanOutAdded  to   ${procCached()} (${scan.key})")
      testOutScan(scan)
    }

    private def scanOutRemoved(scan: Output[S])(implicit tx: S#Tx): Unit = {
      logA(s"scanOutRemoved from ${procCached()} (${scan.key})")
    }

// SCAN
//    private def scanInChange(key: String, scan: Scan[S], changes: Vec[Scan.Change[S]])(implicit tx: S#Tx): Unit = {
//      logA(s"scanInChange in   ${procCached()} ($key)")
//      changes.foreach {
//        case Scan.Added(_) => testInScan(key, scan)
//        case _ =>
//      }
//    }

    // ---- attr events ----

    private def attrAdded(key: String, value: Obj[S])(implicit tx: S#Tx): Unit = {
      logA(s"AttrAdded   to   ${procCached()} ($key)")
      val st    = state
      val aKey  = UGB.AttributeKey(key)
      if (!st.rejectedInputs.contains(aKey) && !st.acceptedInputs.contains(aKey)) return

      mkAttrObserver(key, value)

      if (st.isComplete) {
        for {
          n <- nodeRef.get(tx.peer)
          v <- st.acceptedInputs.get(aKey)
        } attrNodeSet1(n, key, v, value)
      }
    }

    private def addUsedAttr(attr: Obj.AttrMap[S], key: String)(implicit tx: S#Tx): Unit =
      attr.get(key).foreach { value => mkAttrObserver(key, value) }

    private def mkAttrObserver(key: String, value: Obj[S])(implicit tx: S#Tx): Unit = {
      val obs = value.changed.react { implicit tx => _ => attrChange(key) }
      attrMap.put(key, obs)(tx.peer)
    }

    private def attrRemoved(key: String, value: Obj[S])(implicit tx: S#Tx): Unit = {
      logA(s"AttrRemoved from ${procCached()} ($key)")
      for {
        n <- nodeRef.get(tx.peer)
        _ <- state.acceptedInputs.get(UGB.AttributeKey(key))
      } attrNodeUnset1(n, key)

      attrMap.remove(key)(tx.peer)
    }

    private def attrChange(key: String)(implicit tx: S#Tx): Unit = {
      logA(s"AttrChange in   ${procCached()} ($key)")
      for {
        n <- nodeRef.get(tx.peer)
        v <- state.acceptedInputs.get(UGB.AttributeKey(key))
      } {
        attrNodeUnset1(n, key)
        procCached().attr.get(key).foreach { value =>
          attrNodeSet1(n, key, v, value)
        }
      }
    }

    private def attrNodeUnset1(n: NodeRef.Full, key: String)(implicit tx: S#Tx): Unit =
      n.removeAttrResources(key)

    private def attrNodeSet1(n: NodeRef.Full, key: String, assigned: UGB.Value, value: Obj[S])
                            (implicit tx: S#Tx): Unit = {
      val b = new SynthUpdater(procCached(), n.node, key, n)
      buildAttrInput(b, key, assigned)
      b.finish()
    }

    // ----

    private def addUsedOutput(key: String, numChannels: Int)(implicit tx: S#Tx): Unit = {
      ???
    }

// SCAN
//    // if a scan was added or a source was added to an existing scan,
//    // check if the scan is used as currently missing input. if so,
//    // try to build the ugen graph again.
//    private def testInScan(key: String, scan: Scan[S])(implicit tx: S#Tx): Unit = {
//      if (state.rejectedInputs.contains(UGB.ScanKey(key))) {
//        val numCh = scanInNumChannels(scan)
//        // println(s"testInScan($key) -> numCh = $numCh")
//        if (numCh >= 0) {
//          // the scan is ready to be used and was missing before
//          tryBuild()
//        } else {
//          addIncompleteScanIn(key, scan)
//        }
//      }
//    }

// SCAN
//    // incomplete scan ins are scan ins which do exist and are used by
//    // the ugen graph, but their source is not yet available. in order
//    // for them to be detected when a sink is added, the sink must
//    // be able to find a reference to them via the context's auxiliary
//    // map. we thus store the special proxy sub-type `Incomplete` in
//    // there; when the sink finds it, it will invoke `sinkAdded`,
//    // in turn causing the data object to try to rebuild the ugen graph.
//    private def addIncompleteScanIn(key: String, scan: Scan[S])(implicit tx: S#Tx): Unit =
//      context.putAux[AuralScan.Proxy[S]](scan.id, new AuralScan.Incomplete(this, key))

// SCAN
//    // called from scan-view if source is not materialized yet
//    final def sinkAdded(key: String, view: AuralScan[S])(implicit tx: S#Tx): Unit =
//      if (state.rejectedInputs.contains(UGenGraphBuilder.ScanKey(key))) tryBuild()

    private def testOutScan(scan: Output[S])(implicit tx: S#Tx): Unit = {
      val key = scan.key
      state.outputs.get(key).foreach { numCh =>
        scanOutViews.get(key)(tx.peer).fold[Unit] {
          mkAuralOutput(scan, numCh)
        } { view =>
          checkScanNumChannels(view, numCh)
        }
      }
    }

    final def addInstanceNode(n: NodeRef.Full)(implicit tx: S#Tx): Unit = {
      logA(s"addInstanceNode ${procCached()} : $n")
      implicit val itx = tx.peer
      nodeRef().fold {
        val groupImpl = NodeRef.Group(name = s"Group-NodeRef ${procCached()}", in0 = n)
        nodeRef() = Some(groupImpl)
        playScans(groupImpl)

      } { groupImpl =>
        groupImpl.addInstanceNode(n)
      }
    }

    final def removeInstanceNode(n: NodeRef.Full)(implicit tx: S#Tx): Unit = {
      logA(s"removeInstanceNode ${procCached()} : $n")
      implicit val itx = tx.peer
      val groupImpl = nodeRef().getOrElse(sys.error(s"Removing unregistered AuralProc node instance $n"))
      if (groupImpl.removeInstanceNode(n)) {
        nodeRef() = None
// SCAN
//        scanInViews .foreach(_._2.stop   ())
//        scanOutViews.foreach(_._2.stop   ())
        disposeAttrMap()
      }
    }

    final def addInstanceView   (view: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = procViews.add   (view)(tx.peer)
    final def removeInstanceView(view: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = procViews.remove(view)(tx.peer)

    /** Sub-classes may override this if invoking the super-method. */
    def dispose()(implicit tx: S#Tx): Unit = {
      observers.foreach(_.dispose())
      disposeNodeRefAndScans()
      disposeAttrMap()
    }

    private def disposeAttrMap()(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      attrMap  .foreach(_._2.dispose())
      attrMap  .clear()
    }

    private def disposeNodeRefAndScans()(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      nodeRef  .swap(None).foreach(_.dispose())
// SCAN
//      scanInViews.foreach(_._2.dispose())
//      scanInViews.clear()
//      scanOutViews.foreach(_._2.dispose())
//      scanOutViews.clear()
      scanBuses.clear()
      val rj = stateRef().rejectedInputs
      if (rj.nonEmpty) {
// SCAN
//        val scans = procCached().inputs
//        rj.foreach {
//          case UGB.ScanKey(key) =>
//            scans.get(key).foreach { scan =>
//              context.removeAux(scan.id)
//            }
//          case _ =>
//        }
      }
    }

    final def state(implicit tx: S#Tx): UGB.State[S] = stateRef.get(tx.peer)

    /* If the ugen graph is incomplete, tries to (incrementally)
     * build it. Calls `buildAdvanced` with the old and new
     * state then.
     */
    final def tryBuild()(implicit tx: S#Tx): Unit = {
      state match {
        case s0: Incomplete[S] =>
          logA(s"try build ${procCached()} - ${procCached().name}")
          val s1 = s0.retry(this)
          stateRef.set(s1)(tx.peer)
          buildAdvanced(before = s0, now = s1)

        case s0: Complete[S] => // nada
      }
    }

    /* Called after invoking `retry` on the ugen graph builder.
     * The methods looks for new scan-ins and scan-outs used by
     * the ugen graph, and creates aural-scans for them, or
     * at least the bus-proxies if no matching entries exist
     * in the proc's `scans` dictionary.
     *
     * If the now-state indicates that the ugen-graph is complete,
     * it calls `play` on the proc-views whose target-state is to play.
     */
    private def buildAdvanced(before: UGB.State[S], now: UGB.State[S])(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer

      lazy val attr = procCached().attr

      // handle newly rejected inputs
      if (now.rejectedInputs.isEmpty) {
        logA(s"buildAdvanced ${procCached()}; complete? ${now.isComplete}")
      } else {
        logA(s"buildAdvanced ${procCached()}; rejectedInputs = ${now.rejectedInputs.mkString(",")}")
        val newRejected = now.rejectedInputs diff before.rejectedInputs
        if (newRejected.nonEmpty) {
          newRejected.foreach {
            case UGB.AttributeKey(key) => addUsedAttr(attr, key)
            case _ =>
          }
        }
      }

      // handle newly visible outputs
      if (before.outputs ne now.outputs) {
        // detect which new scan outputs have been determined in the last iteration
        // (newOuts is a map from `name: String` to `numChannels Int`)
        val newOuts = now.outputs.filterNot {
          case (key, _) => before.outputs.contains(key)
        }
        logA(s"...newOuts = ${newOuts.mkString(",")}")

        newOuts.foreach { case (key, numCh) =>
          addUsedOutput(key, numCh)
// SCAN
//          activateAuralScanOut(key, numCh)
        }
      }

      // handle newly visible inputs
      if (before.acceptedInputs ne now.acceptedInputs) {
        val newIns = now.acceptedInputs.filterNot {
          case (key, _) => before.acceptedInputs.contains(key)
        }
        logA(s"...newIns  = ${newIns.mkString(",")}")

        newIns.foreach {
          case (UGB.AttributeKey(key), _) =>
            addUsedAttr(attr, key)
// SCAN
//          case (UGB.ScanKey(key), UGB.Input.Scan.Value(numCh)) =>
//            activateAuralScanIn(key, numCh)
          case _ =>
        }
      }

      if (now.isComplete) {
        procViews.foreach { view =>
          if (view.targetState == Playing) {
            // ugen graph became ready and view wishes to play.
            view.playAfterRebuild()
          }
        }
      }
    }

// SCAN
//    /* Ensures that an aural-scan for a given key exists. If it exists,
//     * checks that the number of channels is correct. Otherwise, checks
//     * if a scan for the key exists. If yes, instantiates the aural-scan,
//     * if no, creates an audio-bus for later use.
//     */
//    private[this] def activateAuralScanIn(key: String, numChannels: Int)(implicit tx: S#Tx): Unit =
//      activateAuralScan(key = key, numChannels = numChannels, isInput = true )
//
//    private[this] def activateAuralScanOut(key: String, numChannels: Int)(implicit tx: S#Tx): Unit =
//      activateAuralScan(key = key, numChannels = numChannels, isInput = false)

// SCAN
//    /* Ensures that an aural-scan for a given key exists. If it exists,
//     * checks that the number of channels is correct. Otherwise, checks
//     * if a scan for the key exists. If yes, instantiates the aural-scan,
//     * if no, creates an audio-bus for later use.
//     */
//    private[this] def activateAuralScan(key: String, numChannels: Int, isInput: Boolean)
//                                       (implicit tx: S#Tx): Unit = {
//      val views = if (isInput) scanInViews else scanOutViews
//      views.get(key)(tx.peer).fold {
//        val proc  = procCached()
//// SCAN
//require (!isInput)
//        val scans = /* SCAN if (isInput) proc.inputs else */ proc.outputs
//        scans.get(key).fold[Unit] {
//          mkBus(key, numChannels)
//        } { scan =>
//          mkAuralScan(scan = scan, numChannels = numChannels, isInput = isInput)
//        }
//      } { view =>
//        checkScanNumChannels(view, numChannels = numChannels)
//      }
//    }

    /* Creates a bus for the given scan, unless it already exists.
     * Existing buses are checked for consistency with the given
     * number-of-channels (throws an exception upon discrepancy).
     */
    private def mkBus(key: String, numChannels: Int)(implicit tx: S#Tx): AudioBus = {
      implicit val itx = tx.peer
      val bus = scanBuses.get(key).getOrElse {
        val res = Bus.audio(context.server, numChannels = numChannels)
        scanBuses.put(key, res)
        res
      }
      if (bus.numChannels != numChannels)
        sys.error(s"Scan bus channels changed from ${bus.numChannels} to $numChannels")

      bus
    }

    /* Creates a new aural output */
    private def mkAuralOutput(output: Output[S], numChannels: Int)(implicit tx: S#Tx): AuralScan[S] = {
      val key   = output.key
      val bus   = mkBus(key, numChannels)
      val views = scanOutViews
      val view  = AuralOutput(data = this, output = output, bus = bus)
      views.put(key, view)(tx.peer)
      // note: the view will iterate over the
      //       sources and sinks itself upon initialization,
      //       and establish the playing links if found
      //
      //      nodeOption.foreach { n =>
      //        view.play(n)
      //      }
      view
    }

// SCAN
//    /* Creates a new aural scan */
//    private def mkAuralScan(scan: Output[S], numChannels: Int, isInput: Boolean)
//                           (implicit tx: S#Tx): AuralScan[S] = {
//      val key   = scan.key
//      val bus   = mkBus(key, numChannels)
//      val views = if (isInput) scanInViews else scanOutViews
//      val view  = ??? : AuralScan.Owned[S] // SCAN AuralScan(data = this, key = key, scan = scan, bus = bus, isInput = isInput)
//      views.put(key, view)(tx.peer)
//      // note: the view will iterate over the
//      //       sources and sinks itself upon initialization,
//      //       and establish the playing links if found
//      //
//      //      nodeOption.foreach { n =>
//      //        view.play(n)
//      //      }
//      view
//    }

    private def checkScanNumChannels(view: AuralScan[S], numChannels: Int): Unit = {
      val numCh1 = view.bus.numChannels
      if (numCh1 != numChannels) sys.error(s"Trying to access scan with competing numChannels ($numCh1, $numChannels)")
    }

    //    def scanInBusChanged(sinkKey: String, bus: AudioBus)(implicit tx: S#Tx): Unit = {
    //      if (state.missingIns.contains(sinkKey)) tryBuild()
    //    }

    // def getScanBus(key: String)(implicit tx: S#Tx): Option[AudioBus] = scanViews.get(key)(tx.peer).map(_.bus)

    /** Sub-classes may override this if invoking the super-method. */
    def requestInput[Res](in: UGB.Input { type Value = Res }, st: Incomplete[S])
                         (implicit tx: S#Tx): Res = in match {
      case i: UGB.Input.Attribute =>
        val found  = requestAttrNumChannels(i.name)
        val reqNum = i.numChannels
        if (found >= 0 && reqNum >= 0 && found != reqNum)
          throw new IllegalStateException(s"Attribute ${i.name} requires $reqNum channels (found $found)")
        val res = if (found >= 0) found else if (reqNum >= 0) reqNum else 1
        UGB.Input.Attribute.Value(res)

// SCAN
//      case i: UGB.Input.Scan =>
//        UGB.Input.Scan.Value(requestScanInNumChannels(i))

      case i: UGB.Input.Stream =>
        val numCh0  = requestAttrNumChannels(i.name)
        val numCh   = if (numCh0 < 0) 1 else numCh0     // simply default to 1
        val newSpecs0 = st.acceptedInputs.get(i.key) match {
          case Some(v: UGB.Input.Stream.Value)  => v.specs
          case _                                => Nil
        }
        val newSpecs = if (i.spec.isEmpty) newSpecs0 else {
          i.spec :: newSpecs0
        }
        UGB.Input.Stream.Value(numChannels = numCh, specs = newSpecs)

      case i: UGB.Input.Buffer =>
        val procObj = procCached()
        val (numFr, numCh) = procObj.attr.get(i.name).fold((-1L, -1)) {
          case a: DoubleVector[S] =>
            val v = a.value   // XXX TODO: would be better to write a.peer.size.value
            (v.size.toLong, 1)
          case a: Grapheme.Expr.Audio[S] =>
            // val spec = a.spec
            val spec = a.value.spec
            (spec.numFrames, spec.numChannels)

          case _ => (-1L, -1)
        }
        if (numCh < 0) throw MissingIn(i)
        // larger files are asynchronously prepared, smaller ones read on the fly
        val async = (numCh * numFr) > UGB.Input.Buffer.AsyncThreshold   // XXX TODO - that threshold should be configurable
        UGB.Input.Buffer.Value(numFrames = numFr, numChannels = numCh, async = async)

      case i: UGB.Input.Action  => UGB.Input.Action .Value
      case i: UGB.Input.DiskOut => UGB.Input.DiskOut.Value(i.numChannels)

      case _ => throw new IllegalStateException(s"Unsupported input request $in")
    }

    final def getScanBus(key: String)(implicit tx: S#Tx): Option[AudioBus] = scanBuses.get(key)(tx.peer)

    final def procCached()(implicit tx: S#Tx): Proc[S] = {
      implicit val itx = tx.peer
      if (procLoc.isInitialized) procLoc.get
      else {
        val proc = obj()
        procLoc.set(proc)
        proc
      }
    }

// SCAN
//    private def scanView(scan: Scan[S])(implicit tx: S#Tx): Option[AuralScan[S]] =
//      context.getAux[AuralScan.Proxy[S]](scan.id) match {
//        case Some(view: AuralScan[S]) => Some(view)
//        case _                        => None
//      }

    private def requestAttrNumChannels(key: String)(implicit tx: S#Tx): Int = {
      val procObj = procCached()
      procObj.attr.get(key).fold(-1) {
        case a: DoubleVector[S] => a.value.size // XXX TODO: would be better to write a.peer.size.value
        case a: Grapheme.Expr.Audio [S] =>
          // a.spec.numChannels
          a.value.spec.numChannels
        case _: FadeSpec.Obj       [S] => 4
//        case a: Scan[S] =>
//          scanView(a).getOrElse(throw new MissingIn())
//          requestScanInNumChannels(i)
        case _ => -1
      }
    }

// SCAN
//    private def requestScanInNumChannels(req: UGenGraphBuilder.Input.Scan)(implicit tx: S#Tx): Int = {
//      val procObj = procCached()    /** Sub-classes may override this if invoking the super-method. */
//
//      val proc    = procObj
//// SCAN
//      val numCh0  = ??? : Int // proc.inputs.get(req.name).fold(-1)(scanInNumChannels)
//      val numCh   = if (numCh0 == -1) req.fixed else numCh0
//      if (numCh == -1) throw MissingIn(req) else numCh
//    }

// SCAN
//    private def scanInNumChannels(scan: Scan[S])(implicit tx: S#Tx): Int = {
//      val chans = scan.iterator.toList.map {
//        case Link.Grapheme(peer) =>
//          // val chansOpt = peer.valueAt(time).map(_.numChannels)
//          // chansOpt.getOrElse(numChannels)
//          peer.numChannels
//
//        case Link.Scan(peer) =>
//          val sourceOpt = scanView(peer)
//          sourceOpt.fold(-1) { sourceView =>
//            // val sourceObj = sourceObjH()
//            // getOutputBus(sourceObj, sourceKey)
//            sourceView.bus.numChannels // data.state.scanOuts.get(sourceView.key)
//          }
//      }
//      if (chans.isEmpty) -1 else chans.max
//    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    /** Sub-classes may override this if invoking the super-method. */
    protected def buildAttrValueInput(b: NodeDependencyBuilder[S], key: String, value: Obj[S], numChannels: Int)
                                     (implicit tx: S#Tx): Unit = {
      val ctlName = graph.Attribute.controlName(key)

      def setControl(value: Float): Unit =
        b.addControl(if (numChannels == 1) ctlName -> value else ctlName -> Vector.fill(numChannels)(value))

      def chanCheck(expected: Int): Unit =
        if (numChannels != expected)
          sys.error(s"Mismatch: Attribute $key has $numChannels channels, expected $expected")

      value match {
        case a: IntObj[S] =>
          setControl(a.value)
        case a: DoubleObj[S] =>
          setControl(a.value.toFloat)
        case a: BooleanObj[S] =>
          setControl(if (a.value) 1f else 0f)
        case a: FadeSpec.Obj[S] =>
          val spec = a.value
          // dur, shape-id, shape-curvature, floor
          val values = Vec(
            (spec.numFrames / Timeline.SampleRate).toFloat, spec.curve.id.toFloat, spec.curve match {
              case parametric(c)  => c
              case _              => 0f
            }, spec.floor
          )
          chanCheck(values.size)
          b.addControl(ctlName -> values)

        case a: DoubleVector[S] =>
          val values = a.value.map(_.toFloat)
          chanCheck(values.size)
          b.addControl(ctlName -> values)

        case a: Grapheme.Expr.Audio[S] =>
          val ctlName   = graph.Attribute.controlName(key)
          val audioVal  = a.value
          val spec      = audioVal.spec
          if (spec.numFrames != 1)
            sys.error(s"Audio grapheme $a must have exactly 1 frame to be used as scalar attribute")
          val numCh = spec.numChannels // numChL.toInt
          if (numCh > 4096) sys.error(s"Audio grapheme size ($numCh) must be <= 4096 to be used as scalar attribute")
          chanCheck(numCh)
          val bus = Bus.control(server, numCh)
          val res = BusNodeSetter.mapper(ctlName, bus, b.node)
          b.addUser(res)
          val w = AudioArtifactScalarWriter(bus, audioVal)
          b.addResource(w)


// SCAN
//        case a: Scan[S] =>
//          scanView(a).fold[Unit] {
//            Console.err.println(s"Warning: view for scan $a used as attribute key $key not found.")
//            // XXX TODO --- this is big ugly hack
//            // in order to allow the concurrent appearance
//            // of the source and sink procs.
//            context.waitForAux[AuralScan.Proxy[S]](a.id) {
//              case view: AuralScan[S] =>
//                nodeRef.get(tx.peer).foreach { n =>
//                  Console.err.println(s"...phew, view for scan $a used as attribute key $key appeared.")
//                  val b = new SynthUpdater(procCached(), node = n.node, key = key, nodeRef = n)
//                  // NOTE: because waitForAux is guaranteed to happen within
//                  // the same transaction, we can re-use `a` without handle!
//                  buildAttrValueInput(b, key = key, value = a, numChannels = numChannels)
//                  b.finish()
//                }
//
//              case _ =>
//            }
//
//          } { view =>
//            val bus = view.bus
//            chanCheck(bus.numChannels)
//            val res = BusNodeSetter.mapper(ctlName, bus, b.node)
//            b.addUser(res)
//            // XXX TODO:
//            // - adapt number-of-channels if they don't match (using auxiliary synth)
//          }

        case _ =>
          sys.error(s"Cannot use attribute $value as a scalar value")
      }
    }

    /** Sub-classes may override this if invoking the super-method. */
    def buildAttrInput(b: NodeDependencyBuilder[S], key: String, value: UGB.Value)
                      (implicit tx: S#Tx): Unit = {
      value match {
        case UGB.Input.Attribute.Value(numChannels) =>  // --------------------- scalar
          b.obj.attr.get(key).foreach { a =>
            buildAttrValueInput(b, key, a, numChannels = numChannels)
          }

        case UGB.Input.Stream.Value(numChannels, specs) =>  // ------------------ streaming
          val infoSeq = if (specs.isEmpty) UGB.Input.Stream.EmptySpec :: Nil else specs

          infoSeq.zipWithIndex.foreach { case (info, idx) =>
            val ctlName     = graph.impl.Stream.controlName(key, idx)
            val bufSize     = if (info.isEmpty) server.config.blockSize else {
              val maxSpeed  = if (info.maxSpeed <= 0.0) 1.0 else info.maxSpeed
              val bufDur    = 1.5 * maxSpeed
              val minSz     = (2 * server.config.blockSize * math.max(1.0, maxSpeed)).toInt
              val bestSz    = math.max(minSz, (bufDur * server.sampleRate).toInt)
              import numbers.Implicits._
              val bestSzHi  = bestSz.nextPowerOfTwo
              val bestSzLo  = bestSzHi >> 1
              if (bestSzHi.toDouble/bestSz < bestSz.toDouble/bestSzLo) bestSzHi else bestSzLo
            }
            val (rb, gain) = b.obj.attr.get(key).fold[(Buffer, Float)] {
              // DiskIn and VDiskIn are fine with an empty non-streaming buffer, as far as I can tell...
              // So instead of aborting when the attribute is not set, fall back to zero
              val _buf = Buffer(server)(numFrames = bufSize, numChannels = 1)
              (_buf, 0f)
            } {
              case a: Grapheme.Expr.Audio[S] =>
                val audioVal  = a.value
                val spec      = audioVal.spec
                val path      = audioVal.artifact.getAbsolutePath
                val offset    = audioVal.offset
                val _gain     = audioVal.gain
                val _buf      = if (info.isNative) {
                  // XXX DIRTY HACK
                  val offset1 = if (key.contains("!rnd")) {
                    offset + (math.random * (spec.numFrames - offset)).toLong
                  } else {
                    offset
                  }
                  // println(s"OFFSET = $offset1")
                  Buffer.diskIn(server)(
                    path          = path,
                    startFrame    = offset1,
                    numFrames     = bufSize,
                    numChannels   = spec.numChannels
                  )
                } else {
                  val __buf = Buffer(server)(numFrames = bufSize, numChannels = spec.numChannels)
                  val trig = new StreamBuffer(key = key, idx = idx, synth = b.node, buf = __buf, path = path,
                    fileFrames = spec.numFrames, interp = info.interp, startFrame = offset, loop = false,
                    resetFrame = offset)
                  b.addUser(trig)
                  __buf
                }
                (_buf, _gain.toFloat)

              case a => sys.error(s"Cannot use attribute $a as an audio stream")
            }
            b.addControl(ctlName -> Seq[Float](rb.id, gain): ControlSet)
            b.addResource(rb)
          }

        case UGB.Input.Buffer.Value(numFr, numCh, false) =>   // ----------------------- random access buffer
          val rb = b.obj.attr.get(key).fold[Buffer] {
            sys.error(s"Missing attribute $key for buffer content")
          } {
            case a: Grapheme.Expr.Audio[S] =>
              val audioVal  = a.value
              val spec      = audioVal.spec
              val path      = audioVal.artifact.getAbsolutePath
              val offset    = audioVal.offset
              // XXX TODO - for now, gain is ignored.
              // one might add an auxiliary control proxy e.g. Buffer(...).gain
              // val _gain     = audioElem.gain    .value
              if (spec.numFrames > 0x3FFFFFFF)
                sys.error(s"File too large for in-memory buffer: $path (${spec.numFrames} frames)")
              val bufSize   = spec.numFrames.toInt
              val _buf      = Buffer(server)(numFrames = bufSize, numChannels = spec.numChannels)
              _buf.read(path = path, fileStartFrame = offset)
              _buf

            case a => sys.error(s"Cannot use attribute $a as a buffer content")
          }
          val ctlName    = graph.Buffer.controlName(key)
          b.addControl(ctlName -> rb.id)
          b.addResource(rb)

        case UGB.Input.Action.Value =>   // ----------------------- action
          val resp = new ActionResponder(objH = tx.newHandle(b.obj), key = key, synth = b.node)
          b.addUser(resp)

        case UGB.Input.DiskOut.Value(numCh) =>
          val rb = b.obj.attr.get(key).fold[Buffer] {
            sys.error(s"Missing attribute $key for disk-out artifact")
          } {
            case a: Artifact[S] =>
              val artifact  = a
              val f         = artifact.value.absolute
              val ext       = f.ext.toLowerCase
              val tpe       = AudioFileType.writable.find(_.extensions.contains(ext)).getOrElse(AudioFileType.AIFF)
              val _buf      = Buffer.diskOut(server)(path = f.path, fileType = tpe, numChannels = numCh)
              _buf

            case a => sys.error(s"Cannot use attribute $a as an artifact")
          }
          val ctlName    = graph.DiskOut.controlName(key)
          b.addControl(ctlName -> rb.id)
          b.addResource(rb)

        case _ =>
          throw new IllegalStateException(s"Unsupported input attribute request $value")
      }
    }
  }
}
