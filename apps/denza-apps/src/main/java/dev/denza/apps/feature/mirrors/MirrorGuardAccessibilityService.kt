package dev.denza.apps.feature.mirrors

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.feature.cluster.ClusterSceneService

/**
 * Fast-switch guard. Subscribes to window events with zero notification
 * timeout (see mirror_guard_a11y_service.xml) and, when a stock camera window
 * appears while our AVC session is live, synchronously frees the vendor
 * display via [ClusterSceneService.emergencyReleaseCamera] on this same
 * main-thread callback.
 *
 * The system-emitted TYPE_WINDOWS_CHANGED path is the one that matters: the
 * app-emitted TYPE_WINDOW_STATE_CHANGED event never arrived in the live crash
 * scenario because the stock main thread was already dying (2026-07-25 trial,
 * crash 127 ms after window add). New windows are identified by their window
 * title against a snapshot of known window ids, so our own overlay windows do
 * not self-trigger.
 *
 * Kill switch without reinstall: remove this component from
 * `enabled_accessibility_services`.
 */
class MirrorGuardAccessibilityService : AccessibilityService() {

    private val knownWindowIds = HashSet<Int>()

    override fun onServiceConnected() {
        Log.i(TAG, "guard connected")
        snapshotWindows()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val guardEnabled = MirrorsSettings.fastSwitchGuardEnabled(this)
        logWindowEvent(event, runtime)

        if (!MirrorGuardEvaluator.armed(runtime.phase, guardEnabled)) {
            // Keep the id snapshot fresh while disarmed so arming starts from
            // the current window population, not a stale one.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                snapshotWindows()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (
                    MirrorGuardEvaluator.shouldTriggerOnState(
                        event.packageName,
                        runtime.phase,
                        guardEnabled,
                    )
                ) {
                    trigger("state event", event)
                }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ADDED == 0) {
                    return
                }
                val newTitles = collectNewWindowTitles()
                if (
                    MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                        newTitles,
                        runtime.phase,
                        guardEnabled,
                    )
                ) {
                    trigger("new window ${newTitles.filterNotNull().joinToString()}", event)
                }
            }
        }
    }

    /**
     * Delivery-latency diagnostics: the first two live fast-switch attempts
     * crashed with the guard silent, and without this line it is impossible to
     * tell "the event never came" from "the event came too late or while
     * disarmed". Window-level events are low-volume, so this stays on
     * whenever the mirrors feature is enabled.
     */
    private fun logWindowEvent(
        event: AccessibilityEvent,
        runtime: dev.denza.apps.feature.cluster.CameraRuntimeSnapshot,
    ) {
        if (!MirrorsSettings.isEnabled(this)) return
        val age = SystemClock.uptimeMillis() - event.eventTime
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Log.i(
                TAG,
                "ev windows changes=0x${Integer.toHexString(event.windowChanges)}" +
                    " age=${age}ms phase=${runtime.phase}",
            )
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Log.i(
                TAG,
                "ev state pkg=${event.packageName} age=${age}ms phase=${runtime.phase}",
            )
        }
    }

    private fun trigger(source: String, event: AccessibilityEvent) {
        val eventAgeMs = SystemClock.uptimeMillis() - event.eventTime
        val runtime = ClusterSceneService.cameraRuntimeSnapshot()
        val reason = "guard $source age=${eventAgeMs}ms session=${runtime.side}"
        Log.i(TAG, "trigger: $reason")
        ClusterSceneService.emergencyReleaseCamera(reason)
        snapshotWindows()
    }

    /** Returns titles of windows not present in the last snapshot, updating it. */
    private fun collectNewWindowTitles(): List<CharSequence?> {
        val current = windows ?: return emptyList()
        val titles = ArrayList<CharSequence?>(2)
        val currentIds = HashSet<Int>(current.size)
        for (window in current) {
            currentIds.add(window.id)
            if (window.id !in knownWindowIds) {
                titles.add(window.title)
            }
        }
        knownWindowIds.clear()
        knownWindowIds.addAll(currentIds)
        return titles
    }

    private fun snapshotWindows() {
        val current = windows ?: return
        knownWindowIds.clear()
        for (window in current) {
            knownWindowIds.add(window.id)
        }
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "guard disconnected")
        knownWindowIds.clear()
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "DenzaMirrorGuard"
    }
}
