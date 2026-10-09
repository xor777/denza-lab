package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorSide.LEFT
import dev.denza.apps.feature.mirrors.MirrorSide.RIGHT
import dev.denza.apps.feature.mirrors.MirrorsPosition.CENTER
import dev.denza.apps.feature.mirrors.MirrorsPosition.SIDES
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls the camera lifecycle makes, in order and on the thread each runs on: what
 * com.byd.avc sees from this app.
 *
 * These sequences were recorded from the lifecycle as it stood when it left ClusterSceneService
 * (2026-10-09) and are the contract: a change that alters one alters what AVC sees and has to be
 * argued from docs/instrument-display-findings.md and docs/dishare-api-notes.md, not just re-recorded.
 * `renderer.start` binds AVC and leads to initDisplay and setViewpoint; `renderer.stop` is
 * freeDisplay and unbind; `removeWindow` takes our window, and with it our Surface, off the
 * camera display. An entry `x.renderer> ...` is the car answering, played by the test.
 *
 * What they do not reach: the teardown thread runs only when a test says so, so no real race
 * between main and denza-avc-teardown is explored (the completion still writes the scene's
 * teardown field from that thread, as before); and beginCameraTeardown's check against two
 * teardowns at once is unpinned, because no sequence of calls reaches it.
 */
class CameraSceneControllerTest {
    private val h = CameraSceneHarness()
    private val rec = h.rec

    // Show

    @Test
    fun aShowOpensACameraLayerAndStartsTheRendererOnIt() {
        h.show(LEFT)

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera LEFT SIDES",
                "main: camera#1.renderer.start viewpoint=3205 processing=true",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STARTING, h.runtime.phase)
        assertEquals(LEFT, h.runtime.side)

        h.camera().renderer.textureAvailable()
        h.camera().renderer.ready("avc=TS init=true buffer=1")

