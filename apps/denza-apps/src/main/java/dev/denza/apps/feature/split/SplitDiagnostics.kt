package dev.denza.apps.feature.split

import android.os.SystemClock
import android.util.Log

/**
 * Where a line the split product writes actually ends up.
 *
 * It used to be `Log.i` and nothing else, and on this car that was a line into the void: two full
 * open runs produced not one entry of the `DenzaSplitScreen` tag although every path that writes
 * them demonstrably executed - the shield came down at 5529 ms, so the operation reached its
 * terminal, and a terminal is always recorded. The same tag written from the shell (`log -t
 * DenzaSplitScreen`) reaches the buffer on the same firmware, so the tag itself is not silenced;
 * what cannot be proven from here is that this application's own writes are accepted at all.
 *
 * A diagnostic nobody can read is the silent failure U5 forbids, so this sink no longer depends on
 * any single channel being accepted:
 *
 *  - `Log.i`, as before, because when it does work it is the cheapest and most useful;
 *  - a bounded in-process ring, read back by the support screen, which needs no logcat at all;
 *  - and the lines are handed to whatever operation next has a shell open, to be mirrored through
 *    the one channel this car is proven to accept. That costs one command per operation that was
 *    talking to the firmware anyway, and never opens a session of its own.
 */
internal object SplitDiagnostics {
    const val TAG = "DenzaSplitScreen"

    /** Enough to hold a whole open, its return and their terminals several times over. */
    private const val CAPACITY = 240

    /** One mirror command carries at most this many lines; the rest waits for the next one. */
    private const val MIRROR_BATCH = 40

    private val lock = Any()
    private val recorded = ArrayDeque<String>()
    private val unmirrored = ArrayDeque<String>()

    fun record(message: String) {
        Log.i(TAG, message)
        val line = "${SystemClock.elapsedRealtime()} $message"
        synchronized(lock) {
            recorded += line
            while (recorded.size > CAPACITY) recorded.removeFirst()
            unmirrored += line
            while (unmirrored.size > CAPACITY) unmirrored.removeFirst()
        }
    }

    /** Newest last, for the support screen. */
    fun recent(limit: Int): List<String> = synchronized(lock) { recorded.toList() }.takeLast(limit)

    /** Lines no proven channel has carried yet, taken once. */
    fun drainForMirror(): List<String> = synchronized(lock) {
        val batch = ArrayList<String>(minOf(MIRROR_BATCH, unmirrored.size))
        while (batch.size < MIRROR_BATCH && unmirrored.isNotEmpty()) {
            batch += unmirrored.removeFirst()
        }
        batch
    }

    /** The shell statement that carries [lines] into the buffer the tag is known to reach. */
    fun mirrorCommand(lines: List<String>): String {
        val payload = lines.joinToString("\n").replace("'", "'\\''")
        return "log -t $TAG -p i '$payload'"
    }
}
