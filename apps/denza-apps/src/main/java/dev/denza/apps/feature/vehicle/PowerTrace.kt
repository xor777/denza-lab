package dev.denza.apps.feature.vehicle

import kotlin.math.max
import kotlin.math.min

/**
 * Two minutes of what the pack is doing, as the strip's second page draws it.
 *
 * ### Why this is not [EngineTrace]
 *
 * They share an axis and nothing else. [EngineTrace] keeps what the *engine* returned and is
 * deliberately not a fixed window: it starts at the oldest slot the engine was alive in, grows
 * leftward, and goes empty when the last live slot falls off - which is what lets the cluster's
 * engine box appear and leave without a timer. The pack has no such thing as "not running", so this
 * window is always the same width and always drawn: what a driver reads off it is the rhythm of the
 * last two minutes, and a box whose width moved with the data would make two of those minutes
 * incomparable.
 *
 * ### The axis is time, not sweeps
 *
 * The poll runs about four times a second and backs off when the shell struggles, so "the last
 * hundred and twenty readings" would silently stretch and shrink. Readings land in fixed
 * one-second slots, several in one second collapse to the newest - a slot is a second, and the
 * last answer in it is the one somebody looking at the screen would have seen - and a second the
 * poll never reached stays empty rather than being interpolated across.
 *
 * ### And the steps are anchored to the clock
 *
 * [snapshot] hands out [BIN_SECONDS]-second steps, and a step holds the seconds whose own number
 * on the clock falls inside it. Grouping from the oldest slot instead re-phases every bin the
 * moment the window fills and the front starts being evicted, which recomputes all twenty-four
 * heights every second and means the same two minutes never come back the same shape.
 */
internal class PowerTrace(
    private val slotMillis: Long = SLOT_MS,
    private val capacity: Int = SLOTS,
    private val binSeconds: Int = BIN_SECONDS,
) {
    private val load = ArrayDeque<Double?>()
    private var lastSlot = Long.MIN_VALUE

    private val bins: Int = (capacity / binSeconds).coerceAtLeast(1)

    /**
     * One sweep's answer, in the pack's own convention: positive leaves the battery.
     *
     * [atMillis] must come from a monotonic clock.
     */
    fun sample(atMillis: Long, kw: Double?) {
        val slot = atMillis / slotMillis
        when {
            lastSlot == Long.MIN_VALUE -> Unit

            slot == lastSlot -> {
                load[load.lastIndex] = kw
                return
            }

            // A clock that went backwards is not a history this can extend.
            slot < lastSlot -> load.clear()

            else -> repeat((slot - lastSlot - 1).coerceAtMost(capacity.toLong()).toInt()) {
                push(null)
            }
        }
        lastSlot = slot
        push(kw)
    }

    fun reset() {
        load.clear()
        lastSlot = Long.MIN_VALUE
    }

    /**
     * The window as the steps the box paints, oldest first, `NaN` where nothing answered.
     *
     * Always [bins] wide once the car has been polled for two minutes, and shorter only while the
     * window is still filling - the box grows from the right, where new data arrives, which is the
     * same edge the analyser's newest column is on.
     */
    fun snapshot(): PowerTraceSnapshot {
        if (load.isEmpty()) return PowerTraceSnapshot.EMPTY
        val size = load.size
        val oldest = lastSlot - (size - 1)
        val span = binSeconds.toLong()
        val firstBin = oldest.floorDiv(span)
        val lastBin = lastSlot.floorDiv(span)
        val count = min(lastBin - firstBin + 1L, bins.toLong()).toInt()
        val base = lastBin - count + 1L

        val sums = DoubleArray(count)
        val seen = IntArray(count)
        for (index in 0 until size) {
            val value = load[index] ?: continue
            val bin = ((oldest + index).floorDiv(span) - base).toInt()
            // A step older than the box is wide has already left it at the left edge.
            if (bin < 0) continue
            sums[bin] += value
            seen[bin]++
        }
        val steps = FloatArray(count) {
            if (seen[it] == 0) Float.NaN else (sums[it] / seen[it]).toFloat()
        }
        return PowerTraceSnapshot(steps, binSeconds)
    }

    private fun push(kw: Double?) {
        load.addLast(kw)
        if (load.size > capacity) load.removeFirst()
    }

    companion object {
        /** One slot is a second: finer than anybody reads, coarser than the poll jitters. */
        const val SLOT_MS = 1_000L

        /** Two minutes, which is what the page's own caption promises. */
        const val SLOTS = 120

        /**
         * And the box draws them as steps of five seconds, which is the cluster's own grid.
         *
         * A per-sample line was the first drawing and the cluster settled the question already:
         * 120 points across half a metre of glass is 0.9 mm per sample of a quantity that moves
         * faster than the eye follows. Twenty-four steps is what a glance can read.
         */
        const val BIN_SECONDS = EngineTrace.BIN_SECONDS
    }
}

