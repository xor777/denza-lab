package dev.denza.apps.feature.vehicle

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
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
 *    the same road the unit beside the figure names - and every reading is one odometer tick, which
 *    is what lets a pitch per point be that road (contract §2.6, since 2026-10-09);
 *  - the figure is energy over known road, and nothing else;
 *  - every point is the trailing ten readings, none of them is a `NaN`, and nothing is drawn from
 *    fewer than five;
 *  - the engine's block is never up with the running flag down past its own hold, and a drive the
 *    engine ran in raises it.
 *
 * And one thing that does depend on meaning, because the board pictures it: the engine block's five
 * moments on the Luminofor boards (`cluster-engine`, `-kickdown`, `-stopped`, `-gone`, `-parked`)
 * are these drives at those rows, chart, figure and time, replayed through the same step; and the
 * sixth, `-resumed`, is one of them replayed as one trip, its stops in P left out (since 2026-11-21).
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
 * each. Every row is still *fed* - the log, the ledger, the run and the scene are the cheap part,
 * and the engine block's invariant is about the frame it is asserted in - but the window is only
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
        val charge = EngineCharge()
        val scene = ContourScene()
        var previous = Double.NaN
        var flagDownFor = Double.MAX_VALUE
        var ran = false
        var blockUp = false

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
            // cold sweep left. The scene is stepped on every row - the engine block's invariant is
            // about a frame - and the window is checked below only where a bucket closed.
            closed = false
            val values = VehicleAnsweredSweep.feed(
                parsed = parsed,
                cold = parsed.filterKeys { it in VehicleSignal.COLD },
                atMillis = (mono * 1000.0).toLong(),
                dtSeconds = dt,
                log = log,
                ledger = ledger,
                charge = charge,
            )
            val snapshot = VehicleAnsweredSweep.snapshot(values, log, ledger, charge)
            scene.frame(snapshot, arrived = true, dt = dt.toFloat())
            ran = ran || engineRunning == true
            blockUp = blockUp || scene.stage.engineBlock

            flagDownFor = if (engineRunning == true) 0.0 else flagDownFor + dt
            if (flagDownFor > ContourScene.ENGINE_HOLD_SECONDS + 1.0) {
                assertTrue(
                    "${file.name} at $mono s: the block is up with the flag down for $flagDownFor s",
                    !scene.stage.engineBlock,
                )
            }

            if (!closed) return@forEach
            checkRoad(file, mono, snapshot.consumption, snapshot.chart)
            checkFigure(file, mono, snapshot.consumption, snapshot.consumptionMean)
            checkPoints(file, mono, log.buckets, snapshot.chart)
        }
        // The block's invariant holds trivially over a drive whose block never came up. A drive the
        // engine ran in has to raise it.
        if (ran) assertTrue("${file.name}: the engine ran and its block never came up", blockUp)
    }

    /**
     * The engine block's boards are these drives at these rows.
     *
     * The board's chart, figure and time and the trip's detail after the block has gone, each held
     * to what the hub's own step makes of the recorded rows up to that moment: a board drawn from
     * numbers the app would not print is the defect the fixtures exist to stop.
     */
    @Test
    fun theEngineBlocksBoardsAreTheRecordedDrives() {
        val drive = capture("vehicle-20260924-182412-car.csv")
        val parked = capture("vehicle-20260918-183009.csv")
        assumeTrue("the drives the engine block's boards were drawn from", drive != null && parked != null)

        moment(drive!!, "2026-09-24T18:40:04.500", "cluster-engine", running = true)
        moment(drive, "2026-09-24T18:39:39.500", "cluster-engine-kickdown", running = true)
        moment(drive, "2026-09-24T19:25:30", "cluster-engine-stopped", running = false)
        moment(parked!!, "2026-09-18T18:33:40", "cluster-engine-parked", running = true)
        // Constructed: no drive has the engine resting inside one trip, so the same drive is replayed
        // as one, its stops in P left out - three runs and two pauses in one trip's chart.
        moment(drive, "2026-09-24T19:23:09", "cluster-engine-resumed", running = true, oneTrip = true)

        // Past the hold the block is gone and the trip says what the engine charged this trip.
        val (gone, scene) = replayTo(drive, "2026-09-24T19:25:40")
        val board = SpecJson.fixture("cluster-engine-gone")
        assertTrue("the block left", !scene.stage.engineBlock)
        assertEquals("the trip's «ЗАРЯД ОТ ДВС»", board["gaveKwh"], ContourReadout.tenth(gone.trip.engineKwh))
        val readouts = EnergyReadouts().also { it.read(gone, parked = false) }
        assertEquals(EnergyReadouts.EngineCell.MINUTES, readouts.engineCell)
        assertEquals("the trip's engine minutes", board["iceFigure"], readouts.engineCellFigure)
        assertEquals("the trip", board["tripKwh"], readouts.tripFigure)
        assertEquals("its road", AppText.s(R.string.energy_trip_km, board["tripKm"] as String), readouts.tripCaption)
    }

    private fun moment(file: File, until: String, board: String, running: Boolean, oneTrip: Boolean = false) {
        val (t, scene) = replayTo(file, until, oneTrip)
        val f = SpecJson.fixture(board)
        val run = t.engineCharge
        assertEquals("$board: running", running, run.running)
        assertTrue("$board: the block is up", scene.stage.engineBlock)
        assertEquals("$board: the figure", f["engineKwh"], ContourReadout.engineKwh(run.kwh))
        assertEquals("$board: the time", f["engineRun"], ContourReadout.engineRun(run.seconds.toInt()))
        if (t.trip.open) {
            // On the move the block is the trip's: its figure and time are the ledger's own.
            assertTrue("$board: the trip's engine", run.ofTrip)
            assertEquals("$board: the trip's «ЗАРЯД ОТ ДВС»", t.trip.engineKwh, run.kwh, 0.0)
            assertEquals("$board: the trip's engine time", t.trip.engineSeconds, run.seconds, 0.0)
        }
        val marks = (f["chargeMarks"] as List<*>?).orEmpty().map { (it as Number).toInt() }
        assertEquals("$board: the pauses' marks", marks, run.marks.toList())
        val line = (f["chargeLine"] as List<*>).map { (it as Number).toFloat() }
        assertEquals("$board: points", line.size, run.points.size)
        line.forEachIndexed { index, expected ->
            assertEquals("$board: point $index", expected, run.points[index], 0.006f)
        }
        assertEquals(
            "$board: the ceiling",
            if (line.max() < EngineCharge.LOW_BELOW_KW) EngineCharge.CEILING_LOW_KW else EngineCharge.CEILING_KW,
            run.ceilingKw,
            0f,
        )
    }

    /**
     * The hub's step over [file]'s rows whose wall time is not past [until]: the last snapshot and the
     * scene. [oneTrip] replays the drive as one trip: the selector's column is left out, so no P ends it.
     */
    private fun replayTo(file: File, until: String, oneTrip: Boolean = false): Pair<VehicleTelemetry, ContourScene> {
        val rows = file.readLines().filter { it.isNotBlank() }
        val header = rows[0].split(',')
        val wall = header.indexOf("time")
        val time = header.indexOf(VehicleCaptureColumns.MONO)
        val columns = VehicleSignal.entries
            .associateWith { signal -> header.indexOf(VehicleCaptureColumns.of(signal)) }
            .filterValues { it >= 0 }
            .filterKeys { !oneTrip || it != VehicleSignal.GEARBOX_PARK }
        val log = ConsumptionLog()
        val ledger = TripEnergyLedger()
        val charge = EngineCharge()
        val scene = ContourScene()
        var previous = Double.NaN
        var snapshot = VehicleTelemetry()
        for (line in rows.drop(1)) {
            val cells = line.split(',')
            if (cells.size < header.size) continue
            if (cells[wall] > until) break
            val mono = cells.getOrNull(time)?.toDoubleOrNull() ?: continue
            val dt = if (previous.isNaN() || mono <= previous) 0.0 else mono - previous
            previous = mono
            val parsed = columns.entries.mapNotNull { (signal, index) ->
                cells.getOrNull(index)?.toDoubleOrNull()?.let { signal to it }
            }.toMap()
            val values = VehicleAnsweredSweep.feed(
                parsed = parsed,
                cold = parsed.filterKeys { it in VehicleSignal.COLD },
                atMillis = (mono * 1000.0).toLong(),
                dtSeconds = dt,
                log = log,
                ledger = ledger,
                charge = charge,
            )
            snapshot = VehicleAnsweredSweep.snapshot(values, log, ledger, charge)
            scene.frame(snapshot, arrived = true, dt = dt.toFloat())
        }
        return snapshot to scene
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
        // A step of more than one tick is not a reading, so no reading is longer than a pitch.
        window.forEach { bucket ->
            if (!bucket.known) return@forEach
            assertEquals(
                "${file.name} at $at s: a reading of ${bucket.km} km",
                ConsumptionChart.PITCH_KM,
                bucket.km,
                1e-6,
            )
        }

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
     * the engine ran in wherever that falls.
     *
     * The newest by name alone could leave the engine block's invariant with nothing to hold.
     */
    private fun captures(): List<File> {
        val directory = directory() ?: return emptyList()
        val drives = directory.listFiles { file -> file.isFile && file.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return (drives.takeLast(MAX_FILES) + listOfNotNull(drives.lastOrNull(::engineRan))).distinct()
    }

    private fun directory(): File? {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })) {
            it.parentFile
        }.firstOrNull { File(it, "tools/design-canvas").isDirectory } ?: return null
        return File(root, "captures/vehicle-log").takeIf { it.isDirectory }
    }

    /** One named drive, if this machine has it. */
    private fun capture(name: String): File? = directory()?.let { File(it, name) }?.takeIf { it.isFile }

    /** Whether the engine ran anywhere in the rows [replay] reads of [file]. */
    private fun engineRan(file: File): Boolean = file.useLines { lines ->
        val rows = lines.filter { it.isNotBlank() }.take(MAX_ROWS + 1).iterator()
        val running = if (rows.hasNext()) {
            rows.next().split(',').indexOf(VehicleCaptureColumns.of(VehicleSignal.ENGINE_RUNNING))
        } else {
            -1
        }
        running >= 0 &&
            rows.asSequence().any { (it.split(',').getOrNull(running)?.toDoubleOrNull() ?: 0.0) >= 1.0 }
    }

    private companion object {
        /** The newest few drives, because a machine's capture directory only ever grows. */
        const val MAX_FILES = 3

        /** And an hour and a half of a row a second out of each, which is a drive. */
        const val MAX_ROWS = 5_000
    }
}
