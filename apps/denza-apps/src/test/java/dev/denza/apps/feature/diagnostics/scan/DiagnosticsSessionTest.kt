package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagFailure
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.ecu.IdentityBasis
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.row
import dev.denza.apps.feature.diagnostics.scan.Fixtures.rows
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine of architecture §6 on the scripted bus: order, gates, priorities, stops. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsSessionTest {
    @Test
    fun theAutoscanWalksTheCatalogAndAsksOnlyWhatEachModuleNeeds() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        runCurrent()

        // Modules in catalog order, ECM first, each asked once.
        val order = bus.exchanges.map { it.request.address }.distinct()
        assertEquals(EcuCatalog.routed.map { it.request }, order)
        assertEquals(listOf("7E0 22F194", "7E0 190109", "7E0 0101", "7E0 03", "7E0 07", "7E0 0A"), bus.requests.take(6))
        // A stock-route module has no gate; the brake interface's gate is its OBD name.
        assertEquals(listOf("720 190109", "720 190209"), bus.requests.filter { it.startsWith("720 ") })
        assertEquals(listOf("7E5 090A", "7E5 0101", "7E5 03", "7E5 07", "7E5 0A"), bus.requests.filter { it.startsWith("7E5 ") })
        // DCOBC's 19 01 never answers: its list alone, at once.
        assertEquals(listOf("7E4 22F194", "7E4 190209"), bus.requests.filter { it.startsWith("7E4 ") })
        // 63 gates (62 F194 and one 09 0A), 66 counts, 3 lists, 12 OBD reads.
        assertEquals(63, bus.requests.count { it.endsWith(" 22F194") || it.endsWith(" 090A") })
        assertEquals(66, bus.requests.count { it.endsWith(" 190109") })
        assertEquals(listOf("7E4 190209", "720 190209", "747 190209"), bus.requests.filter { it.endsWith(" 190209") })
        assertEquals(144, bus.requests.size)
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        assertEquals(LinkState.OK, s.model.value.header.link)
        s.stop()
    }

    @Test
    fun theCarNowAgreesWithTheAutoscanBoard() = runTest {
        val store = MemoryStore()
        val s = session(carNow(), store = store)
        s.start()
        runCurrent()
        val m = s.model.value

        // Board «Автоскан» (still drawn with 46 modules): 68/68, 0 active, 10 in history, memory 19/68, MIL off.
        assertEquals("68/68", m.scan.answered)
        assertEquals(0, m.scan.active)
        assertEquals(10, m.scan.history)
        assertEquals("19/68", m.scan.memoryRead)
        assertEquals(MilState.OFF, m.scan.mil)
        assertEquals(68, m.scan.done)
        assertEquals(68, m.scan.total)
        assertEquals("13:31", m.scan.finishedAt)
        assertNull(m.scan.notice)

        // Row by row as the board badges them.
        val ok = setOf(0x7E0, 0x7E2, 0x7E3, 0x7E6, 0x7E7, 0x744, 0x745, 0x7E4, 0x782, 0x7E5, 0x783, 0x7F1, 0x712, 0x7F2, 0x722, 0x726, 0x762)
        val history = setOf(0x720, 0x747)
        m.rows().forEach { r ->
            val expected = when {
                r.request == null -> ModuleStatus.NO_ROUTE
                r.request in ok -> ModuleStatus.OK
                r.request in history -> ModuleStatus.HISTORY
                else -> ModuleStatus.UNREAD
            }
            assertEquals(r.abbreviation, expected, r.status)
        }
        assertEquals(49, m.rows().count { it.status == ModuleStatus.UNREAD })
        assertEquals("8", m.row(0x720).history)
        assertEquals("0", m.row(0x720).active)
        assertEquals("2", m.row(0x747).history)
        assertEquals("—", m.row(0x7E5).history)
        assertEquals("0", m.row(0x7E5).active)
        assertEquals("—", m.row(0x760).active)
        assertEquals("OBD-II, UDS", m.row(0x7E0).protocol)
        assertEquals("OBD-II", m.row(0x7E5).protocol)
        assertEquals("7E0 → 7E8", m.row(0x7E0).route)
        assertEquals("ECM · UAES", m.row(0x7E0).unit)
        assertEquals("Контроллер силового домена", m.row(0x743).name)
        assertEquals("9 бл. · прочитано 8 · DTC не читали 1", m.groups.first { it.id == "power" }.summary)
        assertEquals("19 бл. · прочитано 4 · история 2 · DTC не читали 15", m.groups.first { it.id == "body" }.summary)
        // Every block has a role: the list has no empty «Назначение не установлено».
        assertEquals(listOf("power", "chassis", "assist", "body", "salon", "climate", "lv"), m.groups.map { it.id })
        assertEquals(Target.Module(0x744), m.rows().first { it.abbreviation == "SMCU" }.target)
        // DCOBC: the list alone, codes without a format stay raw, and the screen says why.
        val dcobc = m.modules.getValue(0x7E4)
        assertEquals("блок не отвечает на 19 01", dcobc.dtc.asks[0].count)
        assertEquals("0", dcobc.dtc.asks[1].count)
        assertEquals(DataQuality.COMPLETE_EMPTY, dcobc.dtc.quality)
        assertTrue(dcobc.line, dcobc.line.contains("формат DTC не сообщён"))

        // R_ZCU's screen: the two records of the board «Кузов · DTC».
        val body = m.modules.getValue(0x747).dtc
        assertEquals(listOf("C1170-09", "B130E-01"), body.records.map { it.code })
        assertEquals(listOf("Отказ компонента", "Общий электрический отказ"), body.records.map { it.failureName })
        assertEquals("Шасси · код производителя (C1xxx)", body.records[0].group)
        assertEquals(0x28, body.records[0].status)
        assertEquals(0xFF, body.records[0].availability)
        assertFalse(body.outsideMask)
        assertEquals("2", body.asks[0].count)
        assertTrue(m.modules.getValue(0x747).line.startsWith("747 → 74F · UDS · маска доступности FF · формат DTC 00 (SAE J2012)"))
        // ECM: readiness 4/9 as the board «DTC и готовность».
        val ecm = m.modules.getValue(0x7E0).obd!!
        assertEquals("4/9", ecm.readiness)
        assertEquals("41 01 00 07 F5 F4", ecm.raw)
        assertEquals("MIL выключена · DTC, включивших MIL: 0 · зажигание искровое", ecm.milLine)
        assertEquals(listOf("MIS", "FUEL", "CCM", "CAT", "EVAP", "GPF", "O2S", "O2S HTR", "EGR/VVT", "HCAT", "AIR"),
            ecm.monitors.map { it.abbreviation })
        // A zero count proves the memory empty without a list.
        assertEquals("не нужен: счётчик 0", m.modules.getValue(0x7E0).dtc.asks[1].count)
        assertEquals(DataQuality.COMPLETE_EMPTY, m.modules.getValue(0x7E0).dtc.quality)

        // The scan is kept with every module's bytes.
        assertEquals(1, store.saved.size)
        assertEquals(68, store.saved[0].reads.size)
        s.stop()
    }

    @Test
    fun anUpdatedSoftwareCodePassesTheGateAndTheMemoryIsRead() = runTest {
        // FMCU's route answers a code the catalog's snapshot of 2026-10-18 does not have, as after an update.
        val bus = carNow { on(0x7E3, "22F194").answer("62F19448370011E075000101") }
        val s = session(bus)
        s.start()
        runCurrent()

        assertEquals(listOf("7E3 22F194", "7E3 190109"), bus.requests.filter { it.startsWith("7E3 ") })
        val m = s.model.value
        assertEquals(ModuleStatus.OK, m.row(0x7E3).status)
        assertEquals("0", m.row(0x7E3).active)
        assertEquals("19/68", m.scan.memoryRead)
        assertEquals("48370011E075000101", m.modules.getValue(0x7E3).ident.own.first { "F194" in it.key }.value)
        assertTrue(m.groups.none { "не опознан" in it.summary })
        s.stop()
    }

    @Test
    fun aGateAnswerWithoutASoftwareCodeKeepsTheMemoryUnread() = runTest {
        // A zero placeholder where the code should be: the block does not say who it is.
        val bus = carNow { on(0x7E3, "22F194").answer("62F194000000000000000000") }
        val s = session(bus)
        s.start()
        runCurrent()

        assertEquals(listOf("7E3 22F194"), bus.requests.filter { it.startsWith("7E3 ") })
        val m = s.model.value
        assertEquals(ModuleStatus.UNIDENTIFIED, m.row(0x7E3).status)
        assertEquals("—", m.row(0x7E3).active)
        assertEquals("18/68", m.scan.memoryRead)
        assertEquals("9 бл. · прочитано 7 · DTC не читали 1 · не опознан 1", m.groups.first { it.id == "power" }.summary)
        s.stop()
    }

    @Test
    fun aGateWithoutAnAnswerIsNoReplyAndNothingMoreIsAsked() = runTest {
        val bus = carNow { on(0x7E2, "22F194").noReply(3) }
        val s = session(bus)
        s.start()
        runCurrent()
        assertEquals(listOf("7E2 22F194"), bus.requests.filter { it.startsWith("7E2 ") })
        assertEquals(ModuleStatus.NO_REPLY, s.model.value.row(0x7E2).status)
        assertEquals("67/68", s.model.value.scan.answered)
        s.stop()
    }

    @Test
    fun aZeroCountAsksNoList() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        runCurrent()
        val counted = EcuCatalog.routed.filter { it.uds }.map { "%03X".format(it.request) }
        counted.filter { it != "720" && it != "747" && it != "7E4" }.forEach { a ->
            assertFalse(a, bus.requests.contains("$a 190209"))
        }
        s.stop()
    }

    @Test
    fun recordsOutsideTheRequestMaskAreShownAndFlagged() = runTest {
        // Status 50: not tested since clear and this cycle, neither failed nor confirmed: outside 09.
        val bus = carNow {
            on(0x747, "190109").answer("5901FF000001")
            on(0x747, "190209").answer("5902FF51700950")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val dtc = s.model.value.modules.getValue(0x747).dtc
        assertTrue(dtc.outsideMask)
        assertEquals(listOf("C1170-09"), dtc.records.map { it.code })
        assertEquals(0x50, dtc.records[0].status)
        assertEquals(ModuleStatus.OK, s.model.value.row(0x747).status)
        assertEquals("0", s.model.value.row(0x747).history)
        s.stop()
    }

    @Test
    fun notParkedPausesAndResumesOnlyWhenTheHubSeesPAgain() = runTest {
        val bus = carNow { on(0x7E2, "22F194").fail(DiagFailure.NotParked("GUARD_PARK gear 3")) }
        val car = FakeCar()
        val s = session(bus, car = car)
        s.start()
        runCurrent()

        val m = s.model.value
        assertEquals(ScanPhase.PAUSED, m.scan.phase)
        assertEquals(LinkState.NOT_PARKED, m.header.link)
        assertEquals("Только на стоянке: P и 0 км/ч", m.scan.notice)
        assertEquals(1, m.scan.done)
        val asked = bus.requests.size

        // The hub still says P and 0 km/h, as it did when the helper refused: no second try.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(asked, bus.requests.size)
        // The car leaves P, then comes back: the scan goes on from the module it stopped in.
        car.readings = FakeCar.moving()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(asked, bus.requests.size)
        assertEquals(ScanPhase.PAUSED, s.model.value.scan.phase)
        car.readings = FakeCar.parked(sweep = 2)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        assertNull(s.model.value.scan.notice)
        assertEquals(2, bus.requests.count { it == "7E2 22F194" })
        assertEquals(145, bus.requests.size)
        s.stop()
    }

    @Test
    fun aCarTheHubSeesMovingIsNeverAsked() = runTest {
        val bus = carNow()
        val car = FakeCar(FakeCar.moving())
        val s = session(bus, car = car)
        s.start()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(0, bus.requests.size)
        assertEquals(ScanPhase.PAUSED, s.model.value.scan.phase)
        assertEquals(LinkState.NOT_PARKED, s.model.value.header.link)

        car.readings = FakeCar.parked()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(144, bus.requests.size)
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        s.stop()
    }

    @Test
    fun aBusyChannelStopsUntilRescanOrARequestByHand() = runTest {
        val bus = carNow { on(0x782, "22F194").fail(DiagFailure.ChannelBusy("FOREIGN_TRAFFIC")) }
        val s = session(bus)
        s.start()
        runCurrent()
        val m = s.model.value
        assertEquals(ScanPhase.STOPPED, m.scan.phase)
        assertEquals(LinkState.BUSY, m.header.link)
        assertEquals(DiagnosticsSession.BUSY_NOTICE, m.scan.notice)
        val asked = bus.requests.size

        // Nothing on its own: not after a minute.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(asked, bus.requests.size)

        // A button tries the channel once (on the car 2026-10-20 the section stood «Канал занят»
        // under every screen until «Опросить снова»): the read first, then the stopped autoscan
        // goes on from the block it stopped at.
        s.readModule(0x7E0)
        runCurrent()
        assertEquals("7E0 22F194", bus.requests[asked])
        assertEquals(1, bus.requests.drop(asked).count { it == "782 22F194" })
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        assertEquals(LinkState.OK, s.model.value.header.link)
        assertNull(s.model.value.scan.notice)

        val done = bus.requests.size
        s.rescan()
        runCurrent()
        assertEquals("7E0 22F194", bus.requests[done])
        assertEquals(done + 144, bus.requests.size)
        s.stop()
    }

    @Test
    fun aScreenOpenedOnALostChannelTriesItOnceAndMissingAccessWaitsForRescan() = runTest {
        val lost = carNow()
        lost.failNext(DiagFailure.ChannelLost())
        val s = session(lost)
        s.start()
        runCurrent()
        assertEquals(LinkState.LOST, s.model.value.header.link)
        s.setModuleOpen(0x7E3, true)
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        s.stop()

        val denied = carNow()
        denied.failNext(DiagFailure.NoAccess())
        val t = session(denied)
        t.start()
        runCurrent()
        t.setModuleOpen(0x7E3, true)
        t.readModule(0x7E0)
        runCurrent()
        assertEquals(1, denied.exchanges.size)
        assertEquals(LinkState.NO_ACCESS, t.model.value.header.link)
        t.stop()
    }

    @Test
    fun aLostChannelAndMissingAccessStopToo() = runTest {
        for ((failure, link) in listOf(
            DiagFailure.ChannelLost() to LinkState.LOST,
            DiagFailure.NoAccess() to LinkState.NO_ACCESS,
        )) {
            val bus = carNow()
            bus.failNext(failure)
            val s = session(bus)
            s.start()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, bus.exchanges.size)
            assertEquals(link, s.model.value.header.link)
            assertEquals(ScanPhase.STOPPED, s.model.value.scan.phase)
            s.stop()
        }
    }

    @Test
    fun theUserButtonAndAnOpenModuleGoAheadOfTheAutoscan() = runTest {
        val bus = carNow()
        bus.delayMs = 100
        val s = session(bus)
        s.start()
        runCurrent()
        // ECM's gate is on the wire; a button and an opened screen arrive.
        s.readModule(0x762)
        s.setModuleOpen(0x7E3, true)
        advanceTimeBy(30_000)
        runCurrent()

        val r = bus.requests
        // The autoscan finishes the module it is in, then yields: the button, the identification, then VCU.
        assertEquals(listOf("7E0 22F194", "7E0 190109", "7E0 0101", "7E0 03", "7E0 07", "7E0 0A"), r.take(6))
        assertEquals("762 190109", r[6])
        assertEquals(listOf("7E3 22F194", "7E3 22F195", "7E3 22F193", "7E3 22F18A", "7E3 22F197"), r.subList(7, 12))
        assertEquals("7E2 22F194", r[12])
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        // The identification is read once per opening, not again when the scan reaches the module.
        assertEquals(1, r.count { it == "7E3 22F195" })
        assertTrue(0x7E3 in s.model.value.modules)
        s.stop()
    }

    @Test
    fun aFreshStoredScanIsShownWithItsAgeAndNotRepeated() = runTest {
        val first = MemoryStore()
        val a = session(carNow(), store = first)
        a.start()
        runCurrent()
        a.stop()
        val scan = first.saved.single()
        val live = a.model.value.scan

        // Five minutes later the section opens again: the stored scan, no exchange.
        val bus = carNow()
        val b = session(bus, store = MemoryStore(scan), epoch = scan.finishedAtMillis + 5 * 60_000L)
        b.start()
        runCurrent()
        val m = b.model.value
        assertEquals(0, bus.requests.size)
        assertEquals(ScanPhase.DONE, m.scan.phase)
        assertEquals(live.answered, m.scan.answered)
        assertEquals(live.history, m.scan.history)
        assertEquals(live.memoryRead, m.scan.memoryRead)
        assertEquals("13:31", m.scan.finishedAt)
        assertEquals("Показан скан 13:31, 5 мин назад", m.scan.notice)
        assertEquals(ModuleStatus.HISTORY, m.row(0x720).status)
        b.stop()

        // Eleven minutes later it scans again.
        val again = carNow()
        val c = session(again, store = MemoryStore(scan), epoch = scan.finishedAtMillis + 11 * 60_000L)
        c.start()
        runCurrent()
        assertEquals(144, again.requests.size)
        c.stop()
    }

    @Test
    fun theSystemScreensNameTheRearDrivesByAbbreviationAndTheirSignalsBySide() = runTest {
        // The parked snapshot of 2026-10-20 (boards «ВВ-схема», «Электропривод»).
        val car = FakeCar(CarReadings(
            hub = mapOf(VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.PACK_VOLT to 553.0,
                VehicleSignal.MOTOR_FRONT_C to 8.0, VehicleSignal.MOTOR_REAR_LEFT_C to 7.0, VehicleSignal.MOTOR_REAR_RIGHT_C to 7.0,
                VehicleSignal.INVERTER_C to 9.0, VehicleSignal.CELL_MIN_MV to 3333.0, VehicleSignal.CELL_MAX_MV to 3337.0,
                VehicleSignal.ENGINE_RUNNING to 0.0, VehicleSignal.ENGINE_RPM to 0.0),
            sweep = 1,
            extras = mapOf(DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0, DiagnosticExtra.CHARGE_CURRENT to -4.5,
                DiagnosticExtra.INSULATION to 14429.0, DiagnosticExtra.INSULATION_2 to 19675.0, DiagnosticExtra.BUS_FRONT to 555.0,
                DiagnosticExtra.BUS_REAR to 556.0, DiagnosticExtra.IGBT_REAR_LEFT to 5.0, DiagnosticExtra.RPM_REAR_RIGHT to 0.0),
            extrasFresh = true,
        ))
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        val sys = s.model.value.systems

        assertEquals(listOf("FMCU", "SMCU", "SMCU3", "ISG"), sys.drives.columns.map { it.abbreviation })
        assertEquals(listOf(0x7E3, 0x744, 0x745, 0x7E6), sys.drives.columns.map { it.request })
        // SMCU drives the left rear motor, SMCU3 the right: the columns say the side.
        assertEquals(listOf("Передний", "Задний левый", "Задний правый", "Мотор-генератор"), sys.drives.columns.map { it.name })
        // the sides are in each row's source, «Л» and «П», as the board has them: no row of caveats
        assertEquals("1039·46406018 · Л 285001A8 · П 285001B0", sys.drives.rows[0].source)
        assertEquals(listOf("8", "7", "7", "—"), sys.drives.rows[0].cells.map { it.text })
        assertTrue(sys.drives.notes.isEmpty())
        val dtc = sys.drives.rows.first { it.name == "Коды неисправностей" }
        assertEquals(listOf("0", "0", "0", "0"), dtc.cells.map { it.text })
        assertEquals("48370011E086001100", sys.drives.rows.first { it.name == "Номер ПО поставщика" }.cells[1].text)
        val rpm = sys.drives.rows.first { it.name == "Обороты" }
        assertEquals(listOf("н/д", "н/д", "0", "—"), rpm.cells.map { it.text })
        assertTrue(rpm.cells[0].muted)

        val units = sys.hv.units.associateBy { it.abbreviation }
        assertEquals("744", units.getValue("SMCU").address)
        assertEquals("OK · DTC 0", units.getValue("SMCU").state)
        assertEquals("Инвертор задний левый", units.getValue("SMCU").name)
        assertEquals("Инвертор задний правый", units.getValue("SMCU3").name)
        assertEquals("OK · DTC 0", units.getValue("DCOBC").state)
        assertEquals("OK · IPM 9 °C", units.getValue("FMCU").state)
        assertEquals("OK · MIL ВЫКЛ", units.getValue("ECM").state)
        assertEquals(listOf("553 В · ${Figures.MINUS}4,5 А", "74 % · 99 %", "3 333–3 337 мВ", "4 мВ", "н/д…н/д °C", "14,4 · 19,7 МОм"),
            sys.hv.pack.map { it.value })
        assertEquals(listOf("555 В", "556 В"), sys.hv.busVoltages)
        assertEquals("статор 7 °C · IGBT 5 °C · н/д об/мин · н/д Н·м", sys.hv.machines[1].value)
        assertEquals("ДВС не работает", sys.hv.machines[3].key)
        val soh = sys.pack.first { it.name.startsWith("Состояние (SOH)") }
        assertEquals(true, soh.ok)
        val insulation = sys.pack.first { it.name.endsWith("канал BMC") }
        assertEquals("≥ 500 Ом/В (≥ 277 кОм)", insulation.norm)
        assertEquals(true, insulation.ok)
        assertEquals(listOf(0x782, 0x7E5), sys.brakeModules)
        // The four corner radars answered one F194: one band.
        val f194 = sys.adas.rows.first { it.name == "Номер ПО · F194" }.cells
        assertEquals(listOf(false, false, true, true, true, true, false), f194.map { it.shared })
        s.stop()
    }

    @Test
    fun stopReleasesTheCarAndTheBus() = runTest {
        val bus = carNow()
        val car = FakeCar()
        val s = session(bus, car = car)
        s.start()
        runCurrent()
        s.stop()
        assertEquals(1, car.opened)
        assertEquals(1, car.closed)
        assertTrue(bus.closed)
    }

    @Test
    fun everyCatalogGateIsWhatTheAutoscanSends() {
        // The gates of §5.1: F194 where the catalog knows it, 09 0A for the brake interface, none for stock routes.
        EcuCatalog.routed.forEach { e ->
            val gate = ScanRequests.gate(e)
            when (e.identity) {
                IdentityBasis.F194, IdentityBasis.MODEL_CATALOG -> assertEquals(e.abbreviation, "22F194", gate)
                IdentityBasis.OBD_ECU_NAME -> assertEquals("090A", gate)
                else -> assertNull(e.abbreviation, gate)
            }
        }
    }
}
