package dev.denza.apps

/** A [ServiceReport.Clock] the test turns by hand: nothing runs until it says so. */
internal class ManualReportClock : ServiceReport.Clock {
    private class Periodic(val task: Runnable) {
        var cancelled = false
    }

    private val periodic = mutableListOf<Periodic>()
    private val pending = ArrayDeque<Runnable>()

    val running: Int get() = periodic.count { !it.cancelled }

    override fun every(periodMs: Long, task: Runnable): ServiceReport.Cancellable {
        val entry = Periodic(task)
        periodic += entry
        // A fixed-delay schedule with no initial delay runs once at once.
        pending.addLast(Runnable { if (!entry.cancelled) task.run() })
        return ServiceReport.Cancellable { entry.cancelled = true }
    }

    override fun now(task: Runnable) {
        pending.addLast(task)
    }

    /** Runs what is due now. */
    fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }

    /** One period passes: every live schedule runs once, then whatever that queued. */
    fun tick() {
        periodic.filterNot { it.cancelled }.forEach { it.task.run() }
        runPending()
    }
}
