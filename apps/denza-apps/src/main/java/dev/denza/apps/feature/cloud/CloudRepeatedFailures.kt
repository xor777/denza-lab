package dev.denza.apps.feature.cloud

/**
 * The adapter's failed passes, as its history tells them.
 *
 * A car whose local ADB has gone fails every pass the same way: every 15 s while the client is
 * connecting, every 30 s offline. Until 2026-11-19 each of those was a new history line - the same
 * words under a new UTC time - so every pass rewrote the private history, and with it the report in
 * Downloads, whose content carries that history ([CloudReportWrites] compares content; a new line
 * is a change). The history's 512-event cap then rolled out everything before the outage within
 * about two hours.
 *
 * Now a run of identical failures is told three times over: its first failure; again every
 * [refreshMs] while it lasts, with the count, so a process that dies in the middle has left the
 * last of these; and its end - the next pass that completes, a different failure, or the driver's
 * press. Everything in between is counted, not written. Identical means the same reason: which
 * pass met it (the tick, a status broadcast, a follow-up, the screen's read) does not split a run.
 *
 * On the controller's worker only.
 */
internal class CloudRepeatedFailures(private val refreshMs: Long = CloudReportWrites.REFRESH_MS) {
    private var reason: String? = null
    private var count = 0
    private var toldCount = 0
    private var toldAtMs = 0L

    /** The lines to record for a pass of [stage] that failed with [reason]; none for a repeat. */
    fun failed(stage: String, reason: String, nowMs: Long): List<String> {
        if (reason == this.reason) {
            count += 1
            if (nowMs - toldAtMs < refreshMs) return emptyList()
            toldAtMs = nowMs
            toldCount = count
            return listOf("$stage failed $reason (×$count in a row)")
        }
        val ended = end()
        this.reason = reason
        count = 1
        toldCount = 1
        toldAtMs = nowMs
        return listOfNotNull(ended, "$stage failed $reason")
    }

    /** A pass of [stage] completed: the run, if one was open, is over, and that is told. */
    fun passed(stage: String): List<String> {
        val failed = reason ?: return emptyList()
        val failures = count
        reset()
        return listOf("$stage passed after ×$failures failed in a row ($failed)")
    }

    /** The driver pressed the switch: what follows is a new story ([CloudLinkFailures.pressStarted]). */
    fun pressed(): List<String> = listOfNotNull(end())

    /** The count of a run that ends otherwise than by a pass, when it was not told already. */
    private fun end(): String? {
        val failed = reason ?: return null
        val untold = count > toldCount
        val failures = count
        reset()
        return if (untold) "the failure before repeated ×$failures in a row ($failed)" else null
    }

    private fun reset() {
        reason = null
        count = 0
        toldCount = 0
    }

    companion object {
        /**
         * The reason, for the report and the exported diagnostics; never the tile's words.
         * Exception messages and command output can carry identifiers: only our own controlled
         * messages ([IllegalStateException]) are kept, a transport failure is its class name.
         */
        fun reason(error: Throwable): String =
            if (error is IllegalStateException) {
                error.message.orEmpty().take(160)
            } else {
                "Нет ответа: ${error.javaClass.simpleName}"
            }
    }
}
