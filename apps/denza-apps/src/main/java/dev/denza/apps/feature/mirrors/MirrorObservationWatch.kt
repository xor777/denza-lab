package dev.denza.apps.feature.mirrors

/**
 * How long ago the monitor last read the car, kept apart from the thread that reads it.
 *
 * Every ending of a camera is decided on a read: the window poll over the local ADB shell, or a
 * lamp event from the vehicle-signal feed, which is fed by the same adbd. With both down the
 * reducer's own timeouts never run, because they run only after a poll that succeeded, and a
 * camera of ours could stay on the cluster after the turn ended. The monitor checks this on its
 * own thread and, past [DEADLINE_MS], closes the camera the way the lamps going off close it and
 * tells the tile it cannot read the car.
 *
 * [DEADLINE_MS] holds one shell read that times out (2.5 s) plus the next poll, so a slow read
 * alone never closes a camera; it is twice AVC's own two-second tail, so the camera outlives the
 * stock card by a couple of seconds at most.
 */
internal class MirrorObservationWatch(startedAtMs: Long) {
    @Volatile private var lastReadAtMs = startedAtMs

    /** The last read's failure, for the status line; null after a read that succeeded. */
    @Volatile var lastError: String? = null
        private set

    /** A poll read the car and applied what it saw. */
    fun read(nowMs: Long) {
        lastReadAtMs = nowMs
        lastError = null
    }

    fun failed(error: String) {
        lastError = error
    }

    fun ageMs(nowMs: Long): Long = nowMs - lastReadAtMs

    /** Nothing has been read for the deadline: the monitor can no longer see the car. */
    fun lost(nowMs: Long): Boolean = ageMs(nowMs) >= DEADLINE_MS

    companion object {
        const val DEADLINE_MS = 4_000L

        /** How often the deadline is looked at, on its own thread. */
        const val CHECK_MS = 250L
    }
}
