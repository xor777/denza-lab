package dev.denza.apps.feature.vehicle

import dev.denza.apps.design.luminofor.LuminoforSpec
import kotlin.math.max

/**
 * What the pack takes while the engine runs: the cluster's engine block
 * (`docs/energy-display-contract.md` §2.5).
 *
 * The quantity is the pack's charge, `max(0, −P)`, counted only while the engine's own flag is up.
 * Not `GENERATION_KW`: on this firmware that id is above zero only during forced generation standing
 * in P, and on the move it reads zero while the engine charges the pack (the findings of
 * 2026-11-19). The pack's charge is what it is on both. Regeneration during an engine run is inside
 * it too - the pack cannot tell the two apart - and the trip's recuperation is counted with the
 * engine off only, so nothing is counted twice.
 *
 * ### Whose engine: the trip's, or the run standing in P (since 2026-11-21)
 *
 * **While a trip is open** ([TripEnergy.open]) the block is the trip's engine. Its figure and its
 * time are the trip's own «ЗАРЯД ОТ ДВС» and engine seconds, read off the ledger's [TripEnergy] this
 * class is handed with every sweep - one number in both places, not a second integral beside it -
 * and its chart is every second the engine ran in this trip. A hybrid cycles its engine on the
 * move; a stop and a start more than [RESTART_SECONDS] later is a **pause**: it is cut out of the
 * chart, which carries on where it stopped, and a mark stands where it was ([EngineChargeSnapshot.marks]).
 * A new trip starts the figure, the time and the chart from zero together, as it starts the trip.
 *
 * **With no trip open** - in P after a trip, or before the first one - the block is the run standing
 * there, as it was before 2026-11-21: its own figure and time, integrated sweep by sweep on the
 * ledger's intervals (a sweep's reading over the time since the previous one, nothing over a gap
 * longer than [OdometerGate.MAX_GAP_SECONDS]), from the later of the flag going up and the trip's
 * end. A trip that ends with the engine running hands the block to that run at the P; one that ends
 * with it at rest leaves its chart as it stopped, and a start in P is a run of its own.
 *
 * Either way a start within [RESTART_SECONDS] of the stop is the same uninterrupted run - the block
 * is still on the panel then, and a flag that read down once for a dropped read does not get to
 * break the curve under it: its off seconds are seconds of zero on the chart, and no mark. A flag
 * that did not answer is neither up nor down: it neither ends a run nor counts toward one, in its
 * figure, its time or its chart.
 *
 * ### The chart
 *
 * One sample a second: the mean of the sweeps that landed in that second, on the hub's monotonic
 * clock. A drawn point is the trailing mean of the last [SMOOTH_SECONDS] samples, the rule and the
 * constant of the ten-kilometre chart ([ConsumptionChart.SMOOTH_STEPS]), so the newest point is a
 * figure of its own - what the last ten seconds gave. **Each stretch between two pauses is smoothed
 * as a run of its own**: the seconds before it are samples of zero, as the seconds before the first
 * run are - the engine was off and gave nothing - so after a mark the curve rises from zero. A
 * second no sweep landed in keeps the newest reading - a reading stands until the next one, so the
 * mean is over time, not over sweeps, and a poll slower than a second does not step the curve - for
 * at most [OdometerGate.MAX_GAP_SECONDS], past which it is a hole and left out of the mean rather
 * than counted as a zero. The window is the last [WINDOW_SECONDS] seconds the engine ran, a point a
 * second; a younger chart is as many points as it has seconds, and the panel draws them from its
 * right edge at the full window's pitch, as the ten kilometres fill. After a stop the chart stays as
 * it was; a mark leaves with the samples around it.
 *
 * The samples live in memory. A process that restarts in the middle of a trip keeps the trip's
 * figure and time - they are the ledger's, which the journal restores - and refills the chart.
 *
 * Built once per sweep into an [EngineChargeSnapshot], which is what the screens read in every
 * frame and build in none.
 */
internal class EngineCharge {

    /** The chart's samples, a second each, oldest first: kilowatts, `NaN` for a second nobody answered. */
    private val seconds = ArrayDeque<Float>()
    private var slotSum = 0.0
    private var slotCount = 0

    /** How many samples have left the front of [seconds] since the chart began: what a pause is numbered from. */
    private var dropped = 0L

    /** Where the engine rested: the number of the first sample after each pause, oldest first. */
    private val pauses = ArrayDeque<Long>()

