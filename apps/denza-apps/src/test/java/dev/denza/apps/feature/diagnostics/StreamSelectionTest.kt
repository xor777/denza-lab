package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.diagnostics.scan.CarReadings
import dev.denza.apps.feature.diagnostics.scan.CarStates
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.SignalGet
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import dev.denza.apps.feature.diagnostics.scan.extraPage
import dev.denza.apps.feature.vehicle.VehiclePoll
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The scanner's readings against their source table, research/vehicle-diagnostics/stream-selection-20261021.json
 * (architecture 6): every row one GET of the batch or one hub signal, with the row's device, FID and
 * transaction; the row's codes taken in its words and its invalid encodings refused; the sweep of
 * 2026-10-20 decoded as the row says it reads; and which page carries what, the shared batch not grown.
 */
class StreamSelectionTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private class Row(val system: String, json: JSONObject) {
        val name: String = json.getString("name_ru")
        val device = json.getInt("device")
        val fid = json.getString("fid").removePrefix("0x").toLong(16).toInt()
        val transaction = json.getInt("transaction")
        val float = json.getString("javaType") == "float"
        val scale = json.getDouble("scale")
        val offset = json.getDouble("offset")
        val lo = json.getJSONArray("range").getDouble(0)
        val hi = json.getJSONArray("range").getDouble(1)
        val invalid: List<String> = json.getJSONArray("invalid").let { a -> (0 until a.length()).map { a.getString(it) } }
        val words: Map<String, String>? = json.optJSONObject("words")?.let { w -> w.keys().asSequence().associateWith { w.getString(it) } }

        /** A code the row does not name stays a code, «код N», rather than «н/д». */
        val keepsCodes get() = invalid.any { "«код" in it }

