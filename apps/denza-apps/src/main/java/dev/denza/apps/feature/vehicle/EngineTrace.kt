package dev.denza.apps.feature.vehicle

/**
 * Two minutes of the combustion half, as two traces on one time axis.
 *
 * The cluster already gives current revolutions a number and a bar. What is nowhere else is the
 * *shape*: whether the engine has just woken, how long it has been holding a generating speed, and
 * whether the kilowatts it puts back are steady or sagging. This trace took the room once spent on
 * a duplicate tank percentage and keeps that short history on one shared time axis.
 *
 * The axis is time, not sweeps. The poll runs at 300 ms while the dashboard is on screen and backs
 * off when the shell struggles, so a trace of "the last hundred and twenty readings" would silently
 * stretch and shrink. Readings therefore land in fixed one-second slots, and a slot the poll did not
 * reach stays empty rather than being interpolated across.
 */
internal class EngineTrace(
    private val slotMillis: Long = SLOT_MS,
    private val capacity: Int = SLOTS,
) {
    private val revolutions = ArrayDeque<Double?>()
    private val generation = ArrayDeque<Double?>()
    private var lastSlot = Long.MIN_VALUE

    /** How far back a full trace reaches, for whoever writes the axis label. */
    val spanSeconds: Int = (capacity * slotMillis / 1_000L).toInt()

    /**
     * One sweep's answer.
     *
     * [atMillis] must come from a monotonic clock. Several readings inside one second are ordinary
     * at the dashboard's cadence and the newest of them wins: a slot is a second, and the last
     * answer in it is the one a driver looking at the dashboard would have seen.
     */
    fun sample(atMillis: Long, rpm: Double?, generationKw: Double?) {
        val slot = atMillis / slotMillis
        when {
            lastSlot == Long.MIN_VALUE -> Unit

            slot == lastSlot -> {
                revolutions[revolutions.lastIndex] = rpm
                generation[generation.lastIndex] = generationKw
                return
            }

            // A clock that went backwards is not a history this can extend.
            slot < lastSlot -> {
                revolutions.clear()
                generation.clear()
            }

            else -> repeat((slot - lastSlot - 1).coerceAtMost(capacity.toLong()).toInt()) {
                push(null, null)
            }
        }
        lastSlot = slot
        push(rpm, generationKw)
    }

    fun reset() {
        revolutions.clear()
        generation.clear()
        lastSlot = Long.MIN_VALUE
    }

    /**
     * The trace as the Contour draws it: **from the oldest slot the engine was alive in, to now.**
     *
     * Not front-padded to [capacity] any more, and that is the whole of the engine box's behaviour
     * (CRITIQUE M7). The box's right edge is fixed and its width is the length of this list, so it
     * grows leftward as the engine's history fills and is never drawn empty. After the engine stops
     * the slots keep arriving at zero, which walks the last live sample toward the left edge; when
     * it falls off, there is no live slot left, this returns [EngineTraceSnapshot.EMPTY] and the box
     * leaves. That is 120 seconds of hysteresis with no timer of its own - the trace's own length is
     * the timer - so a winter jam restarting the engine every ninety seconds never swaps the shelf
     * back and forth.
     *
     * A padded list could not express it: with 120 slots always present the box would be born full
     * width and would have to be given a timer to decide when to go.
     */
    fun snapshot(): EngineTraceSnapshot {
        val first = revolutions.indices.firstOrNull { alive(it) } ?: return EngineTraceSnapshot.EMPTY
        return EngineTraceSnapshot(
            revolutions = revolutions.drop(first),
            generationKw = generation.drop(first),
            spanSeconds = spanSeconds,
        )
    }

    /** A slot the engine was alive in: a reading arrived and it was not a resting zero. */
    private fun alive(index: Int): Boolean =
        (revolutions[index] ?: 0.0) > 0.0 || (generation[index] ?: 0.0) > 0.0

    private fun push(rpm: Double?, generationKw: Double?) {
        revolutions.addLast(rpm)
        generation.addLast(generationKw)
        if (revolutions.size > capacity) revolutions.removeFirst()
        if (generation.size > capacity) generation.removeFirst()
    }

    companion object {
        /** One slot is a second: finer than a driver reads, coarser than the poll jitters. */
        const val SLOT_MS = 1_000L

        /** Two minutes, which is about as far back as "has it been generating a while" reaches. */
        const val SLOTS = 120
    }
}

/**
 * What the renderer is handed: two equal-length runs, oldest first, `null` where nothing answered.
 *
 * The runs start at the oldest slot the engine was alive in and end at now, so [slots] is the box's
 * own width in seconds and an empty snapshot means there is no box. Built once per sweep beside the
 * rest of the snapshot rather than per frame, the way the consumption bars are.
 */
internal data class EngineTraceSnapshot(
    val revolutions: List<Double?>,
    val generationKw: List<Double?>,
    val spanSeconds: Int = 0,
) {
    init {
        require(revolutions.size == generationKw.size) { "traces share one time axis" }
    }

    /** The box's width, in seconds. */
    val slots: Int get() = revolutions.size

    /** Whether the engine has been alive inside the retained window at all. */
    val isEmpty: Boolean get() = revolutions.isEmpty()

    companion object {
        val EMPTY = EngineTraceSnapshot(emptyList(), emptyList())
    }
}
