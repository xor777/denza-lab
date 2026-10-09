package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorCameraConfig
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.MirrorsPosition

/**
 * One timeline for every call the camera lifecycle makes through its seams (`CameraSceneSeams.kt`).
 *
 * An entry reads `thread: call`. The thread is the one the test is playing when the call is made:
 * `main` unless the test says otherwise, `teardown` while [FakeTeardownThread] runs its queue, and
 * whatever a test names with [on] - `monitor` for SideCameraMonitorService's executor. Log lines
 * share the timeline, so their place among the calls is kept; [take] leaves them out.
 */
internal class SceneRecorder {
    private val currentThread = ThreadLocal.withInitial { "main" }
    private val entries = mutableListOf<Entry>()
    private var taken = 0

    val thread: String get() = checkNotNull(currentThread.get())

    fun record(call: String) = add(Entry(thread, call, isLog = false))

    fun log(line: String) = add(Entry(thread, line, isLog = true))

    /** Plays [thread] for the duration of [block]. */
    fun <T> on(thread: String, block: () -> T): T {
        val previous = currentThread.get()
        currentThread.set(thread)
        try {
            return block()
        } finally {
            currentThread.set(previous)
        }
    }

    /** The calls recorded since the last take, log lines left out. */
    fun take(): List<String> = synchronized(entries) {
        entries.drop(taken).filterNot(Entry::isLog).map(Entry::text).also { taken = entries.size }
    }

    /** Every log line so far, in order. */
    fun logs(): List<String> = synchronized(entries) {
        entries.filter(Entry::isLog).map(Entry::text)
    }

    private fun add(entry: Entry) {
        synchronized(entries) { entries += entry }
    }

    private class Entry(thread: String, call: String, val isLog: Boolean) {
        val text = "$thread: $call"
    }
}

/**
 * The main looper with a clock the test turns.
 *
 * Like Android's, it runs what is due in the order it was posted, and [handler] gives a Handler on
 * it: a handler's `removeAll` drops what that handler posted and nothing else, so a Hide posted
 * through [post] outlives `ClusterSceneService.onDestroy`, as it does on the car.
 */
internal class FakeMain(private val rec: SceneRecorder) : MainLooper {
    private class Posted(val dueAt: Long, val order: Long, val owner: Any, val task: Runnable)

    private val queue = mutableListOf<Posted>()
    private var order = 0L

    var now = 0L
        private set

    val pending: Int get() = synchronized(queue) { queue.size }

    override fun isCurrent(): Boolean = rec.thread == "main"

    override fun post(task: Runnable) {
        rec.record("looper.post")
        enqueue(this, 0L, task)
    }

    fun handler(name: String = "handler"): SceneHandler = object : SceneHandler {
        override fun postDelayed(task: Runnable, delayMs: Long) {
            rec.record("$name.postDelayed $delayMs")
            enqueue(this, delayMs, task)
        }

        override fun removeCallbacks(task: Runnable) {
            rec.record("$name.removeCallbacks")
            synchronized(queue) { queue.removeAll { it.owner === this && it.task === task } }
        }

        override fun removeAll() {
            rec.record("$name.removeAll")
            synchronized(queue) { queue.removeAll { it.owner === this } }
        }
    }

    /** Runs, as the main thread, everything due by now. */
    fun runDue() = advance(0L)

    /** Moves the clock on by [ms], running what falls due on the way, as the main thread. */
    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = synchronized(queue) {
                queue.filter { it.dueAt <= until }
                    .minWithOrNull(compareBy<Posted>({ it.dueAt }, { it.order }))
                    ?.also { queue.remove(it) }
            } ?: break
            now = next.dueAt
            rec.on("main") { next.task.run() }
        }
        now = until
    }

    private fun enqueue(owner: Any, delayMs: Long, task: Runnable) {
        synchronized(queue) { queue += Posted(now + delayMs, order++, owner, task) }
    }
}

/** `denza-avc-teardown`: what is posted waits until the test runs it, as that thread. */
internal class FakeTeardownThread(private val rec: SceneRecorder) : TeardownThread {
    private val queue = ArrayDeque<Runnable>()

    val pending: Int get() = synchronized(queue) { queue.size }

    override fun post(task: Runnable) {
        rec.record("teardown.post")
        synchronized(queue) { queue.addLast(task) }
    }

    fun runAll() {
        while (true) {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            rec.on("teardown") { next.run() }
        }
    }
}

internal class RecordingLog(private val rec: SceneRecorder) : SceneLog {
    override fun i(message: String) = rec.log("I $message")

    override fun w(message: String) = rec.log("W $message")

    override fun e(message: String, error: Throwable) = rec.log("E $message: ${error.message}")
}