    /** The second the newest sample is for, and the chart's first. */
    private var lastSlot = Long.MIN_VALUE
    private var firstSlot = Long.MIN_VALUE

    /** The newest second the flag was up in, and whether the engine is still running. */
    private var aliveSlot = Long.MIN_VALUE
    private var running = false

    /** The newest reading and the second it came in: what a second with none keeps. */
    private var heldKw = Float.NaN
    private var heldSlot = Long.MIN_VALUE

    /** The standing run's own figure and time; a trip's are the ledger's. */
    private var kwh = 0.0
    private var runSeconds = 0.0

    /** Whether the chart is a trip's, whether the newest sweep had a trip open, and that trip. */
    private var ofTrip = false
    private var tripOpen = false
    private var trip = NO_TRIP

    /** The newest second any sweep reached, run or no run: what a stop is timed against. */
    private var observed = Long.MIN_VALUE

    /**
     * One sweep's answer.
     *
     * @param atMillis a monotonic clock
     * @param engineRunning the engine's flag, null while it has not answered
     * @param loadKw pack power, positive out of the pack ([VehicleConvention.load])
     * @param dtSeconds the interval since the previous sweep, as the trip's ledger is given it
     * @param trip the ledger's trip after this same sweep: whether one is open, and its engine
     */
    fun sample(
        atMillis: Long,
        engineRunning: Boolean?,
        loadKw: Double?,
        dtSeconds: Double,
        trip: TripEnergy = NO_TRIP,
    ) {
        val slot = atMillis.floorDiv(SLOT_MS)
        if (observed != Long.MIN_VALUE && slot < observed) {
            // A clock that went backwards is not a history this can extend.
            reset()
        }
        observed = slot
        this.trip = trip
        if (trip.open != tripOpen) {
            tripOpen = trip.open
            // A new trip starts the block from zero, as it starts the trip. A trip that ends with the
            // engine running hands the block to the run standing in P, from now; one that ends with
            // it at rest keeps its chart as it stopped, for the hold and the engine's cell, and
            // [start] never carries a trip's chart on without a trip.
            if (tripOpen || running) clear()
        }
        when (engineRunning) {
            true -> {
                if (!running) start(slot)
                aliveSlot = slot
            }
            false -> if (running) {
                close()
                running = false
            }
            null -> Unit
        }
        if (!running) return

        advance(slot)
        // Only a sweep the flag confirms is a sample of the run. One whose flag did not answer is
        // a missing sample - the second keeps the reading before it, as for a power that did not
        // answer - and not a charge the run then neither counts nor times: a braking sweep of 60 kW
        // with the flag missing put a quarter of itself into the newest ten-second mean.
        val charge = loadKw?.takeIf { engineRunning == true }?.let { max(0.0, -it) }
        if (charge != null) {
            slotSum += charge
            slotCount++
            heldKw = (slotSum / slotCount).toFloat()
            heldSlot = slot
        }
        if (engineRunning == true && dtSeconds > 0.0 && dtSeconds <= OdometerGate.MAX_GAP_SECONDS) {
            runSeconds += dtSeconds
            if (charge != null) kwh += charge * dtSeconds / 3600.0
        }
    }

    fun reset() {
        clear()
        tripOpen = false
        trip = NO_TRIP
        observed = Long.MIN_VALUE
    }

    /** The block as the screens draw it. */
    fun snapshot(): EngineChargeSnapshot {
        if (firstSlot == Long.MIN_VALUE) return EngineChargeSnapshot.EMPTY
        val count = seconds.size + if (running) 1 else 0
        // The second still filling is the newest sample while the engine runs.
        val samples = FloatArray(count) { index ->
            if (index < seconds.size) seconds[index] else closing(lastSlot)
        }
        val drawn = minOf(count, WINDOW_SECONDS)
        val from = count - drawn
        val points = FloatArray(drawn)
        var marks = 0
        for (pause in pauses) if ((pause - dropped - from).toInt() in 1 until drawn) marks++
        val marked = IntArray(marks)
        var mark = 0
        // The pause each point's stretch begins after, walked forward with the points.
        var next = 0
        var stretch = Int.MIN_VALUE
        var held = 0f
        for (point in 0 until drawn) {
            val at = from + point
            while (next < pauses.size && (pauses[next] - dropped).toInt() <= at) {
                stretch = (pauses[next] - dropped).toInt()
                if (stretch - from in 1 until drawn) marked[mark++] = stretch - from
                next++
            }
            var sum = 0.0
            var seen = 0
            for (back in 0 until SMOOTH_SECONDS) {
                val index = at - back
                // Before the chart, and before the pause this stretch follows, the engine was off:
                // a sample of zero, and it counts.
                if (index < 0 || index < stretch) {
                    seen++
                    continue
                }
                val value = samples[index]
                if (value.isNaN()) continue
                sum += value
                seen++
            }
            if (seen > 0) held = (sum / seen).toFloat()
            points[point] = held
        }
        return EngineChargeSnapshot(
            points = points,
            kwh = if (ofTrip) trip.engineKwh else kwh,
            seconds = if (ofTrip) trip.engineSeconds else runSeconds,
            running = running,
            // The newest second is [lastSlot] whether it is still filling or closed.
            stoppedSeconds = if (running) 0L else max(0L, observed - lastSlot),
            marks = marked,
            ofTrip = ofTrip,
            kwhKnown = !ofTrip || trip.engineKwhKnown,
        )
    }