/**
 * What the renderer is handed: the steps, and the span they are drawn in.
 *
 * An array of primitives rather than a list of boxed floats because this is read in every frame
 * and built in none of them.
 */
internal class PowerTraceSnapshot(val steps: FloatArray, val binSeconds: Int) {

    val isEmpty: Boolean get() = steps.isEmpty() || steps.all { it.isNaN() }

    /**
     * A step, held inside the box it is drawn in.
     *
     * The ladder's last rung is a ceiling and not a promise: a reading past it is drawn flat
     * against the edge, which is the honest way to say "more than this box holds" and the one
     * thing that must never happen instead - drawing it over the figure above the box.
     */
    fun clamp(kw: Float, ceiling: Int, floor: Int): Float =
        kw.coerceIn(-floor.toFloat(), ceiling.toFloat())

    /** How far back the box actually reaches, which is what its caption may claim. */
    val seconds: Int get() = steps.size * binSeconds

    companion object {
        val EMPTY = PowerTraceSnapshot(FloatArray(0), PowerTrace.BIN_SECONDS)
    }
}

/**
 * The span the box is drawn in: one rung above the axis, one below, and one scale across both.
 *
 * A single fixed span was the first answer, borrowed from the cluster's engine box, and on this
 * page it is the wrong one - the quantity lives in two orders of magnitude at once. Sixty
 * kilowatts out is right for a climb and turns an eight-kilowatt generation into a sliver against
 * the axis; ten is right for the generation and clamps every acceleration flat. The owner's words
 * for the first drawing were that the box must not be «сплющен по вертикали» and that space, where
 * there is space, is to be used.
 *
 * So each half takes the smallest rung that holds it and the axis sits between the two at
 * `top / (top + bottom)`, which makes the kilowatts per pixel identical above and below: one
 * honest scale, and a full box whichever way the pack has been working.
 *
 * **A ladder rather than a fit.** A span that follows the data continuously redraws the same drive
 * at a new height every second; four rungs apart by a factor of two change rarely, visibly, and
 * the page says which pair is up - a shape names the span it is drawn in the way a figure names
 * the window it is true over.
 */
internal object PowerSpan {

    /**
     * Six rungs became eight when the owner asked the obvious question: *«что будет при расходе
     * 200 кВт и заряде 100 кВт?»* - and the honest answer was that the box drew them outside
     * itself, over the figure above it, because the ladder stopped at 160 and nothing clamped what
     * came off the end of it. This pack is gated at ±600 kW because it really can pull hundreds,
     * so the ladder reaches where the car does, and [PowerTraceSnapshot.clamp] holds anything past
     * the last rung against the edge of the box instead of drawing it in the head.
     */
    val RUNGS = intArrayOf(5, 10, 20, 40, 80, 160, 320, 640)

    /** The smallest rung that holds [magnitude], or the largest there is. */
    fun rung(magnitude: Float): Int =
        RUNGS.firstOrNull { magnitude <= it } ?: RUNGS.last()

    /** What leaves the pack, at the top of the box. */
    fun ceiling(steps: FloatArray): Int =
        rung(steps.fold(0f) { top, kw -> if (kw.isNaN()) top else max(top, kw) })

    /** And what comes back, at the bottom of it. */
    fun floor(steps: FloatArray): Int =
        rung(steps.fold(0f) { low, kw -> if (kw.isNaN()) low else max(low, -kw) })
}
