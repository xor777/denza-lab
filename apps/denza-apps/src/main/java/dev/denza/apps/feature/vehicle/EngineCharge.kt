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
 * ### A run
 *
 * A run starts when the flag goes up and ends when it reads down. A flag that did not answer is
 * neither: it neither ends a run nor counts toward one, in its figure, its time or its chart. A run that starts again within
 * [RESTART_SECONDS] of its stop is the same run - the block is still on the panel then, and a
 * flag that read down once for a dropped read does not get to zero the figure under it. The run
 * carries the energy it put into the pack and the seconds the engine ran, integrated sweep by
 * sweep on the same intervals the trip's ledger integrates (a sweep's reading over the time since
 * the previous one, nothing over a gap longer than [OdometerGate.MAX_GAP_SECONDS]).
 *
 * ### The chart
 *
 * One sample a second: the mean of the sweeps that landed in that second, on the hub's monotonic
 * clock. A drawn point is the trailing mean of the last [SMOOTH_SECONDS] samples, the rule and the
 * constant of the ten-kilometre chart ([ConsumptionChart.SMOOTH_STEPS]), so the newest point is a
 * figure of its own - what the last ten seconds gave. A second before the run is a sample of zero:
 * the engine was off and gave nothing. A second no sweep landed in keeps the newest reading - a
 * reading stands until the next one, so the mean is over time, not over sweeps, and a poll slower
 * than a second does not step the curve - for at most [OdometerGate.MAX_GAP_SECONDS], past which it
 * is a hole and left out of the mean rather than counted as a zero. The window is the run's last [WINDOW_SECONDS] seconds, a point a second;
 * a younger run is as many points as it is seconds old, and the panel draws them from its right
 * edge at the full window's pitch, as the ten kilometres fill. After the run ends the chart stays
 * as it was at the stop.
 *
 * Built once per sweep into an [EngineChargeSnapshot], which is what the screens read in every
 * frame and build in none.
 */
internal class EngineCharge {

    /** The run's samples, a second each, oldest first: kilowatts, `NaN` for a second nobody answered. */
    private val seconds = ArrayDeque<Float>()
    private var slotSum = 0.0
    private var slotCount = 0

    /** The second the newest sample is for, and the run's first. */
    private var lastSlot = Long.MIN_VALUE
    private var firstSlot = Long.MIN_VALUE

    /** The newest second the flag was up in, and whether the run is still going. */
    private var aliveSlot = Long.MIN_VALUE
    private var running = false

    /** The newest reading of the run and the second it came in: what a second with none keeps. */
    private var heldKw = Float.NaN
    private var heldSlot = Long.MIN_VALUE

    private var kwh = 0.0
    private var runSeconds = 0.0

    /** The newest second any sweep reached, run or no run: what a stop is timed against. */
    private var observed = Long.MIN_VALUE

    /**
     * One sweep's answer.
     *
     * @param atMillis a monotonic clock
     * @param engineRunning the engine's flag, null while it has not answered
     * @param loadKw pack power, positive out of the pack ([VehicleConvention.load])
     * @param dtSeconds the interval since the previous sweep, as the trip's ledger is given it
     */
    fun sample(atMillis: Long, engineRunning: Boolean?, loadKw: Double?, dtSeconds: Double) {
        val slot = atMillis.floorDiv(SLOT_MS)
        if (observed != Long.MIN_VALUE && slot < observed) {
            // A clock that went backwards is not a history this can extend.
            reset()
        }
        observed = slot
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
        seconds.clear()
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
        observed = Long.MIN_VALUE
    }