/**
 * `AvcCameraRenderer` as the lifecycle sees it.
 *
 * What happens inside the real one - bindService, initDisplay, setViewpoint, freeDisplay, unbind -
 * is its own and unchanged; here [start] and [stop] are the AVC-facing calls. The rest is the
 * car's side, played by the test: [textureAvailable] (the TextureView laid out), [ready], [fail]
 * and [firstFrame] (AVC answering), [textureDestroyed] (the window holding the TextureView going
 * away). The Surface follows the real renderer: made when the texture is available and the
 * renderer listens to it (from the first [start] on), released by [stop] - which reports it only
 * if one was held - and by the texture's destruction, which always reports it.
 */
internal class FakeRenderer(
    private val name: String,
    private val rec: SceneRecorder,
) : CameraRenderer {
    lateinit var events: CameraRendererEvents
    private var listening = false
    private var textureAlive = false
    private var surface = false

    /** A failure the renderer reports from inside [start], as a refused bindService does. */
    var failOnStart: String? = null

    /** An exception [start] throws once the call is recorded. */
    var throwOnStart: RuntimeException? = null

    override fun start(viewpoint: Int, processingEnabled: Boolean) {
        rec.record("$name.renderer.start viewpoint=$viewpoint processing=$processingEnabled")
        throwOnStart?.let { throw it }
        // The real start() stops first, then takes a texture that is already available at once.
        if (surface) releaseSurface()
        listening = true
        if (textureAlive) surface = true
        failOnStart?.let(::fail)
    }

    override fun stop() {
        rec.record("$name.renderer.stop")
        if (surface) releaseSurface()
    }

    override fun hasLocalSurfaceHandle(): Boolean {
        rec.record("$name.renderer.hasLocalSurfaceHandle -> $surface")
        return surface
    }

    fun textureAvailable() {
        textureAlive = true
        if (listening) surface = true
    }

    fun textureDestroyed() {
        if (!textureAlive) return
        textureAlive = false
        if (listening) releaseSurface()
    }

    fun ready(details: String) {
        rec.record("$name.renderer> ready")
        events.onReady(details)
    }

    fun fail(details: String) {
        rec.record("$name.renderer> failure")
        events.onFailure(details)
    }

    fun firstFrame(details: String) {
        rec.record("$name.renderer> first frame")
        events.onFirstFrame(details)
    }

    private fun releaseSurface() {
        surface = false
        rec.record("$name.renderer> local surface released")
        events.onLocalSurfaceReleased()
    }
}

/**
 * A presentation's window and views. Taking the window down destroys its TextureView, and with it
 * the renderer's Surface, before `dismiss` returns, as Android does for a window removed on its own
 * thread; [windowKeepsTexture] plays a platform that would not.
 */
internal class FakeViews(
    private val name: String,
    private val rec: SceneRecorder,
    private val renderer: FakeRenderer,
) : SceneLayerViews {
    var windowKeepsTexture = false

    override fun removeWindow() {
        rec.record("$name.removeWindow")
        if (!windowKeepsTexture) renderer.textureDestroyed()
    }

    override fun layoutCamera(config: MirrorCameraConfig) =
        rec.record("$name.layoutCamera ${config.side} ${config.position}")

    override fun hideCameraFrame() = rec.record("$name.hideCameraFrame")

    override fun drawDiagnostic(position: MirrorsPosition, visible: Boolean) =
        rec.record("$name.drawDiagnostic $position visible=$visible")

    override fun hideDiagnostic() = rec.record("$name.hideDiagnostic")

    override fun showMap(placement: ClusterMapPlacement, consumer: MapSurfaceConsumer) =
        rec.record("$name.showMap $placement")

    override fun hideMap() = rec.record("$name.hideMap")

    override fun showDashboard(placement: ClusterMapPlacement) =
        rec.record("$name.showDashboard $placement")

    override fun hideDashboard() = rec.record("$name.hideDashboard")
}

/** One presentation the fake service opened: its layer and the fakes it is made of. */
internal class FakePresentation(
    val name: String,
    val layer: SceneLayer,
    val views: FakeViews,
    val renderer: FakeRenderer,
)

/**
 * The service's half of [SceneLayers]: displays the test chooses and presentations made of fakes.
 * Opening one records what ClusterSceneService does - the window shown on that display, its
 * onCreate handing the renderer to the layer - and nothing that reaches AVC.
 */
