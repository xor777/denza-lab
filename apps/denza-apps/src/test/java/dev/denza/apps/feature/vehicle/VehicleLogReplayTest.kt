package dev.denza.apps.feature.vehicle

import dev.denza.apps.feature.cluster.dashboard.ContourScene
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recorded drive, fed through the same log, chart, ledger and trace the hub uses.
 *
 * `docs/energy-display-contract.md` §7, «replay». Everything else in this package states a case and
 * asserts what the code should say about it; this asserts the things that are true **whatever the
 * signals turn out to mean**, over whatever the car actually did:
 *
 *  - the road under the chart is the road the odometer covered - a point per hundred metres of it;
 *  - the figure is energy over known road, and nothing else;
 *  - every point is the trailing kilometre, and none is drawn where the log had no energy;
 *  - the engine's box is never up with the running flag down past its own hold.
 *
 * `tools/vehicle_log.py` writes the files, into `captures/vehicle-log/` (git-ignored, so this test
 * runs against whatever the machine has). **It passes with nothing there**, which is the state it
 * was written in: the drive that closes the contract's open items - the engine running at speed -
 * has not been recorded yet, and a test that failed for the absence of a capture would be a test
 * nobody could run.
 *
 * ### And its work is bounded, because the captures are not
 *
 * The recorder writes a row a second for as long as it is asked to, so an eight-hour drive is
 * thirty thousand rows and the next one is another; three of the window checks are `O(window)`
 * each. Every row is still *fed* - the log, the ledger, the trace and the scene are the cheap part,
 * and the engine box's invariant is about the frame it is asserted in - but the window is only
 * *checked* where it changed, which is where a bucket closed. [MAX_FILES] and [MAX_ROWS] are the
 * outer bound: a suite that gets slower every time somebody drives is a suite people stop running.
 */
class VehicleLogReplayTest {

    @Test
    fun everyRecordedDriveSatisfiesTheInvariantsThatDoNotDependOnMeaning() {
        val logs = captures()
        if (logs.isEmpty()) return
        logs.forEach { replay(it) }
    }

    private fun replay(file: File) {
        val rows = file.readLines().filter { it.isNotBlank() }.take(MAX_ROWS + 1)
        if (rows.size < 2) return
        val header = rows[0].split(',')
        val time = header.indexOf("mono_s")
        val power = header.indexOf("power_kw")
        val odometer = header.indexOf("odometer_km")
        val generation = header.indexOf("generation_kw")
        val running = header.indexOf("engine_running")
        if (time < 0 || power < 0 || odometer < 0 || running < 0) {
            error("${file.name} is not a vehicle log: its header is ${rows[0]}")
        }

        var closed = false
        val log = ConsumptionLog(onBucketClosed = { closed = true })
        val ledger = TripEnergyLedger()
        val trace = EngineTrace()
        val scene = ContourScene()
        var previous = Double.NaN
        var flagDownFor = Double.MAX_VALUE

        rows.drop(1).forEach { line ->
            val cells = line.split(',')
            if (cells.size < header.size) return@forEach
            val mono = cells.getOrNull(time)?.toDoubleOrNull() ?: return@forEach
            val dt = if (previous.isNaN() || mono <= previous) 0.0 else mono - previous
            previous = mono
            val odometerKm = cells.getOrNull(odometer)?.toDoubleOrNull()
            val powerKw = VehicleConvention.load(cells.getOrNull(power)?.toDoubleOrNull())
            val generationKw =
                if (generation < 0) null else cells.getOrNull(generation)?.toDoubleOrNull()
            val engineRunning = cells.getOrNull(running)?.toDoubleOrNull()?.let { it >= 1.0 }

            closed = false
            log.sample(odometerKm, powerKw, dt)
            trace.sample((mono * 1000.0).toLong(), engineRunning, generationKw)
            ledger.sample(
                odometerKm = odometerKm,
                powerKw = powerKw,
                generationKw = generationKw,
                engineRunning = engineRunning,
                parked = null,
                dtSeconds = dt,
            )

            // The window is only rebuilt where it changed, which is where a bucket closed. The
            // scene is stepped on every row regardless: the engine box's invariant is about a
            // frame, and a frame the window did not change in is still a frame.
            val window = if (closed) log.window else emptyList()
            val tail = if (closed) log.chartTail else emptyList()
            val snapshot = VehicleTelemetry(
                access = VehicleAccess.READY,
                values = engineRunning?.let {
                    mapOf(VehicleSignal.ENGINE_RUNNING to if (it) 3.0 else 0.0)
                } ?: emptyMap(),
                consumption = window,
                chart = ConsumptionChart.of(tail),
                engineTrace = trace.snapshot(),
                trip = ledger.trip,
            )
            scene.frame(snapshot, arrived = true, dt = dt.toFloat())

            flagDownFor = if (engineRunning == true) 0.0 else flagDownFor + dt
            if (flagDownFor > ContourScene.ENGINE_HOLD_SECONDS + 1.0) {
                assertTrue(
                    "${file.name} at $mono s: the box is up with the flag down for $flagDownFor s",
                    !scene.stage.engineBox,
                )
            }

            if (!closed) return@forEach
            checkRoad(file, mono, window, tail, snapshot.chart)
            checkFigure(file, mono, window, snapshot.consumptionMean)
            checkPoints(file, mono, tail, snapshot.chart)
        }
    }