    private fun start(slot: Long) {
        // A chart of the same kind as now: a trip's while a trip is open, a standing run's while none is.
        val same = firstSlot != Long.MIN_VALUE && ofTrip == tripOpen
        val resumes = same && aliveSlot != Long.MIN_VALUE && slot - aliveSlot <= RESTART_SECONDS
        running = true
        if (resumes) {
            if (slot == lastSlot) {
                // Back within the second it stopped in: that second is filling again.
                val closed = seconds.removeLast()
                if (!closed.isNaN()) {
                    slotSum = closed.toDouble()
                    slotCount = 1
                }
                return
            }
            // The seconds it was off gave nothing, and they are seconds of this run's chart.
            var second = lastSlot + 1
            while (second < slot) {
                seconds.addLast(0f)
                second++
            }
            heldKw = 0f
            heldSlot = slot - 1
            lastSlot = slot
            trim()
            return
        }
        if (same && ofTrip) {
            // The engine rested inside the trip: the pause is cut out, the chart carries on from where
            // it stopped, and a mark stands before the first second after it.
            pauses.addLast(dropped + seconds.size)
            slotSum = 0.0
            slotCount = 0
            lastSlot = slot
            heldKw = Float.NaN
            heldSlot = Long.MIN_VALUE
            return
        }
        clear()
        running = true
        ofTrip = tripOpen
        firstSlot = slot
        lastSlot = slot
    }

    /** No chart at all: what a new trip, or a trip handed to the run in P, starts from. */
    private fun clear() {
        seconds.clear()
        pauses.clear()
        dropped = 0L
        slotSum = 0.0
        slotCount = 0
        lastSlot = Long.MIN_VALUE
        firstSlot = Long.MIN_VALUE
        aliveSlot = Long.MIN_VALUE
        running = false
        heldKw = Float.NaN
        heldSlot = Long.MIN_VALUE
        kwh = 0.0
        runSeconds = 0.0
        ofTrip = false
    }

    /**
     * Moves the filling second on to [slot], closing the ones before it.
     *
     * Only the seconds [trim] would keep are ever made: the window and the smoothing behind it. A
     * process suspended for a day used to wake with one entry a second for all of it - 86,400 -
     * before the trim took them back off, on the poll loop's thread, with both screens and the
     * journals waiting.
     */
    private fun advance(slot: Long) {
        if (slot == lastSlot) return
        seconds.addLast(closing(lastSlot))
        slotSum = 0.0
        slotCount = 0
        lastSlot++
        val kept = (WINDOW_SECONDS + SMOOTH_SECONDS).toLong()
        if (slot - lastSlot > kept) {
            // Everything before the oldest second the window can still use goes in the trim anyway.
            dropped += seconds.size
            seconds.clear()
            lastSlot = slot - kept
        }
        while (lastSlot < slot) {
            seconds.addLast(closing(lastSlot))
            lastSlot++
        }
        trim()
    }

    /** The engine has stopped: its filling second is its last. */
    private fun close() {
        seconds.addLast(closing(lastSlot))
        slotSum = 0.0
        slotCount = 0
        trim()
    }

    private fun current(): Float = if (slotCount == 0) Float.NaN else (slotSum / slotCount).toFloat()

