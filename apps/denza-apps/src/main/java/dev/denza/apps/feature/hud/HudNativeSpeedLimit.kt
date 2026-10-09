package dev.denza.apps.feature.hud

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ServiceCallParcel

/**
 * The car's own speed sign, fed with the limit Yandex shows.
 *
 * The stock navigator gives the car its map limit through two Setting properties,
 * `SETTING_SPEED_LIMIT_SET` and `SETTING_RODE_TYPE_SET`; the cluster and the HUD sign follow what
 * the car's speed-limit assistance then outputs, `ADAS_SLA_OUTPUT_SPEED_LIMIT`. Those writes need
 * a `BYDAUTO_*` permission the app UID does not hold, so they go through the shell like the speaker
 * report. The order - road type 7, the limit, road type 6, 100 ms apart - and the readback
 * are the sequence other senders found to be taken on BYD cars; see
 * docs/instrument-display-findings.md, "The car's speed sign from Yandex".
 */
internal object HudNativeSpeedLimitProtocol {
    private const val SERVICE = "autoservice"
    private const val TRANSACT_GET_INT = 5
    private const val TRANSACT_GET_INT_ALT = 7
    private const val TRANSACT_SET_INT = 6

    /** `BYDAUTO_DEVICE_SETTING`. */
    private const val DEVICE_SETTING = 1023

    /** `BYDAUTO_DEVICE_ADAS`. */
    private const val DEVICE_ADAS = 1038

    /** `SETTING_SPEED_LIMIT_SET`, `0x4CA00040`, km/h. */
    private const val SPEED_LIMIT_SET = 1_285_554_240

    /** `SETTING_RODE_TYPE_SET`, `0x4CA00050`. */
    private const val ROAD_TYPE_SET = 1_285_554_256

    /** `ADAS_SLA_OUTPUT_SPEED_LIMIT`, `0x2D500020`: `limit / 5 + 1`. */
    private const val SLA_OUTPUT_SPEED_LIMIT = 760_217_632

    private const val ROAD_TYPE_BEFORE = 7
    private const val ROAD_TYPE_AFTER = 6

    private const val MARKER = "@@"

    /** -10013: the call went to the wrong transact. Like -10011 (not here), never a value. */
    private const val WRONG_TRANSACT = 0xFFFFD8E3.toInt()

    /** The values the setting takes: 5 to 130 km/h on the 5 km/h grid. */
    fun supported(limitKmh: Int): Boolean = limitKmh in 5..130 && limitKmh % 5 == 0

    fun expectedRaw(limitKmh: Int): Int = limitKmh / 5 + 1

    fun readCommand(alternateTransact: Boolean = false): String {
        val transact = if (alternateTransact) TRANSACT_GET_INT_ALT else TRANSACT_GET_INT
        return "service call $SERVICE $transact i32 $DEVICE_ADAS i32 $SLA_OUTPUT_SPEED_LIMIT"
    }

    /** The three writes in one shell trip, each tagged so its answer cannot land on another. */
    fun writeCommand(limitKmh: Int): String {
        require(supported(limitKmh)) { "unsupported limit $limitKmh" }
        return listOf(
            ROAD_TYPE_SET to ROAD_TYPE_BEFORE,
            SPEED_LIMIT_SET to limitKmh,
            ROAD_TYPE_SET to ROAD_TYPE_AFTER,
        ).mapIndexed { index, (fid, value) ->
            "echo $MARKER$index; service call $SERVICE $TRANSACT_SET_INT " +
                "i32 $DEVICE_SETTING i32 $fid i32 $value null"
        }.joinToString("; sleep 0.1; ")
    }

    /** What one ADAS read said. */
    sealed interface Read {
        data class Value(val raw: Int) : Read
        object WrongTransact : Read
        object Failed : Read
    }

    /** A read answers `Parcel(00000000 0000000d ...)`: status, then the value. */
    fun parseRead(output: String): Read {
        val words = ServiceCallParcel.oneLineWords(output)?.take(2) ?: return Read.Failed
        return when {
            words.any { it == WRONG_TRANSACT } -> Read.WrongTransact
            words.size < 2 -> Read.Failed
            words[1] < 0 -> Read.Failed
            else -> Read.Value(words[1])
        }
    }

