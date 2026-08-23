package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class SplitScreenPhase { OFF, STARTING, ACTIVE, ERROR }

data class SplitScreenSession(
    val enabled: Boolean = false,
    val phase: SplitScreenPhase = SplitScreenPhase.OFF,
    val message: String = "",
    val details: String? = null,
)

/**
 * The Android boundary of the split product, and nothing else.
 *
 * Every decision, every command and every piece of state lives in [SplitCoordinatorCore], which
 * knows nothing about Android and is therefore covered by ordinary unit tests. What is left here is
 * what genuinely needs a `Context`: opening a persistent shell, drawing the waiting window, reading
 * the launcher catalog, borrowing the two global leases and posting a callback to the main thread.
 */
// The stored value is normalized to applicationContext on the first call.
@SuppressLint("StaticFieldLeak")
object SplitScreenCoordinator {
    private const val TAG = "DenzaSplitScreen"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timers = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "split-clock").apply { isDaemon = true }
    }

    @Volatile private var core: SplitCoordinatorCore? = null

    fun initialize(context: Context, onStateChanged: () -> Unit) {
        // Read-only by construction: the core loads one durable snapshot, publishes the session and
        // stops. No scene is restored, no lease is taken, no command is sent (K7, invariant 1).
        core(context).initialize(onStateChanged)
        // Not a command and not a mutation: a PackageManager read on a background thread, so the
        // first tap does not have to wait for it (1.13.1).
        SplitPickerCatalog.warm(context)
    }

    fun snapshot(): SplitScreenSession = core?.snapshot() ?: SplitScreenSession()

    /** Opens the explicit two-picker product flow from its launcher icon. */
    fun openPickerSession(context: Context, onComplete: (String?) -> Unit = {}) {
        core(context).openPickerSession(onComplete)
    }

    /** A picker tap has an exact pane and therefore needs no foreground inference. */
    fun selectApp(
        context: Context,
        pickerTaskId: Int,
        packageName: String,
        onComplete: (String?) -> Unit = {},
    ) {
        core(context).selectApp(pickerTaskId, packageName, onComplete)
    }

    /** Exact picker reveal event; only the recorded app task can be removed. */
    fun onPickerVisible(context: Context, hostTaskId: Int?, onComplete: (String?) -> Unit = {}) {
        core(context).pickerVisible(hostTaskId, onComplete)
    }

    /** Exact product-picker window hint; the shell snapshot must resolve one visible task. */
    fun onProductPickerVisible(context: Context) {
        core(context).pickerVisible(hostTaskId = null)
    }

    /** A picker that left both panel roots is the pane-dismiss gesture (1.6.3, 1.7.4). */
    fun onPickerHidden(context: Context, hostTaskId: Int) {
        core(context).pickerHidden(hostTaskId)
    }

    /** Every window-topology hint collapses into one queued reconcile (1.8.1-1.8.3). */
    fun onDividerResized(context: Context) {
        core(context).dividerResized()
    }

    /** An uninstall a live picker saw: no pane may keep a package that is gone (1.5.6). */
    fun onPackageRemoved(context: Context, packageName: String) {
        core(context).packageRemoved(packageName)
    }

    /** Accepts only Home from the app-wide observer; all other global window traffic is ignored. */
    @JvmStatic
    fun onGlobalAccessibilityWindowChanged(context: Context, packageName: String?) {
        if (
            SplitAccessibilityEventPolicy.target(packageName, className = null) ==
            SplitAccessibilityEventTarget.HOME
        ) {
            onHomeVisible(context)
        }
    }

    /** A Home accessibility event is only a hint; firmware area 0 is the mutation authority. */
    @JvmStatic
    fun onHomeVisible(context: Context) {
        core(context).homeVisible()
    }

    /** Accessibility event for the stock picker created by dragging a fullscreen pane open. */
    @JvmStatic
    fun onNativePickerVisible(context: Context): Boolean = core(context).nativePickerVisible()

    @JvmStatic
    fun onProjectionStarted(taskId: Int) {
        core?.projectionStarted(taskId)
    }

    @JvmStatic
    fun onProjectionReturned(taskId: Int) {
        core?.projectionReturned(taskId)
    }

    internal fun prepareNavigationReturn(originalRootTaskId: Int): SplitNavigationReturnPlan {
        val live = core ?: error("Split coordinator is not initialized")
        return live.prepareNavigationReturn(originalRootTaskId)
    }

    internal fun completeNavigationReturn(
        plan: SplitNavigationReturnPlan,
        taskId: Int,
        packageName: String,
    ) {
        core?.completeNavigationReturn(plan, taskId, packageName)
    }

    /**
     * Navigation owns its explicit moves to and from the instrument display. Passive split
     * reconciliation stands aside until that move and its configuration changes have settled.
     */
    @JvmStatic
    fun bypassExternalTaskMoves() {
        core?.bypassExternalTaskMoves()
    }

    /** Keeps reconciliation suspended while an external-display task move is actually in flight. */
    @JvmStatic
    fun holdExternalTaskMoves() {
        core?.holdExternalTaskMoves()
        Log.i(TAG, "external task routing held")
    }

    /** Releases the long-lived handoff hold; the acquirer's settle deadline is already in force. */
    @JvmStatic
    fun releaseExternalTaskMoves() {
        core?.releaseExternalTaskMoves()
        Log.i(TAG, "external task routing released")
    }

    fun setEnabled(enabled: Boolean) {
        core?.setEnabled(enabled)
    }

    private fun core(context: Context): SplitCoordinatorCore {
        core?.let { return it }
        return synchronized(lock) {
            core ?: build(context.applicationContext).also { built -> core = built }
        }
    }

    private fun build(app: Context): SplitCoordinatorCore {
        val clock = SystemSplitClock()
        return SplitCoordinatorCore(
            // One handshake for the process, not one per operation (1.13.3).
            shellFactory = SplitPersistentShell { persistentShell(app) },
            clock = clock,
            store = SplitScreenSettings.stateStore(app),
            actor = SplitActor(clock),
            overlayOwner = SplitOverlayOwner { overlayLease(app) },
            notices = SplitNoticeSink { message -> SplitPickerNotice.publish(app, message) },
            catalog = AndroidSplitLaunchCatalog(app),
            gateLeaseStore = SplitScreenSettings.gateLeaseStore(app),
            leases = listOf(
                resizeabilityLease(app),
                pickerAccessLease(app),
                smartMultiLease(app),
            ),
            apkPath = app.applicationInfo.sourceDir,
            proxyClasspath = stagedProxy(app),
            appLabel = { packageName -> applicationLabel(app, packageName) },
            log = SplitDiagnosticLog(SplitDiagnostics::record),
            logMirror = SplitDiagnostics::drainForMirror,
            post = { action -> mainHandler.post(action) },
        )
    }

    /**
     * The one-class jar the build packs, staged where the shell user can read it (1.13.3).
     *
     * The version tag is what makes an update stage a fresh copy: a jar named after the version
     * that produced it can never be the previous build's proxy, and the previous build's copies are
     * swept away when a new one is written.
     */
    @Suppress("DEPRECATION")
    private fun stagedProxy(app: Context): SplitProxyClasspath {
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toString()
        }.getOrDefault("unknown")
        return SplitStagedProxyDex(
            apkPath = app.applicationInfo.sourceDir,
            versionTag = version,
            jar = { app.assets.open(SplitStagedProxyDex.ASSET).use { it.readBytes() } },
            log = { message -> Log.i(TAG, message) },
        )
    }

    /**
     * The name the user knows an app by (1.3.2). A package the system will not name - removed,
     * hidden, or simply unreadable right now - is reported as itself rather than as nothing.
     */
    @Suppress("DEPRECATION")
    private fun applicationLabel(app: Context, packageName: String): String = runCatching {
        val packages = app.packageManager
        packages.getApplicationInfo(packageName, 0).loadLabel(packages).toString()
    }.getOrDefault(packageName)

    private fun persistentShell(app: Context): SplitShellHandle {
        val session = DenzaLocalAdb.client(app).openPersistentShell()
        return object : SplitShellHandle {
            override fun shell(command: String): String = session.shell(command)

            override fun close() = session.close()
        }
    }

    private fun overlayLease(app: Context): SplitOverlayLease {
        // U6: the tap is answered within a second by this window and by nothing else, so an open
        // that starts without the permission to draw it starts already unable to keep that
        // promise. The overlay itself tells the user (SplitLaunchOverlay.UNAVAILABLE_NOTICE);
        // this line is what says so in the log of the operation that began here.
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "open starts with no waiting window: overlay permission is not granted")
        }
        val lease = SplitLaunchOverlay.begin(app)
        return object : SplitOverlayLease {
            override fun close() = lease.close()

            override fun closeImmediately() = lease.closeImmediately()
        }
    }

    private fun resizeabilityLease(app: Context) = object : SplitLeaseController {
        override val kind: String get() = SplitLeaseKind.RESIZEABILITY

        override fun ownedValue(): String? =
            SplitScreenSettings.resizeabilityLeaseStore(app).loadOriginal()?.name

        override fun enable(shell: (String) -> String) = controller(shell).enable()

        override fun restore(shell: (String) -> String) = controller(shell).restore()

        private fun controller(shell: (String) -> String) = SplitResizeabilityController(
            shell = shell,
            leaseStore = SplitScreenSettings.resizeabilityLeaseStore(app),
        )
    }

    private fun pickerAccessLease(app: Context) = object : SplitLeaseController {
        override val kind: String get() = SplitLeaseKind.PICKER_ACCESS

        override fun ownedValue(): String? =
            if (SplitScreenSettings.nativePickerAccessLeaseStore(app).isOwned()) OWNED else null

        override fun enable(shell: (String) -> String) = controller(shell).enable()

        override fun restore(shell: (String) -> String) = controller(shell).restore()

        private fun controller(shell: (String) -> String) = SplitNativePickerAccessController(
            shell = shell,
            leaseStore = SplitScreenSettings.nativePickerAccessLeaseStore(app),
        )
    }

    private fun smartMultiLease(app: Context) = object : SplitLeaseController {
        override val kind: String get() = SplitLeaseKind.SMART_MULTI

        override fun ownedValue(): String? =
            if (SplitScreenSettings.smartMultiLeaseStore(app).loadOriginal() != null) OWNED else null

        override fun enable(shell: (String) -> String) = controller(shell).enable()

        override fun restore(shell: (String) -> String) = controller(shell).restore()

        private fun controller(shell: (String) -> String) = SplitSmartMultiController(
            shell = shell,
            leaseStore = SplitScreenSettings.smartMultiLeaseStore(app),
        )
    }

    private const val OWNED = "owned"

    /** The only source of time in production: monotonic, and its timers are daemon threads. */
    private class SystemSplitClock : SplitClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()

        override fun schedule(delayMs: Long, action: () -> Unit): SplitCancellable {
            val scheduled = timers.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            return SplitCancellable { scheduled.cancel(false) }
        }
    }

    /**
     * Read from [SplitLaunchCatalogCache], which package events - and only package events - clear
     * (1.4.2). Neither read here may scan on the open path: doing so used to wake every launchable
     * BYD process before the first command of the recipe was sent.
     */
    private class AndroidSplitLaunchCatalog(private val app: Context) : SplitLaunchCatalog {
        override fun installedPackages(): Set<String> =
            SplitPickerCatalog.load(app).mapTo(mutableSetOf(), SplitLaunchTarget::packageName)

        override fun resolve(packageName: String): SplitLaunchTarget? =
            SplitPickerCatalog.resolve(app, packageName)
    }
}
