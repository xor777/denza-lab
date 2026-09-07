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
 *  - the road under the chart is the road the odometer covered;
 *  - the figure is energy over known road, and nothing else;
 *  - no bin is drawn where the log had no energy;
 *  - the engine's box is never up with the running flag down past its own hold.
 *
 * `tools/vehicle_log.py` writes the files, into `captures/vehicle-log/` (git-ignored, so this test
 * runs against whatever the machine has). **It passes with nothing there**, which is the state it
 * was written in: the drive that closes the contract's open items - the engine running at speed -
 * has not been recorded yet, and a test that failed for the absence of a capture would be a test
 * nobody could run.
 */
class VehicleLogReplayTest {

    @Test
    fun everyRecordedDriveSatisfiesTheInvariantsThatDoNotDependOnMeaning() {
        val logs = captures()
        if (logs.isEmpty()) return
        logs.forEach { replay(it) }
    }

    private fun replay(file: File) {
        val rows = file.readLines().filter { it.isNotBlank() }
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

        val log = ConsumptionLog()
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

            val window = log.window
            val snapshot = VehicleTelemetry(
                access = VehicleAccess.READY,
                values = engineRunning?.let {
                    mapOf(VehicleSignal.ENGINE_RUNNING to if (it) 3.0 else 0.0)
                } ?: emptyMap(),
                consumption = window,
                chart = ConsumptionChart.of(window),
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

            checkRoad(file, mono, window, snapshot.chart)
            checkFigure(file, mono, window, snapshot.consumptionMean)
            checkHoles(file, mono, window, snapshot.chart)
        }
    }

    /** The chart's own width is the road the odometer covered under it, bin by bin. */
    private fun checkRoad(
        file: File,
        at: Double,
        window: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        if (window.isEmpty()) return
        // The road the odometer covered over the window, from the readings rather than from the
        // records: each bucket's road is the difference it accumulated, so the sum is the trip
        // between the first bucket's start and the last one's close.
        val travelled = window.last().odometerKm - window.first().odometerKm + window.first().km
        val recorded = window.sumOf { it.km }
        assertEquals("${file.name} at $at s: the window's road", travelled, recorded, 1e-6)

        // And the chart draws that road, one anchored half kilometre at a time.
        val newest = floor(window.last().odometerKm / ConsumptionChart.BIN_KM).toLong()
        val base = newest - ConsumptionChart.BINS + 1
        val perBin = DoubleArray(ConsumptionChart.BINS)
        window.forEach { bucket ->
            val bin = (floor(bucket.odometerKm / ConsumptionChart.BIN_KM).toLong() - base).toInt()
            if (bin in perBin.indices) perBin[bin] += bucket.km
        }
        val expected = perBin.sumOf { minOf(it, ConsumptionChart.BIN_KM) }
        val drawn = chart.widths.sumOf { it.toDouble() } * ConsumptionChart.BIN_KM
        assertEquals("${file.name} at $at s: the road under the chart", expected, drawn, 1e-4)
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

    /** Nothing is drawn where the log had no energy, and no bin is an invented zero. */
    private fun checkHoles(
        file: File,
        at: Double,
        window: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
    ) {
        if (window.isEmpty()) return
        val newest = floor(window.last().odometerKm / ConsumptionChart.BIN_KM).toLong()
        val base = newest - ConsumptionChart.BINS + 1
        val known = DoubleArray(ConsumptionChart.BINS)
        val road = DoubleArray(ConsumptionChart.BINS)
        window.forEach { bucket ->
            val bin = (floor(bucket.odometerKm / ConsumptionChart.BIN_KM).toLong() - base).toInt()
            if (bin in known.indices) {
                known[bin] += bucket.knownKm
                road[bin] += bucket.km
            }
        }
        var first = 0
        while (first < ConsumptionChart.BINS && road[first] <= 0.0) first++
        chart.values.forEachIndexed { index, value ->
            val bin = first + index
            if (value.isNaN()) return@forEachIndexed
            assertTrue(
                "${file.name} at $at s: bin $bin is drawn over ${known[bin]} km of known road",
                known[bin] > 0.0 && known[bin] * 2.0 >= road[bin] - 1e-9,
            )
            assertTrue(
                "${file.name} at $at s: bin $bin is not finite",
                !value.isInfinite() && abs(value) < 1e6f,
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
            ?: emptyList()
    }
}
