package dev.denza.apps.feature.diagnostics.report

/** Where a report goes: the shared «Загрузки» on the device ([DownloadsReportWriter]), a fake in tests. */
internal interface ReportWriter {
    /** The newest stream recording, or null; called off the caller's thread. */
    fun lastRecording(): ReportRecording?

    /**
     * Writes [bundle] - the PDF, then the JSON and the recording where it has them - under the first
     * free name of its base, and says where they went. Throws when any file cannot be written, leaving
     * none of this bundle behind.
     */
    fun write(bundle: ReportBundle): ReportOutcome.Saved

    companion object {
        /** A session without storage: nothing to copy, and every save fails quietly. */
        val NONE: ReportWriter = object : ReportWriter {
            override fun lastRecording(): ReportRecording? = null
            override fun write(bundle: ReportBundle): ReportOutcome.Saved = throw IllegalStateException("no report writer")
        }
    }
}

/**
 * The section's side of «Сохранить» (architecture, 7.4): one save at a time, its [outcome] for the
 * model, and the newest recording the offer names. A failure is [ReportOutcome.Failed] and a log line,
 * never a message: the button is there again and a second press tries again.
 */
internal class ReportDesk(private val writer: ReportWriter, private val log: (String) -> Unit = {}) {
    @Volatile var outcome: ReportOutcome = ReportOutcome.Idle
        private set

    @Volatile var recording: ReportRecording? = null
        private set

    /** The section opened: the last outcome is forgotten unless a save still runs. */
    fun open() {
        if (outcome != ReportOutcome.Saving) outcome = ReportOutcome.Idle
    }

    /** Looks for the newest recording; off the caller's thread. */
    fun look() {
        recording = runCatching { writer.lastRecording() }.onFailure { log("diagnostics: recordings unreadable: $it") }.getOrNull()
    }

    /** Takes the desk for a save; false while one runs. Under the session's lock. */
    fun claim(): Boolean {
        if (outcome == ReportOutcome.Saving) return false
        outcome = ReportOutcome.Saving
        return true
    }

    /** A save that was claimed and never ran - its section closed first - is no save: the button comes back. */
    fun settle() {
        if (outcome == ReportOutcome.Saving) outcome = ReportOutcome.Failed
    }

    /** Makes the bundle and writes it, off the caller's thread; after [claim]. */
    fun save(make: () -> ReportBundle) {
        outcome = runCatching { writer.write(make()) }
            .onFailure { log("diagnostics: report not saved: $it") }
            .getOrElse { ReportOutcome.Failed }
    }
}
