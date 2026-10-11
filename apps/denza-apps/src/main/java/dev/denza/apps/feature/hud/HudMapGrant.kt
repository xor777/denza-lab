package dev.denza.apps.feature.hud

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellGrants
import java.util.concurrent.Executors

/**
 * The map's screen-capture grant: the MediaProjection token the main screen's mirror is made with,
 * held from the moment our Activity can ask for it until the map or the hints go off.
 *
 * How this car's IVI firmware gives one (2026-09-23 OTA):
 * - SystemUI's `MediaProjectionPermissionActivity` answers `RESULT_OK` at once, with no dialog, when
 *   `hasProjectionPermission` holds, which is op 46 `PROJECT_MEDIA` allowed for us
 *   (`MediaProjectionManagerService.java:229-237`). It names the caller by `getCallingPackage()`,
 *   so it is started for a result by our own Activity, which [attach] hands in while it is resumed.
 *   It has no `allowEmbedded`, so it cannot run on a virtual display of ours, and
 *   `MANAGE_MEDIA_PROJECTION` is signature-only and not held by shell: there is no other way to a
 *   token.
 * - `MediaProjection.start` - run by `getMediaProjection` - needs a running foreground service of
 *   type `mediaProjection` (32) for our uid (`:411`), and `handleForegroundServicesChanged` stops
 *   the projection the moment our uid's foreground types lose 32. [HudMapProjectionService] holds
 *   that type for exactly as long as the token lives.
 * - One projection exists for the whole system: another app's start stops ours, and selecting a
 *   live-video MediaRouter route does too (`startProjectionLocked`). [onStopped] hears it.
 *
 * When to ask is [HudMapGrantPolicy]: from our Activity only, with op 46 allowed - over the local
 * ADB shell if it is not - and never twice in a row on resumes. Without a token the map window
 * stays empty; [HudMapRuntime] says why. Main-thread state; the calls from elsewhere hop there.
 */
object HudMapGrant {
    private const val TAG = "DenzaHudMap"
    private const val PROJECT_MEDIA = "android:project_media"

