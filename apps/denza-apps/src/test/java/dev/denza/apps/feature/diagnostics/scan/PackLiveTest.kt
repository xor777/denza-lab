package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Тяговая батарея · Потоковые данные» as a stream (architecture 6, 6.3, 7.2; board
 * `scanner-sys-pack-live`): while the tab is in front the batch is read round after round, every
 * reading keeps its last minute and its extremes, «Пауза» freezes the table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackLiveTest {

    // ------------------------------------------------------------------ the cadence

    /** A shell whose batch answers SOC and costs [cost] ms of the head unit's clock. */
    private class BatchShell : ExtrasShell {
        val commands = mutableListOf<String>()
        var cost = 0L
        var spent = 0L
        var fail = false
        override fun shell(command: String): String {
            if (command.startsWith("getprop")) return "170\n"
            commands += command
            if (fail) throw IOException("shell gone")
            spent += cost
            return "@@extra:soc\nResult: Parcel(00000000 %08x   '........')\n".format(74f.toBits())
        }
        override fun close() {}
    }

    @Test
    fun whileThePacksStreamShowsTheBatchGoesBackToBackAndFiveSecondsApartOtherwise() = runTest {
        val shell = BatchShell().apply { cost = 60 }
        val poller = ExtrasPoller(
            open = { shell },
            log = {},
            context = backgroundScope.coroutineContext,
            wallClock = { testScheduler.currentTime },
            elapsed = { testScheduler.currentTime + shell.spent },
            nanos = { 0L },
        )
        poller.start()
        runCurrent()
        assertEquals(1, shell.commands.size)
        assertEquals("every batch says how long it took", 60L, poller.latest.tookMs)
        assertEquals(74.0, poller.latest.values.getValue(DiagnosticExtra.SOC), 0.0)
        // Not shown: five seconds after an answer, as always.
        advanceTimeBy(4_900)
        assertEquals(1, shell.commands.size)
        advanceTimeBy(200)
        assertEquals(2, shell.commands.size)

        // Shown: the next batch at once, then one every 100 ms from start to start.
        shell.cost = 0
        poller.show(ExtraPage.PACK_LIVE, true)
        runCurrent()
        assertEquals(3, shell.commands.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(13, shell.commands.size)
        assertTrue("no GETs of its own: the shared batch", shell.commands.all { it == DiagnosticExtraReader.command })
        assertEquals(DiagnosticExtraReader.command, DiagnosticExtraReader.command(setOf(ExtraPage.PACK_LIVE)))

        // A failure still waits five seconds, shown or not.
        shell.fail = true
        advanceTimeBy(100)
        runCurrent()
        val failed = shell.commands.size
        advanceTimeBy(4_800)
        assertEquals(failed, shell.commands.size)
        shell.fail = false
        advanceTimeBy(300)
        assertEquals(failed + 1, shell.commands.size)

        // Left: the next batch at once, then five seconds again.
        poller.show(ExtraPage.PACK_LIVE, false)
        runCurrent()
        val left = shell.commands.size
        advanceTimeBy(4_900)
        assertEquals(left, shell.commands.size)
        advanceTimeBy(200)
        assertEquals(left + 1, shell.commands.size)
        poller.stop()
    }

    // ------------------------------------------------------------------ the minute

    private val parkedHub = mapOf(
        VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.PACK_VOLT to 553.0,
        VehicleSignal.CELL_MIN_MV to 3333.0, VehicleSignal.CELL_MAX_MV to 3337.0, VehicleSignal.PACK_TEMP_AVG to 14.0,
    )
    private val parkedExtras = mapOf(
        DiagnosticExtra.CHARGE_CURRENT to -4.5, DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0,
        DiagnosticExtra.TEMP_MIN to 13.0, DiagnosticExtra.TEMP_MAX to 18.0,
        DiagnosticExtra.INSULATION to 14429.0, DiagnosticExtra.INSULATION_2 to 19675.0,
        DiagnosticExtra.CELL_COUNT to 166.0, DiagnosticExtra.PROBE_COUNT to 14.0,
        DiagnosticExtra.CONTACTOR to 1.0, DiagnosticExtra.PACK_CHARGE_STATE to 3.0,
        DiagnosticExtra.CHARGER_STATE to 1.0, DiagnosticExtra.CHARGER_FAULT to 1.0,
    )

    /** Round [n]: hub sweep [n] and batch [n], both at [n] seconds on the car's clock. */
    private fun round(
        n: Long,
        hub: Map<VehicleSignal, Double> = emptyMap(),
        extras: Map<DiagnosticExtra, Double> = emptyMap(),
        tookMs: Long? = 400,
    ) = CarReadings(
        hub = parkedHub + hub, sweep = n, atNanos = n * SECOND,
        extras = parkedExtras + extras, extrasFresh = true, extrasAtNanos = n * SECOND, extrasTookMs = tookMs,
    )

    private fun PackLiveView.row(name: String) = rows.single { it.name == name }

    @Test
    fun aNumberKeepsItsLastMinuteAndItsExtremesAStateOrACountOnlyItsValue() {
        val live = PackLive().apply { show() }
        (1L..90L).forEach { n -> live.take(round(n, hub = mapOf(VehicleSignal.PACK_VOLT to 500.0 + n), extras = mapOf(DiagnosticExtra.SOC to n.toDouble()))) }
        val v = live.view()

        val volts = v.row("Напряжение ВВБ")
        assertEquals("590", volts.value)
        assertEquals("501" to "590", volts.min to volts.max)
        // the last 60 s on the car's clock, a hub point per sweep
        assertEquals((30L..90L).map { it * SECOND }, volts.samples.map { it.atNanos })
        assertEquals(590f, volts.samples.last().value)
        val soc = v.row("Уровень заряда (SOC)")
        assertEquals((30L..90L).map { it * SECOND }, soc.samples.map { it.atNanos })
        assertEquals("1" to "90", soc.min to soc.max)

        // a count: its value, no trace, no extremes
        val cells = v.row("Ячеек последовательно")
        assertEquals("166", cells.value)
        assertEquals("" to "", cells.min to cells.max)
        assertTrue(cells.samples.isEmpty())
        // a state: its word
        val contactor = v.row("Главный контактор, разряд")
        assertEquals("замкнут", contactor.value)
        assertEquals("" to "", contactor.min to contactor.max)
        assertTrue(contactor.samples.isEmpty())
        assertEquals("разряд", v.row("Состояние заряда батареи").value)
    }

    @Test
    fun theSameSweepOrBatchTwiceIsOnePoint() {
        val live = PackLive().apply { show() }
        live.take(round(1))
        live.take(round(1))
        // a new hub sweep with the batch unchanged: the hub's rows take a point, the GETs' do not
        live.take(round(1).copy(sweep = 2, atNanos = 2 * SECOND))
        val v = live.view()
        assertEquals(2, v.row("Напряжение ВВБ").samples.size)
        assertEquals(1, v.row("Ток ВВБ").samples.size)
    }

    @Test
    fun theSpreadFollowsTheCellsMinimumAndMaximum() {
        val live = PackLive().apply { show() }
        listOf(3334.0 to 3337.0, 3332.0 to 3337.0, 3333.0 to 3336.0, 3333.0 to 3338.0, 3333.0 to 3337.0).forEachIndexed { i, (min, max) ->
            live.take(round(i + 1L, hub = mapOf(VehicleSignal.CELL_MIN_MV to min, VehicleSignal.CELL_MAX_MV to max)))
        }
        val names = live.view().rows.map { it.name }
        assertEquals("right after the cells' maximum", names.indexOf("Ячейка, макс.") + 1, names.indexOf("Разброс ячеек"))
        val spread = live.view().row("Разброс ячеек")
        assertEquals(listOf("4", "3", "5"), listOf(spread.value, spread.min, spread.max))
        assertEquals(listOf(3f, 5f, 3f, 5f, 4f), spread.samples.map { it.value })
        assertEquals("расч." to "расч.", spread.device to spread.source)
        assertNull("a calculation has nothing to poll alone", spread.key)
        // a cell missing: a gap and «н/д»
        live.take(round(6, hub = mapOf()).let { it.copy(hub = it.hub - VehicleSignal.CELL_MAX_MV) })
        val gap = live.view().row("Разброс ячеек")
        assertEquals("н/д", gap.value)
        assertNull(gap.samples.last().value)
        assertEquals("the extremes stay", "3" to "5", gap.min to gap.max)
    }

    @Test
    fun resetStartsTheExtremesAtTheValueNowAndShowingAgainStartsThemOver() {
        val live = PackLive().apply { show() }
        listOf(552.0, 554.0, 553.0).forEachIndexed { i, u -> live.take(round(i + 1L, hub = mapOf(VehicleSignal.PACK_VOLT to u))) }
        assertEquals("552" to "554", live.view().row("Напряжение ВВБ").let { it.min to it.max })

        live.resetMinMax()
        assertEquals("553" to "553", live.view().row("Напряжение ВВБ").let { it.min to it.max })
        live.take(round(4, hub = mapOf(VehicleSignal.PACK_VOLT to 551.0)))
        assertEquals("551" to "553", live.view().row("Напряжение ВВБ").let { it.min to it.max })
        assertEquals("the trace stays", 4, live.view().row("Напряжение ВВБ").samples.size)

        // left and shown again: the extremes are what came since, the trace goes on
        live.show()
        live.take(round(4, hub = mapOf(VehicleSignal.PACK_VOLT to 551.0)))
        val again = live.view().row("Напряжение ВВБ")
        assertEquals("what is there now counts", "551" to "551", again.min to again.max)
        assertEquals(5, again.samples.size)
    }

    @Test
    fun aPauseTakesNothingAndTheTableStands() {
        val live = PackLive().apply { show() }
        live.take(round(1))
        live.pause()
        val before = live.view()
        assertTrue(before.paused)
        // the car moves on, the batch goes stale, the voltage the insulation is judged against goes
        live.take(round(2, hub = mapOf(VehicleSignal.PACK_VOLT to 400.0), extras = mapOf(DiagnosticExtra.INSULATION to 100.0), tookMs = 900))
        live.take(round(3).copy(extrasFresh = false, hub = mapOf()))
        assertEquals(before, live.view())
        assertEquals("обход 0,4 с", before.round)

        live.resume()
        live.take(round(4, extras = mapOf(DiagnosticExtra.INSULATION to 100.0)))
        val after = live.view()
        assertFalse(after.paused)
        assertEquals("100", after.row("Изоляция, канал BMC").value)
        assertEquals(2, after.row("Напряжение ВВБ").samples.size)
    }

    @Test
    fun aBatchPastItsHorizonIsAGapAndNoData() {
        val live = PackLive().apply { show() }
        live.take(round(1))
        live.take(round(2).copy(extrasFresh = false))
        live.take(round(3).copy(extrasFresh = false))
        val v = live.view()
        val current = v.row("Ток ВВБ")
        assertEquals("н/д", current.value)
        assertEquals("one gap, not one a tick", listOf(-4.5f, null), current.samples.map { it.value })
        assertEquals("н/д", v.row("Главный контактор, разряд").value)
        assertEquals("н/д", v.row("Ячеек последовательно").value)
        // the hub's rows go on with the hub
        assertEquals(3, v.row("Напряжение ВВБ").samples.size)
        // the batch back: its values again
        live.take(round(4))
        assertEquals("−4,5", live.view().row("Ток ВВБ").value)
    }

    @Test
    fun aValueOutsideItsNormIsMarkedAndNothingElseIs() {
        fun view(hub: Map<VehicleSignal, Double> = emptyMap(), extras: Map<DiagnosticExtra, Double> = emptyMap(), drop: Set<Any> = emptySet()): PackLiveView {
            val r = round(1, hub = hub, extras = extras)
            return PackLive.of(r.copy(hub = r.hub - drop.filterIsInstance<VehicleSignal>().toSet(), extras = r.extras - drop.filterIsInstance<DiagnosticExtra>().toSet()))
        }
        val parked = view()
        assertTrue(parked.rows.none { it.outOfNorm })
        assertEquals("≥ 80", parked.row("Состояние (SOH), BMS").norm)
        // 500 Ω/V of the pack's 553 V: 276,5 kΩ, said as the board says it
        assertEquals("≥ 277", parked.row("Изоляция, канал BMC").norm)
        assertEquals("≥ 277", parked.row("Изоляция, канал 2").norm)
        assertTrue(parked.rows.filter { it.name !in setOf("Состояние (SOH), BMS", "Изоляция, канал BMC", "Изоляция, канал 2") }.all { it.norm == "—" })

        assertTrue(view(extras = mapOf(DiagnosticExtra.SOH to 79.0)).row("Состояние (SOH), BMS").outOfNorm)
        assertFalse(view(extras = mapOf(DiagnosticExtra.SOH to 80.0)).row("Состояние (SOH), BMS").outOfNorm)
        assertTrue(view(extras = mapOf(DiagnosticExtra.INSULATION_2 to 276.0)).row("Изоляция, канал 2").outOfNorm)
        assertFalse(view(extras = mapOf(DiagnosticExtra.INSULATION_2 to 277.0)).row("Изоляция, канал 2").outOfNorm)
        // no pack voltage: the floor said per volt and nothing judged
        val noVolts = view(extras = mapOf(DiagnosticExtra.INSULATION to 10.0), drop = setOf(VehicleSignal.PACK_VOLT))
        assertEquals("≥ 500 Ом/В", noVolts.row("Изоляция, канал BMC").norm)
        assertFalse(noVolts.row("Изоляция, канал BMC").outOfNorm)
        // a code above the stock cap is no resistance: «н/д», not judged
        val capped = DiagnosticExtraReader.decode("@@extra:insulation\nResult: Parcel(00000000 0000fffe   '........')", 1, 1).values
        val invalid = PackLive.of(round(1).copy(extras = parkedExtras - DiagnosticExtra.INSULATION + capped))
        assertEquals("н/д", invalid.row("Изоляция, канал BMC").value)
        assertFalse(invalid.row("Изоляция, канал BMC").outOfNorm)
        // nothing came: «н/д», not judged
        val none = view(drop = setOf(DiagnosticExtra.SOH))
        assertEquals("н/д", none.row("Состояние (SOH), BMS").value)
        assertFalse(none.row("Состояние (SOH), BMS").outOfNorm)
    }

    @Test
    fun theRowsAreTheBoardsColumnsAndTheirKeysOpenTheirPages() {
        val v = PackLive.of(round(1))
        assertEquals(
            listOf(
                "1009" to "44400008", "1009" to "44400018", "1014" to "4A505038", "1014" to "44400028", "1014" to "44600010",
                "1014" to "44600030", "расч." to "", "1014" to "44700010", "1014" to "44700020", "1014" to "44700038",
                "1039" to "43A00018", "1039" to "26200010", "1001" to "43A00008", "1039" to "43A00010", "1032" to "34400010",
                "1009" to "34400018", "1009" to "27C0000A", "1009" to "27C00008",
            ),
            v.rows.map { it.device to it.abbreviation },
        )
        assertEquals(
            listOf("В", "А", "%", "%", "мВ", "мВ", "мВ", "°C", "°C", "°C", "кОм", "кОм", "шт", "шт", "", "", "", ""),
            v.rows.map { it.unit },
        )
        // the keys the rows had before they were a stream: the parameter pages open as they did
        assertEquals(
            listOf(
                "PACK_VOLT", "charge_current", "soc", "soh", "CELL_MIN_MV", "CELL_MAX_MV", null, "temp_min", "temp_max",
                "PACK_TEMP_AVG", "insulation", "insulation_2", "cell_count", "probe_count", "contactor", "pack_charge_state",
                "charger_state", "charger_fault",
            ),
            v.rows.map { (it.key as ParamKey.Signal?)?.key },
        )
        v.rows.filter { it.key != null }.forEach { r ->
            val spec = ParamSpec.of(r.key!!)
            assertNotNull(r.name, spec)
            assertEquals("the page is titled as its row", r.name, spec!!.name)
            assertEquals("· ${r.device}·${r.abbreviation}", spec.aside)
            assertEquals("· ${r.source}", spec.aside)
        }
    }

    // ------------------------------------------------------------------ the board

    /**
     * `scanner-sys-pack-live` is what the table makes of a minute: the parked reading of 2026-10-20
     * as the board's last value, each number passing through its board minimum and maximum on the
     * way (the cells so that their spread does too), the last batch 2,1 s long. Every word of a row
     * is the table's; a trace is there where the board draws one (its shape is illustrative).
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun theBoardIsWhatTheTableMakesOfItsMinute() {
        val live = PackLive().apply { show() }
        val volts = listOf(552.0, 554.0, 553.0, 553.0, 553.0)
        val current = listOf(-6.1, -3.2, -4.5, -4.5, -4.5)
        val cellMin = listOf(3334.0, 3332.0, 3333.0, 3333.0, 3333.0)
        val cellMax = listOf(3337.0, 3337.0, 3336.0, 3338.0, 3337.0)
        val bmc = listOf(14410.0, 14450.0, 14429.0, 14429.0, 14429.0)
        val second = listOf(19660.0, 19690.0, 19675.0, 19675.0, 19675.0)
        (0..4).forEach { i ->
            live.take(round(
                i + 1L,
                hub = mapOf(VehicleSignal.PACK_VOLT to volts[i], VehicleSignal.CELL_MIN_MV to cellMin[i], VehicleSignal.CELL_MAX_MV to cellMax[i]),
                extras = mapOf(DiagnosticExtra.CHARGE_CURRENT to current[i], DiagnosticExtra.INSULATION to bmc[i], DiagnosticExtra.INSULATION_2 to second[i]),
                tookMs = 2_100,
            ))
        }
        val view = live.view()
        val board = SpecJson.fixture("scanner-sys-pack-live")
        val rows = board["rows"] as List<Map<String, Any?>>
        assertEquals(rows.map { it["name"] }, view.rows.map { it.name })
        rows.zip(view.rows).forEach { (b, r) ->
            val id = b["name"] as String
            assertEquals(id, b["pid"], r.device)
            assertEquals(id, b["abbr"], r.abbreviation)
            assertEquals(id, b["value"], r.value)
            assertEquals(id, b["unit"], r.unit)
            assertEquals(id, b["min"] to b["max"], r.min to r.max)
            assertEquals(id, b["norm"], r.norm)
            assertEquals(id, b["out"], r.outOfNorm)
            assertEquals("$id: a trace where the board draws one", (b["trace"] as List<*>).isNotEmpty(), r.samples.isNotEmpty())
        }
        val footer = board["footer"] as Map<String, Any?>
        assertEquals("the board's «Пауза»: running", listOf("Пауза", "Сбросить мин/макс"), (footer["buttons"] as List<List<Any?>>).map { it[0] })
        assertFalse(view.paused)
        assertEquals((footer["notes"] as List<List<Any?>>).single()[0], view.round)
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun theTabAsksForTheRoundsKeepsTheMinuteAndPausesThem() = runTest {
        val car = FakeCar(round(1, tookMs = 2_100))
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        assertTrue("not shown: one look, no minute", s.model.value.systems.pack.row("Напряжение ВВБ").samples.size <= 1)

        s.setPackLiveVisible(true)
        assertEquals(listOf(ExtraPage.PACK_LIVE to true), car.pageLog)
        assertEquals("обход 2,1 с", s.model.value.systems.pack.round)
        // a tick takes what is new: one round a tick here
        (2L..5L).forEach { n ->
            car.readings = round(n, hub = mapOf(VehicleSignal.PACK_VOLT to 550.0 + n), tookMs = if (n == 5L) 3_000 else 2_100)
            advanceTimeBy(DiagnosticsSession.TICK_MS)
            runCurrent()
        }
        val shown = s.model.value.systems.pack
        assertEquals(5, shown.row("Напряжение ВВБ").samples.size)
        assertEquals("552" to "555", shown.row("Напряжение ВВБ").let { it.min to it.max })
        assertEquals("the last round's length", "обход 3,0 с", shown.round)

        s.resetPackLive()
        assertEquals("555" to "555", s.model.value.systems.pack.row("Напряжение ВВБ").let { it.min to it.max })

        // «Пауза»: the batch's own five seconds back, the table stands
        s.setPackLivePaused(true)
        assertEquals(ExtraPage.PACK_LIVE to false, car.pageLog.last())
        val paused = s.model.value.systems.pack
        assertTrue(paused.paused)
        car.readings = round(6, hub = mapOf(VehicleSignal.PACK_VOLT to 540.0))
        advanceTimeBy(DiagnosticsSession.TICK_MS * 4)
        runCurrent()
        assertEquals(paused, s.model.value.systems.pack)

        // «Продолжить»: the rounds again, the minute goes on
        s.setPackLivePaused(false)
        assertEquals(ExtraPage.PACK_LIVE to true, car.pageLog.last())
        assertEquals("540", s.model.value.systems.pack.row("Напряжение ВВБ").value)
        assertEquals(6, s.model.value.systems.pack.row("Напряжение ВВБ").samples.size)

        // left while paused: the rounds were already given back
        s.setPackLivePaused(true)
        val asked = car.pageLog.size
        s.setPackLiveVisible(false)
        assertEquals(asked, car.pageLog.size)
        // shown again: no pause, the extremes over, the minute kept
        s.setPackLiveVisible(true)
        val again = s.model.value.systems.pack
        assertFalse(again.paused)
        assertEquals(ExtraPage.PACK_LIVE to true, car.pageLog.last())
        assertEquals("540" to "540", again.row("Напряжение ВВБ").let { it.min to it.max })
        assertEquals(7, again.row("Напряжение ВВБ").samples.size)
        s.setPackLiveVisible(false)
        assertEquals(ExtraPage.PACK_LIVE to false, car.pageLog.last())
        s.stop()
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
