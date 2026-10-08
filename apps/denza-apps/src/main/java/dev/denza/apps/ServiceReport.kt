package dev.denza.apps

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * «Сервис» → «Технические сведения» and the split's «Журнал работы», built only while somebody is
 * looking at them.
 *
 * The report reads about twenty features, the displays and the package manager, and it used to be
 * built on every recompute of the dashboard - every turn signal, every cloud tick - for a page that
 * is closed nearly all the time. Now the panel opens it and closes it: while it is open it is
 * built at once and again every [periodMs] (its readings carry ages, «12 с назад», that would
 * otherwise stand still), and [rebuildNow] answers a change worth showing before the next tick.
 *
 * It needs no shell, so it does not care about the ADB gate: the seven-tap door behind the gate
 * opens the same panel and gets the same filled page.
 */
internal class ServiceReport(
    private val clock: Clock,
    private val periodMs: Long,
    private val build: () -> Pages,
    private val publish: (Pages) -> Unit,
) {
    /** The two pages, in the report's text format ([TechnicalReadings]). */
    data class Pages(val technicalDetails: String, val splitJournal: String)

    /** Where and when the report is built. One thread in the app; by hand in the tests. */
    interface Clock {
        fun every(periodMs: Long, task: Runnable): Cancellable

        fun now(task: Runnable)
    }

    fun interface Cancellable {
        fun cancel()
    }

    private val lock = Any()
    private var ticking: Cancellable? = null

    val isOpen: Boolean get() = synchronized(lock) { ticking != null }

    /** The panel is on screen (true) or gone (false). Idempotent both ways. */
    fun setOpen(open: Boolean) {
        synchronized(lock) {
            if (open == (ticking != null)) return
            ticking = if (open) clock.every(periodMs, ::rebuild) else {
                ticking?.cancel()
                null
            }
        }
    }

    /** Something the pages show changed: build them now rather than at the next tick. */
    fun rebuildNow() {
        if (isOpen) clock.now(::rebuild)
    }

    private fun rebuild() {
        if (!isOpen) return
        val pages = try {
            build()
        } catch (error: RuntimeException) {
            Log.w(TAG, "service report failed", error)
            return
        }
        publish(pages)
    }

    /** The app's clock: one daemon thread, which builds nothing while the panel is closed. */
    class ThreadClock(name: String) : Clock {
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            }

        override fun every(periodMs: Long, task: Runnable): Cancellable {
            val future: ScheduledFuture<*> =
                executor.scheduleWithFixedDelay(task, 0L, periodMs, TimeUnit.MILLISECONDS)
            return Cancellable { future.cancel(false) }
        }

        override fun now(task: Runnable) {
            executor.execute(task)
        }
    }

    private companion object {
        const val TAG = "DenzaApps.Report"
    }
}