    private val main = Handler(Looper.getMainLooper())
    private val shell = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "denza-hud-map-grant").apply { isDaemon = true }
    }

    /** The live token, or null. Read by the map's thread. */
    @Volatile
    var projection: MediaProjection? = null
        private set

    private var appContext: Context? = null
    private var launcher: ((Intent) -> Unit)? = null
    private var inFlight = false
    private var lastAttemptMs: Long? = null

    /** `MainActivity` resumed: it can ask now, and does if the policy says so. */
    fun attach(activity: Activity, launch: (Intent) -> Unit) {
        launcher = launch
        evaluate(activity, HudMapGrantTrigger.RESUME)
    }

    /** `MainActivity` paused: nothing can be asked until it resumes. */
    fun detach(launch: (Intent) -> Unit) {
        if (launcher === launch) launcher = null
    }

    /** The map or the hints moved, or our Activity resumed: ask, release or wait, per the policy. */
    fun evaluate(context: Context, trigger: HudMapGrantTrigger) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { evaluate(app, trigger) }
            return
        }
        appContext = app
        val action = HudMapGrantPolicy.decide(
            trigger = trigger,
            hintsOn = HudGuidanceSettings.isEnabled(app),
            mapOn = HudGuidanceSettings.isMapEnabled(app),
            live = projection != null,
            inFlight = inFlight,
            opAllowed = opAllowed(app),
            lastAttemptMs = lastAttemptMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
        when (action) {
            HudMapGrantAction.RELEASE -> release()
            HudMapGrantAction.REQUEST -> request(app, allowOpFirst = false)
            HudMapGrantAction.ALLOW_OP_THEN_REQUEST -> request(app, allowOpFirst = true)
            HudMapGrantAction.NOTHING -> setReason(
                HudMapGrantPolicy.idleReason(
                    hintsOn = HudGuidanceSettings.isEnabled(app),
                    mapOn = HudGuidanceSettings.isMapEnabled(app),
                    live = projection != null,
                    inFlight = inFlight,
                    activityResumed = launcher != null,
                    current = HudMapRuntime.grant(),
                ),
            )
        }
    }

    /**
     * The map needs a token now - a route, Yandex on the main screen - and none of our Activities
     * may be there to ask: start [HudMapCaptureRequestActivity] from the background, at most once a
     * [HudMapGrantPolicy.RESUME_RETRY_MS], allowing op 46 over the local ADB shell first if needed.
     * The app may start an Activity from the background because it holds the overlay right.
     */
    fun requestInBackground(context: Context) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { requestInBackground(app) }
            return
        }
        appContext = app
        val action = HudMapGrantPolicy.decide(
            trigger = HudMapGrantTrigger.BACKGROUND,
            hintsOn = HudGuidanceSettings.isEnabled(app),
            mapOn = HudGuidanceSettings.isMapEnabled(app),
            live = projection != null,
            inFlight = inFlight,
            opAllowed = opAllowed(app),
            lastAttemptMs = lastAttemptMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
        when (action) {
            HudMapGrantAction.REQUEST -> openRequestScreen(app)
            HudMapGrantAction.ALLOW_OP_THEN_REQUEST -> {
                inFlight = true
                lastAttemptMs = SystemClock.elapsedRealtime()
                setReason(HudMapGrantReason.REQUESTING)
                shell.execute {
                    val allowed = try {
                        DenzaLocalAdb.client(app).shell(ShellGrants.appop(app.packageName, "PROJECT_MEDIA", "allow"))
                        opAllowed(app)
                    } catch (error: Exception) {
                        Log.w(TAG, "PROJECT_MEDIA not allowed over ADB: $error")
                        false
                    }
                    main.post {
                        if (!inFlight) return@post
                        if (allowed) openRequestScreen(app) else fail(HudMapGrantReason.NO_ADB, "op 46 still not allowed")
                    }
                }
            }
            HudMapGrantAction.RELEASE -> release()
            HudMapGrantAction.NOTHING -> Unit
        }
    }

    /** [HudMapCaptureRequestActivity] is up: ask from it. False when there is nothing to ask. */
    internal fun onRequestScreen(activity: Activity, launch: (Intent) -> Unit): Boolean {
        val app = activity.applicationContext
        appContext = app
        if (projection != null || !stillWanted(app)) {
            inFlight = false
            return false
        }
        inFlight = true
        if (lastAttemptMs == null) lastAttemptMs = SystemClock.elapsedRealtime()
        setReason(HudMapGrantReason.REQUESTING)
        ask(app, launch)
        return inFlight
    }

    private fun openRequestScreen(app: Context) {
        inFlight = true
        lastAttemptMs = SystemClock.elapsedRealtime()
        setReason(HudMapGrantReason.REQUESTING)
        try {
            app.startActivity(
                Intent(app, HudMapCaptureRequestActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
            Log.i(TAG, "grant: request screen started from the background")
        } catch (error: RuntimeException) {
            fail(HudMapGrantReason.REFUSED, "request screen: $error")
        }
    }

    /** The map or the hints went off: the token goes, and the foreground type with it. */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { release() }
            return
        }
        inFlight = false
        val live = projection
        if (live == null) {
            setReason(HudMapGrantReason.OFF)
            return
        }
        // Not left to onStop: the source stops looking at it now, and the foreground type goes now.
        projection = null
        live.stop()
        setReason(HudMapGrantReason.OFF)
        appContext?.let(HudMapProjectionService::release)
    }

    /** SystemUI's answer to [request], handed in by `MainActivity`. */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(HudMapGrantReason.REFUSED, "consent result=$resultCode")
            return
        }
        if (!stillWanted(context)) {
            inFlight = false
            setReason(HudMapGrantReason.OFF)
            return
        }
        // The token is taken inside the service, after its startForeground: see [onForeground].
        try {
            HudMapProjectionService.hold(context, resultCode, data)
        } catch (error: RuntimeException) {
            fail(HudMapGrantReason.NO_SERVICE, "service start: $error")
        }
    }

    /**
     * [HudMapProjectionService] is in the foreground with type 32: only now may the token be taken,
     * since taking it starts the projection (`:411`). False when there is nothing to hold.
     */
    internal fun onForeground(service: HudMapProjectionService, resultCode: Int, data: Intent): Boolean {
        appContext = service.applicationContext
        inFlight = false
        if (!stillWanted(service)) {
            setReason(HudMapGrantReason.OFF)
            return false
        }
        val manager = service.getSystemService(MediaProjectionManager::class.java)
        val token = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (error: RuntimeException) {
            fail(HudMapGrantReason.NO_SERVICE, "getMediaProjection: $error")
            return false
        }
        if (token == null) {
            fail(HudMapGrantReason.REFUSED, "getMediaProjection gave nothing")
            return false
        }
        // One callback per token, so a late stop of an older one cannot end this one.
        token.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = onStopped(token)
            },
            main,
        )
        projection = token
        setReason(HudMapGrantReason.LIVE)
        return true
    }

    /** Whether the service should keep its foreground type: true while a token is held. */
    internal fun isLive(): Boolean = projection != null

    /** The system stopped [token]: another capture began, or a live-video route was chosen. */
    private fun onStopped(token: MediaProjection) {
        // Released by us already, or an older token: nothing of ours to end.
        if (projection !== token) return
        projection = null
        setReason(HudMapGrantReason.STOPPED)
        appContext?.let(HudMapProjectionService::release)
    }

    private fun request(app: Context, allowOpFirst: Boolean) {
        val launch = launcher
        if (launch == null) {
            setReason(HudMapGrantReason.WAITING_APP)
            return
        }
        inFlight = true
        lastAttemptMs = SystemClock.elapsedRealtime()
        setReason(HudMapGrantReason.REQUESTING)
        if (!allowOpFirst) {
            ask(app, launch)
            return
        }
        // The op is given over the local ADB shell, as OverlayGrant gives its right: only when it is
        // not held, and off the main thread, since the shell blocks.
        shell.execute {
            val allowed = try {
                DenzaLocalAdb.client(app).shell(ShellGrants.appop(app.packageName, "PROJECT_MEDIA", "allow"))
                opAllowed(app)
            } catch (error: Exception) {
                Log.w(TAG, "PROJECT_MEDIA not allowed over ADB: $error")
                false
            }
            main.post {
                if (!inFlight) return@post
                val now = launcher
                when {
                    !allowed -> fail(HudMapGrantReason.NO_ADB, "op 46 still not allowed")
                    now == null -> fail(HudMapGrantReason.WAITING_APP, "activity paused while allowing op 46")
                    else -> ask(app, now)
                }
            }
        }
    }

    /** Op 46 is allowed: SystemUI answers without a dialog. Never asked otherwise. */
    private fun ask(app: Context, launch: (Intent) -> Unit) {
        if (!opAllowed(app)) {
            fail(HudMapGrantReason.NO_ADB, "op 46 not allowed at ask")
            return
        }
        val intent = app.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()
        if (intent == null) {
            fail(HudMapGrantReason.NO_SERVICE, "no MediaProjectionManager")
            return
        }
        try {
            launch(intent)
        } catch (error: RuntimeException) {
            fail(HudMapGrantReason.REFUSED, "consent launch: $error")
        }
    }

    private fun fail(reason: HudMapGrantReason, why: String) {
        inFlight = false
        Log.w(TAG, "grant: $why")
        setReason(reason)
    }

    private fun setReason(reason: HudMapGrantReason) {
        if (HudMapRuntime.onGrant(reason)) Log.i(TAG, "grant ${reason.name}")
    }

    private fun stillWanted(context: Context): Boolean =
        HudGuidanceSettings.isEnabled(context) && HudGuidanceSettings.isMapEnabled(context)

    /** Exactly allowed, as `hasProjectionPermission` reads it: the default mode would bring the dialog. */
    private fun opAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            ops.unsafeCheckOpNoThrow(PROJECT_MEDIA, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) {
            false
        }
    }
}