        override fun toString() = "%s «%s» %d·%08X".format(system, name, device, fid)
    }

    /** Every row the scanner takes: all of `systems` but ADAS's two and the engine's energy mode (owner, 2026-10-21). */
    private val rows: List<Row> by lazy {
        val systems = JSONObject(File(root, "research/vehicle-diagnostics/stream-selection-20261021.json").readText()).getJSONObject("systems")
        systems.keys().asSequence().flatMap { s -> systems.getJSONArray(s).let { a -> (0 until a.length()).map { Row(s, a.getJSONObject(it)) } } }
            .filter { it.system != "ADAS" && !(it.system == "ENGINE" && it.fid == 0x34200024) }
            .toList()
    }

    private fun extraOf(r: Row) = DiagnosticExtra.entries.filter { it.device == r.device && it.fid == r.fid }
    private fun hubOf(r: Row) = VehicleSignal.entries.filter { it.device == r.device && it.fid == r.fid }

    private fun parcel(word: Int) = "Result: Parcel(00000000 %08x   '........')".format(word)
    private fun batch(vararg pairs: Pair<DiagnosticExtra, Int>) = pairs.joinToString("\n") { "@@extra:${it.first.key}\n${parcel(it.second)}" }

    /** [word] of [e] through the batch's reader, its gate - if it has one - saying yes: the unit °C, the flag valid. */
    private fun read(e: DiagnosticExtra, word: Int, gate: Int? = 1): Double? {
        val g = ExtraGates.gate(e)
        val pairs = listOfNotNull(e to word, if (g != null && gate != null) g to gate else null)
        return DiagnosticExtraReader.decode(batch(*pairs.toTypedArray()), 1, 1).values[e]
    }

    /** The wire word of [value] for [r]: its raw before scale and offset, as an int or a float's bits. */
    private fun wordOf(r: Row, value: Double): Int {
        val raw = (value - r.offset) / r.scale
        return if (r.float) raw.toFloat().toBits() else raw.roundToInt()
    }

    /** The readings C1 lets keep a real −1.0: the front drive's converters hold it. */
    private val minusOneIsAValue = setOf(DiagnosticExtra.TORQUE_FRONT, DiagnosticExtra.CURRENT_FRONT)

    @Test
    fun everyRowIsOneGetOrOneHubSignalWithItsDeviceFidAndTransaction() {
        assertEquals(87, rows.size)
        rows.forEach { r ->
            val extras = extraOf(r)
            val hub = hubOf(r).filter { it.poll != VehiclePoll.EVENT }
            assertEquals("$r: one reading, never two for one FID", 1, extras.size + hub.size)
            val transact = extras.singleOrNull()?.transact ?: hub.single().transact.code
            assertEquals("$r", r.transaction, transact)
            assertEquals("$r: an int on 5, a float on 7", if (r.float) 7 else 5, transact)
        }
        // one GET a FID across the batch, and none the hub's sweep already brings
        val gets = DiagnosticExtra.entries.map { it.device to it.fid }
        assertEquals(gets.size, gets.toSet().size)
        val swept = VehicleSignal.entries.filter { it.poll != VehiclePoll.EVENT }.map { it.device to it.fid }.toSet()
        assertTrue(gets.none { it in swept })
    }

    @Test
    fun everyRowsCodesAreTakenInItsWordsAndItsInvalidEncodingsRefused() {
        rows.forEach { r ->
            val hub = hubOf(r).singleOrNull()
            if (hub != null) {
                // a hub state: its words; the hub's sweep decodes it
                r.words?.let { w -> assertEquals("$r", w.mapKeys { it.key.toInt() }, CarStates.of(hub)!!.named) }
                return@forEach
            }
            val e = extraOf(r).single()
            val states = CarStates.of(e)
            // the range's ends are values
            listOf(r.lo, r.hi).forEach { v -> assertEquals("$r $v", v, read(e, wordOf(r, v))!!, 1e-6) }
            // just outside them, nothing - unless a code the row does not name stays a code
            if (!r.keepsCodes) {
                val step = if (r.float && r.scale == 1.0) 0.1 else r.scale
                listOf(r.lo - step, r.hi + step).forEach { v -> assertNull("$r $v", read(e, wordOf(r, v))) }
            }
            // its words, each code taken
            val words = r.words
            if (words == null) {
                assertNull("$r is a number", states)
            } else {
                assertNotNull("$r has words", states)
                val named = words.filterKeys { it != "other" }.mapKeys { it.key.toInt() }
                assertEquals("$r", named, states!!.named)
                named.keys.forEach { code -> assertEquals("$r $code", named[code], states.word(read(e, code))) }
                words["other"]?.let { other ->
                    val code = (0..254).first { it !in named }
                    assertEquals("$r $code", other, states.word(read(e, code)))
                }
            }
            r.invalid.forEach { s -> refused(r, e, s) }
        }
    }

    /** The invalid encoding [s] of the row, as [e] reads it. */
    private fun refused(r: Row, e: DiagnosticExtra, s: String) {
        val minusOne = (-1f).toBits()
        val word = { v: Double? -> CarStates.of(e)!!.word(v) }
        when {
            s.startsWith("-10011") -> assertNull("$r", read(e, -10011))
            s.startsWith("-10013") -> assertNull("$r", read(e, -10013))
            s.startsWith("0xBF800000") || s.contains("-1.0 = missing entry") ->
                if (e in minusOneIsAValue) assertEquals("$r: C1", -1.0, read(e, minusOne)!!, 0.0) else assertNull("$r", read(e, minusOne))
            s.startsWith("wire 65535") -> assertNull("$r", read(e, 65535f.toBits()))
            s.startsWith("65535") -> assertNull("$r", read(e, 65535))
            s.startsWith("255") -> assertNull("$r", read(e, 255))
            Regex("0 ([A-Z_]*INVALID|INVALID|= stock).*").matches(s) -> assertNull("$r", read(e, 0))
            s.startsWith("214/215") -> { assertNull("$r", read(e, 214)); assertNull("$r", read(e, 215)) }
            s.startsWith("-40 (byte 0)") -> assertNull("$r", read(e, -40))
            s.startsWith("front: 2/3") -> if (r.hi == 1.0) { assertNull("$r", read(e, 2)); assertNull("$r", read(e, 3)) }
            s.startsWith("outside 0..100") -> { assertNull("$r", read(e, 101)); assertNull("$r", read(e, -1)) }
            s.startsWith("raw outside 1..6") -> { assertNull("$r", read(e, 0)); assertNull("$r", read(e, 7)) }
            s.startsWith("valid only while") -> {
                assertNull("$r: the flag says no", read(e, 50, gate = 0))
                assertNull("$r: no flag", read(e, 50, gate = null))
            }
            s.startsWith("valid as °C") -> {
                assertEquals("$r: °F", 10.0, read(e, 50, gate = 0)!!, 1e-9)
                assertNull("$r: no unit", read(e, 12, gate = null))
            }
            s.contains("«код 3»") || s == "3 unnamed" -> {
                assertEquals("$r", "код 3", word(read(e, 3)))
            }
            s.contains("«код N»") -> {
                val code = (0..254).first { it !in CarStates.of(e)!!.named }
                assertEquals("$r", "код $code", word(read(e, code)))
                assertNull("$r: the byte's all-ones", read(e, 255))
            }
            else -> throw AssertionError("$r: an invalid encoding the test does not know: $s")
        }
        // the HAL passes DC-DC's 2-bit 0..3, so 4 is no code at all
        if (e == DiagnosticExtra.DCDC_MODE) assertNull(read(e, 4))
    }

    // ------------------------------------------------------------------ the sweep of 2026-10-20

    /** captures/diagnostics-sweep-20261020/fids.tsv, git-ignored: in this checkout or the one it is a worktree of. */
    private val sweep: File? = generateSequence(root) { it.parentFile }
        .map { File(it, "captures/diagnostics-sweep-20261020/fids.tsv") }.firstOrNull { it.exists() }

    /** Pass [pass] of the sweep: (device, FID) → (int word, float word or null). */
    private fun pass(pass: Int): Map<Pair<Int, Int>, Pair<Int, Int?>> = sweep!!.readLines()
        .map { it.split('\t') }
        .filter { it.size >= 6 && it[0] == "FID" && it[1] == pass.toString() }
        .associate { c ->
            (c[3].toInt() to c[4].removePrefix("0x").toLong(16).toInt()) to
                (c[5].removePrefix("0x").toLong(16).toInt() to c.getOrNull(6)?.takeIf { it.isNotBlank() }?.removePrefix("0x")?.toLong(16)?.toInt())
        }

    @Test
    fun theParkedSweepDecodesAsTheTableSaysItReads() {
        assumeTrue("the sweep of 2026-10-20 is a local capture", sweep != null)
        for (p in 1..2) {
            val words = pass(p)
            fun wordOf(e: DiagnosticExtra): Int? = words[e.device to e.fid]?.let { (int, float) -> if (e.transact == 7) float ?: int else int }
            val decoded = DiagnosticExtraReader.decode(
                batch(*DiagnosticExtra.entries.mapNotNull { e -> wordOf(e)?.let { e to it } }.toTypedArray()), 1, 1,
            ).values
            rows.forEach { r ->
                val e = extraOf(r).singleOrNull() ?: return@forEach
                val w = wordOf(e) ?: return@forEach
                val raw = if (e.transact == 7) Float.fromBits(w).toDouble() else w.toDouble()
                val v = raw * r.scale + r.offset
                val expected = v.takeIf { w != -10011 && w != -10013 && it.isFinite() && it in r.lo..r.hi }
                if (expected == null) assertNull("$r pass $p", decoded[e]) else assertEquals("$r pass $p", expected, decoded[e]!!, 1e-6)
                val code = decoded[e]?.toInt()
                if (code != null) r.words?.get(code.toString())?.let { assertEquals("$r pass $p", it, CarStates.of(e)!!.word(decoded[e])) }
            }
            // what the table writes down for the parked car
            assertEquals(if (p == 1) 73.9 else 74.1, decoded.getValue(DiagnosticExtra.SOC_TENTHS), 1e-9)
            assertEquals(-4.5, decoded.getValue(DiagnosticExtra.CHARGE_CURRENT), 1e-9)
            assertEquals(12.0, decoded.getValue(DiagnosticExtra.OUTSIDE_TEMP), 0.0)
            assertEquals(if (p == 1) 0.0 else 1.0, decoded.getValue(DiagnosticExtra.AQS_OUTSIDE), 0.0)
            assertNull("FFFF", decoded[DiagnosticExtra.RPM_REAR_LEFT])
            val car = CarReadings(extras = decoded, extrasFresh = true)
            assertEquals("ON", car.word(DiagnosticExtra.POWER_LEVEL))
            assertEquals("понижение", car.word(DiagnosticExtra.DCDC_MODE))
            assertEquals("P", car.word(DiagnosticExtra.GEAR_SELECTOR))
            assertEquals("затянут", car.word(DiagnosticExtra.EPB))
            assertEquals("завершено", car.word(DiagnosticExtra.SUSPENSION_PROCESS))
            assertEquals("средний", car.word(DiagnosticExtra.SUSPENSION_LEVEL))
            assertEquals("выкл", car.word(DiagnosticExtra.TURN_LIGHTS))
            assertEquals("не горит", car.word(DiagnosticExtra.STOP_LAMP))
            assertEquals("не горит", car.word(DiagnosticExtra.REVERSE_LAMP))
            assertEquals("вкл", car.word(DiagnosticExtra.SIDE_LIGHTS))
            assertEquals("закрыт", car.word(DiagnosticExtra.HOOD))
            assertEquals("норма", car.word(DiagnosticExtra.TPMS_STATE))
            assertEquals(0.0, decoded.getValue(DiagnosticExtra.ACCEL_PEDAL), 0.0)
            assertEquals(0.0, decoded.getValue(DiagnosticExtra.BRAKE_PEDAL), 0.0)
            assertEquals(13.8, decoded.getValue(DiagnosticExtra.DCDC_VOLTAGE), 1e-6)
        }
    }

    // ------------------------------------------------------------------ the pages

    private fun keys(page: ExtraPage) = DiagnosticExtra.entries.filter { page in it.pages }.map { it.key }.toSet()

    @Test
    fun eachPageCarriesItsSystemsReadingsAndTheSharedBatchDidNotGrow() {
        // 61 GETs before 2026-10-21's selection; C2 brought the climate's unit in and sent PM2.5 to its scheme
        assertEquals(60, DiagnosticExtra.entries.count { it.pages.isEmpty() })
        val chassisScheme = setOf("epb", "suspension_process", "suspension_level", "tpms_state", "tpms_battery") +
            listOf("fl", "fr", "rl", "rr").flatMap { listOf("${it}_leak", "${it}_pressure_state") }
        val body = setOf("turn_lights", "stop_lamp", "reverse_lamp", "low_beam", "high_beam", "hood", "trunk")
        val safety = listOf("fl", "fr", "rl", "rr", "rc").flatMap { listOf("belt_$it", "seated_$it") }.toSet()
        val expected = mapOf(
            ExtraPage.HV_STATE to setOf("power_level"),
            ExtraPage.HV_LIVE to setOf("power_level", "dcdc_mode"),
            ExtraPage.PACK_LIVE to setOf("soc_tenths"),
            ExtraPage.DRIVES_STATE to setOf("gear_selector"),
            ExtraPage.DRIVES_LIVE to setOf("gear_selector", "accel_pedal", "accel_pedal_valid"),
            ExtraPage.CHASSIS_STATE to chassisScheme,
            ExtraPage.CHASSIS_LIVE to chassisScheme + setOf("brake_pedal", "brake_pedal_valid", "brake_switch", "tyre_temp_state"),
            ExtraPage.SAFETY_STATE to safety,
            ExtraPage.SAFETY_LIVE to safety,
            ExtraPage.BODY_STATE to body,
            ExtraPage.BODY_LIVE to body + "side_lights",
            ExtraPage.CLIMATE_STATE to setOf("pm_in", "pm_out"),
            ExtraPage.CLIMATE_LIVE to setOf("ac_power", "ac_compressor", "ac_recirculation", "ac_fan", "aqs_outside", "co2_outside", "humidity_sensor", "sun_sensor"),
            ExtraPage.LV_STATE to setOf("power_level", "dcdc_mode"),
            ExtraPage.LV_LIVE to setOf("power_level", "dcdc_mode", "dcdc_voltage"),
        )
        expected.forEach { (page, k) -> assertEquals("$page", k, keys(page)) }
        assertEquals(44, keys(ExtraPage.CABIN).size)
        assertTrue(safety.all { it in keys(ExtraPage.CABIN) })
        assertEquals(14, keys(ExtraPage.GB_FLAGS).size)
        // a live page goes round after round as the pack's; a scheme every five seconds
        ExtraPage.entries.forEach { assertEquals("$it", if (it.name.endsWith("_LIVE")) 100L else null, it.periodMs) }

        // every row of a system's table is read while its «Потоковые данные» is in front
        val live = mapOf(
            "HV" to ExtraPage.HV_LIVE, "PACK" to ExtraPage.PACK_LIVE, "DRIVES" to ExtraPage.DRIVES_LIVE, "CHASSIS" to ExtraPage.CHASSIS_LIVE,
            "SAFETY" to ExtraPage.SAFETY_LIVE, "BODY" to ExtraPage.BODY_LIVE, "CLIMATE" to ExtraPage.CLIMATE_LIVE, "LV" to ExtraPage.LV_LIVE,
        )
        rows.forEach { r ->
            val e = extraOf(r).singleOrNull() ?: return@forEach
            val page = live[r.system] ?: return@forEach
            assertTrue("$r", e.pages.isEmpty() || page in e.pages)
            assertTrue("$r", DiagnosticExtraReader.command(setOf(page)).contains("@@extra:${e.key};"))
        }
        // a gate rides every batch its reading rides
        DiagnosticExtra.entries.forEach { e ->
            val gate = ExtraGates.gate(e) ?: return@forEach
            assertTrue("$e", gate.pages.isEmpty() || (e.pages.isNotEmpty() && gate.pages.containsAll(e.pages)))
        }
        // a reading two pages need is asked once when both show
        val both = DiagnosticExtraReader.command(setOf(ExtraPage.CHASSIS_STATE, ExtraPage.CHASSIS_LIVE))
        assertEquals(1, Regex("@@extra:epb;").findAll(both).count())
        assertTrue(both.startsWith(DiagnosticExtraReader.command))
    }

    @Test
    fun aTabAsksForItsSystemsPage() {
        val state = mapOf(
            CarSystem.HV to ExtraPage.HV_STATE, CarSystem.DRIVES to ExtraPage.DRIVES_STATE, CarSystem.CHASSIS to ExtraPage.CHASSIS_STATE,
            CarSystem.SAFETY to ExtraPage.SAFETY_STATE, CarSystem.BODY to ExtraPage.BODY_STATE, CarSystem.CABIN to ExtraPage.CABIN,
            CarSystem.CLIMATE to ExtraPage.CLIMATE_STATE, CarSystem.LV to ExtraPage.LV_STATE,
        )
        val live = mapOf(
            CarSystem.HV to ExtraPage.HV_LIVE, CarSystem.PACK to ExtraPage.PACK_LIVE, CarSystem.DRIVES to ExtraPage.DRIVES_LIVE,
            CarSystem.CHASSIS to ExtraPage.CHASSIS_LIVE, CarSystem.SAFETY to ExtraPage.SAFETY_LIVE, CarSystem.BODY to ExtraPage.BODY_LIVE,
            CarSystem.CLIMATE to ExtraPage.CLIMATE_LIVE, CarSystem.LV to ExtraPage.LV_LIVE,
        )
        CarSystem.entries.forEach { s ->
            assertEquals("$s", state[s], extraPage(s, SystemTab.STATE))
            // a stream's page is setSystemLiveVisible's, which pauses; the engine's is ECM's
            assertEquals("$s", live[s], extraPage(s, SystemTab.LIVE))
            assertEquals("$s", ExtraPage.GB_FLAGS, extraPage(s, SystemTab.GB_FLAGS))
            listOf(SystemTab.DTC, SystemTab.BLOCKS, SystemTab.CELLS, SystemTab.COMPARE, SystemTab.DTC_READINESS)
                .forEach { assertNull("$s $it", extraPage(s, it)) }
        }
    }

    // ------------------------------------------------------------------ the readings for the rows

    @Test
    fun theReadingsSayTheirWordsAndNothingWhenNothingCame() {
        val car = CarReadings(
            hub = mapOf(VehicleSignal.CHARGE_GUN to 2.0, VehicleSignal.GENERATION_STATE to 0.0, VehicleSignal.PACK_VOLT to 553.0),
            extras = mapOf(DiagnosticExtra.EPB to 3.0, DiagnosticExtra.FL_PRESSURE_STATE to 3.0, DiagnosticExtra.SEATED_FL to 1.0,
                DiagnosticExtra.CONTACTOR to 1.0, DiagnosticExtra.SOC_TENTHS to 73.9),
            extrasFresh = true,
        )
        assertEquals("затянут", car.word(DiagnosticExtra.EPB))
        assertEquals("код 3", car.word(DiagnosticExtra.FL_PRESSURE_STATE))
        assertEquals("нет", car.word(DiagnosticExtra.SEATED_FL))
        assertEquals("замкнут", car.word(DiagnosticExtra.CONTACTOR))
        assertEquals("н/д", car.word(DiagnosticExtra.GEAR_SELECTOR))
        assertNull("a number has no words", car.word(DiagnosticExtra.SOC_TENTHS))
        assertEquals(73.9, car.extra(DiagnosticExtra.SOC_TENTHS)!!, 0.0)
        assertEquals("AC", car.word(VehicleSignal.CHARGE_GUN))
        assertEquals("the stock INVALID is no state", "н/д", car.word(VehicleSignal.GENERATION_STATE))
        assertNull(car.word(VehicleSignal.PACK_VOLT))
        // a batch past its horizon says nothing
        val stale = car.copy(extrasFresh = false)
        assertEquals("н/д", stale.word(DiagnosticExtra.EPB))
        assertNull(stale.extra(DiagnosticExtra.SOC_TENTHS))
        // a pedal alone on its parameter page is asked with its flag
        val get = SignalGet.of(DiagnosticExtra.BRAKE_PEDAL)
        assertEquals(DiagnosticExtraReader.batch(listOf(DiagnosticExtra.BRAKE_PEDAL_VALID, DiagnosticExtra.BRAKE_PEDAL)), get.command)
        assertEquals(35.0, get.value(batch(DiagnosticExtra.BRAKE_PEDAL_VALID to 1, DiagnosticExtra.BRAKE_PEDAL to 35))!!, 0.0)
        assertNull(get.value(batch(DiagnosticExtra.BRAKE_PEDAL_VALID to 0, DiagnosticExtra.BRAKE_PEDAL to 35)))
    }
}
