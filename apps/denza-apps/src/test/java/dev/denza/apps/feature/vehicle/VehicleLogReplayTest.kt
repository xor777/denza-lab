package dev.denza.apps.feature.vehicle

import dev.denza.apps.feature.cluster.dashboard.ContourScene
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A recorded drive, fed through the hub's own sweep step (`VehicleAnsweredSweep`) - the same lines
 * the hub runs, not a copy of them, which until 2026-10-08 it was.
 *
 * `docs/energy-display-contract.md` §7, «replay». Everything else in this package states a case and
 * asserts what the code should say about it; this asserts the things that are true **whatever the
 * signals turn out to mean**, over whatever the car actually did:
 *
 *  - the road under the chart is the road the log recorded - a point per reading bucket of it, and
 *    the same road the unit beside the figure names;
 *  - the figure is energy over known road, and nothing else;
 *  - every point is the trailing ten readings, none of them is a `NaN`, and nothing is drawn from
 *    fewer than five;
 *  - the engine's box is never up with the running flag down past its own hold.
 *
 * `tools/vehicle_log.py` writes the files, into `captures/vehicle-log/` (git-ignored, so this test
 * runs against whatever the machine has). **It is skipped with nothing there**, which is the state
 * it was written in: a test that failed for the absence of a capture would be a test nobody could
 * run. Skipped rather than passed, since 2026-10-08: a clean checkout used to count a replay that
 * never ran as one that held.
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
        assumeTrue("no recorded drive in captures/vehicle-log", logs.isNotEmpty())
        logs.forEach { replay(it) }
    }

    private fun replay(file: File) {
        val rows = file.readLines().filter { it.isNotBlank() }.take(MAX_ROWS + 1)
        if (rows.size < 2) return
        val header = rows[0].split(',')
        val time = header.indexOf(VehicleCaptureColumns.MONO)
        // Every id either recorder wrote, looked up by its one spelling. A column a file lacks is an
        // id that never answered - speed before 2026-09-18 replays as a car that never stood still,
        // which is exactly what a missing read means (contract §2.2).
        val columns = VehicleSignal.entries
            .associateWith { signal -> header.indexOf(VehicleCaptureColumns.of(signal)) }
            .filterValues { it >= 0 }
        val needed = listOf(VehicleSignal.POWER_KW, VehicleSignal.ODOMETER_KM, VehicleSignal.ENGINE_RUNNING)
        if (time < 0 || needed.any { it !in columns }) {
            error("${file.name} is not a vehicle log: its header is ${rows[0]}")
        }

        var closed = false
        val log = ConsumptionLog(onBucketClosed = { closed = true })
        val ledger = TripEnergyLedger()
        val trace = EngineTrace()
        val scene = ContourScene()
        var previous = Double.NaN
        var flagDownFor = Double.MAX_VALUE
        var gave = false
        var boxUp = false

        rows.drop(1).forEach { line ->
            val cells = line.split(',')
            if (cells.size < header.size) return@forEach
            val mono = cells.getOrNull(time)?.toDoubleOrNull() ?: return@forEach
            val dt = if (previous.isNaN() || mono <= previous) 0.0 else mono - previous
            previous = mono
            // The row as a sweep parses it: every id that answered, and nothing for one that did not.
            val parsed = columns.entries.mapNotNull { (signal, index) ->
                cells.getOrNull(index)?.toDoubleOrNull()?.let { signal to it }
            }.toMap()
            val engineRunning = parsed[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }

            // The hub's own step, not a copy of it: the row's cold readings stand for what the last
            // cold sweep left. The scene is stepped on every row - the engine box's invariant is
            // about a frame - and the window is checked below only where a bucket closed.
            closed = false
            val values = VehicleAnsweredSweep.feed(
                parsed = parsed,
                cold = parsed.filterKeys { it in VehicleSignal.COLD },
                atMillis = (mono * 1000.0).toLong(),
                dtSeconds = dt,
                log = log,
                ledger = ledger,
                trace = trace,
            )
            val snapshot = VehicleAnsweredSweep.snapshot(values, log, ledger, trace)
            scene.frame(snapshot, arrived = true, dt = dt.toFloat())
            gave = gave || (parsed[VehicleSignal.GENERATION_KW] ?: 0.0) > 0.0
            boxUp = boxUp || scene.stage.engineBox

            flagDownFor = if (engineRunning == true) 0.0 else flagDownFor + dt
            if (flagDownFor > ContourScene.ENGINE_HOLD_SECONDS + 1.0) {
                assertTrue(
                    "${file.name} at $mono s: the box is up with the flag down for $flagDownFor s",
                    !scene.stage.engineBox,
                )
            }

            if (!closed) return@forEach
            checkRoad(file, mono, snapshot.consumption, snapshot.chart)
            checkFigure(file, mono, snapshot.consumption, snapshot.consumptionMean)
            checkPoints(file, mono, log.buckets, snapshot.chart)
        }
        // The box's invariant holds trivially over a drive whose box never came up, and over the
        // three newest drives (2026-09-22 to 24) it never did. A drive the engine gave in has to
        // raise it.
        if (gave) assertTrue("${file.name}: the engine gave and its box never came up", boxUp)
    }

    /**
     * The chart's own width is the road the log recorded under it, a point per reading bucket.
     *
     * And that is the same road the unit beside the figure names, which is the promise «за 8,6 км»
     * makes about the chart beside it: one statement, two places it is printed.
     */
    private fun checkRoad(
        file: File,
        at: Double,
        window: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        if (window.isEmpty()) return
        // The odometer's own road over the window, from the readings rather than from the records:
        // each bucket's road is the difference it accumulated, so the sum is the trip between the
        // first bucket's start and the last one's close - across a seam, whatever the odometer did.
        val recorded = window.sumOf { it.km }
        val readings = window.count { it.known }
        assertTrue(
            "${file.name} at $at s: the window holds $recorded km in $readings readings",
            recorded >= readings * ConsumptionChart.PITCH_KM - 1e-6,
        )

        // Under five readings nothing is drawn at all (§2.3, «Filling»): every file starts a fresh
        // log, so the first four hundred metres of every capture are the floor, not a lost point.
        // The first real drive the test ever read, 2026-09-22, is the one that said so.
        if (readings < ConsumptionChart.MIN_STEPS) {
            assertEquals("${file.name} at $at s: nothing under five readings", 0, chart.span)
            return
        }
        assertEquals(
            "${file.name} at $at s: the points under the chart",
            readings,
            chart.span,
        )
        assertEquals(
            "${file.name} at $at s: the road under the chart",
            ConsumptionWindow.coveredKm(window),
            chart.span * ConsumptionChart.PITCH_KM,
            1e-6,
        )
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
     * Every point is the trailing ten readings, and no point is ever a `NaN`.
     *
     * The value and the floor, both against arithmetic this test does itself: a point is
     * `Σ kWh / Σ knownKm × 100` over the ten reading buckets ending at it, over what there is when
     * fewer stand behind it, and never over fewer than five - a log with four readings draws
     * nothing at all.
     */
    private fun checkPoints(
        file: File,
        at: Double,
        all: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        val readings = all.filter { it.known }
        if (readings.size < ConsumptionChart.MIN_STEPS) {
            assertTrue(
                "${file.name} at $at s: ${readings.size} readings drew a chart",
                chart.isEmpty,
            )
            return
        }
        val first = maxOf(0, readings.size - ConsumptionChart.POINTS)
        assertEquals(
            "${file.name} at $at s: a point per reading, newest hundred",
            readings.size - first,
            chart.values.size,
        )
        chart.values.forEachIndexed { index, value ->
            val point = first + index
            var from = point - ConsumptionChart.SMOOTH_STEPS + 1
            if (from < 0) from = 0
            var to = point
            if (to - from + 1 < ConsumptionChart.MIN_STEPS) to = from + ConsumptionChart.MIN_STEPS - 1
            val over = readings.subList(from, to + 1)
            val sumKwh = over.sumOf { it.kwh }
            val sumKnown = over.sumOf { it.knownKm }
            assertTrue("${file.name} at $at s: point $index is a NaN", !value.isNaN())
            assertEquals(
                "${file.name} at $at s: point $index over ${over.size} readings",
                sumKwh / sumKnown * 100.0,
                value.toDouble(),
                1e-4 + abs(sumKwh / sumKnown * 100.0) * 1e-6,
            )
        }
    }

    /**
     * The recorder's own directory, at the repository root: its newest drives, and the newest drive
     * the engine gave in wherever that falls.
     *
     * The newest by name alone left the engine box's invariant with nothing to hold: none of the
     * three newest drives (2026-09-22 to 24) had the engine give, and the one that did
     * (2026-09-18) had sorted out of reach.
     */
    private fun captures(): List<File> {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })) {
            it.parentFile
        }.firstOrNull { File(it, "tools/design-canvas").isDirectory } ?: return emptyList()
        val directory = File(root, "captures/vehicle-log")
        if (!directory.isDirectory) return emptyList()
        val drives = directory.listFiles { file -> file.isFile && file.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return (drives.takeLast(MAX_FILES) + listOfNotNull(drives.lastOrNull(::engineGave))).distinct()
    }

    /** Whether the engine gave anywhere in the rows [replay] reads of [file]. */
    private fun engineGave(file: File): Boolean = file.useLines { lines ->
        val rows = lines.filter { it.isNotBlank() }.take(MAX_ROWS + 1).iterator()
        val generation = if (rows.hasNext()) rows.next().split(',').indexOf("generation_kw") else -1
        generation >= 0 &&
            rows.asSequence().any { (it.split(',').getOrNull(generation)?.toDoubleOrNull() ?: 0.0) > 0.0 }
    }

    private companion object {
        /** The newest few drives, because a machine's capture directory only ever grows. */
        const val MAX_FILES = 3

        /** And an hour and a half of a row a second out of each, which is a drive. */
        const val MAX_ROWS = 5_000
    }
}
