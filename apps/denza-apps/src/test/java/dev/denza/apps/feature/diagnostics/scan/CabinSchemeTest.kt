package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Салон · Состояние» (architecture 7.2): the words of every value of every cabin GET, the «н/д»
 * rules, the massage's on/off rule and the fridges' set points; then the two boards
 * `scanner-sys-cabin-parked` (the sweep of 2026-10-20 in research cabin-signals-20261021.json) and
 * `scanner-sys-cabin` (a trip) against [SystemPagesBuilder.cabin], plate by plate and seat by seat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CabinSchemeTest {
    private val b = SystemPagesBuilder
    private val m = Figures.MINUS

    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private fun car(vararg extras: Pair<DiagnosticExtra, Double>, fresh: Boolean = true) = CarReadings(extras = extras.toMap(), extrasFresh = fresh)

    private fun plate(c: Scheme.Cabin, place: CabinPlace) = (c.left + c.right).single { it.place == place }

    private fun texts(c: Scheme.Cabin, place: CabinPlace) = plate(c, place).rows.map { it.text }

    // ------------------------------------------------------------------ the words

    @Test
    fun seatHeatingAndVentilationAreThreeLevels() {
        assertEquals(listOf(0, 1, 2, 3), listOf(1.0, 2.0, 3.0, 4.0).map(b::seatLevel))
        listOf(null, 0.0, 5.0, 6.0, 65535.0).forEach { assertNull("$it", b.seatLevel(it)) }
        val c = b.cabin(car(DiagnosticExtra.SEAT_HEAT_FL to 4.0, DiagnosticExtra.SEAT_VENT_FL to 2.0))
        assertEquals(3, c.seats.getValue(CabinPlace.FL).heat)
        assertEquals(1, c.seats.getValue(CabinPlace.FL).vent)
        assertEquals("подогрев 3 · вентиляция 1", texts(c, CabinPlace.FL)[1])
        val off = b.cabin(car(DiagnosticExtra.SEAT_HEAT_RR to 1.0))
        assertEquals("подогрев выкл · вентиляция н/д", texts(off, CabinPlace.RR)[1])
        assertEquals(0, off.seats.getValue(CabinPlace.RR).heat)
        assertNull("no reading draws no pips", off.seats.getValue(CabinPlace.RR).vent)
    }

    @Test
    fun theBeltsAndWhoSits() {
        assertEquals(true, b.seated(2.0))
        listOf(0.0, 1.0, 3.0, 254.0).forEach { assertEquals("$it", false, b.seated(it)) }
        assertNull(b.seated(null))
        // the front belts 1 fastened, 0 not; the second row's 2 and 1
        val front = b.cabin(car(DiagnosticExtra.BELT_FL to 1.0, DiagnosticExtra.BELT_FR to 0.0, DiagnosticExtra.BELT_RL to 2.0,
            DiagnosticExtra.BELT_RR to 1.0, DiagnosticExtra.BELT_RC to 2.0))
        assertEquals(listOf(true, false, true, false, true),
            listOf(CabinPlace.FL, CabinPlace.FR, CabinPlace.RL, CabinPlace.RR, CabinPlace.RC).map { front.seats.getValue(it).belt })
        assertEquals(CabinRow(listOf(CabinRun("сидит · пристёгнут")), false), b.presence(true, true))
        assertEquals(CabinRow(listOf(CabinRun("сидит · "), CabinRun("не пристёгнут", CabinTone.ALERT)), false), b.presence(true, false))
        assertEquals(CabinRow(listOf(CabinRun("сидит · ремень н/д")), false), b.presence(true, null))
        assertEquals(CabinRow(listOf(CabinRun("пусто", CabinTone.QUIET)), false), b.presence(false, false))
        assertEquals(CabinRow(listOf(CabinRun("пусто", CabinTone.QUIET)), false), b.presence(false, null))
        assertEquals("a belt buckled behind nobody", CabinRow(listOf(CabinRun("пусто · пристёгнут", CabinTone.QUIET)), false), b.presence(false, true))
        assertEquals(CabinRow(listOf(CabinRun("н/д", CabinTone.QUIET)), false), b.presence(null, null))
        assertEquals(CabinRow(listOf(CabinRun("н/д · не пристёгнут", CabinTone.QUIET)), false), b.presence(null, false))
        // the centre: one row, no pips, no lamp
        val rc = b.cabin(car(DiagnosticExtra.SEATED_RC to 2.0, DiagnosticExtra.BELT_RC to 1.0))
        assertEquals(CabinSeat(occupied = true, belt = false, heat = null, vent = null, lamp = null), rc.seats.getValue(CabinPlace.RC))
        assertEquals(listOf("сидит · не пристёгнут"), texts(rc, CabinPlace.RC))
    }

    @Test
    fun massageIsOnOnlyWithAProgrammeAndALevel() {
        val modes = mapOf(2 to "волна", 3 to "пульс", 4 to "растяжка", 5 to "разглаживание", 6 to "расслабление",
            8 to "горячие камни · плечи", 9 to "горячие камни · поясница")
        val levels = mapOf(1 to "слабо", 2 to "средне", 3 to "сильно")
        modes.forEach { (mode, name) ->
            levels.forEach { (level, word) -> assertEquals("массаж: $name · $word", b.massage(mode.toDouble(), level.toDouble())) }
            // a programme with the level off or INVALID does not massage, as the stock reads it
            assertEquals("массаж выкл", b.massage(mode.toDouble(), 4.0))
            assertEquals("массаж выкл", b.massage(mode.toDouble(), 0.0))
            assertEquals("массаж н/д", b.massage(mode.toDouble(), null))
        }
        (0..4).forEach { assertEquals("массаж выкл", b.massage(1.0, it.toDouble())) }
        assertEquals("массаж выкл", b.massage(1.0, null))
        assertEquals("массаж н/д", b.massage(null, 2.0))
        assertEquals("массаж н/д", b.massage(7.0, 2.0))
        val c = b.cabin(car(DiagnosticExtra.MASSAGE_MODE_FR to 8.0, DiagnosticExtra.MASSAGE_LEVEL_FR to 3.0, DiagnosticExtra.READING_FR to 2.0))
        assertEquals("массаж: горячие камни · плечи · сильно · свет для чтения", texts(c, CabinPlace.FR)[2])
    }

    @Test
    fun theReadingLightsTheWheelAndTheHud() {
        val on = b.cabin(car(DiagnosticExtra.READING_FL to 2.0, DiagnosticExtra.READING_FR to 1.0, DiagnosticExtra.READING_RL to 2.0,
            DiagnosticExtra.WHEEL_HEAT to 2.0, DiagnosticExtra.HUD to 1.0))
        assertEquals(listOf(true, false, true, null), listOf(CabinPlace.FL, CabinPlace.FR, CabinPlace.RL, CabinPlace.RR).map { on.seats.getValue(it).lamp })
        assertEquals("массаж н/д · свет для чтения", texts(on, CabinPlace.FL)[2])
        assertEquals("массаж н/д", texts(on, CabinPlace.FR)[2])
        assertEquals(true, on.wheelHeat)
        assertEquals(listOf(CabinRow(listOf(CabinRun("подогрев вкл", CabinTone.WARM)), false)), plate(on, CabinPlace.WHEEL).rows)
        assertEquals("проекция вкл", texts(on, CabinPlace.CLUSTER)[1])
        val off = b.cabin(car(DiagnosticExtra.WHEEL_HEAT to 1.0, DiagnosticExtra.HUD to 2.0))
        assertEquals(false, off.wheelHeat)
        assertEquals(listOf(CabinRow(listOf(CabinRun("подогрев выкл", CabinTone.QUIET)), false)), plate(off, CabinPlace.WHEEL).rows)
        assertEquals("проекция выкл", texts(off, CabinPlace.CLUSTER)[1])
        val none = b.cabin(car())
        assertNull(none.wheelHeat)
        assertEquals(listOf(CabinRow(listOf(CabinRun("подогрев н/д", CabinTone.QUIET)), false)), plate(none, CabinPlace.WHEEL).rows)
        assertEquals(listOf("н/д", "проекция н/д"), texts(none, CabinPlace.CLUSTER))
    }

    @Test
    fun theClustersPlateSaysItsMessage() {
        assertEquals("нет сообщений", texts(b.cabin(car(DiagnosticExtra.OEM_WARNING to 0.0)), CabinPlace.CLUSTER)[0])
        assertEquals("Проверьте систему ABS.", texts(b.cabin(car(DiagnosticExtra.OEM_WARNING to 70.0)), CabinPlace.CLUSTER)[0])
        assertEquals("сообщение 0x02A", texts(b.cabin(car(DiagnosticExtra.OEM_WARNING to 42.0)), CabinPlace.CLUSTER)[0])
        assertEquals("ПРИБОРЫ · INS 723 · HUD 7B0", plate(b.cabin(car()), CabinPlace.CLUSTER).label)
    }

    @Test
    fun thePads() {
        val words = mapOf(0.0 to "заряжает", 1.0 to "заряжен", 2.0 to "отказ", 3.0 to "ожидание")
        assertEquals(listOf(PadState.CHARGING, PadState.FULL, PadState.FAULT, PadState.STANDBY), words.keys.map(b::pad))
        listOf(null, 4.0, 65535.0).forEach { assertNull(b.pad(it)) }
        words.forEach { (raw, word) ->
            val c = b.cabin(car(DiagnosticExtra.PAD_LEFT to raw, DiagnosticExtra.PAD_RIGHT to raw))
            assertEquals(listOf("левая: $word", "правая: $word"), texts(c, CabinPlace.PADS))
        }
        val fault = b.cabin(car(DiagnosticExtra.PAD_LEFT to 2.0))
        assertEquals(CabinRow(listOf(CabinRun("левая: "), CabinRun("отказ", CabinTone.ALERT)), false), plate(fault, CabinPlace.PADS).rows[0])
        assertEquals(CabinRow(listOf(CabinRun("правая: н/д")), true), plate(fault, CabinPlace.PADS).rows[1])
    }

    @Test
    fun theFridgesSayTheSetPointOfTheModeTheyAreIn() {
        assertEquals(listOf(FridgeMode.COLD, FridgeMode.HOT, FridgeMode.OFF, FridgeMode.FRESH), listOf(1.0, 2.0, 3.0, 4.0).map(b::fridge))
        listOf(null, 0.0, 5.0).forEach { assertNull(b.fridge(it)) }
        // cold raw − 19 for 1..34, hot raw + 34 for 1..16, fresh raw + 7 for 1..3
        assertEquals(-18.0, b.setPoint(FridgeMode.COLD, 1.0)!!, 0.0)
        assertEquals(0.0, b.setPoint(FridgeMode.COLD, 19.0)!!, 0.0)
        assertEquals(-6.0, b.setPoint(FridgeMode.COLD, 13.0)!!, 0.0)
        assertEquals(15.0, b.setPoint(FridgeMode.COLD, 34.0)!!, 0.0)
        assertEquals(35.0, b.setPoint(FridgeMode.HOT, 1.0)!!, 0.0)
        assertEquals(40.0, b.setPoint(FridgeMode.HOT, 6.0)!!, 0.0)
        assertEquals(50.0, b.setPoint(FridgeMode.HOT, 16.0)!!, 0.0)
        assertEquals(8.0, b.setPoint(FridgeMode.FRESH, 1.0)!!, 0.0)
        assertEquals(10.0, b.setPoint(FridgeMode.FRESH, 3.0)!!, 0.0)
        listOf(FridgeMode.COLD to 35.0, FridgeMode.HOT to 17.0, FridgeMode.FRESH to 4.0, FridgeMode.COLD to 0.0, FridgeMode.OFF to 6.0, null to 6.0)
            .forEach { (mode, raw) -> assertNull("$mode $raw", b.setPoint(mode, raw)) }

        val set = arrayOf(
            DiagnosticExtra.FRIDGE_FRONT_COLD to 13.0, DiagnosticExtra.FRIDGE_FRONT_HOT to 6.0, DiagnosticExtra.FRIDGE_FRONT_FRESH to 2.0,
            DiagnosticExtra.FRIDGE_REAR_COLD to 19.0, DiagnosticExtra.FRIDGE_REAR_HOT to 16.0, DiagnosticExtra.FRIDGE_REAR_FRESH to 1.0,
        )
        fun rows(front: Double?, rear: Double?, vararg more: Pair<DiagnosticExtra, Double>) =
            texts(b.cabin(car(*set, *more, *listOfNotNull(front?.let { DiagnosticExtra.FRIDGE_FRONT to it }, rear?.let { DiagnosticExtra.FRIDGE_REAR to it }).toTypedArray())), CabinPlace.FRIDGES)
        assertEquals(listOf("передний: холод · ${m}6 °C", "задний: тепло · 50 °C"), rows(1.0, 2.0))
        assertEquals(listOf("передний: тепло · 40 °C", "задний: свежесть · 8 °C"), rows(2.0, 4.0))
        assertEquals(listOf("передний: свежесть · 9 °C", "задний: холод · 0 °C"), rows(4.0, 1.0))
        assertEquals(listOf("передний: выкл", "задний: выкл"), rows(3.0, 3.0))
        assertEquals(listOf("передний: н/д", "задний: н/д"), rows(null, null))
        // the set point not read: its place says so, the unit kept
        assertEquals("передний: холод · н/д °C", texts(b.cabin(car(DiagnosticExtra.FRIDGE_FRONT to 1.0)), CabinPlace.FRIDGES)[0])
        val c = b.cabin(car(DiagnosticExtra.FRIDGE_FRONT to 3.0, DiagnosticExtra.FRIDGE_REAR to 3.0))
        assertEquals(FridgeMode.OFF, c.fridgeFront)
        assertEquals(FridgeMode.OFF, c.fridgeRear)
    }

    @Test
    fun aFridgesReminderFollowsInOrange() {
        val words = mapOf(1.0 to "нет связи", 2.0 to "долго работает", 3.0 to "режим тепла", 4.0 to "дверь не закрыта")
        words.forEach { (raw, word) ->
            val c = b.cabin(car(DiagnosticExtra.FRIDGE_FRONT to 3.0, DiagnosticExtra.FRIDGE_FRONT_REMIND to raw,
                DiagnosticExtra.FRIDGE_REAR to 2.0, DiagnosticExtra.FRIDGE_REAR_HOT to 6.0, DiagnosticExtra.FRIDGE_REAR_REMIND to raw))
            assertEquals(CabinRow(listOf(CabinRun("передний: выкл · "), CabinRun(word, CabinTone.ALERT)), false), plate(c, CabinPlace.FRIDGES).rows[0])
            assertEquals(CabinRow(listOf(CabinRun("задний: тепло · 40 °C · "), CabinRun(word, CabinTone.ALERT)), true), plate(c, CabinPlace.FRIDGES).rows[1])
        }
        val none = b.cabin(car(DiagnosticExtra.FRIDGE_FRONT to 3.0, DiagnosticExtra.FRIDGE_FRONT_REMIND to 0.0))
        assertEquals(CabinRow(listOf(CabinRun("передний: выкл")), false), plate(none, CabinPlace.FRIDGES).rows[0])
    }

    @Test
    fun anOldBatchIsNoReadingAtAll() {
        val c = b.cabin(car(DiagnosticExtra.SEATED_FL to 2.0, DiagnosticExtra.BELT_FL to 1.0, DiagnosticExtra.WHEEL_HEAT to 2.0, fresh = false))
        assertEquals(CabinSeat(null, null, null, null, null), c.seats.getValue(CabinPlace.FL))
        assertEquals(listOf("н/д", "подогрев н/д · вентиляция н/д", "массаж н/д"), texts(c, CabinPlace.FL))
        assertNull(c.wheelHeat)
    }

    @Test
    fun thePageAsksForTheCabinsGetsThroughTheCarFeed() = runTest {
        val car = FakeCar()
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        s.setExtrasVisible(ExtraPage.CABIN, true)
        s.setExtrasVisible(ExtraPage.CABIN, false)
        assertEquals(listOf(ExtraPage.CABIN to true, ExtraPage.CABIN to false), car.pageLog)
        s.stop()
    }

    // ------------------------------------------------------------------ the boards

    /** The 2026-10-20 sweep of the research, every cabin GET as the car answered it; the cluster's word of 2026-10-18. */
    private fun parked(): CarReadings {
        val a = JSONArray(File(root, "research/vehicle-diagnostics/cabin-signals-20261021.json").readText())
        val entries = (0 until a.length()).map { a.getJSONObject(it) }
        val batch = DiagnosticExtra.entries.filter { ExtraPage.CABIN in it.pages }.joinToString("\n") { e ->
            val r = entries.single { it.getLong("fidSigned").toInt() == e.fid && it.getInt("device") == e.device }
            "@@extra:${e.key}\nResult: Parcel(00000000 %08x   '........')".format(r.getString("observedParked20261020").toInt())
        }
        val warning = JSONObject(File(root, "research/vehicle-diagnostics/oem-warning-mappings-20261021.json").readText())
            .getJSONObject("property").getJSONArray("retainedGET").getJSONObject(0).getString("word").removePrefix("0x").toLong(16).toInt()
        val extras = DiagnosticExtraReader.decode(batch, 1, 1).values + (DiagnosticExtra.OEM_WARNING to warning.toDouble())
        return CarReadings(extras = extras, extrasFresh = true)
    }

    /** The trip of `scanner-sys-cabin`, as the car would say it. */
    private fun trip() = car(
        DiagnosticExtra.SEATED_FL to 2.0, DiagnosticExtra.BELT_FL to 1.0, DiagnosticExtra.SEAT_HEAT_FL to 3.0, DiagnosticExtra.SEAT_VENT_FL to 1.0,
        DiagnosticExtra.MASSAGE_MODE_FL to 2.0, DiagnosticExtra.MASSAGE_LEVEL_FL to 2.0, DiagnosticExtra.READING_FL to 1.0,
        DiagnosticExtra.SEATED_FR to 1.0, DiagnosticExtra.BELT_FR to 0.0, DiagnosticExtra.SEAT_HEAT_FR to 1.0, DiagnosticExtra.SEAT_VENT_FR to 1.0,
        DiagnosticExtra.MASSAGE_MODE_FR to 1.0, DiagnosticExtra.MASSAGE_LEVEL_FR to 4.0, DiagnosticExtra.READING_FR to 1.0,
        DiagnosticExtra.SEATED_RL to 2.0, DiagnosticExtra.BELT_RL to 1.0, DiagnosticExtra.SEAT_HEAT_RL to 1.0, DiagnosticExtra.SEAT_VENT_RL to 1.0,
        DiagnosticExtra.MASSAGE_MODE_RL to 1.0, DiagnosticExtra.MASSAGE_LEVEL_RL to 4.0, DiagnosticExtra.READING_RL to 2.0,
        DiagnosticExtra.SEATED_RC to 1.0, DiagnosticExtra.BELT_RC to 1.0,
        DiagnosticExtra.SEATED_RR to 1.0, DiagnosticExtra.BELT_RR to 1.0,
        DiagnosticExtra.MASSAGE_MODE_RR to 1.0, DiagnosticExtra.MASSAGE_LEVEL_RR to 0.0, DiagnosticExtra.READING_RR to 1.0,
        DiagnosticExtra.WHEEL_HEAT to 2.0, DiagnosticExtra.HUD to 1.0, DiagnosticExtra.OEM_WARNING to 0.0,
        DiagnosticExtra.PAD_LEFT to 0.0, DiagnosticExtra.PAD_RIGHT to 3.0,
        DiagnosticExtra.FRIDGE_FRONT to 1.0, DiagnosticExtra.FRIDGE_FRONT_COLD to 19.0, DiagnosticExtra.FRIDGE_FRONT_REMIND to 0.0,
        DiagnosticExtra.FRIDGE_REAR to 3.0, DiagnosticExtra.FRIDGE_REAR_COLD to 13.0, DiagnosticExtra.FRIDGE_REAR_REMIND to 0.0,
    )

    @Test
    fun theParkedBoardIsTheSweepOf20261020() = check("scanner-sys-cabin-parked", b.cabin(parked()))

    @Test
    fun theTripBoardIsWhatTheBuilderSaysForItsValues() = check("scanner-sys-cabin", b.cabin(trip()))

    private val places = mapOf(
        "cluster" to CabinPlace.CLUSTER, "wheel" to CabinPlace.WHEEL, "fl" to CabinPlace.FL, "fr" to CabinPlace.FR,
        "rl" to CabinPlace.RL, "rc" to CabinPlace.RC, "rr" to CabinPlace.RR, "pads" to CabinPlace.PADS, "fridges" to CabinPlace.FRIDGES,
    )

    /** A board's word in the model's terms: the ink of its row's style, or a tone of its own. */
    private fun tone(style: String, ink: String): CabinTone = when (ink) {
        "orange" -> CabinTone.ALERT
        "orangeInk" -> CabinTone.WARM
        "white" -> CabinTone.PLAIN.also { assertEquals("white on a first row", "cell", style) }
        "dim" -> if (style == "cell") CabinTone.QUIET else CabinTone.PLAIN
        else -> error("ink $ink")
    }

    /** Runs of one tone side by side are one run. */
    private fun joined(runs: List<CabinRun>): List<CabinRun> = runs.fold(mutableListOf()) { acc, r ->
        acc.also { if (it.isNotEmpty() && it.last().tone == r.tone) it[it.lastIndex] = CabinRun(it.last().text + r.text, r.tone) else it += r }
    }

    private fun boardRow(row: List<*>): CabinRow {
        val runs = if (row[0] is List<*>) row.map { it as List<*> } else listOf(row)
        val style = runs.first()[1] as String
        assertEquals(setOf(style), runs.map { it[1] }.toSet())
        return CabinRow(runs.map { CabinRun(it[0] as String, tone(it[1] as String, it[2] as String)) }, small = style == "cellSmall")
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(id: String, c: Scheme.Cabin) {
        val f = SpecJson.fixture(id)
        assertEquals("$id: the head's line", (f["block"] as Map<String, Any?>)["line"], SystemPagesBuilder.CABIN_LINE)
        val plates = f["plates"] as Map<String, List<String>>
        assertEquals("$id: the left column", plates.getValue("left").map(places::getValue), c.left.map { it.place })
        assertEquals("$id: the right column", plates.getValue("right").map(places::getValue), c.right.map { it.place })
        val text = f["text"] as Map<String, List<Any?>>
        (c.left + c.right).forEach { p ->
            val key = places.entries.single { it.value == p.place }.key
            val board = text.getValue(key)
            val label = board[0] as List<*>
            assertEquals("$id $key", listOf(p.label, "counterLabel", "mute"), label)
            val rows = board.drop(1).map { boardRow(it as List<*>) }
            assertEquals("$id $key", rows.map { it.copy(runs = joined(it.runs)) }, p.rows.map { it.copy(runs = joined(it.runs)) })
        }
        val seats = f["seats"] as Map<String, Map<String, Any?>>
        seats.forEach { (key, s) ->
            fun level(k: String) = (s[k] as Number?)?.toInt()
            assertEquals("$id seat $key", CabinSeat(s["occupied"] as Boolean?, s["belt"] as Boolean?, level("heat"), level("vent"), s["lamp"] as Boolean?),
                c.seats.getValue(places.getValue(key)))
        }
        assertEquals("$id: the wheel", f["wheelHeat"], c.wheelHeat)
        val pads = f["pads"] as Map<String, String?>
        fun pad(s: String?) = s?.let { PadState.valueOf(if (it == "full") "FULL" else it.uppercase()) }
        assertEquals("$id: the pads", pad(pads["left"]) to pad(pads["right"]), c.padLeft to c.padRight)
        val fridges = f["fridges"] as Map<String, String?>
        assertEquals("$id: the fridges", fridges["front"]?.uppercase()?.let(FridgeMode::valueOf) to fridges["rear"]?.uppercase()?.let(FridgeMode::valueOf),
            c.fridgeFront to c.fridgeRear)
    }
}
