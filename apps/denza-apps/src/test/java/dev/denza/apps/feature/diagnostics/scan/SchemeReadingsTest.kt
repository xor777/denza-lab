package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readings the «Состояние» schemes of the body, the chassis, the drives, HV, 12 V and safety
 * draw (architecture 7.2; research stream-selection-20261021.json): every code's word and look, the
 * warnings in orange, «н/д» for what was not read; then each board - the parked sweep of 2026-10-20
 * and the two illustrative ones - against what the builder makes of its codes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchemeReadingsTest {
    private val b = SystemPagesBuilder

    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private fun car(vararg extras: Pair<DiagnosticExtra, Double>, hub: Map<VehicleSignal, Double> = emptyMap(), fresh: Boolean = true) =
        CarReadings(hub = hub, extras = extras.toMap(), extrasFresh = fresh)

    private fun plain(text: String, small: Boolean = true) = CabinRow(listOf(CabinRun(text)), small)

    private fun alert(lead: String, word: String, small: Boolean = true) =
        CabinRow(listOfNotNull(lead.takeIf { it.isNotEmpty() }?.let { CabinRun(it) }, CabinRun(word, CabinTone.ALERT)), small)

    // ------------------------------------------------------------------ the chassis

    @Test
    fun aTyreSaysItsPressureJudgementAndItsLeak() {
        fun rows(pressure: Double?, leak: Double?) = b.tyreStates(
            car(*listOfNotNull(pressure?.let { DiagnosticExtra.RL_PRESSURE_STATE to it }, leak?.let { DiagnosticExtra.RL_LEAK to it }).toTypedArray()),
            Corner.RL,
        )
        // a word that names its subject stands alone; high, low and a leak are warnings
        assertEquals(listOf(plain("давление: норма"), plain("утечка: норма")), rows(0.0, 0.0))
        assertEquals(listOf(alert("", "давление высокое"), alert("", "быстрая утечка")), rows(1.0, 1.0))
        assertEquals(listOf(alert("", "давление низкое"), alert("", "медленная утечка")), rows(2.0, 2.0))
        // the stock listener's unnamed 3 and any other code stay codes, not warnings
        assertEquals(listOf(plain("давление: код 3"), plain("утечка: код 7")), rows(3.0, 7.0))
        assertEquals(listOf(plain("давление: н/д"), plain("утечка: н/д")), rows(null, null))
        // each corner reads its own wheel
        val fr = b.tyreStates(car(DiagnosticExtra.FR_PRESSURE_STATE to 2.0, DiagnosticExtra.RL_PRESSURE_STATE to 0.0), Corner.FR)
        assertEquals(alert("", "давление низкое"), fr[0])
        // the byte's 255 is refused by the reading's range: «н/д»
        val refused = DiagnosticExtraReader.decode("@@extra:fl_leak\nResult: Parcel(00000000 000000ff   '........')", 1, 1).values
        assertEquals(plain("утечка: н/д"), b.tyreStates(CarReadings(extras = refused, extrasFresh = true), Corner.FL)[1])
    }

    @Test
    fun tpmsSaysItsStateAndItsBatteries() {
        fun row(state: Double?, battery: Double?) = b.tpms(
            car(*listOfNotNull(state?.let { DiagnosticExtra.TPMS_STATE to it }, battery?.let { DiagnosticExtra.TPMS_BATTERY to it }).toTypedArray()),
        ).rows.single()
        assertEquals("TPMS", b.tpms(car()).label)
        assertEquals(plain("система: норма · батареи: норма"), row(0.0, 0.0))
        assertEquals(plain("система: самопроверка · батареи: норма"), row(1.0, 0.0))
        assertEquals(plain("система: отключена · батареи: норма"), row(4.0, 0.0))
        // no signal and a fault, flat batteries: orange
        listOf(2.0 to "нет сигнала", 3.0 to "отказ").forEach { (code, word) ->
            assertEquals(CabinRow(listOf(CabinRun("система: "), CabinRun(word, CabinTone.ALERT), CabinRun(" · батареи: норма")), true), row(code, 0.0))
        }
        assertEquals(CabinRow(listOf(CabinRun("система: норма · батареи: "), CabinRun("разряжены", CabinTone.ALERT)), true), row(0.0, 1.0))
        assertEquals(plain("система: н/д · батареи: н/д"), row(null, null))
    }

    @Test
    fun theParkingBrakeHoldsReleasesOrFails() {
        assertEquals(listOf(EpbLook.FREE, EpbLook.FREE, EpbLook.HELD, EpbLook.HELD, EpbLook.FAULT), (0..4).map { b.epbLook(it.toDouble()) })
        assertNull(b.epbLook(null))
        val words = listOf("отпускается", "отпущен", "затягивается", "затянут")
        words.forEachIndexed { code, word -> assertEquals(plain("EPB $word"), b.epb(car(DiagnosticExtra.EPB to code.toDouble())).row) }
        assertEquals(Epb(EpbLook.FAULT, alert("EPB ", "отказ")), b.epb(car(DiagnosticExtra.EPB to 4.0)))
        assertEquals(Epb(null, plain("EPB н/д")), b.epb(car()))
    }

    @Test
    fun theSuspensionsPlateSaysItsLevelAndItsChange() = runTest {
        fun plate(level: Double?, process: Double?) = schemeOf<Scheme.Chassis>(CarSystem.CHASSIS, car(
            *listOfNotNull(level?.let { DiagnosticExtra.SUSPENSION_LEVEL to it }, process?.let { DiagnosticExtra.SUSPENSION_PROCESS to it }).toTypedArray(),
        )).suspension!!
        assertEquals(WordPlate("ПОДВЕСКА", listOf(plain("уровень: средний", small = false), plain("изменение: завершено"))), plate(2.0, 3.0))
        assertEquals(listOf("уровень: низкий", "изменение: подъём"), plate(1.0, 1.0).rows.map { it.text })
        assertEquals(listOf("уровень: высокий", "изменение: опускание"), plate(3.0, 2.0).rows.map { it.text })
        assertEquals("изменение: прервано", plate(3.0, 4.0).rows[1].text)
        assertEquals(listOf("уровень: н/д", "изменение: н/д"), plate(null, null).rows.map { it.text })
    }

    // ------------------------------------------------------------------ the body

    @Test
    fun theHoodAndTheTailgate() {
        assertEquals(Lid(false, plain("капот закрыт")), b.lid(car(DiagnosticExtra.HOOD to 0.0), DiagnosticExtra.HOOD, "капот"))
        assertEquals(Lid(true, alert("капот ", "открыт")), b.lid(car(DiagnosticExtra.HOOD to 1.0), DiagnosticExtra.HOOD, "капот"))
        assertEquals(Lid(true, alert("багажник ", "открыт")), b.lid(car(DiagnosticExtra.TRUNK to 1.0), DiagnosticExtra.TRUNK, "багажник"))
        assertEquals(Lid(null, plain("багажник н/д")), b.lid(car(), DiagnosticExtra.TRUNK, "багажник"))
        // getDoorState's UNDEFINED 255 is refused by the range
        val undefined = DiagnosticExtraReader.decode("@@extra:hood\nResult: Parcel(00000000 000000ff   '........')", 1, 1).values
        assertEquals(Lid(null, plain("капот н/д")), b.lid(CarReadings(extras = undefined, extrasFresh = true), DiagnosticExtra.HOOD, "капот"))
    }

    @Test
    fun theIndicatorsLightTheSidesTheirWordsName() {
        fun sides(code: Double?) = b.lamps(car(*listOfNotNull(code?.let { DiagnosticExtra.TURN_LIGHTS to it }).toTypedArray())).let { it.turnLeft to it.turnRight }
        assertEquals(false to false, sides(1.0))
        assertEquals(true to false, sides(2.0))
        assertEquals(true to false, sides(3.0))
        assertEquals(false to true, sides(4.0))
        assertEquals(false to true, sides(5.0))
        assertEquals(true to true, sides(6.0))
        // emergency braking, a rear threat, the fast flash: the stock's names, no side said
        listOf(7.0, 8.0, 9.0, null).forEach { assertEquals("$it", false to false, sides(it)) }
        // the words: any flashing in orange, off and not read plain
        fun turn(code: Double?) = b.lights(car(*listOfNotNull(code?.let { DiagnosticExtra.TURN_LIGHTS to it }).toTypedArray())).rows[2]
        assertEquals(plain("поворотники выкл"), turn(1.0))
        mapOf(2.0 to "левый", 3.0 to "левый, быстро", 4.0 to "правый", 5.0 to "правый, быстро", 6.0 to "аварийная",
            7.0 to "экстренное торможение", 8.0 to "угроза сзади", 9.0 to "быстрое мигание").forEach { (code, word) ->
            assertEquals(alert("поворотники ", word), turn(code))
        }
        assertEquals(plain("поворотники н/д"), turn(null))
    }

    @Test
    fun theBeamsAndTheTailLamps() {
        val lit = car(DiagnosticExtra.LOW_BEAM to 1.0, DiagnosticExtra.HIGH_BEAM to 0.0, DiagnosticExtra.STOP_LAMP to 2.0, DiagnosticExtra.REVERSE_LAMP to 1.0)
        assertEquals(BodyLamps(low = true, high = false, turnLeft = false, turnRight = false, stop = true, reverse = true), b.lamps(lit))
        assertEquals(WordPlate("СВЕТ", listOf(plain("ближний вкл", small = false), plain("дальний выкл"), plain("поворотники н/д"))), b.lights(lit))
        assertEquals(CabinRow(listOf(CabinRun("стоп-сигнал "), CabinRun("мигает", CabinTone.ALERT), CabinRun(" · задний ход горит")), false),
            b.tail(lit, b.lamps(lit)).rows.single())
        val off = car(DiagnosticExtra.STOP_LAMP to 3.0, DiagnosticExtra.REVERSE_LAMP to 2.0, DiagnosticExtra.HIGH_BEAM to 5.0)
        assertEquals(BodyLamps(low = null, high = null, turnLeft = false, turnRight = false, stop = false, reverse = false), b.lamps(off))
        assertEquals(plain("стоп-сигнал не горит · задний ход не горит", small = false), b.tail(off, b.lamps(off)).rows.single())
        // a beam's other code stays a code
        assertEquals("дальний код 5", b.lights(off).rows[1].text)
        assertEquals("ЗАДНИЕ ФОНАРИ", b.tail(off, b.lamps(off)).label)
        assertEquals(alert("стоп-сигнал ", "горит", small = false).runs + CabinRun(" · задний ход н/д"),
            b.tail(car(DiagnosticExtra.STOP_LAMP to 1.0), b.lamps(car(DiagnosticExtra.STOP_LAMP to 1.0))).rows.single().runs)
        val none = b.lamps(car())
        assertEquals(BodyLamps(null, null, false, false, null, null), none)
        assertEquals(listOf("ближний н/д", "дальний н/д", "поворотники н/д"), b.lights(car()).rows.map { it.text })
    }

    // ------------------------------------------------------------------ HV, 12 V, drives

    @Test
    fun theChargePortSaysItsPlugLitWhileOneIsIn() {
        fun hv(code: Double?) = b.hv(car(hub = listOfNotNull(code?.let { VehicleSignal.CHARGE_GUN to it }).toMap()))
        assertEquals(Scheme.Hv("не подключён", plugged = false), hv(1.0))
        assertEquals(Scheme.Hv("AC", plugged = true), hv(2.0))
        assertEquals(Scheme.Hv("DC", plugged = true), hv(3.0))
        assertEquals(Scheme.Hv("AC + DC", plugged = true), hv(4.0))
        assertEquals(Scheme.Hv("V2L", plugged = true), hv(5.0))
        // the hub's wider gate lets 0 and 6..8 by: no plug named
        listOf(0.0, 6.0, 8.0, null).forEach { assertEquals("$it", Scheme.Hv("н/д", plugged = false), hv(it)) }
    }

    @Test
    fun theHeadsSayThePowerModeAndTheSelector() = runTest {
        val power = mapOf(0.0 to "выкл", 1.0 to "ACC", 2.0 to "ON", 3.0 to "OK (готов)", 4.0 to "OK (ложный)")
        power.forEach { (code, word) ->
            val m = scanned(car(DiagnosticExtra.POWER_LEVEL to code))
            assertEquals(HeadCounter("ПИТАНИЕ", word), m.systemPages.getValue(CarSystem.HV).counters.first())
            assertEquals(HeadCounter("ПИТАНИЕ", word), m.systemPages.getValue(CarSystem.LV).counters.first())
        }
        val gears = mapOf(1.0 to "P", 2.0 to "R", 3.0 to "N", 4.0 to "D", 5.0 to "M", 6.0 to "S", 9.0 to "код 9")
        gears.forEach { (code, word) ->
            assertEquals(HeadCounter("СЕЛЕКТОР", word), scanned(car(DiagnosticExtra.GEAR_SELECTOR to code)).systemPages.getValue(CarSystem.DRIVES).counters.first())
        }
        val none = scanned(car())
        assertEquals(HeadCounter("ПИТАНИЕ", "н/д"), none.systemPages.getValue(CarSystem.HV).counters.first())
        assertEquals(HeadCounter("СЕЛЕКТОР", "н/д"), none.systemPages.getValue(CarSystem.DRIVES).counters.first())
        // an old batch is none
        val stale = scanned(car(DiagnosticExtra.POWER_LEVEL to 2.0, fresh = false))
        assertEquals(HeadCounter("ПИТАНИЕ", "н/д"), stale.systemPages.getValue(CarSystem.LV).counters.first())
    }

    @Test
    fun theConvertersMode() = runTest {
        val modes = mapOf(0.0 to "выключен", 1.0 to "повышение", 2.0 to "понижение", 3.0 to "код 3")
        modes.forEach { (code, word) -> assertEquals(word, schemeOf<Scheme.Lv>(CarSystem.LV, car(DiagnosticExtra.DCDC_MODE to code)).dcdcMode) }
        assertEquals("н/д", schemeOf<Scheme.Lv>(CarSystem.LV, car()).dcdcMode)
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun theSafetySeatsAreTheCabinsSeatsAndWords() {
        val seats = b.seatBelts(car(
            DiagnosticExtra.SEATED_FL to 2.0, DiagnosticExtra.BELT_FL to 1.0,
            DiagnosticExtra.SEATED_FR to 2.0, DiagnosticExtra.BELT_FR to 0.0,
            DiagnosticExtra.SEATED_RL to 1.0, DiagnosticExtra.BELT_RL to 2.0,
            DiagnosticExtra.SEATED_RC to 2.0,
        ))
        assertEquals(listOf(CabinPlace.FL, CabinPlace.FR, CabinPlace.RL, CabinPlace.RC, CabinPlace.RR), seats.map { it.place })
        assertEquals(listOf("ВОДИТЕЛЬ", "ПАССАЖИР", "2-Й РЯД ЛЕВЫЙ", "2-Й РЯД ЦЕНТР", "2-Й РЯД ПРАВЫЙ"), seats.map { it.label })
        assertEquals(CabinSeat(true, true, null, null, null), seats[0].seat)
        assertEquals(b.presence(true, true), seats[0].row)
        // seated unbuckled: orange, as the cabin says it
        assertEquals(CabinRow(listOf(CabinRun("сидит · "), CabinRun("не пристёгнут", CabinTone.ALERT)), false), seats[1].row)
        assertEquals(CabinSeat(false, true, null, null, null), seats[2].seat)
        assertEquals("пусто · пристёгнут", seats[2].row.text)
        assertEquals("сидит · ремень н/д", seats[3].row.text)
        assertEquals(CabinSeat(null, null, null, null, null), seats[4].seat)
        assertEquals("н/д", seats[4].row.text)
        // the same seats as the cabin's
        val cabin = b.cabin(car(DiagnosticExtra.SEATED_FR to 2.0, DiagnosticExtra.BELT_FR to 0.0))
        assertEquals(cabin.seats.getValue(CabinPlace.FR).let { CabinSeat(it.occupied, it.belt, null, null, null) },
            b.seatBelts(car(DiagnosticExtra.SEATED_FR to 2.0, DiagnosticExtra.BELT_FR to 0.0))[1].seat)
        // an old batch is no reading
        assertTrue(b.seatBelts(car(DiagnosticExtra.SEATED_FL to 2.0, fresh = false)).all { it.seat.occupied == null })
    }

    @Test
    fun theSchemesPagesAskForWhatTheyDraw() {
        // what each scheme says is read on its «Состояние» page (6), and the safety scheme's seats on its own
        fun keys(page: ExtraPage) = DiagnosticExtra.entries.filter { page in it.pages }.toSet()
        assertTrue(keys(ExtraPage.BODY_STATE).containsAll(listOf(DiagnosticExtra.HOOD, DiagnosticExtra.TRUNK, DiagnosticExtra.TURN_LIGHTS,
            DiagnosticExtra.LOW_BEAM, DiagnosticExtra.HIGH_BEAM, DiagnosticExtra.STOP_LAMP, DiagnosticExtra.REVERSE_LAMP)))
        assertTrue(keys(ExtraPage.CHASSIS_STATE).containsAll(listOf(DiagnosticExtra.EPB, DiagnosticExtra.SUSPENSION_LEVEL, DiagnosticExtra.SUSPENSION_PROCESS,
            DiagnosticExtra.TPMS_STATE, DiagnosticExtra.TPMS_BATTERY) + PRESSURE_STATES + LEAKS))
        assertEquals(setOf(DiagnosticExtra.GEAR_SELECTOR), keys(ExtraPage.DRIVES_STATE))
        assertEquals(setOf(DiagnosticExtra.POWER_LEVEL), keys(ExtraPage.HV_STATE))
        assertEquals(setOf(DiagnosticExtra.POWER_LEVEL, DiagnosticExtra.DCDC_MODE), keys(ExtraPage.LV_STATE))
        assertTrue(keys(ExtraPage.SAFETY_STATE).containsAll(SAFETY))
    }

    // ------------------------------------------------------------------ the boards

    private val selection by lazy {
        JSONObject(File(root, "research/vehicle-diagnostics/stream-selection-20261021.json").readText()).getJSONObject("systems")
    }

    /** The code the 2026-10-20 sweep read at [device]·[fid], as the selection writes it down («…fids.tsv): 3 (applied)…»). */
    private fun sweepCode(device: Int, fid: Int): Int {
        val rows = selection.keys().asSequence().flatMap { s -> selection.getJSONArray(s).let { a -> (0 until a.length()).map(a::getJSONObject) } }
        val row = rows.first { it.getInt("device") == device && it.getString("fid").removePrefix("0x").toLong(16).toInt() == fid }
        val live = row.getString("liveOnCar")
        return Regex("fids\\.tsv\\):\\s*(-?\\d+)").find(live)?.groupValues?.get(1)?.toInt() ?: error("no sweep code in «$live»")
    }

    private val PRESSURE_STATES = listOf(DiagnosticExtra.FL_PRESSURE_STATE, DiagnosticExtra.FR_PRESSURE_STATE, DiagnosticExtra.RL_PRESSURE_STATE, DiagnosticExtra.RR_PRESSURE_STATE)
    private val LEAKS = listOf(DiagnosticExtra.FL_LEAK, DiagnosticExtra.FR_LEAK, DiagnosticExtra.RL_LEAK, DiagnosticExtra.RR_LEAK)
    private val SAFETY = listOf(DiagnosticExtra.SEATED_FL, DiagnosticExtra.SEATED_FR, DiagnosticExtra.SEATED_RL, DiagnosticExtra.SEATED_RC, DiagnosticExtra.SEATED_RR,
        DiagnosticExtra.BELT_FL, DiagnosticExtra.BELT_FR, DiagnosticExtra.BELT_RL, DiagnosticExtra.BELT_RC, DiagnosticExtra.BELT_RR)

    /** Every GET the six schemes draw, through the batch's own decoding. */
    private fun decoded(codes: Map<DiagnosticExtra, Int>): Map<DiagnosticExtra, Double> = DiagnosticExtraReader.decode(
        codes.entries.joinToString("\n") { (e, code) -> "@@extra:${e.key}\nResult: Parcel(00000000 %08x   '........')".format(code) }, 1, 1,
    ).values

    private val schemeGets = listOf(
        ExtraPage.BODY_STATE, ExtraPage.CHASSIS_STATE, ExtraPage.DRIVES_STATE, ExtraPage.HV_STATE, ExtraPage.LV_STATE, ExtraPage.SAFETY_STATE,
    ).let { pages -> DiagnosticExtra.entries.filter { e -> e.pages.any { it in pages } } }

    /** The parked sweep of 2026-10-20: every scheme GET and the hub's charge port, as the selection records them. */
    private fun sweep(): CarReadings = CarReadings(
        hub = mapOf(VehicleSignal.CHARGE_GUN to sweepCode(VehicleSignal.CHARGE_GUN.device, VehicleSignal.CHARGE_GUN.fid).toDouble()),
        extras = decoded(schemeGets.associateWith { sweepCode(it.device, it.fid) }),
        extrasFresh = true,
    )

    @Test
    fun theSweepIsWhatTheSelectionWritesDown() {
        val car = sweep()
        assertEquals(schemeGets.toSet(), car.extras.keys)
        // the parked car: P, ON, an AC plug, the brake applied, all closed and dark, an empty car
        assertEquals("P", car.word(DiagnosticExtra.GEAR_SELECTOR))
        assertEquals("ON", car.word(DiagnosticExtra.POWER_LEVEL))
        assertEquals("AC", car.word(VehicleSignal.CHARGE_GUN))
        assertEquals("понижение", car.word(DiagnosticExtra.DCDC_MODE))
        assertEquals("затянут", car.word(DiagnosticExtra.EPB))
        assertEquals(listOf("закрыт", "закрыт", "выкл", "не горит", "не горит"),
            listOf(DiagnosticExtra.HOOD, DiagnosticExtra.TRUNK, DiagnosticExtra.TURN_LIGHTS, DiagnosticExtra.STOP_LAMP, DiagnosticExtra.REVERSE_LAMP).map { car.word(it) })
        assertTrue(listOf(DiagnosticExtra.SEATED_FL, DiagnosticExtra.SEATED_FR, DiagnosticExtra.SEATED_RL, DiagnosticExtra.SEATED_RC, DiagnosticExtra.SEATED_RR)
            .all { car.word(it) == "нет" })
    }

    @Test
    fun theParkedBoardsAreTheSweepOf20261020() = runTest {
        val car = sweep()
        val model = scanned(car)
        chassis("scanner-sys-chassis", model.systemPages.getValue(CarSystem.CHASSIS).scheme as Scheme.Chassis)
        body("scanner-sys-body-state", model.systemPages.getValue(CarSystem.BODY).scheme as Scheme.Body)
        safety("scanner-sys-safety", model.systemPages.getValue(CarSystem.SAFETY).scheme as Scheme.Safety)
        val hv = model.systemPages.getValue(CarSystem.HV)
        assertEquals(counter("scanner-sys-hv"), hv.counters.first())
        val charge = fixture("scanner-sys-hv")["charge"] as Map<*, *>
        assertEquals(Scheme.Hv(charge["state"] as String, charge["plugged"] == true), hv.scheme)
        assertEquals(counter("scanner-sys-drives"), model.systemPages.getValue(CarSystem.DRIVES).counters.first())
        val lv = model.systemPages.getValue(CarSystem.LV)
        assertEquals(counter("scanner-sys-lv"), lv.counters.first())
        assertEquals(fixture("scanner-sys-lv")["dcdcMode"], (lv.scheme as Scheme.Lv).dcdcMode)
    }

    @Test
    fun theLitBodyIsWhatTheBuilderSaysForItsCodes() = runTest {
        // both beams, the hazard lights, the stop and reversing lamps, the tailgate open (illustrative)
        val codes = mapOf(DiagnosticExtra.LOW_BEAM to 1, DiagnosticExtra.HIGH_BEAM to 1, DiagnosticExtra.TURN_LIGHTS to 6,
            DiagnosticExtra.STOP_LAMP to 1, DiagnosticExtra.REVERSE_LAMP to 1, DiagnosticExtra.HOOD to 0, DiagnosticExtra.TRUNK to 1)
        body("scanner-sys-body-state-lit", schemeOf(CarSystem.BODY, CarReadings(extras = decoded(codes), extrasFresh = true)))
    }

    @Test
    fun theSafetyTripIsTheCabinsTrip() = runTest {
        // scanner-sys-cabin's seats: the driver buckled, the second row's left sitting unbuckled
        val codes = mapOf(DiagnosticExtra.SEATED_FL to 2, DiagnosticExtra.BELT_FL to 1, DiagnosticExtra.SEATED_FR to 1, DiagnosticExtra.BELT_FR to 0,
            DiagnosticExtra.SEATED_RL to 2, DiagnosticExtra.BELT_RL to 1, DiagnosticExtra.SEATED_RC to 1, DiagnosticExtra.BELT_RC to 1,
            DiagnosticExtra.SEATED_RR to 1, DiagnosticExtra.BELT_RR to 1)
        safety("scanner-sys-safety-trip", schemeOf(CarSystem.SAFETY, CarReadings(extras = decoded(codes), extrasFresh = true)))
    }

    // ------------------------------------------------------------------ the boards' terms

    @Suppress("UNCHECKED_CAST")
    private fun fixture(id: String): Map<String, Any?> = SpecJson.fixture(id)

    @Suppress("UNCHECKED_CAST")
    private fun counter(id: String): HeadCounter =
        ((fixture(id)["block"] as Map<String, Any?>)["counters"] as List<List<String>>).first().let { HeadCounter(it[0], it[1]) }

    /** A board's word in the model's terms: the ink of its row's style, or a tone of its own. */
    private fun tone(style: String, ink: String): CabinTone = when (ink) {
        "orange" -> CabinTone.ALERT
        "orangeInk" -> CabinTone.WARM
        "white" -> CabinTone.PLAIN.also { assertEquals("white on a first row", "cell", style) }
        "dim" -> if (style == "cell") CabinTone.QUIET else CabinTone.PLAIN
        else -> error("ink $ink")
    }

    /** Runs of one tone side by side are one run. */
    private fun joined(r: CabinRow): CabinRow = r.copy(runs = r.runs.fold(mutableListOf()) { acc, run ->
        acc.also { if (it.isNotEmpty() && it.last().tone == run.tone) it[it.lastIndex] = CabinRun(it.last().text + run.text, run.tone) else it += run }
    })

    private fun row(row: Any?): CabinRow {
        val r = row as List<*>
        val runs = if (r[0] is List<*>) r.map { it as List<*> } else listOf(r)
        val style = runs.first()[1] as String
        assertEquals(setOf(style), runs.map { it[1] }.toSet())
        return joined(CabinRow(runs.map { CabinRun(it[0] as String, tone(it[1] as String, it[2] as String)) }, small = style == "cellSmall"))
    }

    /** A board's `P(label, rows)`. */
    private fun plate(p: Any?): WordPlate {
        val rows = p as List<*>
        val label = rows[0] as List<*>
        assertEquals(listOf("counterLabel", "mute"), label.drop(1))
        return WordPlate(label[0] as String, rows.drop(1).map(::row))
    }

    private fun WordPlate.joined() = copy(rows = rows.map(::joined))

    @Suppress("UNCHECKED_CAST")
    private fun chassis(id: String, s: Scheme.Chassis) {
        val f = fixture(id)
        val tyres = f["tyres"] as List<Map<String, Any?>>
        tyres.forEach { t ->
            val corner = Corner.valueOf((t["axle"] as String) + (t["side"] as String))
            assertEquals("$id ${t["label"]}", (t["states"] as List<*>).map(::row), s.tyres.single { it.corner == corner }.states.map(::joined))
        }
        val tpms = f["tpms"] as Map<String, Any?>
        assertEquals("$id TPMS", WordPlate(tpms["label"] as String, listOf(row(tpms["row"]))), s.tpms!!.joined())
        assertEquals("$id suspension", plate(f["suspension"]), s.suspension!!.joined())
        val epb = f["epb"] as Map<String, Any?>
        assertEquals("$id EPB", Epb((epb["look"] as String?)?.uppercase()?.let(EpbLook::valueOf), row(epb["row"])), s.epb!!.let { it.copy(row = joined(it.row)) })
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(id: String, s: Scheme.Body) {
        val f = fixture(id)
        fun lid(key: String): Lid = (f[key] as Map<String, Any?>).let { Lid(it["open"] as Boolean?, row(it["row"])) }
        assertEquals("$id hood", lid("hood"), s.hood!!.let { it.copy(row = joined(it.row)) })
        assertEquals("$id trunk", lid("trunk"), s.trunk!!.let { it.copy(row = joined(it.row)) })
        val m = f["lamps"] as Map<String, Any?>
        assertEquals("$id lamps", BodyLamps(m["low"] as Boolean?, m["high"] as Boolean?, m["turnLeft"] == true, m["turnRight"] == true,
            m["stop"] as Boolean?, m["reverse"] as Boolean?), s.lamps)
        assertEquals("$id lights", plate(f["lights"]), s.lights!!.joined())
        assertEquals("$id tail", plate(f["tail"]), s.tail!!.joined())
    }

    @Suppress("UNCHECKED_CAST")
    private fun safety(id: String, s: Scheme.Safety) {
        val seats = fixture(id)["seats"] as List<Map<String, Any?>>
        assertEquals(seats.map { it["place"] }, s.seats.map { it.place.name.lowercase() })
        seats.zip(s.seats).forEach { (board, seat) ->
            assertEquals("$id ${board["place"]}", board["label"], seat.label)
            assertEquals("$id ${board["place"]}", CabinSeat(board["occupied"] as Boolean?, board["belt"] as Boolean?, null, null, null), seat.seat)
            assertEquals("$id ${board["place"]}", row(board["row"]), joined(seat.row))
        }
    }

    // ------------------------------------------------------------------ the session

    /** The section's model on «машина сейчас», the car parked at P and 0 km/h with [readings]. */
    private fun TestScope.scanned(readings: CarReadings): DiagnosticsModel {
        val s = session(carNow(), car = FakeCar(readings.copy(hub = FakeCar.parked().hub + readings.hub, sweep = 1)))
        s.start()
        runCurrent()
        return s.model.value.also { s.stop() }
    }

    private inline fun <reified T : Scheme> TestScope.schemeOf(system: CarSystem, readings: CarReadings): T =
        scanned(readings).systemPages.getValue(system).scheme as T
}
