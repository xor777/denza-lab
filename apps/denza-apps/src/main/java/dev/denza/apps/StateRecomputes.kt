package dev.denza.apps

import android.os.SystemClock

/**
 * The last recomputes of the dashboard's state: what asked for each, how long it took and on
 * which thread it ran.
 *
 * Every event of every feature used to end in one full recompute - the launcher catalog with its
 * icons, the whole service report, every tile - on whatever thread raised it, the main one
 * included. How often and how long that was on the car nobody had measured, so the service page
 * carries the numbers: an owner photographs them before and after a build, and the two photos
 * are the measurement. It costs a clock read and a few fields a recompute, under one lock.
 */
internal class RecomputeLog(private val capacity: Int = CAPACITY) {

    /** One recompute: when it ended, what asked for it, where it ran and for how long. */
    data class Entry(
        val atMs: Long,
        val trigger: String,
        val thread: String,
        val durationUs: Long,
    )

    private val lock = Any()
    private val recent = ArrayDeque<Entry>(capacity)
    private var count = 0L
    private var mainCount = 0L
    private var totalUs = 0L
    private var mainTotalUs = 0L
    private var longestUs = 0L
    private var failures = 0L
    private var lastFailure: String? = null
    private var lastFailureAtMs = 0L

    fun record(atMs: Long, trigger: String, thread: String, durationUs: Long) {
        val entry = Entry(atMs, trigger, thread, durationUs.coerceAtLeast(0L))
        synchronized(lock) {
            if (recent.size == capacity) recent.removeFirst()
            recent.addLast(entry)
            count += 1
            totalUs += entry.durationUs
            if (entry.thread == MAIN_THREAD) {
                mainCount += 1
                mainTotalUs += entry.durationUs
            }
            if (entry.durationUs > longestUs) longestUs = entry.durationUs
        }
    }

    /**
     * A slice that could not be read: [what] and the error. The rest of that read was published
     * all the same; this is where the one left out is still seen.
     */
    fun recordFailure(atMs: Long, what: String, error: String) {
        synchronized(lock) {
            failures += 1
            lastFailure = "$what: $error"
            lastFailureAtMs = atMs
        }
    }

    /**
     * The section's rows: the totals since the process started, then the newest recomputes first.
     */
    fun rows(nowMs: Long, shown: Int = SHOWN): List<TechnicalRow> = synchronized(lock) {
        if (count == 0L) return listOf(TechnicalRow("Пересчётов", "0"))
        buildList {
            add(TechnicalRow("Пересчётов", "$count · на главном потоке $mainCount"))
            add(
                TechnicalRow(
                    "Время",
                    "всего ${duration(totalUs)} · на главном ${duration(mainTotalUs)} · " +
                        "в среднем ${duration(totalUs / count)} · самый долгий ${duration(longestUs)}",
                ),
            )
            lastFailure?.let { failure ->
                add(
                    TechnicalRow(
                        "Сбоев чтения",
                        "$failures · последний $failure · ${ago(nowMs, lastFailureAtMs)}",
                    ),
                )
            }
            recent.reversed().take(shown).forEach { entry ->
                add(
                    TechnicalRow(
                        entry.trigger,
                        "${duration(entry.durationUs)} · ${entry.thread} · " +
                            ago(nowMs, entry.atMs),
                    ),
                )
            }
        }
    }

    companion object {
        const val CAPACITY = 32
        const val SHOWN = 10
        const val MAIN_THREAD = "main"

        /** «0,4 мс», «12 мс», «1,2 с» - a decimal where it says something, a comma for it. */
        fun duration(us: Long): String = when {
            us < 10_000L -> "${tenths(us / 100L)} мс"
            us < 1_000_000L -> "${us / 1_000L} мс"
            else -> "${tenths(us / 100_000L)} с"
        }

        private fun tenths(value: Long): String = "${value / 10L},${value % 10L}"

        private fun ago(nowMs: Long, atMs: Long): String =
            "${((nowMs - atMs).coerceAtLeast(0L) / 1_000L)} с назад"
    }
}

/**
 * The process's one [RecomputeLog], and the clock it is read against. [DenzaStatePublisher] records
 * every read it makes here, under what asked for it.
 */
internal object StateRecomputes {
    val log = RecomputeLog()

    const val SECTION = "Пересчёт состояния"

    fun rows(): List<TechnicalRow> = log.rows(SystemClock.elapsedRealtime())
}
