package dev.denza.apps.feature.vehicle

import kotlin.math.min

/**
 * Two minutes of what the combustion half put back into the pack.
 *
 * The cluster already gives generation a figure and the revolutions a number of their own. What is
 * nowhere else is the *shape*: whether the kilowatts the engine is returning are climbing, steady or
 * sagging, and how long it has been at it. This trace took the room once spent on a duplicate tank
 * percentage and keeps that short history.
 *
 * ### One series, since the eighth pass
 *
 * It carried the revolutions as well until the owner looked at the built panel and could not read
 * the legend that told the two runs apart. A driver's display does not get to need a key, so the
 * box draws one quantity and the revolutions keep the number they already have in the corner - which
 * is where they were being read anyway.
 *
 * ### And "alive" is the engine's own flag, since the first drive
 *
 * Until 2026-09-05 a slot counted as alive when the rpm reading was above zero *or* the generation
 * reading was, on the argument that whichever id answered first, the engine had turned. On the first
 * drive the box stood on the shelf for half the trip with the engine off - «плоская синяя полоса»,
 * a flat blue line at zero, coming and going with speed rather than with the engine. One of those
 * two ids is not zero on an electric drive, and which one is a question for the car
 * (`docs/vehicle-data-findings.md`); neither is an engine-alive test this panel has proved. The
 * flag `ENGINE_RUNNING` is: it has been read through a full start/stop cycle and answers 0 or 3 and
 * nothing else. So [sample] takes the flag. An engine that turns while returning nothing is still
 * alive under it, which is the property the rpm was there for.
 *
 * ### The axis is time, not sweeps
 *
 * The poll runs about four times a second while the dashboard is on screen and backs off when the
 * shell struggles, so a trace of "the last hundred and twenty readings" would silently stretch and
 * shrink. Readings land in fixed one-second slots, and a slot the poll did not reach stays empty
 * rather than being interpolated across.
 *
 * ### And the steps are anchored to the clock, not to the run
 *
 * [snapshot] hands out the [BIN_SECONDS]-second steps the box draws rather than the seconds behind
 * them, and each step's membership is decided by the absolute second counter: bin `n` holds the
 * slots whose own number divides into it, whatever else the deque holds. Grouping from the oldest
 * slot instead - which is what this did - re-phased every bin once the window was full and the front
 * started being evicted, so all twenty-four step heights were recomputed every second and the box
 * never drew the same two minutes twice. That also contradicted the box's own documented behaviour:
 * a right-anchored grid whose grouping is stable.
 */
