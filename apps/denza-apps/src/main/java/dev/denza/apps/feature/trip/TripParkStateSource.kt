package dev.denza.apps.feature.trip

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The one live-proven gearbox fact the trip needs: whether the selector is in P. */
internal object TripParkSignal {
    // autoservice getInt(dev=gearbox, fid=GEARBOX_PARK_SWITCH)
    const val COMMAND = "service call autoservice 5 i32 1011 i32 89129008"

    private val PARCEL = Regex("""Parcel\([0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")

    fun parse(output: String): Boolean? {
        val word = PARCEL.find(output)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt() ?: return null
        return when (word) {
            0 -> false
            1 -> true
            else -> null
        }
    }
}

/** Polls [TripParkSignal] only while the trip panel is visible. */
internal class TripParkStateSource(
    context: Context,
    private val onState: (Boolean?) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()

    /** Accessed only by [worker]. */
    private var shell: LocalAdbClient.PersistentShellSession? = null

    fun start() {
        val current = generation.incrementAndGet()
        schedule(current, 0L)
    }

    fun stop() {
        generation.incrementAndGet()
        worker.execute {
            shell?.runCatching { close() }
            shell = null
        }
    }

    private fun schedule(current: Int, delayMs: Long) {
        main.postDelayed(
            {
                if (generation.get() == current) poll(current)
            },
            delayMs,
        )
    }

    private fun poll(current: Int) {
        worker.execute {
            if (generation.get() != current) return@execute
            val state = runCatching {
                val session = shell ?: DenzaLocalAdb.client(app).openPersistentShell().also { shell = it }
                TripParkSignal.parse(session.shell(TripParkSignal.COMMAND, READ_TIMEOUT_MS))
            }.getOrElse {
                shell?.runCatching { close() }
                shell = null
                null
            }
            main.post {
                if (generation.get() != current) return@post
                onState(state)
                schedule(current, POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val READ_TIMEOUT_MS = 2_000
    }
}