    /**
     * Whether all three writes were answered. A write answers one word, its status (`1` for the
     * speaker report); the negative ones (-10011, -10013) are refusals. An answer is not proof the
     * car took the value - the ADAS read is - but a missing or negative one is proof it did not.
     */
    fun writeAnswered(output: String): Boolean {
        val answered = BooleanArray(3)
        var index = -1
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith(MARKER)) {
                index = line.removePrefix(MARKER).toIntOrNull() ?: -1
            } else if (index in answered.indices && !answered[index]) {
                val status = ServiceCallParcel.words(line)?.firstOrNull() ?: return@forEach
                answered[index] = status >= 0
            }
        }
        return answered.all { it }
    }
}

/**
 * When to read the car's sign and when to write Yandex's limit into it. Pure and single-threaded;
 * [HudNativeSpeedLimitRunner] drives it from one worker thread.
 *
 * - A new target is read first; a car already showing it is left alone.
 * - A sign that differs is written after it has stayed different for [BASE_DELAY_MS], or for
 *   [BASE_DELAY_MS] + [ADAS_CHANGE_DELAY_MS] when the car moved away from a value we had written and
 *   seen it take (its camera read a sign).
 * - A write counts once the read shows it within [CONFIRM_MS]; at most [MAX_ATTEMPTS] writes per
 *   divergence, [RETRY_MS] apart.
 * - No target means nothing happens. Nothing is cleared: a route that ends or a sign Yandex hides
 *   leaves the car's own assistance to carry on from what it last had.
 */
internal class HudNativeSpeedLimitEngine(private val log: (String) -> Unit = {}) {
    private var target: Int? = null
    private var cycle = 0
    private var attempt = 0
    private var confirmed = false
    private var awaiting = false
    private var confirmAt = 0L
    private var delayStarted = false
    private var delayDueAt = 0L
    private var nextAttemptAt = 0L
    private var nextReadAt = 0L
    private var lastRaw: Int? = null
    private var writtenRaw = 0
    private var confirmedRaw = 0
    private var adasChanged = false
    private var readFailing = false

    val currentTarget: Int? get() = target

    fun setTarget(limitKmh: Int?, nowMs: Long) {
        val next = limitKmh?.takeIf(HudNativeSpeedLimitProtocol::supported)
        if (next == target) return
        if (limitKmh != null && next == null) log("target $limitKmh unsupported, left to the car")
        target = next
        cycle = if (next == null) 0 else 1
        attempt = 0
        confirmed = false
        awaiting = false
        delayStarted = false
        nextAttemptAt = 0L
        nextReadAt = nowMs
        if (next != null) log("target $next expectedRaw=${HudNativeSpeedLimitProtocol.expectedRaw(next)}")
    }

    /** Milliseconds until the next read, or null while there is no target. */
    fun nextReadDelayMs(nowMs: Long): Long? {
        target ?: return null
        expireConfirmation(nowMs)
        return (nextReadAt - nowMs).coerceAtLeast(0L)
    }

    /** Feeds one read (null when it failed) and returns the limit to write now, if any. */
    fun onRead(raw: Int?, nowMs: Long): Int? {
        val goal = target ?: return null
        if (raw == null) {
            if (!readFailing) log("read failed, retry in ${READ_RETRY_MS} ms")
            readFailing = true
            nextReadAt = nowMs + READ_RETRY_MS
            return null
        }
        readFailing = false
        val changed = lastRaw != null && raw != lastRaw
        if (writtenRaw != 0 && raw == writtenRaw) {
            confirmedRaw = raw
            adasChanged = false
        }
        if (raw == HudNativeSpeedLimitProtocol.expectedRaw(goal)) {
            if (!confirmed) {
                val outcome = when {
                    attempt == 0 -> "observed-match"
                    awaiting && nowMs <= confirmAt -> "confirmed"
                    else -> "late-match"
                }
                log("$outcome target=$goal cycle=$cycle attempt=$attempt")
            }
            confirmed = true
            awaiting = false
            delayStarted = false
            adasChanged = false
        } else {
            if (changed && confirmedRaw != 0) adasChanged = raw != confirmedRaw
            if (confirmed) {
                confirmed = false
                cycle++
                attempt = 0
                log("car moved to raw=$raw from target=$goal, cycle=$cycle")
                startDelay(nowMs)
            } else if (!delayStarted || changed) {
                startDelay(nowMs)
            }
            expireConfirmation(nowMs)
        }
        lastRaw = raw
        nextReadAt = nowMs + if (awaiting) CONFIRM_READ_MS else READ_MS
        if (!shouldWrite(nowMs)) return null
        attempt++
        nextAttemptAt = nowMs + RETRY_MS
        log("write target=$goal cycle=$cycle attempt=$attempt")
        return goal
    }