    /** The chart's own width is the road the odometer covered under it, a point per hundred metres. */
    private fun checkRoad(
        file: File,
        at: Double,
        window: List<ConsumptionSample>,
        tail: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        if (window.isEmpty()) return
        // The road the odometer covered over the window, from the readings rather than from the
        // records: each bucket's road is the difference it accumulated, so the sum is the trip
        // between the first bucket's start and the last one's close.
        val travelled = window.last().odometerKm - window.first().odometerKm + window.first().km
        val recorded = window.sumOf { it.km }
        assertEquals("${file.name} at $at s: the window's road", travelled, recorded, 1e-6)

        // And the chart stands a point on every hundred metres of that road, never fewer: the
        // axis is the odometer's grid, so a stretch with no record is holes rather than a
        // shorter chart. The grid is walked here rather than taken from the production helper.
        val road = perStep(tail) { it.km }
        var first = 0
        while (first < ConsumptionChart.POINTS && road[first + ConsumptionChart.SMOOTH_STEPS - 1] <= 0.0) {
            first++
        }
        assertEquals(
            "${file.name} at $at s: the points under the chart",
            ConsumptionChart.POINTS - first,
            chart.span,
        )
        assertEquals(
            "${file.name} at $at s: the road under the chart",
            (ConsumptionChart.POINTS - first) * ConsumptionChart.PITCH_KM,
            chart.span * ConsumptionChart.PITCH_KM,
            1e-9,
        )
    }

    /**
     * A quantity spread over the grid steps each bucket's road covers, `[odometer − km, odometer)`.
     *
     * The independent arithmetic behind the two checks around it: energy and known road go with the
     * road pro rata, because a bucket holds one integral over one stretch and no record of where
     * inside it anything happened. The array reaches a kilometre further back than the first point
     * does, because that point is a mean over the kilometre ending at it.
     */
    private fun perStep(
        tail: List<ConsumptionSample>,
        of: (ConsumptionSample) -> Double,
    ): DoubleArray {
        val pitch = ConsumptionChart.PITCH_KM
        val smooth = ConsumptionChart.SMOOTH_STEPS
        val newest = floor((tail.last().odometerKm - 1e-6) / pitch).toLong()
        val base = newest - ConsumptionChart.POINTS + 1 - (smooth - 1)
        val out = DoubleArray(ConsumptionChart.POINTS + smooth - 1)
        tail.forEach { bucket ->
            if (bucket.km <= 0.0) return@forEach
            val end = bucket.odometerKm
            val start = end - bucket.km
            var step = floor((start + 1e-6) / pitch).toLong()
            val last = floor((end - 1e-9) / pitch).toLong()
            while (step <= last) {
                val overlap = minOf(end, (step + 1) * pitch) - maxOf(start, step * pitch)
                val slot = (step - base).toInt()
                if (overlap > 0.0 && slot in out.indices) out[slot] += of(bucket) * overlap / bucket.km
                step++
            }
        }
        return out
    }

