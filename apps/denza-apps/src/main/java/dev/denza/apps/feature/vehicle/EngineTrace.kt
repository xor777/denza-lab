package dev.denza.apps.feature.vehicle

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
 * is where they were being read anyway. The rpm reading still decides one thing here and only one:
 * [sample] takes it because **a slot the engine was alive in is a slot it turned in**, and an engine
 * that is running while it happens to return nothing is still an engine the box should be up for.
 *
 * ### The axis is time, not sweeps
 *
 * The poll runs at 300 ms while the dashboard is on screen and backs off when the shell struggles,
 * so a trace of "the last hundred and twenty readings" would silently stretch and shrink. Readings
 * land in fixed one-second slots, and a slot the poll did not reach stays empty rather than being
 * interpolated across.
 */
internal class EngineTrace(
    private val slotMillis: Long = SLOT_MS,
    private val capacity: Int = SLOTS,
) {
    private val generation = ArrayDeque<Double?>()
    private val running = ArrayDeque<Boolean>()
    private var lastSlot = Long.MIN_VALUE

    /** How far back a full trace reaches, for whoever writes the window into a caption. */
    val spanSeconds: Int = (capacity * slotMillis / 1_000L).toInt()

    /**
     * One sweep's answer.
     *
     * [atMillis] must come from a monotonic clock. Several readings inside one second are ordinary
     * at the dashboard's cadence and the newest of them wins: a slot is a second, and the last
     * answer in it is the one a driver looking at the dashboard would have seen.
     *
     * [rpm] is not kept. It decides whether this slot counts as one the engine was alive in, which
     * is what holds the box on the shelf through a generating pause.
     */
    fun sample(atMillis: Long, rpm: Double?, generationKw: Double?) {
        val slot = atMillis / slotMillis
        val alive = (rpm ?: 0.0) > 0.0 || (generationKw ?: 0.0) > 0.0
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
     * The trace as the Contour draws it: **from the oldest slot the engine was alive in, to now.**
     *
     * Not front-padded to [capacity], and that is the whole of the engine box's behaviour
     * (CRITIQUE M7). The box's right edge is fixed and its width is the length of this list, so it
     * grows leftward as the engine's history fills and is never drawn empty. After the engine stops
     * the slots keep arriving dead, which walks the last live sample toward the left edge; when it
     * falls off, there is no live slot left, this returns [EngineTraceSnapshot.EMPTY] and the box
     * leaves. That is 120 seconds of hysteresis with no timer of its own - the trace's own length is
     * the timer - so a winter jam restarting the engine every ninety seconds never swaps the shelf
     * back and forth.
     *
     * A padded list could not express it: with 120 slots always present the box would be born full
     * width and would have to be given a timer to decide when to go.
     */
    fun snapshot(): EngineTraceSnapshot {
        val first = running.indices.firstOrNull { running[it] } ?: return EngineTraceSnapshot.EMPTY
        return EngineTraceSnapshot(
            generationKw = generation.drop(first),
            spanSeconds = spanSeconds,
        )
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
    }
}

/**
 * What the renderer is handed: one run of kilowatts, oldest first, `null` where nothing answered.
 *
 * The run starts at the oldest slot the engine was alive in and ends at now, so [slots] is the box's
 * own width in seconds and an empty snapshot means there is no box. Built once per sweep beside the
 * rest of the snapshot rather than per frame, the way the consumption bars are.
 */
internal data class EngineTraceSnapshot(
    val generationKw: List<Double?>,
    val spanSeconds: Int = 0,
) {

    /** The box's width, in seconds. */
    val slots: Int get() = generationKw.size

    /** Whether the engine has been alive inside the retained window at all. */
    val isEmpty: Boolean get() = generationKw.isEmpty()

    /**
     * The same run as steps of [binSeconds], oldest first: what the box actually draws.
     *
     * A bin is the mean of the samples that arrived inside it, so a poll the shell missed costs
     * resolution rather than a hole; a bin nothing answered in at all is `null` and breaks the area
     * rather than being drawn through. **The bin that can be short is the newest one**, because the
     * grouping runs from the oldest slot and the box grows from the right - so a history 82 seconds
     * long is sixteen full steps and one of two, at the right-hand edge where the new data arrives.
     *
     * Per-second points were what the box drew until the eighth pass: 120 of them across 526 units
     * is 4.4 apart, which is 0.9 mm of glass for one sample of a quantity that moves on the scale of
     * a traffic light.
     */
    fun bins(binSeconds: Int, limit: Int): List<Double?> {
        if (binSeconds <= 0 || limit <= 0) return emptyList()
        val out = ArrayList<Double?>((slots + binSeconds - 1) / binSeconds)
        var start = 0
        while (start < slots) {
            var sum = 0.0
            var seen = 0
            for (index in start until minOf(start + binSeconds, slots)) {
                generationKw[index]?.let {
                    sum += it
                    seen++
                }
            }
            out.add(if (seen == 0) null else sum / seen)
            start += binSeconds
        }
        return if (out.size <= limit) out else out.subList(out.size - limit, out.size)
    }

    companion object {
        val EMPTY = EngineTraceSnapshot(emptyList())
    }
}