    /** The write [onRead] asked for has finished at [nowMs]. */
    fun onWritten(answered: Boolean, nowMs: Long) {
        val goal = target ?: return
        if (answered) {
            writtenRaw = HudNativeSpeedLimitProtocol.expectedRaw(goal)
            awaiting = true
            confirmAt = nowMs + CONFIRM_MS
            nextReadAt = nowMs + CONFIRM_READ_MS
        } else {
            log("write unanswered target=$goal cycle=$cycle attempt=$attempt")
            nextReadAt = nowMs + READ_MS
        }
    }

    private fun shouldWrite(nowMs: Long): Boolean =
        target != null && !confirmed && !awaiting && attempt < MAX_ATTEMPTS &&
            delayStarted && nowMs >= delayDueAt && nowMs >= nextAttemptAt

    private fun startDelay(nowMs: Long) {
        delayStarted = true
        delayDueAt = nowMs + BASE_DELAY_MS + if (adasChanged) ADAS_CHANGE_DELAY_MS else 0L
    }

    private fun expireConfirmation(nowMs: Long) {
        if (!awaiting || nowMs <= confirmAt) return
        awaiting = false
        log(
            "unconfirmed target=$target cycle=$cycle attempt=$attempt raw=$lastRaw" +
                if (attempt >= MAX_ATTEMPTS) ", attempts spent" else "",
        )
    }

    companion object {
        const val READ_MS = 1_000L
        const val CONFIRM_READ_MS = 300L
        const val READ_RETRY_MS = 10_000L
        const val CONFIRM_MS = 3_000L
        const val RETRY_MS = 10_000L
        const val BASE_DELAY_MS = 1_000L
        const val ADAS_CHANGE_DELAY_MS = 5_000L
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * One worker thread that runs [HudNativeSpeedLimitEngine] against the shell. Targets arrive from
 * the guidance monitor on the main thread; every shell call happens here.
 */
internal class HudNativeSpeedLimitRunner(context: Context) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("denza-hud-speed-limit").apply { start() }
    private val handler = Handler(thread.looper)
    private val engine = HudNativeSpeedLimitEngine { Log.i(TAG, it) }
    private val pump = Runnable { step() }
    private var alternateRead = false

    fun setTarget(limitKmh: Int?) {
        handler.post {
            engine.setTarget(limitKmh, SystemClock.uptimeMillis())
            schedule()
        }
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun schedule() {
        handler.removeCallbacks(pump)
        val delay = engine.nextReadDelayMs(SystemClock.uptimeMillis()) ?: return
        handler.postDelayed(pump, delay)
    }

    private fun step() {
        engine.currentTarget ?: return
        val raw = read()
        val write = engine.onRead(raw, SystemClock.uptimeMillis())
        if (write != null) {
            engine.onWritten(write(write), SystemClock.uptimeMillis())
        }
        schedule()
    }

    private fun read(): Int? {
        repeat(2) {
            when (val read = HudNativeSpeedLimitProtocol.parseRead(shell(
                HudNativeSpeedLimitProtocol.readCommand(alternateRead),
            ))) {
                is HudNativeSpeedLimitProtocol.Read.Value -> return read.raw
                HudNativeSpeedLimitProtocol.Read.WrongTransact -> alternateRead = !alternateRead
                HudNativeSpeedLimitProtocol.Read.Failed -> return null
            }
        }
        return null
    }

    private fun write(limitKmh: Int): Boolean =
        HudNativeSpeedLimitProtocol.writeAnswered(
            shell(HudNativeSpeedLimitProtocol.writeCommand(limitKmh)),
        )

    private fun shell(command: String): String = try {
        DenzaLocalAdb.client(appContext).shell(command, SHELL_TIMEOUT_MS)
    } catch (error: Exception) {
        Log.w(TAG, "shell failed: ${error.message}")
        ""
    }

    private companion object {
        const val TAG = "DenzaHudSpeedLimit"
        const val SHELL_TIMEOUT_MS = 4_000
    }
}