    /** And the figure beside it is energy over the road that energy is known for. */
    private fun checkFigure(
        file: File,
        at: Double,
        window: List<ConsumptionSample>,
        mean: Double?,
    ) {
        var kwh = 0.0
        var km = 0.0
        window.forEach { bucket ->
            if (bucket.knownKm <= 0.0) return@forEach
            if (bucket.knownKm * 2.0 < bucket.km - 1e-9) return@forEach
            kwh += bucket.kwh
            km += bucket.knownKm
        }
        if (km <= 0.0) {
            assertTrue("${file.name} at $at s: a figure over nothing known", mean == null)
            return
        }
        assertEquals("${file.name} at $at s: the figure", kwh / km * 100.0, mean!!, 1e-6)
    }

    /**
     * Every point is the trailing kilometre, and nothing is drawn where the log had no energy.
     *
     * The value, the hole rule and the grid-step rule, all three against arithmetic this test does
     * itself: a point is `Σ kWh / Σ knownKm × 100` over the kilometre ending at it, `NaN` where
     * under half of that kilometre is known road, and `NaN` where no bucket covers its own hundred
     * metres at all.
     */
    private fun checkPoints(
        file: File,
        at: Double,
        tail: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        if (tail.isEmpty()) return
        val smooth = ConsumptionChart.SMOOTH_STEPS
        val kwh = perStep(tail) { it.kwh }
        val road = perStep(tail) { it.km }
        val known = perStep(tail) { it.knownKm }
        val first = ConsumptionChart.POINTS - chart.values.size
        chart.values.forEachIndexed { index, value ->
            val slot = first + index + smooth - 1
            var sumKwh = 0.0
            var sumKm = 0.0
            var sumKnown = 0.0
            for (step in slot - smooth + 1..slot) {
                sumKwh += kwh[step]
                sumKm += road[step]
                sumKnown += known[step]
            }
            val readable = road[slot] > 0.0 && sumKnown > 0.0 &&
                sumKnown * 2.0 >= ConsumptionChart.SMOOTH_KM - 1e-9
            if (!readable) {
                assertTrue(
                    "${file.name} at $at s: point $index is drawn over ${road[slot]} km of its own " +
                        "road and $sumKnown of $sumKm known",
                    value.isNaN(),
                )
                return@forEachIndexed
            }
            assertTrue("${file.name} at $at s: point $index is a hole over known road", !value.isNaN())
            assertEquals(
                "${file.name} at $at s: point $index",
                sumKwh / sumKnown * 100.0,
                value.toDouble(),
                1e-4 + abs(sumKwh / sumKnown * 100.0) * 1e-6,
            )
        }
    }

    /** The recorder's own directory, at the repository root, if there is anything in it. */
    private fun captures(): List<File> {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })) {
            it.parentFile
        }.firstOrNull { File(it, "tools/design-canvas").isDirectory } ?: return emptyList()
        val directory = File(root, "captures/vehicle-log")
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            ?.takeLast(MAX_FILES)
            ?: emptyList()
    }

    private companion object {
        /** The newest few drives, because a machine's capture directory only ever grows. */
        const val MAX_FILES = 3

        /** And an hour and a half of a row a second out of each, which is a drive. */
        const val MAX_ROWS = 5_000
    }
}
