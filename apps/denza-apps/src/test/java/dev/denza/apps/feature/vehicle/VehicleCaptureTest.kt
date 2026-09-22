package dev.denza.apps.feature.vehicle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The flight recorder: that it is off, that it is bounded, and that what it writes is readable.
 *
 * Three groups of cases, and they are the three ways this could hurt somebody. **Off** - a car
 * whose owner never asked for a recording must not find a directory, a file or a byte of one.
 * **Bounded** - a marker left in place for a month must cost four files and not a partition.
 * **Readable** - a file pulled off the car has to go through `VehicleLogReplayTest`'s own column
 * lookups and come back as numbers, because a recording nobody can replay is a recording that was
 * never made.
 *
 * Both clocks are handed in, so a four-hour file and a minute between marker checks are a few
 * lines each rather than a test somebody has to wait for.
 */
class VehicleCaptureTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var elapsed = 0L
    private var wall = STARTED_AT
    private val failures = mutableListOf<String>()

    private fun directory() = File(folder.root, VehicleCapture.DIRECTORY)

    private fun capture(): VehicleCapture = VehicleCapture(
        directory = directory(),
        elapsedMillis = { elapsed },
        wallMillis = { wall },
        onFailed = { failures.add(it) },
    )

    /** What the host does before a drive, and nothing the app is allowed to do for it. */
    private fun enable() {
        directory().mkdirs()
        File(directory(), VehicleCapture.MARKER).writeText("")
    }

    private fun disable() {
        File(directory(), VehicleCapture.MARKER).delete()
    }

    private fun files(): List<File> =
        directory().listFiles { file -> file.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    private fun lines(file: File = files().last()): List<String> =
        file.readLines().filter { it.isNotEmpty() }

    private fun rows(file: File = files().last()): List<String> = lines(file).drop(1)

    private fun sweep(
        capture: VehicleCapture,
        values: Map<VehicleSignal, Double> = readings(),
        stepMs: Long = 0L,
    ) {
        elapsed += stepMs
        wall += stepMs
        capture.sample(values)
    }

    private fun readings(
        powerKw: Double = 34.0,
        odometerKm: Double = 12_345.6,
        speedKmh: Double = 51.5,
    ): Map<VehicleSignal, Double> = linkedMapOf(
        VehicleSignal.POWER_KW to powerKw,
        VehicleSignal.PACK_VOLT to 552.0,
        VehicleSignal.ODOMETER_KM to odometerKm,
        VehicleSignal.GEARBOX_PARK to 0.0,
        VehicleSignal.VEHICLE_SPEED to speedKmh,
        VehicleSignal.PACK_TEMP_AVG to 23.0,
        VehicleSignal.ENGINE_RUNNING to 3.0,
        VehicleSignal.ENGINE_RPM to 1776.0,
        VehicleSignal.GENERATION_KW to 8.0,
    )

    // ---- off ----

    @Test
    fun withoutTheMarkerNothingIsWrittenAndNothingIsCreated() {
        val capture = capture()
        repeat(50) { sweep(capture, stepMs = 1_000L) }
        assertFalse("the capture created its own directory", directory().exists())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun anEmptyDirectoryWithoutTheMarkerIsStillNothing() {
        directory().mkdirs()
        val capture = capture()
        repeat(50) { sweep(capture, stepMs = 1_000L) }
        assertEquals(emptyList<File>(), files())
    }

    @Test
    fun withTheMarkerTheSweepReachesTheDisk() {
        enable()
        val capture = capture()
        sweep(capture)
        assertEquals(1, files().size)
        assertEquals(1, rows().size)
        assertTrue("the file is named like the host recorder's", files().single().name.matches(NAME))
    }

    // ---- the cadence ----

    /**
     * Ten sweeps a second, one row of them.
     *
     * The hub sweeps every 100 ms while a screen is up ([VehicleSweepCadence.HOT_INTERVAL_MS]) and
     * a recording at that rate would be ten times the file for a quantity that is integrated over
     * hundreds of metres. Eleven rows over ten seconds because the first one is the enable itself.
     */
    @Test
    fun aHundredMillisecondCadenceIsOneRowASecond() {
        enable()
        val capture = capture()
        sweep(capture)
        repeat(100) { sweep(capture, stepMs = 100L) }
        assertEquals(11, rows().size)
    }

    /** And at the cadence the ledger actually runs at, every sweep is a row. */
    @Test
    fun aSecondCadenceWritesEverySweep() {
        enable()
        val capture = capture()
        sweep(capture)
        repeat(9) { sweep(capture, stepMs = 1_000L) }
        assertEquals(10, rows().size)
    }

    /**
     * The marker is looked at when the loop starts and then once a minute.
     *
     * Not on every sweep: that is a `stat` four times a second for the life of the process, on a
     * switch that changes when somebody plugs a laptop in. A minute is the price of turning it on
     * mid-drive, and the owner is not standing beside the car with a stopwatch.
     */
    @Test
    fun theMarkerIsCheckedAtMostOnceAMinute() {
        val capture = capture()
        sweep(capture)
        enable()
        repeat(59) { sweep(capture, stepMs = 1_000L) }
        assertEquals("the marker was read before its minute was up", emptyList<File>(), files())
        sweep(capture, stepMs = 1_000L)
        assertEquals(1, files().size)
        assertEquals(1, rows().size)
    }

    /** And the loop's own start is not subject to it: a process that just came up has never looked. */
    @Test
    fun aLoopThatStartsLooksAtTheMarkerStraightAway() {
        val capture = capture()
        sweep(capture)
        enable()
        capture.recheck()
        sweep(capture, stepMs = 100L)
        assertEquals(1, rows().size)
    }

    // ---- the columns ----

    @Test
    fun theHeaderIsTheTwoClocksAndEverySignalInDeclarationOrder() {
        enable()
        val capture = capture()
        sweep(capture)
        val header = lines().first().split(',')
        assertEquals("time", header[0])
        assertEquals("mono_s", header[1])
        assertEquals(
            VehicleSignal.entries.map { VehicleCaptureColumns.of(it) },
            header.drop(2),
        )
        assertEquals(VehicleSignal.entries.size + 2, header.size)
    }

    @Test
    fun theColdSignalsCarryTheirEnumNames() {
        assertEquals("pack_temp_avg", VehicleCaptureColumns.of(VehicleSignal.PACK_TEMP_AVG))
        assertEquals("cell_min_mv", VehicleCaptureColumns.of(VehicleSignal.CELL_MIN_MV))
        assertEquals("motor_rear_left_c", VehicleCaptureColumns.of(VehicleSignal.MOTOR_REAR_LEFT_C))
        assertEquals("charge_minutes", VehicleCaptureColumns.of(VehicleSignal.CHARGE_MINUTES))
    }

    /**
     * Every shared name is still a `Signal` in the host recorder.
     *
     * The two recorders write overlapping sets of the same ids and one test reads both files by
     * column name. A rename on either side turns a pulled file into a drive with no power in it -
     * a silence, not a failure - so the names are held together the way a board and its renderer
     * are.
     */
    @Test
    fun everySharedNameIsAColumnTheHostRecorderAlsoWrites() {
        val source = File(repositoryRoot(), "tools/vehicle_log.py").readText()
        VehicleCaptureColumns.SHARED.forEach { (signal, name) ->
            assertTrue(
                "$signal is written as '$name' here and tools/vehicle_log.py has no Signal('$name'",
                source.contains("Signal('$name'"),
            )
        }
        assertEquals("the shared names are one per signal", 11, VehicleCaptureColumns.SHARED.size)
    }

    @Test
    fun aSignalThatDidNotAnswerIsAnEmptyCellAndNeverAZero() {
        enable()
        val capture = capture()
        sweep(capture, values = mapOf(VehicleSignal.POWER_KW to -12.0))
        val header = lines().first().split(',')
        val cells = rows().single().split(',')
        assertEquals(header.size, cells.size)
        assertEquals("-12", cells[header.indexOf("power_kw")])
        assertEquals("", cells[header.indexOf("odometer_km")])
        assertEquals("", cells[header.indexOf("pack_temp_avg")])
        assertEquals("", cells[header.indexOf("engine_running")])
    }

    /** The id's own number, three decimals at most, and never an exponent. */
    @Test
    fun theValuesAreWrittenTheWayANumberIsRead() {
        assertEquals("34", VehicleCaptureColumns.format(34.0))
        assertEquals("-22", VehicleCaptureColumns.format(-22.0))
        assertEquals("12345.6", VehicleCaptureColumns.format(12_345.6))
        assertEquals("2.4", VehicleCaptureColumns.format(2.4000000953674316))
        assertEquals("0.001", VehicleCaptureColumns.format(0.001))
        assertFalse("an exponent reached the file", VehicleCaptureColumns.format(1e-4).contains('E'))
        assertEquals("", VehicleCaptureColumns.format(Double.NaN))
    }

    /** And the clocks read back: elapsed realtime in seconds, the wall clock as local ISO-8601. */
    @Test
    fun bothClocksAreWrittenTheWayTheHostRecorderWritesThem() {
        enable()
        elapsed = 4_500L
        val capture = capture()
        sweep(capture)
        val cells = rows().single().split(',')
        assertEquals(4.5, cells[1].toDouble(), 1e-9)
        assertTrue("'${cells[0]}' is not a local ISO-8601 moment", cells[0].matches(MOMENT))
    }

    // ---- the bounds ----

    /**
     * Past two megabytes the recording moves to a new file, and only four of them are kept.
     *
     * The file is grown from outside rather than by driving fifteen thousand rows through it: the
     * bound is a length on the disk, and what this has to prove is that the length is the one
     * written down.
     */
    @Test
    fun theFileRotatesPastItsBoundAndOnlyFourAreKept() {
        enable()
        val capture = capture()
        sweep(capture)
        val first = files().single()

        repeat(4) {
            fill(files().last())
            sweep(capture, stepMs = 1_000L)
        }

        assertEquals("five files left more than the cap", VehicleCapture.MAX_FILES, files().size)
        assertFalse("the oldest file survived the cap", first.exists())

        // And the newest one is the one being written: a rotation opens a file with a header and
        // the next sweep's row goes into it, not into the file that was full.
        sweep(capture, stepMs = 1_000L)
        assertEquals(VehicleCapture.MAX_FILES, files().size)
        assertEquals(1, rows(files().last()).size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aFileUnderTheBoundIsNotRotated() {
        enable()
        val capture = capture()
        sweep(capture)
        files().single().appendText("x".repeat((VehicleCapture.MAX_BYTES - 1024L).toInt()))
        sweep(capture, stepMs = 1_000L)
        assertEquals(1, files().size)
    }

    /** A new file per enable: the marker's removal closes the one that is open. */
    @Test
    fun removingTheMarkerClosesTheFileAndPuttingItBackOpensANewOne() {
        enable()
        val capture = capture()
        sweep(capture)
        sweep(capture, stepMs = 1_000L)
        val first = files().single()
        assertEquals(2, rows(first).size)

        disable()
        elapsed += VehicleCapture.MARKER_MS
        wall += VehicleCapture.MARKER_MS
        repeat(10) { sweep(capture, stepMs = 1_000L) }
        assertEquals("rows kept arriving after the marker went", 2, rows(first).size)
        assertEquals(1, files().size)

        enable()
        elapsed += VehicleCapture.MARKER_MS
        wall += VehicleCapture.MARKER_MS
        sweep(capture)
        assertEquals(2, files().size)
        assertEquals("the second enable appended to the first file", 2, rows(first).size)
        assertEquals(1, rows(files().last()).size)
    }

    // ---- durability and failure ----

    /** Every row is on the disk before the call returns; a reader sees it without waiting. */
    @Test
    fun everyRowIsReadableAsSoonAsItIsWritten() {
        enable()
        val capture = capture()
        sweep(capture)
        repeat(5) { index ->
            assertEquals(index + 1, rows().size)
            sweep(capture, stepMs = 1_000L)
        }
        assertEquals(6, rows().size)
    }

    /**
     * A disk that refuses stops the recording and says so once; the poll loop never hears about it.
     *
     * The file is replaced by a directory of the same name, which is the one refusal a test can
     * arrange without being root. What must not happen is an exception: the hub's sweep feeds the
     * ledger and the panel, and a recording nobody asked for does not get to cost a kilometre of
     * road.
     */
    @Test
    fun aDiskThatRefusesTurnsTheCaptureOffWithoutThrowing() {
        enable()
        val capture = capture()
        sweep(capture)
        val open = files().single()
        assertTrue(open.delete())
        assertTrue(File(open.path).mkdirs())

        repeat(20) { sweep(capture, stepMs = 1_000L) }
        assertEquals("the failure was reported more than once", 1, failures.size)

        // And it comes back at the next marker check, because the marker is still there.
        elapsed += VehicleCapture.MARKER_MS
        wall += VehicleCapture.MARKER_MS
        sweep(capture, stepMs = 1_000L)
        assertEquals(1, rows(files().last { it.isFile }).size)
    }

    // ---- readable ----

    /**
     * A file this wrote, read the way a recorded drive is read.
     *
     * The same six lookups `VehicleLogReplayTest` does on every `.csv` in `captures/vehicle-log`:
     * pull a file off the car into that directory and the replay runs against it without knowing
     * which recorder wrote it. That is the whole point of the shared names, asserted end to end
     * rather than as a mapping.
     */
    @Test
    fun whatItWritesIsWhatTheReplayReads() {
        enable()
        val capture = capture()
        sweep(capture, values = readings(powerKw = 41.0, odometerKm = 1_000.0, speedKmh = 62.0))
        sweep(capture, values = readings(powerKw = -7.5, odometerKm = 1_000.1, speedKmh = 60.0), stepMs = 1_000L)

        val all = lines()
        val header = all.first().split(',')
        val mono = header.indexOf("mono_s")
        val power = header.indexOf("power_kw")
        val odometer = header.indexOf("odometer_km")
        val generation = header.indexOf("generation_kw")
        val running = header.indexOf("engine_running")
        val speed = header.indexOf("speed_kmh")
        assertTrue(
            "a column the replay needs is missing",
            listOf(mono, power, odometer, generation, running, speed).none { it < 0 },
        )

        val first = all[1].split(',')
        val second = all[2].split(',')
        assertTrue("the replay skips a short row", first.size >= header.size)
        assertEquals(0.0, first[mono].toDouble(), 1e-9)
        assertEquals(1.0, second[mono].toDouble(), 1e-9)
        assertEquals(41.0, first[power].toDouble(), 1e-6)
        assertEquals(-7.5, second[power].toDouble(), 1e-6)
        assertEquals(1_000.0, first[odometer].toDouble(), 1e-6)
        assertEquals(1_000.1, second[odometer].toDouble(), 1e-6)
        assertEquals(8.0, first[generation].toDouble(), 1e-6)
        assertEquals(62.0, first[speed].toDouble(), 1e-6)
        assertTrue("the engine flag does not read as running", first[running].toDouble() >= 1.0)

        // And the convention is the reader's, not the file's: the raw id goes down as it arrived.
        assertEquals(41.0, VehicleConvention.load(first[power].toDouble())!!, 1e-6)
    }

    // ---- helpers ----

    /** Push a file past the bound from outside, the cheap way. */
    private fun fill(file: File) {
        file.appendText("x".repeat(VehicleCapture.MAX_BYTES.toInt()))
    }

    /** The repository, found the way `VehicleLogReplayTest` finds it. */
    private fun repositoryRoot(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")) { "user.dir" })) { it.parentFile }
            .firstOrNull { File(it, "tools/design-canvas").isDirectory }
            ?: error("the repository root is not above ${System.getProperty("user.dir")}")

    private companion object {
        /** 2026-09-18T18:30:00Z, so the names and moments in this test are somebody's real evening. */
        const val STARTED_AT = 1_789_763_400_000L

        val NAME = Regex("""vehicle-\d{8}-\d{6}\.csv""")
        val MOMENT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}""")
    }
}
