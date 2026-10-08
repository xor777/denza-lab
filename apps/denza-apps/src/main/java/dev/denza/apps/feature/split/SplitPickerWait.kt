package dev.denza.apps.feature.split

/**
 * How long a picker waits for its selection to be over, and when it stops waiting (U5, 1.5.9).
 *
 * The picker runs in `:picker` and the coordinator in the main process, and the only thing that
 * ended the wait was the coordinator's answer through a `ResultReceiver`. A main process that dies
 * between taking the command and answering it - a crash elsewhere, the low-memory killer - never
 * answers, and the grid stayed dimmed under a spinner with every tap refused: the third state U5
 * does not allow. So the wait has an end of its own: the select's budget, which the coordinator's
 * deadline enforces on its side, plus a margin for what comes before the submit (the catalogue
 * read) and after the deadline (the binder hop and the post back). Past that the answer is not
 * coming, and the pane is a working picker again.
 *
 * Every wait has a number, so an answer that comes late for an earlier wait cannot end a newer
 * one. Plain JVM: the Activity owns the clock and the timer, this owns the decision.
 */
internal class SplitPickerWait(private val limitMs: Long = LIMIT_MS) {
    private var current: Pending? = null
    private var lastId = 0

    /** The package whose selection is being waited for, or `null` while the grid is live. */
    val packageName: String? get() = current?.packageName

    /** A tap: the number of the wait it starts, or `null` while an earlier one is still running. */
    fun begin(packageName: String, nowMs: Long): Int? {
        expire(nowMs)
        if (current != null) return null
        lastId += 1
        current = Pending(lastId, packageName, nowMs)
        return lastId
    }

    /**
     * The answer of wait [id] came, or its command was never taken.
     *
     * @return whether that ended the wait in progress.
     */
    fun answered(id: Int): Boolean {
        if (current?.id != id) return false
        current = null
        return true
    }

    /** @return whether the wait in progress has outlived its limit, and is therefore over now. */
    fun expire(nowMs: Long): Boolean {
        val pending = current ?: return false
        if (nowMs - pending.startedAtMs < limitMs) return false
        current = null
        return true
    }

    private data class Pending(val id: Int, val packageName: String, val startedAtMs: Long)

    companion object {
        /**
         * What comes on top of the select's own deadline: the catalogue read before the submit
         * (0.9-1.9 s measured when it has to scan), the binder hop and the post of the answer.
         */
        const val MARGIN_MS = 5_000L

        /** The emergency ceiling of 1.3.8, 15 s, reached the picker's way. */
        const val LIMIT_MS = SELECT_BUDGET_MS + MARGIN_MS
    }
}