internal class FakeLayers(
    private val rec: SceneRecorder,
    private val teardown: TeardownThread,
    private val clock: SceneClock,
    private val log: SceneLog,
) : SceneLayers {
    /** The display each resolver finds; null is `ClusterDisplaySelection.Missing`. */
    var baseDisplay: Int? = 3
    var cameraDisplay: Int? = 7

    /** The next open finds its display gone, or its window cannot be shown. */
    var refuseNextOpen = false

    /** The next camera presentation's renderer reports this failure from inside start. */
    var nextCameraFailsOnStart: String? = null

    /** The next camera presentation's renderer throws this from start. */
    var nextCameraThrowsOnStart: RuntimeException? = null

    val cameras = mutableListOf<FakePresentation>()
    val bases = mutableListOf<FakePresentation>()

    override fun resolve(cameraLayer: Boolean): ClusterDisplaySelection {
        val id = if (cameraLayer) cameraDisplay else baseDisplay
        rec.record("layers.resolve ${kind(cameraLayer)} -> ${id ?: "missing"}")
        return if (id == null) {
            ClusterDisplaySelection.Missing
        } else {
            ClusterDisplaySelection.Selected(
                ClusterDisplayDescriptor(id, "display $id", 2560, 720, 240, 0, 0),
            )
        }
    }

    override fun open(displayId: Int, cameraLayer: Boolean, events: AvcEvents): SceneLayer? {
        rec.record("layers.open ${kind(cameraLayer)} display=$displayId")
        if (refuseNextOpen) {
            refuseNextOpen = false
            return null
        }
        val opened = if (cameraLayer) cameras else bases
        val name = "${kind(cameraLayer)}#${opened.size + 1}"
        val renderer = FakeRenderer(name, rec)
        if (cameraLayer) {
            renderer.failOnStart = nextCameraFailsOnStart
            renderer.throwOnStart = nextCameraThrowsOnStart
            nextCameraFailsOnStart = null
            nextCameraThrowsOnStart = null
        }
        val views = FakeViews(name, rec, renderer)
        val layer = SceneLayer(displayId, views, events, teardown, clock, log)
        renderer.events = layer
        rec.record("$name.show")
        layer.attach(renderer)
        opened += FakePresentation(name, layer, views, renderer)
        return layer
    }

    private fun kind(cameraLayer: Boolean) = if (cameraLayer) "camera" else "base"
}

/**
 * The camera scene as the car runs it, every seam a fake: one process-wide
 * [CameraSceneController], service instances started and destroyed as Android would, the
 * monitor's calls on its own thread, intents delivered when the test says.
 */
internal class CameraSceneHarness {
    val rec = SceneRecorder()
    val main = FakeMain(rec)
    val teardown = FakeTeardownThread(rec)
    private val log = RecordingLog(rec)
    private val clock = SceneClock { main.now }

    /** The controller's runtime, for the states only a cross-thread moment produces. */
    val runtimeTracker = CameraRuntimeTracker()
    val controller = CameraSceneController(main, clock, log, runtimeTracker)
    val layers = FakeLayers(rec, teardown, clock, log)
    private var instances = 0

    /** The live service instance's scene. */
    lateinit var service: CameraSceneController.Scene
        private set

    init {
        startService()
    }

    val runtime: CameraRuntimeSnapshot get() = controller.runtimeSnapshot()

    /** A new ClusterSceneService instance: its own handler on the main looper, then onCreate. */
    fun startService(): CameraSceneController.Scene {
        instances++
        val handler = main.handler(if (instances == 1) "handler" else "handler#$instances")
        service = controller.Scene(layers, handler).also { it.created() }
        return service
    }

    /** The newest camera presentation, or the [n]th (1-based). */
    fun camera(n: Int = layers.cameras.size): FakePresentation = layers.cameras[n - 1]

    fun base(n: Int = layers.bases.size): FakePresentation = layers.bases[n - 1]

    /** ClusterSceneService.showCamera on the monitor's thread: a generation, an intent on its way. */
    fun requestShow(
        side: MirrorSide,
        position: MirrorsPosition = MirrorsPosition.SIDES,
        processing: Boolean = true,
    ): ShowIntent = rec.on("monitor") {
        val config = MirrorCameraConfig(side, position, processing)
        ShowIntent(config, controller.requestShow(config))
    }

    /** The Show intent reaching onStartCommand of the live instance. */
    fun deliver(intent: ShowIntent) = service.onShowCameraAction(intent.config, intent.generation)

    fun show(
        side: MirrorSide,
        position: MirrorsPosition = MirrorsPosition.SIDES,
        processing: Boolean = true,
    ) = deliver(requestShow(side, position, processing))

    /** Show, the TextureView laid out, AVC ready: a camera on screen. */
    fun showReady(side: MirrorSide) {
        show(side)
        camera().renderer.textureAvailable()
        camera().renderer.ready("avc=TS init=true buffer=1")
    }

    /** SideCameraMonitorService.preemptCamera, whose callbacks record themselves as [who]. */
    fun preempt(who: String = "monitor"): Long = rec.on("monitor") {
        controller.preemptCamera(
            onLocalSurfaceDetached = { rec.record("$who.localDetached") },
            onVendorFreeCompleted = { rec.record("$who.vendorFreed") },
        )
    }

    class ShowIntent(val config: MirrorCameraConfig, val generation: Long)
}