    /** What [second] stands at: its own sweeps' mean, or the reading before it while that is fresh. */
    private fun closing(second: Long): Float {
        if (second == lastSlot && slotCount > 0) return current()
        return if (heldSlot != Long.MIN_VALUE && second - heldSlot <= OdometerGate.MAX_GAP_SECONDS) heldKw else Float.NaN
    }

    /** The window and the ten seconds behind its oldest point, no more; a pause leaves with them. */
    private fun trim() {
        while (seconds.size > WINDOW_SECONDS + SMOOTH_SECONDS) {
            seconds.removeFirst()
            dropped++
        }
        while (pauses.isNotEmpty() && pauses.first() < dropped) pauses.removeFirst()
    }

    companion object {
        const val SLOT_MS = 1_000L

        /** Two minutes the engine ran, a point a second: the spec's `cluster.engineCharge.points`. */
        const val WINDOW_SECONDS = LuminoforSpec.Cluster.EngineCharge.POINTS

        /** A point is the mean of this many seconds: the ten kilometres' own ten. */
        const val SMOOTH_SECONDS = ConsumptionChart.SMOOTH_STEPS

        /**
         * A start this soon after the stop is the same uninterrupted run: the block's own hold. Later
         * than this it is a pause inside a trip, and a new run in P.
         */
        const val RESTART_SECONDS = 10L

        /** The chart's ceiling: 50 kW, and 25 while the window's highest point is under 20 (`spec.json`). */
        const val CEILING_KW = LuminoforSpec.Cluster.EngineCharge.UP_TO
        const val CEILING_LOW_KW = LuminoforSpec.Cluster.EngineCharge.UP_TO_LOW
        const val LOW_BELOW_KW = LuminoforSpec.Cluster.EngineCharge.LOW_BELOW

        /** What a caller that has no ledger hands in: no trip, so the block is the run standing there. */
        private val NO_TRIP = TripEnergy()

        /**
         * The scale the block draws [points] on, the one rule both the app and the board follow.
         *
         * Fixed rather than fitted, for the reason the ten kilometres' ladder is fixed: a curve that
         * changes height because a different point changed is not the same two minutes twice. Two
         * steps rather than one because the engine's ordinary standing charge is 8 kW, which on 50
         * is a sixth of the box and reads as nothing; on 25 it is a third. A point past the ceiling
         * lies along it with the chart's tick.
         */
        fun ceilingKw(points: FloatArray, count: Int = points.size): Float {
            var highest = 0f
            for (index in 0 until count) if (points[index] > highest) highest = points[index]
            return if (highest < LOW_BELOW_KW) CEILING_LOW_KW else CEILING_KW
        }
    }
}

/**
 * The engine's block as the screens are handed it.
 *
 * [points] oldest first, never a `NaN`; [kwh] what went into the pack - the trip's «ЗАРЯД ОТ ДВС»
 * while [ofTrip], the standing run's own otherwise; [seconds] how long the engine ran, on the same
 * terms; [stoppedSeconds] how long ago it stopped, zero while it runs; [marks] the points that follow
 * a pause, each the index of the first point after it, never the oldest point.
 */
internal class EngineChargeSnapshot(
    val points: FloatArray,
    val kwh: Double,
    val seconds: Double,
    val running: Boolean,
    val stoppedSeconds: Long,
    val marks: IntArray = NO_MARKS,
    /** Whether this is the open trip's engine, whose figure and time are the ledger's. */
    val ofTrip: Boolean = false,
    /** False for a trip restored from 0.7.1, whose engine charge is unknown ([TripEnergy.engineKwhKnown]). */
    val kwhKnown: Boolean = true,
) {
    /** Nothing to draw: no run since the hub started, or a new trip the engine has not run in yet. */
    val isEmpty: Boolean get() = points.isEmpty()

    /** The scale the block draws [points] on. */
    val ceilingKw: Float = EngineCharge.ceilingKw(points)

    /**
     * Whether the engine stopped inside the last [RECENT_SECONDS], which is how long the engine's
     * cell keeps saying how long it ran this trip: a cell that says «за поездку» beside an engine
     * cold since yesterday is read as *this* drive.
     */
    val stoppedRecently: Boolean get() = !isEmpty && !running && stoppedSeconds < RECENT_SECONDS

    companion object {
        private val NO_MARKS = IntArray(0)

        val EMPTY = EngineChargeSnapshot(FloatArray(0), 0.0, 0.0, false, 0L)

        /** The two minutes the old trace's own length gave the minutes cell. */
        const val RECENT_SECONDS = 120L
    }
}