    /** The run as the screens draw it. */
    fun snapshot(): EngineChargeSnapshot {
        if (firstSlot == Long.MIN_VALUE) return EngineChargeSnapshot.EMPTY
        val count = seconds.size + if (running) 1 else 0
        // The second still filling is the newest sample while the run goes on.
        val samples = FloatArray(count) { index ->
            if (index < seconds.size) seconds[index] else closing(lastSlot)
        }
        val drawn = minOf(count, WINDOW_SECONDS)
        val points = FloatArray(drawn)
        val from = count - drawn
        var held = 0f
        for (point in 0 until drawn) {
            val at = from + point
            var sum = 0.0
            var seen = 0
            for (back in 0 until SMOOTH_SECONDS) {
                val index = at - back
                // Before the run the engine was off: a sample of zero, and it counts.
                if (index < 0) {
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
            kwh = kwh,
            seconds = runSeconds,
            running = running,
            // The run's newest second is [lastSlot] whether it is still filling or closed.
            stoppedSeconds = if (running) 0L else max(0L, observed - lastSlot),
        )
    }

    private fun start(slot: Long) {
        val resumes = firstSlot != Long.MIN_VALUE && aliveSlot != Long.MIN_VALUE &&
            slot - aliveSlot <= RESTART_SECONDS
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
        seconds.clear()
        slotSum = 0.0
        slotCount = 0
        firstSlot = slot
        lastSlot = slot
        heldKw = Float.NaN
        heldSlot = Long.MIN_VALUE
        kwh = 0.0
        runSeconds = 0.0
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
            seconds.clear()
            lastSlot = slot - kept
        }
        while (lastSlot < slot) {
            seconds.addLast(closing(lastSlot))
            lastSlot++
        }
        trim()
    }

    /** The run has stopped: its filling second is its last. */
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

    /** The window and the ten seconds behind its oldest point, no more. */
    private fun trim() {
        while (seconds.size > WINDOW_SECONDS + SMOOTH_SECONDS) seconds.removeFirst()
    }

    companion object {
        const val SLOT_MS = 1_000L

        /** Two minutes of the run, a point a second: the spec's `cluster.engineCharge.points`. */
        const val WINDOW_SECONDS = LuminoforSpec.Cluster.EngineCharge.POINTS

        /** A point is the mean of this many seconds: the ten kilometres' own ten. */
        const val SMOOTH_SECONDS = ConsumptionChart.SMOOTH_STEPS

        /** A run that starts again this soon after its stop is the same run: the block's own hold. */
        const val RESTART_SECONDS = 10L

        /** The chart's ceiling: 50 kW, and 25 while the window's highest point is under 20 (`spec.json`). */
        const val CEILING_KW = LuminoforSpec.Cluster.EngineCharge.UP_TO
        const val CEILING_LOW_KW = LuminoforSpec.Cluster.EngineCharge.UP_TO_LOW
        const val LOW_BELOW_KW = LuminoforSpec.Cluster.EngineCharge.LOW_BELOW

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
 * One run of the engine as the screens are handed it.
 *
 * [points] oldest first, never a `NaN`; [kwh] what went into the pack since the run started;
 * [seconds] how long the engine ran; [stoppedSeconds] how long ago it stopped, zero while it runs.
 */
internal class EngineChargeSnapshot(
    val points: FloatArray,
    val kwh: Double,
    val seconds: Double,
    val running: Boolean,
    val stoppedSeconds: Long,
) {
    /** No run since the hub started. */
    val isEmpty: Boolean get() = points.isEmpty()

    /** The scale the block draws [points] on. */
    val ceilingKw: Float = EngineCharge.ceilingKw(points)

    /**
     * Whether a run ended inside the last [RECENT_SECONDS], which is how long the engine's cell
     * keeps saying how long it ran this trip: a cell that says «за поездку» beside an engine cold
     * since yesterday is read as *this* drive.
     */
    val stoppedRecently: Boolean get() = !isEmpty && !running && stoppedSeconds < RECENT_SECONDS

    companion object {
        val EMPTY = EngineChargeSnapshot(FloatArray(0), 0.0, 0.0, false, 0L)

        /** The two minutes the old trace's own length gave the minutes cell. */
        const val RECENT_SECONDS = 120L
    }
}
