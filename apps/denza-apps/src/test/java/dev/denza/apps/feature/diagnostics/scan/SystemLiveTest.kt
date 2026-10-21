package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.AutoserviceShell
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTransact
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The systems' «Потоковые данные» (architecture 7.2; boards `scanner-sys-<system>-live`): each table is
 * its system's rows of research/vehicle-diagnostics/stream-selection-20261021.json, keeps its minute as
 * the pack's does ([PackLiveTest]), asks its own page while in front and gives it back on «Пауза», and
 * each board is what its table makes of a minute - and, where the sweep of 2026-10-20 is on this
 * machine, of that sweep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemLiveTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    /** The boards of the systems the selection gave a table, the pack's own board aside. */
    private val boards: Map<CarSystem, String> = SystemStreams.ROWS.keys.filter { it != CarSystem.PACK }
        .associateWith { "scanner-sys-${it.name.lowercase()}-live" }

    // ------------------------------------------------------------------ the tables

    @Test
    fun everyTableIsItsSystemsRowsOfTheSelectionInItsOrder() {
        val systems = JSONObject(File(root, "research/vehicle-diagnostics/stream-selection-20261021.json").readText()).getJSONObject("systems")
        fun selection(system: CarSystem): List<Pair<Int, Int>> = systems.getJSONArray(system.name).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.getInt("device") to it.getString("fid").removePrefix("0x").toLong(16).toInt() }
        }
        fun table(system: CarSystem) = SystemStreams.ROWS.getValue(system).filter { it.calc == null }
            .map { r -> (r.extra?.device ?: r.hub!!.device) to (r.extra?.fid ?: r.hub!!.fid) }
        boards.keys.forEach { s -> assertEquals("$s", selection(s), table(s)) }
        // the pack keeps its board's rows and takes the selection's five - the current was there
        assertTrue(table(CarSystem.PACK).containsAll(selection(CarSystem.PACK)))
        assertEquals(22, SystemStreams.PACK.size)
        // ADAS's two, the cabin's none and the engine's energy mode stay out (owner, 2026-10-21); the engine's is ECM's stream
        assertEquals(listOf(CarSystem.HV, CarSystem.PACK, CarSystem.DRIVES, CarSystem.CHASSIS, CarSystem.SAFETY, CarSystem.BODY,
            CarSystem.CLIMATE, CarSystem.LV), SystemStreams.ROWS.keys.toList())
        // every system with a table has the tab, after «Состояние»
        SystemStreams.ROWS.keys.forEach { s ->
            val tabs = SystemPagesBuilder.TABS.getValue(s)
            assertTrue("$s", SystemTab.LIVE in tabs)
            if (s != CarSystem.PACK) assertEquals("$s", 1, tabs.indexOf(SystemTab.LIVE))
            assertNotNull("$s", extraPage(s, SystemTab.LIVE))
        }
    }

    @Test
    fun aReadingHasOneNameInEveryTableAndItsPageIsTitledByIt() {
        val all = SystemStreams.ROWS.values.flatten()
        all.filter { it.key != null }.groupBy { it.key }.forEach { (key, readings) ->
            assertEquals("$key", 1, readings.map { Triple(it.name, it.unit, it.decimals) }.toSet().size)
            val spec = ParamSpec.of(ParamKey.Signal(key!!))
            assertNotNull("$key opens a page", spec)
            assertEquals("$key", readings.first().name, spec!!.name)
            assertEquals("$key", "· ${readings.first().source}", spec.aside)
        }
        // a hub signal the engine's stream also shows reads there in the same unit and decimals
        LiveStream.CarRow.entries.forEach { c ->
            all.firstOrNull { it.hub == c.signal }?.let { r -> assertEquals(c.name, c.unit to c.decimals, r.unit to r.decimals) }
        }
        // a state's page says its word, as its row does
        assertEquals("затянут", ParamSpec.of(ParamKey.Signal("epb"))!!.text!!.invoke(3.0))
        // within a table, one row a reading
        SystemStreams.ROWS.forEach { (s, rows) -> assertEquals("$s", rows.size, rows.map { it.key ?: it.name }.toSet().size) }
    }

    @Test
    fun aStateItsCardNamesAFaultOrAnAlarmIsOrangeAndANumberOnlyOutsideAKnownNorm() {
        fun row(system: CarSystem, name: String, car: CarReadings) = SystemLive.of(system, car).rows.single { it.name == name }
        fun extras(vararg v: Pair<DiagnosticExtra, Double>) = CarReadings(extras = mapOf(*v), extrasFresh = true, extrasAtNanos = 1)
        listOf(
            Triple(CarSystem.CHASSIS, "Стояночный тормоз (EPB)", DiagnosticExtra.EPB) to listOf(4.0 to true, 3.0 to false),
            Triple(CarSystem.CHASSIS, "TPMS, система", DiagnosticExtra.TPMS_STATE) to listOf(3.0 to true, 2.0 to false, 0.0 to false),
            Triple(CarSystem.CHASSIS, "TPMS, батареи датчиков", DiagnosticExtra.TPMS_BATTERY) to listOf(1.0 to true, 0.0 to false),
            Triple(CarSystem.CHASSIS, "Шины, температура", DiagnosticExtra.TYRE_TEMP_STATE) to listOf(1.0 to true, 2.0 to true, 3.0 to false),
            Triple(CarSystem.CHASSIS, "Утечка воздуха, ЗП", DiagnosticExtra.RR_LEAK) to listOf(1.0 to true, 2.0 to true, 7.0 to false),
            Triple(CarSystem.CHASSIS, "Давление, оценка ПЛ", DiagnosticExtra.FL_PRESSURE_STATE) to listOf(2.0 to true, 3.0 to false),
            Triple(CarSystem.CLIMATE, "Датчик солнца", DiagnosticExtra.SUN_SENSOR) to listOf(1.0 to true, 0.0 to false),
            Triple(CarSystem.PACK, "Зарядное устройство, отказ", DiagnosticExtra.CHARGER_FAULT) to listOf(3.0 to true, 1.0 to false),
            Triple(CarSystem.PACK, "Состояние заряда батареи", DiagnosticExtra.PACK_CHARGE_STATE) to listOf(6.0 to true, 3.0 to false),
            Triple(CarSystem.BODY, "Указатели поворота", DiagnosticExtra.TURN_LIGHTS) to listOf(6.0 to false, 7.0 to false),
            Triple(CarSystem.SAFETY, "Ремень, водитель", DiagnosticExtra.BELT_FL) to listOf(0.0 to false),
        ).forEach { (at, cases) ->
            val (system, name, e) = at
            cases.forEach { (code, orange) ->
                val r = row(system, name, extras(e to code))
                assertEquals("$name $code", orange, r.outOfNorm)
                assertEquals("$name: a state keeps no trace", "" to "", r.min to r.max)
            }
            assertFalse("$name: nothing read is no alarm", row(system, name, CarReadings()).outOfNorm)
        }
        // a hub state: the energy system's generation, «ошибка» orange, the stock INVALID «н/д»
        val generation = { v: Double -> row(CarSystem.HV, "Генерация, состояние", CarReadings(hub = mapOf(VehicleSignal.GENERATION_STATE to v), sweep = 1)) }
        assertEquals("ошибка" to true, generation(3.0).let { it.value to it.outOfNorm })
        assertEquals("н/д" to false, generation(0.0).let { it.value to it.outOfNorm })
        // no table but the pack's has a norm; a number there is never orange
        SystemStreams.ROWS.filterKeys { it != CarSystem.PACK }.values.flatten().forEach { assertEquals(it.name, null, it.norm) }
        assertFalse(row(CarSystem.DRIVES, "Обороты, передний мотор", extras(DiagnosticExtra.RPM_FRONT to -20000.0)).outOfNorm)
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun aSystemsTabAsksForItsPageKeepsItsMinuteAndPausesIt() = runTest {
        fun round(n: Long, rpm: Double) = CarReadings(
            hub = mapOf(VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.INVERTER_C to 9.0),
            sweep = n, atNanos = n * SECOND,
            extras = mapOf(DiagnosticExtra.RPM_FRONT to rpm, DiagnosticExtra.GEAR_SELECTOR to 1.0), extrasFresh = true,
            extrasAtNanos = n * SECOND, extrasTookMs = 400,
        )
        val car = FakeCar(round(1, 0.0))
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        fun drives() = s.model.value.systems.live.getValue(CarSystem.DRIVES)
        fun rpm() = drives().rows.single { it.name == "Обороты, передний мотор" }

        s.setSystemLiveVisible(CarSystem.DRIVES, true)
        assertEquals(listOf(ExtraPage.DRIVES_LIVE to true), car.pageLog)
        (2L..4L).forEach { n ->
            car.readings = round(n, 100.0 * n)
            advanceTimeBy(DiagnosticsSession.TICK_MS)
            runCurrent()
        }
        assertEquals("400", rpm().value)
        assertEquals("0" to "400", rpm().min to rpm().max)
        assertEquals(4, rpm().samples.size)
        assertEquals("P", drives().rows.single { it.name == "Селектор" }.value)
        assertEquals("обход 0,4 с", drives().round)
        // another system's table stands as one look at the car
        assertTrue(s.model.value.systems.live.getValue(CarSystem.HV).rows.all { it.samples.size <= 1 })

        s.resetSystemLive(CarSystem.DRIVES)
        assertEquals("400" to "400", rpm().min to rpm().max)

        // «Пауза»: the page leaves the batch, the table stands
        s.setSystemLivePaused(CarSystem.DRIVES, true)
        assertEquals(ExtraPage.DRIVES_LIVE to false, car.pageLog.last())
        val paused = drives()
        assertTrue(paused.paused)
        car.readings = round(5, 900.0)
        advanceTimeBy(DiagnosticsSession.TICK_MS * 2)
        runCurrent()
        assertEquals(paused, drives())
        // the pause of a table not in front is nobody's
        val asked = car.pageLog.size
        s.setSystemLivePaused(CarSystem.CHASSIS, true)
        assertEquals(asked, car.pageLog.size)

        // another table in front while this one is paused: its page was already given back
        s.setSystemLiveVisible(CarSystem.CHASSIS, true)
        assertEquals(ExtraPage.CHASSIS_LIVE to true, car.pageLog.last())
        assertEquals(asked + 1, car.pageLog.size)
        assertFalse(s.model.value.systems.live.getValue(CarSystem.DRIVES).paused)
        // and one in front when another comes: the first gives its page back
        s.setSystemLiveVisible(CarSystem.HV, true)
        assertEquals(listOf(ExtraPage.CHASSIS_LIVE to false, ExtraPage.HV_LIVE to true), car.pageLog.takeLast(2))
        s.setSystemLiveVisible(CarSystem.CHASSIS, false)
        assertEquals("leaving a table not in front asks nothing", ExtraPage.HV_LIVE to true, car.pageLog.last())
        s.setSystemLiveVisible(CarSystem.HV, false)
        assertEquals(ExtraPage.HV_LIVE to false, car.pageLog.last())

        // the drives shown again: no pause, the extremes over, the minute kept
        s.setSystemLiveVisible(CarSystem.DRIVES, true)
        assertFalse(drives().paused)
        assertEquals("900" to "900", rpm().min to rpm().max)
        assertEquals(5, rpm().samples.size)
        // the engine's stream is ECM's: no page
        val before = car.pageLog.size
        s.setSystemLiveVisible(CarSystem.ENGINE, true)
        assertEquals(before, car.pageLog.size)
        s.stop()
    }

    // ------------------------------------------------------------------ the boards

    private fun board(id: String) = SpecJson.fixture(id)

    @Suppress("UNCHECKED_CAST")
    private fun rowsOf(board: Map<String, Any?>) = board["rows"] as List<Map<String, Any?>>

    /** A board's figure as a number: «−4,5» → −4.5, «14 429» → 14429; null for «—» and «н/д». */
    private fun figure(text: Any?): Double? = (text as String).replace('−', '-').replace(",", ".").replace(" ", "").toDoubleOrNull()

    /** The code [words] says [word] with; null where the word is «н/д», which nothing read says. */
    private fun code(words: StateWords, word: String): Double? =
        if (word == "н/д") null else (0..254).first { words.word(it.toDouble()) == word }.toDouble()

    /** Where a reading lands in [CarReadings]. */
    private fun MutableMap<VehicleSignal, Double>.put(r: StreamReading, v: Double?, extras: MutableMap<DiagnosticExtra, Double>) {
        if (v == null) return
        r.hub?.let { this[it] = v }
        r.extra?.let { extras[it] = v }
    }

    private fun minute(rounds: List<Pair<Map<VehicleSignal, Double>, Map<DiagnosticExtra, Double>>>, system: CarSystem): SystemLiveView {
        val live = SystemLive(system).apply { show() }
        rounds.forEachIndexed { i, (hub, extras) ->
            val n = i + 1L
            live.take(CarReadings(hub, n, n * SECOND, extras, true, n * SECOND, 400))
        }
        return live.view()
    }

    /** Every word of [board] is [view]'s: the row's device, name, FID, value, unit, extremes, norm, ink and trace. */
    @Suppress("UNCHECKED_CAST")
    private fun assertBoard(id: String, board: Map<String, Any?>, view: SystemLiveView, system: CarSystem) {
        val rows = rowsOf(board)
        assertEquals(id, rows.map { it["name"] }, view.rows.map { it.name })
        rows.zip(view.rows).forEach { (b, r) ->
            val at = "$id «${b["name"]}»"
            assertEquals(at, b["pid"], r.device)
            assertEquals(at, b["abbr"], r.abbreviation)
            assertEquals(at, b["value"], r.value)
            assertEquals(at, b["unit"], r.unit)
            assertEquals(at, b["min"] to b["max"], r.min to r.max)
            assertEquals(at, b["norm"], r.norm)
            assertEquals(at, b["out"], r.outOfNorm)
            assertEquals("$at: a trace where the board draws one", (b["trace"] as List<*>).isNotEmpty(), r.samples.any { it.value != null })
        }
        val tabs = board["tabs"] as Map<String, Any?>
        assertEquals(id, SystemPagesBuilder.TABS.getValue(system).map { it.label }, tabs["items"])
        assertEquals(id, SystemPagesBuilder.TABS.getValue(system).indexOf(SystemTab.LIVE), (tabs["active"] as Number).toInt())
        val footer = board["footer"] as Map<String, Any?>
        assertEquals("$id: running", listOf("Пауза", "Сбросить мин/макс"), (footer["buttons"] as List<List<Any?>>).map { it[0] })
        assertFalse(view.paused)
        assertEquals(id, (footer["notes"] as List<List<Any?>>).single()[0], view.round)
    }

    /**
     * Each `scanner-sys-<system>-live` is what its table makes of a minute through the board's own
     * extremes - its minimum, its maximum, then its value - the last batch 0,4 s long: the rows, their
     * order, devices, FIDs, units and decimals, the states' words and their ink are the table's.
     */
    @Test
    fun everyStreamBoardIsWhatItsTableMakesOfItsMinute() {
        boards.forEach { (system, id) ->
            val b = board(id)
            val readings = SystemStreams.ROWS.getValue(system)
            val rows = rowsOf(b)
            assertEquals(id, readings.size, rows.size)
            val rounds = listOf("min", "max", "value").map { which ->
                val hub = mutableMapOf<VehicleSignal, Double>()
                val extras = mutableMapOf<DiagnosticExtra, Double>()
                readings.zip(rows).forEach { (r, row) ->
                    val words = r.words
                    val v = when {
                        words != null -> code(words, row["value"] as String)
                        r.count -> figure(row["value"])
                        else -> figure(row[which]).takeIf { figure(row["value"]) != null }
                    }
                    hub.put(r, v, extras)
                }
                hub.toMap() to extras.toMap()
            }
            assertBoard(id, b, minute(rounds, system), system)
        }
    }

    // ------------------------------------------------------------------ the sweep of 2026-10-20

    /** captures/diagnostics-sweep-20261020/fids.tsv, git-ignored: in this checkout or the one it is a worktree of. */
    private val sweep: File? = generateSequence(root) { it.parentFile }
        .map { File(it, "captures/diagnostics-sweep-20261020/fids.tsv") }.firstOrNull { it.exists() }

    /** Pass [pass] of the sweep as the batch and the hub would read it. */
    private fun pass(pass: Int): Pair<Map<VehicleSignal, Double>, Map<DiagnosticExtra, Double>> {
        val words = sweep!!.readLines().map { it.split('\t') }
            .filter { it.size >= 6 && it[0] == "FID" && it[1] == pass.toString() }
            .associate { c ->
                (c[3].toInt() to c[4].removePrefix("0x").toLong(16).toInt()) to
                    (c[5].removePrefix("0x").toLong(16).toInt() to c.getOrNull(6)?.takeIf { it.isNotBlank() }?.removePrefix("0x")?.toLong(16)?.toInt())
            }
        fun word(device: Int, fid: Int, float: Boolean) = words[device to fid]?.let { (int, f) -> if (float) f ?: int else int }
        val batch = DiagnosticExtra.entries.mapNotNull { e -> word(e.device, e.fid, e.transact == 7)?.let { e to it } }
            .joinToString("\n") { (e, w) -> "@@extra:${e.key}\nResult: Parcel(00000000 %08x   '........')".format(w) }
        val extras = DiagnosticExtraReader.decode(batch, 1, 1).values
        val hub = VehicleSignal.entries.mapNotNull { s ->
            word(s.device, s.fid, s.transact == VehicleTransact.FLOAT)?.let { AutoserviceShell.decode(s, it) }?.let { s to it }
        }.toMap()
        return hub to extras
    }

    /**
     * Each board says what its table makes of the parked sweep: the second pass, then the first - the
     * value - so a reading that moved has both passes in its extremes. A row the sweep did not read on
     * its device (the 12 V on 1001) is the board's own and not compared.
     */
    @Test
    fun everyStreamBoardSaysWhatTheParkedSweepRead() {
        assumeTrue("the sweep of 2026-10-20 is a local capture", sweep != null)
        val second = pass(2)
        val first = pass(1)
        boards.forEach { (system, id) ->
            val view = minute(listOf(second, first, first), system)
            val read = SystemStreams.ROWS.getValue(system).map { r ->
                r.hub?.let { it in first.first } ?: (r.extra!! in first.second || readsNothing(r.extra))
            }
            rowsOf(board(id)).zip(view.rows).zip(read).forEach { (pair, swept) ->
                val (b, r) = pair
                if (!swept) return@forEach
                val at = "$id «${b["name"]}»"
                assertEquals(at, b["value"], r.value)
                assertEquals(at, b["min"] to b["max"], r.min to r.max)
                assertEquals(at, b["out"], r.outOfNorm)
            }
        }
    }

    /** A GET the sweep answered with a code its range refuses - rear-left rpm FFFF - is read, as «н/д». */
    private fun readsNothing(e: DiagnosticExtra): Boolean = e == DiagnosticExtra.RPM_REAR_LEFT

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