internal class EngineTrace(
    private val slotMillis: Long = SLOT_MS,
    private val capacity: Int = SLOTS,
    private val binSeconds: Int = BIN_SECONDS,
) {
    private val generation = ArrayDeque<Double?>()
    private val running = ArrayDeque<Boolean>()
    private var lastSlot = Long.MIN_VALUE

    /** The widest the box ever is, in steps. */
    private val maxBins: Int = (capacity / binSeconds).coerceAtLeast(1)

    /**
     * One sweep's answer.
     *
     * [atMillis] must come from a monotonic clock. Several readings inside one second are ordinary
     * at the dashboard's cadence and the newest of them wins: a slot is a second, and the last
     * answer in it is the one a driver looking at the dashboard would have seen.
     *
     * [engineRunning] is not kept as a series. It decides whether this slot counts as one the engine
     * was alive in, which is what holds the box on the shelf through a generating pause; null - the
     * flag did not answer - is a slot the engine was not known to be alive in, and a generation
     * figure with no flag behind it is recorded but does not make the slot alive.
     */
    fun sample(atMillis: Long, engineRunning: Boolean?, generationKw: Double?) {
        val slot = atMillis / slotMillis
        val alive = engineRunning == true
        when {
            lastSlot == Long.MIN_VALUE -> Unit

            slot == lastSlot -> {
                generation[generation.lastIndex] = generationKw
                running[running.lastIndex] = alive
                return
            }

            // A clock that went backwards is not a history this can extend.
            slot < lastSlot -> {
                generation.clear()
                running.clear()
            }

            else -> repeat((slot - lastSlot - 1).coerceAtMost(capacity.toLong()).toInt()) {
                push(null, false)
            }
        }
        lastSlot = slot
        push(generationKw, alive)
    }

    fun reset() {
        generation.clear()
        running.clear()
        lastSlot = Long.MIN_VALUE
    }

    /**
     * The trace as the Contour draws it: **from the oldest slot the engine was alive in, to now**,
     * already grouped into the steps the box paints.
     *
     * Not front-padded to [capacity], and that is the whole of the engine box's behaviour
     * (CRITIQUE M7). The box's right edge is fixed and its width is the length of this run, so it
     * grows leftward as the engine's history fills and is never drawn empty. After the engine stops
     * the slots keep arriving dead, which walks the last live sample toward the left edge; when it
     * falls off, there is no live slot left, this returns [EngineTraceSnapshot.EMPTY] and the box
     * leaves. That is 120 seconds of hysteresis with no timer of its own - the trace's own length is
     * the timer - so a winter jam restarting the engine every ninety seconds never swaps the shelf
     * back and forth.
     *
     * A padded list could not express it: with 120 slots always present the box would be born full
     * width and would have to be given a timer to decide when to go.
     *
     * Built once per sweep, which is what [EngineTraceSnapshot.bins] being an array rather than a
     * method is for: the renderer used to group the run inside `onDraw`, allocating a list and
     * boxing every mean thirty times a second over a quantity that changes four.
     */
    fun snapshot(): EngineTraceSnapshot {
        val first = running.indices.firstOrNull { running[it] } ?: return EngineTraceSnapshot.EMPTY
        val size = generation.size
        val oldest = lastSlot - (size - 1)
        val span = binSeconds.toLong()
        val firstBin = (oldest + first).floorDiv(span)
        val lastBin = lastSlot.floorDiv(span)
        val count = min(lastBin - firstBin + 1L, maxBins.toLong()).toInt()
        val base = lastBin - count + 1L

        val sums = DoubleArray(count)
        val seen = IntArray(count)
        for (index in first until size) {
            val value = generation[index] ?: continue
            val bin = ((oldest + index).floorDiv(span) - base).toInt()
            // A step older than the box is wide has already left it at the left edge.
            if (bin < 0) continue
            sums[bin] += value
            seen[bin]++
        }
        val bins = FloatArray(count) {
            if (seen[it] == 0) Float.NaN else (sums[it] / seen[it]).toFloat()
        }
        return EngineTraceSnapshot(bins, binSeconds, size - first)
    }

    private fun push(generationKw: Double?, alive: Boolean) {
        generation.addLast(generationKw)
        running.addLast(alive)
        if (generation.size > capacity) generation.removeFirst()
        if (running.size > capacity) running.removeFirst()
    }

    companion object {
        /** One slot is a second: finer than a driver reads, coarser than the poll jitters. */
        const val SLOT_MS = 1_000L

        /** Two minutes, which is about as far back as "has it been generating a while" reaches. */
        const val SLOTS = 120

        /**
         * And the box draws them as steps of five seconds, the way the petal draws its buckets.
         *
         * A per-second line across that box was 120 points inside 526 units - 4.4 apart, which is
         * 0.9 mm of glass for one sample of a quantity that moves on the scale of a traffic light.
         * `ContourPlan` takes its own step size from here so the two cannot drift.
         */
        const val BIN_SECONDS = 5
    }
}

/**
 * What the renderer is handed: the box's steps, oldest first, `NaN` where nothing answered.
 *
 * A bin is the mean of the samples that arrived inside it, so a poll the shell missed costs
 * resolution rather than a hole; a bin nothing answered in at all is `NaN` and breaks the area
 * rather than being drawn through. **The bin that is still filling is the newest one**, at the edge
 * where new data arrives, and the oldest is whatever the retained window has left of its own step.
 *
 * An array of primitives rather than a list of boxed doubles because this is read in every frame and
 * built in none of them.
 */
internal class EngineTraceSnapshot(
    val bins: FloatArray,
    /** How many seconds one step covers. */
    val binSeconds: Int = EngineTrace.BIN_SECONDS,
    /** How far back the box reaches, in seconds, which is what its caption names. */
    val spanSeconds: Int = 0,
) {

    /** Whether the engine has been alive inside the retained window at all. */
    val isEmpty: Boolean get() = bins.isEmpty()

    companion object {
        val EMPTY = EngineTraceSnapshot(FloatArray(0))
    }
}