        assertEquals(listOf("main: camera#1.renderer> ready"), rec.take())
        assertEquals(CameraRuntimePhase.READY, h.runtime.phase)
        assertEquals("avc=TS init=true buffer=1", h.runtime.details)
    }

    @Test
    fun theRightCameraAsksViewpoint3204AndCarriesTheProcessingChoice() {
        h.show(RIGHT, position = CENTER, processing = false)

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera RIGHT CENTER",
                "main: camera#1.renderer.start viewpoint=3204 processing=false",
            ),
            rec.take(),
        )
    }

    @Test
    fun aShowWithNoCameraDisplayTouchesNothingAndFails() {
        h.layers.cameraDisplay = null
        h.show(LEFT)

        assertEquals(listOf("main: layers.resolve camera -> missing"), rec.take())
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("camera presentation unavailable", h.runtime.details)
    }

    @Test
    fun aShowWhoseWindowCannotBeShownFails() {
        h.layers.refuseNextOpen = true
        h.show(LEFT)

        assertEquals(
            listOf("main: layers.resolve camera -> 7", "main: layers.open camera display=7"),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("camera presentation unavailable", h.runtime.details)
    }

    // Hide

    @Test
    fun aHideTakesTheWindowDownFirstAndFreesAvcOnTheTeardownThread() {
        h.showReady(LEFT)
        rec.take()

        h.preempt()
        assertEquals(listOf("monitor: looper.post"), rec.take())

        h.main.runDue()
        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: monitor.localDetached",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STOPPING, h.runtime.phase)

        h.teardown.runAll()
        assertEquals(
            listOf(
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
        assertEquals("camera hidden", h.runtime.details)
    }

    @Test
    fun aSurfaceTheWindowLeftBehindIsReleasedByFreeDisplaysThread() {
        h.showReady(LEFT)
        h.camera().views.windowKeepsTexture = true
        h.preempt()
        rec.take()

        h.main.runDue()
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> true",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
                "teardown: camera#1.renderer> local surface released",
                "teardown: monitor.localDetached",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
    }

    @Test
    fun aHideBeforeTheTextureWasLaidOutReportsTheLocalSurfaceGoneAtOnce() {
        h.show(LEFT)
        h.preempt()
        rec.take()

        h.main.runDue()
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: monitor.localDetached",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    @Test
    fun aHideIntentAndASyncHideOnMainTearDownInPlace() {
        h.showReady(LEFT)
        rec.take()

        h.service.onHideCameraAction()

        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )

        // The monitor stopping: hideCameraSync from main finds the teardown already running.
        assertTrue(h.controller.hideCameraSync(250L))
        h.teardown.runAll()
        assertEquals(listOf("teardown: camera#1.renderer.stop"), rec.take())
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    @Test
    fun aSyncHideFromAnotherThreadWaitsForTheWindowNotForFreeDisplay() {
        h.showReady(LEFT)
        rec.take()
        var hidden = false

        val monitor = thread {
            rec.on("monitor") { hidden = h.controller.hideCameraSync(5_000L) }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (h.main.pending == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        h.main.runDue()
        monitor.join(5_000L)

        assertTrue("the window came down within the wait", hidden)
        assertEquals(
            listOf(
                "monitor: looper.post",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )
        assertEquals("freeDisplay is still to come", 1, h.teardown.pending)
    }

    @Test
    fun aHideWithNothingShownOnlyIdlesTheRuntime() {
        h.preempt()
        h.main.runDue()

        assertEquals(
            listOf("monitor: looper.post", "main: monitor.localDetached", "main: monitor.vendorFreed"),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
        assertEquals("camera hidden", h.runtime.details)
    }

    @Test
    fun stoppingTheSceneTearsTheCameraDownBeforeTheBase() {
        h.service.showDashboard(ClusterMapPlacement.FULL)
        h.showReady(LEFT)
        rec.take()

        h.service.onStopAction()
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "main: base#1.removeWindow",
                "main: base#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    // Show during teardown

    @Test
    fun aShowDuringTeardownIsRefusedAndTheNextOneOpensAFreshLayer() {
        h.showReady(LEFT)
        h.preempt()
        h.main.runDue()
        rec.take()

        h.show(RIGHT)
        assertEquals("nothing reaches AVC while it is being let go", emptyList<String>(), rec.take())
        assertEquals(CameraRuntimePhase.STOPPING, h.runtime.phase)

        h.teardown.runAll()
        h.show(RIGHT)
        assertEquals(
            listOf(
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#2.show",
                "main: camera#2.hideDiagnostic",
                "main: camera#2.layoutCamera RIGHT SIDES",
                "main: camera#2.renderer.start viewpoint=3204 processing=true",
            ),
            rec.take(),
        )
    }

    /*
     * A Show has two guards, the runtime STOPPING and the teardown barrier held. In every state the
     * scene reaches by itself they agree; they part only for the moment on denza-avc-teardown
     * between the runtime going idle and the barrier clearing. These two put the runtime there by
     * hand, so that each guard is held on its own.
     */

    @Test
    fun aShowWhileTheBarrierIsHeldIsRefusedEvenWithTheRuntimeIdle() {
        h.showReady(LEFT)
        h.preempt()
        h.main.runDue()
        rec.take()
        h.runtimeTracker.idle("camera hidden")

        h.show(RIGHT)
        h.service.showPreview(SIDES, visible = true, durationMs = 2_200L, cameraOverlay = true)

        assertEquals(emptyList<String>(), rec.take())
    }

    @Test
    fun aShowWhileTheRuntimeIsStoppingIsRefusedEvenWithTheBarrierClear() {
        h.runtimeTracker.stopping("closing camera surface")

        h.show(LEFT)
        h.service.showPreview(SIDES, visible = true, durationMs = 2_200L, cameraOverlay = true)

        assertEquals(emptyList<String>(), rec.take())
    }

    // AVC refusal during STARTING

    @Test
    fun avcRefusingTheDisplayWhileStartingTearsTheLayerDownAndFails() {
        h.show(LEFT)
        h.camera().renderer.textureAvailable()
        rec.take()

        h.camera().renderer.fail("com.byd.avc initDisplay returned false buffer=1 viewpoint=3205")
        assertEquals(
            listOf(
                "main: camera#1.renderer> failure",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STOPPING, h.runtime.phase)

        h.teardown.runAll()
        assertEquals(listOf("teardown: camera#1.renderer.stop"), rec.take())
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("com.byd.avc initDisplay returned false buffer=1 viewpoint=3205", h.runtime.details)

        // The monitor answers a failure with a Hide: nothing is left to let go.
        h.preempt()
        h.main.runDue()
        assertEquals(
            listOf("monitor: looper.post", "main: monitor.localDetached", "main: monitor.vendorFreed"),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    @Test
    fun aBindRefusedInsideStartTearsDownFromWithinIt() {
        h.layers.nextCameraFailsOnStart = "com.byd.avc bind failed"
        h.show(LEFT)
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera LEFT SIDES",
                "main: camera#1.renderer.start viewpoint=3205 processing=true",
                "main: camera#1.renderer> failure",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("com.byd.avc bind failed", h.runtime.details)
    }

    @Test
    fun aRendererThatThrowsOnStartIsTornDownAsAFailure() {
        h.layers.nextCameraThrowsOnStart = IllegalStateException("no binder")
        h.show(LEFT)
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera LEFT SIDES",
                "main: camera#1.renderer.start viewpoint=3205 processing=true",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("camera start failed: IllegalStateException", h.runtime.details)
    }

    @Test
    fun aFailureFromAnOlderShowIsIgnored() {
        h.showReady(LEFT)
        val stale = h.camera()
        h.preempt()
        h.main.runDue()
        h.teardown.runAll()
        rec.take()

        stale.renderer.fail("com.byd.avc disconnected")

        assertEquals(listOf("main: camera#1.renderer> failure"), rec.take())
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    // Display changed

    @Test
    fun aCameraDisplayThatChangedUnderACameraTearsItDownAndFails() {
        h.showReady(LEFT)
        rec.take()
        h.layers.cameraDisplay = 9

        h.show(RIGHT)
        assertEquals(
            listOf(
                "main: layers.resolve camera -> 9",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STOPPING, h.runtime.phase)

        h.teardown.runAll()
        assertEquals(listOf("teardown: camera#1.renderer.stop"), rec.take())
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("camera display changed", h.runtime.details)
    }

    @Test
    fun aPreviewLayerLeftOnAnOldDisplayCostsTheNextTurnItsCamera() {
        h.service.showPreview(SIDES, visible = true, durationMs = 2_000L, cameraOverlay = true)
        h.main.advance(2_000L)
        rec.take()
        h.layers.cameraDisplay = 9

        h.show(LEFT)
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 9",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.FAILED, h.runtime.phase)
        assertEquals("camera display changed", h.runtime.details)

        // After the monitor's Hide the next turn opens on the new display.
        h.preempt()
        h.main.runDue()
        rec.take()
        h.show(LEFT)
        assertEquals(
            listOf(
                "main: layers.resolve camera -> 9",
                "main: layers.open camera display=9",
                "main: camera#2.show",
                "main: camera#2.hideDiagnostic",
                "main: camera#2.layoutCamera LEFT SIDES",
                "main: camera#2.renderer.start viewpoint=3205 processing=true",
            ),
            rec.take(),
        )
    }

    @Test
    fun aBaseDisplayThatChangedReplacesTheBaseLayer() {
        h.service.showDashboard(ClusterMapPlacement.FULL)
        rec.take()
        h.layers.baseDisplay = 4

        h.service.showDashboard(ClusterMapPlacement.FULL)
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: layers.resolve base -> 4",
                "main: base#1.removeWindow",
                "main: base#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "main: layers.open base display=4",
                "main: base#2.show",
                "main: base#2.showDashboard FULL",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    // The service destroyed mid-teardown

    @Test
    fun aTeardownOutlivesTheServiceAndHoldsEveryLaterCallerUntilFreeDisplayReturns() {
        h.showReady(LEFT)
        h.preempt("first")
        h.main.runDue()
        rec.take()

        h.service.destroyed()
        assertEquals(listOf("main: handler.removeAll"), rec.take())

        // No service instance: the monitor's next Hide waits on the process-wide barrier.
        h.preempt("second")
        // A turn signal starts a new instance; its Show and its Hides meet the old teardown.
        val show = h.requestShow(RIGHT)
        h.startService()
        h.deliver(show)
        h.service.onHideCameraAction()
        h.preempt("third")
        h.main.runDue()
        assertEquals(
            "nothing reaches AVC before the old freeDisplay returns",
            listOf("monitor: looper.post"),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STOPPING, h.runtime.phase)

        h.teardown.runAll()
        assertEquals(
            listOf(
                "teardown: camera#1.renderer.stop",
                "teardown: second.localDetached",
                "teardown: second.vendorFreed",
                "teardown: third.localDetached",
                "teardown: third.vendorFreed",
                "teardown: first.vendorFreed",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)

        h.show(RIGHT)
        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#2.show",
                "main: camera#2.hideDiagnostic",
                "main: camera#2.layoutCamera RIGHT SIDES",
                "main: camera#2.renderer.start viewpoint=3204 processing=true",
            ),
            rec.take(),
        )
    }

    @Test
    fun aHideQueuedBeforeTheServiceDiedStillReachesItsTeardown() {
        h.showReady(LEFT)
        h.preempt()
        rec.take()

        // onDestroy runs before the posted Hide: it clears its own handler, not the looper.
        h.service.destroyed()
        h.main.runDue()
        h.teardown.runAll()

        assertEquals(
            listOf(
                "main: handler.removeAll",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "main: monitor.localDetached",
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    // Two Shows racing a Hide

    @Test
    fun aShowOvertakenByAHideNeverStartsAndItsCameraAnswersAreIgnored() {
        h.show(LEFT)
        h.camera().renderer.textureAvailable()
        val second = h.requestShow(RIGHT)
        h.preempt()
        rec.take()

        h.deliver(second)
        h.camera().renderer.ready("avc=TS init=true buffer=1")
        assertEquals(listOf("main: camera#1.renderer> ready"), rec.take())
        assertEquals("the stale ready does not promote the camera", CameraRuntimePhase.STARTING, h.runtime.phase)

        h.main.runDue()
        h.deliver(second)
        h.teardown.runAll()
        assertEquals(
            listOf(
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: monitor.localDetached",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
        assertEquals(
            listOf(
                "main: I stale showCamera rejected; generation=2",
                "main: I stale AVC ready ignored; generation=1",
                "main: I stale showCamera rejected; generation=2",
            ),
            rec.logs().filter { "stale" in it },
        )
    }

    /*
     * Every way the scene is told to let go makes the Shows issued before it stale, not only the
     * monitor's preempt: a Show already on its way as an intent must not open AVC after it. For
     * hideCameraSync - the monitor's last word when it stops - nothing else would: the monitor's
     * gate closes only on Shows not yet issued, and no one is left to close a camera opened after.
     */

    @Test
    fun aShowIssuedBeforeASyncHideNeverStarts() {
        val show = h.requestShow(LEFT)

        h.controller.hideCameraSync(250L)
        h.deliver(show)

        assertStaleAndUntouched(emptyList())
    }

    @Test
    fun aShowIssuedBeforeAHideIntentNeverStarts() {
        val show = h.requestShow(LEFT)

        h.service.onHideCameraAction()
        h.deliver(show)

        assertStaleAndUntouched(emptyList())
    }

    @Test
    fun aShowIssuedBeforeStopNeverStarts() {
        val show = h.requestShow(LEFT)

        h.service.onStopAction()
        h.deliver(show)

        assertStaleAndUntouched(emptyList())
    }

    @Test
    fun aShowIssuedBeforeOnDestroyNeverReachesTheNextInstance() {
        val show = h.requestShow(LEFT)

        h.service.destroyed()
        h.startService()
        h.deliver(show)

        assertStaleAndUntouched(listOf("main: handler.removeAll"))
    }

    private fun assertStaleAndUntouched(calls: List<String>) {
        assertEquals("nothing opened, nothing started", calls, rec.take())
        assertEquals(
            listOf("main: I stale showCamera rejected; generation=1"),
            rec.logs().filter { "stale" in it },
        )
        assertEquals(CameraRuntimePhase.IDLE, h.runtime.phase)
    }

    /**
     * Two Shows that are both current, with no Hide between, restart AVC on the window that is
     * still up: freeDisplay on the main thread with our Surface attached, the order of the
     * 2026-07-18 abort. The monitor never sends this - its reducer issues Show only with the
     * runtime IDLE or FAILED and no teardown in flight - so it is pinned here as it stands, not
     * endorsed.
     */
    @Test
    fun twoCurrentShowsWithNoHideBetweenRestartAvcOnTheSameWindow() {
        h.showReady(LEFT)
        rec.take()

        h.show(RIGHT)
        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: camera#1.renderer.stop",
                "main: camera#1.renderer> local surface released",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera RIGHT SIDES",
                "main: camera#1.renderer.start viewpoint=3204 processing=true",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.STARTING, h.runtime.phase)
        assertEquals(RIGHT, h.runtime.side)

        h.preempt()
        h.main.runDue()
        h.teardown.runAll()
        assertEquals(
            listOf(
                "monitor: looper.post",
                "main: camera#1.removeWindow",
                "main: camera#1.renderer> local surface released",
                "main: monitor.localDetached",
                "main: camera#1.renderer.hasLocalSurfaceHandle -> false",
                "main: teardown.post",
                "teardown: camera#1.renderer.stop",
                "teardown: monitor.vendorFreed",
            ),
            rec.take(),
        )
    }

    // A turn signal during a diagnostic preview

    @Test
    fun aTurnSignalDuringABasePreviewLeavesTheBaseLayersHideDue() {
        h.service.showPreview(SIDES, visible = true, durationMs = 2_200L, cameraOverlay = false)
        h.main.advance(500L)
        h.show(LEFT)
        h.main.advance(1_700L)

        assertEquals(
            listOf(
                "main: layers.resolve base -> 3",
                "main: layers.open base display=3",
                "main: base#1.show",
                "main: base#1.hideCameraFrame",
                "main: base#1.drawDiagnostic SIDES visible=true",
                "main: handler.postDelayed 2200",
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera LEFT SIDES",
                "main: camera#1.renderer.start viewpoint=3205 processing=true",
                "main: base#1.hideDiagnostic",
            ),
            rec.take(),
        )
    }

    @Test
    fun aTurnSignalDuringACameraPreviewTakesOverItsLayerAndDropsItsHide() {
        h.service.showPreview(CENTER, visible = true, durationMs = 2_200L, cameraOverlay = true)
        h.main.advance(500L)
        h.show(LEFT)
        h.main.advance(5_000L)

        assertEquals(
            listOf(
                "main: layers.resolve camera -> 7",
                "main: layers.open camera display=7",
                "main: camera#1.show",
                "main: camera#1.hideCameraFrame",
                "main: camera#1.drawDiagnostic CENTER visible=true",
                "main: handler.postDelayed 2200",
                "main: layers.resolve camera -> 7",
                "main: handler.removeCallbacks",
                "main: camera#1.hideDiagnostic",
                "main: camera#1.layoutCamera LEFT SIDES",
                "main: camera#1.renderer.start viewpoint=3205 processing=true",
            ),
            rec.take(),
        )
    }

    @Test
    fun aCameraPreviewWaitsForTheCameraButABasePreviewDoesNot() {
        h.showReady(LEFT)
        rec.take()

        h.service.showPreview(SIDES, visible = true, durationMs = 100L, cameraOverlay = true)
        assertEquals(emptyList<String>(), rec.take())

        h.service.showPreview(SIDES, visible = false, durationMs = 10_000L, cameraOverlay = false)
        assertEquals(
            listOf(
                "main: layers.resolve base -> 3",
                "main: layers.open base display=3",
                "main: base#1.show",
                "main: base#1.hideCameraFrame",
                "main: base#1.drawDiagnostic SIDES visible=false",
                "main: handler.postDelayed 5000",
            ),
            rec.take(),
        )
        assertEquals(CameraRuntimePhase.READY, h.runtime.phase)
    }

    // What a car run leaves in logcat

    @Test
    fun theLinesTheStartupAnalyzerReadsKeepTheirWords() {
        h.show(LEFT)
        h.camera().renderer.textureAvailable()
        h.camera().renderer.ready("avc=TS init=true buffer=1 viewpoint=3205")
        h.camera().renderer.firstFrame("ready_to_first_update_ms=12")
        h.camera().renderer.fail("com.byd.avc disconnected")
        h.teardown.runAll()

        // tools/analyze_mirrors_startup.py: REQUEST_RE, DISPATCH_RE, READY_RE, FRAME_RE, FAILURE_RE.
        assertEquals(
            listOf(
                "monitor: I camera request; generation=1 side=LEFT at_ms=0",
                "main: I showCamera LEFT; generation=1 at_ms=0",
                "main: I AVC ready; generation=1 side=LEFT details=avc=TS init=true buffer=1 viewpoint=3205",
                "main: I AVC first texture update; generation=1 ready_to_first_update_ms=12",
                "main: W AVC failure; generation=1 side=LEFT details=com.byd.avc disconnected",
                "teardown: I vendor display freed in 0ms",
                "teardown: I hideCamera: surface released, display freed",
            ),
            rec.logs(),
        )
    }
}
