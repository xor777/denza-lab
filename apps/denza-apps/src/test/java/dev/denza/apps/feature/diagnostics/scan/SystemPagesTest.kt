package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.ecu.EcuGroup
import dev.denza.apps.feature.diagnostics.protocol.DtcStatus
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.row
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.vehicle.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The section around the car's systems (architecture, 7.2) on «машина сейчас» and the parked snapshot of the boards `scanner-sys-*`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemPagesTest {
    private val m = Figures.MINUS

    /** The sweep and the GET batch of the parked car, 2026-10-20 19:31, as the boards show it. */
    private val parked = CarReadings(
        hub = mapOf(
            VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.PACK_VOLT to 553.0,
            VehicleSignal.MOTOR_FRONT_C to 8.0, VehicleSignal.MOTOR_REAR_LEFT_C to 7.0, VehicleSignal.MOTOR_REAR_RIGHT_C to 7.0,
            VehicleSignal.INVERTER_C to 9.0, VehicleSignal.CELL_MIN_MV to 3333.0, VehicleSignal.CELL_MAX_MV to 3337.0,
            VehicleSignal.PACK_TEMP_AVG to 14.0, VehicleSignal.ENGINE_RUNNING to 0.0, VehicleSignal.ENGINE_RPM to 0.0,
        ),
        sweep = 1,
        extras = mapOf(
            DiagnosticExtra.SOC to 74.0, DiagnosticExtra.SOH to 99.0, DiagnosticExtra.CHARGE_CURRENT to -4.5,
            DiagnosticExtra.INSULATION to 14429.0, DiagnosticExtra.INSULATION_2 to 19675.0,
            DiagnosticExtra.TEMP_MIN to 13.0, DiagnosticExtra.TEMP_MAX to 18.0,
            DiagnosticExtra.CELL_COUNT to 166.0, DiagnosticExtra.PROBE_COUNT to 14.0,
            DiagnosticExtra.BUS_FRONT to 555.0, DiagnosticExtra.BUS_REAR to 556.0,
            DiagnosticExtra.IGBT_REAR_LEFT to 5.0, DiagnosticExtra.IGBT_REAR_RIGHT to 5.0,
            // The rear-left rotation speed read 0xFFFF parked: refused, «н/д».
            DiagnosticExtra.RPM_FRONT to 0.0, DiagnosticExtra.RPM_REAR_RIGHT to 0.0,
            DiagnosticExtra.TORQUE_FRONT to 0.0, DiagnosticExtra.TORQUE_REAR_LEFT to 0.0, DiagnosticExtra.TORQUE_REAR_RIGHT to 0.0,
            DiagnosticExtra.CURRENT_FRONT to 0.0, DiagnosticExtra.CURRENT_REAR_LEFT to 0.0, DiagnosticExtra.CURRENT_REAR_RIGHT to 0.0,
            DiagnosticExtra.FL_PRESSURE to 2.72, DiagnosticExtra.FR_PRESSURE to 2.60,
            DiagnosticExtra.RL_PRESSURE to 2.70, DiagnosticExtra.RR_PRESSURE to 2.62,
            DiagnosticExtra.FL_TYRE_TEMP to 19.0, DiagnosticExtra.FR_TYRE_TEMP to 8.0,
            DiagnosticExtra.RL_TYRE_TEMP to 17.0, DiagnosticExtra.RR_TYRE_TEMP to 8.0,
            DiagnosticExtra.FL_POSITION to 2.0, DiagnosticExtra.FR_POSITION to 2.0,
            DiagnosticExtra.RL_POSITION to 2.0, DiagnosticExtra.RR_POSITION to 2.0,
            DiagnosticExtra.FL_LOCK to 1.0, DiagnosticExtra.FR_LOCK to 1.0, DiagnosticExtra.RL_LOCK to 1.0, DiagnosticExtra.RR_LOCK to 1.0,
            DiagnosticExtra.FL_WINDOW to 0.0, DiagnosticExtra.FR_WINDOW to 0.0, DiagnosticExtra.RL_WINDOW to 0.0, DiagnosticExtra.RR_WINDOW to 0.0,
            DiagnosticExtra.OUTSIDE_TEMP to 12.0, DiagnosticExtra.CABIN_TEMP to 21.0,
            DiagnosticExtra.PM_OUT to 18.0, DiagnosticExtra.PM_IN to 5.0, DiagnosticExtra.LV to 13.7,
            // the power mode ON, the selector at P, the DC-DC stepping down (the sweep of 2026-10-20)
            DiagnosticExtra.POWER_LEVEL to 2.0, DiagnosticExtra.GEAR_SELECTOR to 1.0, DiagnosticExtra.DCDC_MODE to 2.0,
        ),
        extrasFresh = true,
    )

    private fun TestScope.scanned(readings: CarReadings = parked, bus: dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus = carNow()): DiagnosticsModel {
        val s = session(bus, car = FakeCar(readings))
        s.start()
        runCurrent()
        return s.model.value.also { s.stop() }
    }

    private fun DiagnosticsModel.page(system: CarSystem) = systemPages.getValue(system)

    private fun addresses(system: CarSystem, m: DiagnosticsModel) = m.page(system).blocks.map { Figures.address(it.request!!) }

    @Test
    fun everySystemHoldsTheBlocksOfItsTableInTheAutoscansOrder() = runTest {
        val model = scanned()
        // Every block has a role: «Прочие блоки» has none and is no page.
        assertEquals(CarSystem.entries.toSet() - CarSystem.OTHER, model.systemPages.keys)
        assertEquals(listOf("7E0", "7E3", "7E6", "7E7", "744", "745", "7E4", "766", "762"), addresses(CarSystem.HV, model))
        assertEquals(listOf("7E7"), addresses(CarSystem.PACK, model))
        assertEquals(listOf("7E2", "743", "7E3", "7E6", "744", "745"), addresses(CarSystem.DRIVES, model))
        assertEquals(listOf("7E0"), addresses(CarSystem.ENGINE, model))
        assertEquals(listOf("782", "7E5", "783", "582", "796", "597"), addresses(CarSystem.CHASSIS, model))
        assertEquals(listOf("7F1", "742", "713", "5E0"), addresses(CarSystem.SAFETY, model))
        assertEquals(listOf("712", "7F2", "760", "761", "7C2", "7D4", "701"), addresses(CarSystem.ADAS, model))
        assertEquals(EcuCatalog.inGroup(EcuGroup.BODY).map { it.request }, model.page(CarSystem.BODY).blocks.map { it.request })
        assertEquals(19, model.page(CarSystem.BODY).blocks.size)
        // The cabin without the child-presence radar, which stands in SAFETY.
        assertEquals(EcuCatalog.inGroup(EcuGroup.CABIN).map { it.request } - 0x5E0, model.page(CarSystem.CABIN).blocks.map { it.request })
        assertEquals(18, model.page(CarSystem.CABIN).blocks.size)
        assertEquals(listOf("7B3", "7B4", "766", "590"), addresses(CarSystem.CLIMATE, model))
        assertEquals(listOf("762"), addresses(CarSystem.LV, model))
        // The rows are the autoscan's own.
        val scanRows = model.groups.flatMap { it.rows }.associateBy { it.request }
        model.systemPages.values.flatMap { it.blocks }.forEach { assertEquals(scanRows.getValue(it.request), it) }

        fun tabs(system: CarSystem) = model.page(system).tabs.map { it.label }
        assertEquals(listOf("Состояние", "Ячейки", "Потоковые данные", "Флаги GB/T 32960", "DTC", "Блоки"), tabs(CarSystem.PACK))
        assertEquals(listOf("Состояние", "Потоковые данные", "Сравнение", "DTC", "Блоки"), tabs(CarSystem.DRIVES))
        assertEquals(listOf("Состояние", "Потоковые данные", "DTC и готовность", "Блоки"), tabs(CarSystem.ENGINE))
        assertEquals(listOf("Состояние", "Сравнение", "DTC", "Блоки"), tabs(CarSystem.ADAS))
        assertEquals(listOf("Состояние", "DTC", "Блоки"), tabs(CarSystem.CABIN))
        // «Потоковые данные» after «Состояние» wherever a system has readings to stream (2026-10-21)
        listOf(CarSystem.HV, CarSystem.CHASSIS, CarSystem.SAFETY, CarSystem.BODY, CarSystem.CLIMATE, CarSystem.LV).forEach {
            assertEquals(it.name, listOf("Состояние", "Потоковые данные", "DTC", "Блоки"), tabs(it))
        }
        assertTrue(model.page(CarSystem.CABIN).scheme is Scheme.Cabin)
    }

    @Test
    fun eachSystemCountsTheBlocksOfItsTable() = runTest {
        val model = scanned()
        assertEquals(
            mapOf(CarSystem.HV to 9, CarSystem.PACK to 1, CarSystem.DRIVES to 6, CarSystem.ENGINE to 1, CarSystem.CHASSIS to 6,
                CarSystem.SAFETY to 4, CarSystem.ADAS to 7, CarSystem.BODY to 19, CarSystem.CABIN to 18, CarSystem.CLIMATE to 4,
                CarSystem.LV to 1),
            model.systemPages.mapValues { it.value.blocks.size },
        )
        assertTrue(SystemPagesBuilder.MEMBERS.getValue(CarSystem.OTHER).isEmpty())
        // Every catalogued block stands in at least one system.
        val placed = SystemPagesBuilder.MEMBERS.values.flatten().toSet()
        assertEquals(EcuCatalog.all.mapNotNull { it.request }.toSet(), placed)
    }

    @Test
    fun theRailMarksOnlyTheSystemsWithCodes() = runTest {
        val rail = scanned().rail
        assertEquals(RailItem(null, "Автоскан", "68/68", RailTone.COUNT), rail.first())
        assertEquals(CarSystem.entries - CarSystem.OTHER, rail.drop(1).map { it.system })
        assertEquals((CarSystem.entries - CarSystem.OTHER).map { it.railName }, rail.drop(1).map { it.name })
        // LA_BCM 8 and R_ZCU 2 in history; nothing else holds a code.
        assertEquals(RailItem(CarSystem.BODY, "Кузов", "ИСТ 10", RailTone.HISTORY), rail.first { it.system == CarSystem.BODY })
        rail.drop(1).filter { it.system != CarSystem.BODY }.forEach {
            assertNull(it.name, it.mark)
            assertEquals(it.name, RailTone.NONE, it.tone)
        }
    }

    @Test
    fun anObdOnlyBlocksStoredCodeMarksItsSystemActive() = runTest {
        val model = scanned(bus = carNow { on(0x7E5, "03").answer("43010420") })
        assertEquals(RailItem(CarSystem.CHASSIS, "Шасси", "АКТ 1", RailTone.ACTIVE), model.rail.first { it.system == CarSystem.CHASSIS })
        val row = model.page(CarSystem.CHASSIS).dtc.single()
        assertEquals("BSCM", row.block)
        assertEquals("7E5", row.address)
        assertEquals("P0420", row.code)
        assertEquals("0x08", row.status)
        assertEquals("подтверждённый", row.word)
        assertTrue(model.page(CarSystem.CHASSIS).dtcFootnote.contains("Mode 03 · Mode 07 · Mode 0A"))
    }

    @Test
    fun theBodysCodesAreItsBlocksRecordsBlockByBlock() = runTest {
        val body = scanned().page(CarSystem.BODY)
        assertEquals("· 19 блоков", body.aside)
        assertEquals("LA_BCM 720 · R_ZCU 747 · F_ZCU 722 · B_ZCU 726 · фары 770 771 · UWB-ключ 7F3 и 4 антенны · " +
            "радары дверей 5B2 5B1 5B0 5A7 · HFRM 754 · SEC 7D6 · R-NCM 595 · PDCMB 724", body.line)
        // v4 (7.3): the records' count with its kind's sign, only where there are records
        assertEquals(listOf(HeadCounter("ЗАПИСИ", "10", mark = Mark.HISTORY)), body.counters)

        assertEquals(listOf("C1160-06", "U029D-87", "U0121-87", "B2148-02", "B2250-00", "B225B-00", "U0229-87", "U022A-87", "C1170-09", "B130E-01"),
            body.dtc.map { it.code })
        assertEquals(List(8) { "LA_BCM" } + List(2) { "R_ZCU" }, body.dtc.map { it.block })
        assertEquals(List(8) { "720" } + List(2) { "747" }, body.dtc.map { it.address })
        val first = body.dtc.first()
        assertEquals("06", first.failureType)
        // The protocol's SAE J2012-DA names, as on the block's own screen (the board writes «Отказ алгоритма», «Без подтипа»).
        assertEquals(listOf("Отказ по алгоритму", "Нет сообщения", "Нет сообщения", "Общий отказ сигнала", "Подтип не указан", "Подтип не указан",
            "Нет сообщения", "Нет сообщения", "Отказ компонента", "Общий электрический отказ"), body.dtc.map { it.failureName })
        // No catalog heading for the body: the code's group.
        assertEquals("Шасси · код производителя (C1xxx)", first.description)
        assertEquals("Сеть · общий код SAE (U0xxx)", body.dtc[1].description)
        assertEquals("Нет сообщения", body.dtc[1].failureName)
        // The 2026-10-18 reads with mask FF carried here keep their 0x28; the car's 19 02 09 reported 0x08.
        assertTrue(body.dtc.all { it.status == "0x28" && it.word == "подтверждённый" && !it.active })
        // The body's read memories all hold the pending bit in their availability: read with 0D (architecture, 5.2).
        assertTrue(body.dtcFootnote, body.dtcFootnote.startsWith("19 01 0D · 19 02 0D · снимок 13:31:"))
    }

    @Test
    fun aRepeatedCodeIsARowPerRecordOnTheBlockAndOnItsSystem() = runTest {
        // R_ZCU lists C1170-09 twice (architecture 4): the memory is read, both records stand where they came.
        val model = scanned(bus = carNow {
            on(0x747, "190109").answer("5901FF000003")
            on(0x747, "190209").answer("5902FF51700928930E012851700929")
            on(0x747, "19010D").answer("5901FF000003")
            on(0x747, "19020D").answer("5902FF51700928930E012851700929")
        })
        val block = model.modules.getValue(0x747).dtc
        assertEquals(listOf("C1170-09", "B130E-01", "C1170-09"), block.records.map { it.code })
        assertEquals(listOf(0x28, 0x28, 0x29), block.records.map { it.status })
        assertEquals("3", block.asks[1].count)
        val body = model.page(CarSystem.BODY)
        assertEquals(listOf("C1170-09", "B130E-01", "C1170-09"), body.dtc.filter { it.request == 0x747 }.map { it.code })
        assertEquals(listOf(false, false, true), body.dtc.filter { it.request == 0x747 }.map { it.active })
        assertEquals(listOf(HeadCounter("ЗАПИСИ", "11", on = true, mark = Mark.ACTIVE)), body.counters)
        assertEquals(ModuleStatus.ACTIVE, model.row(0x747).status)
        assertEquals("1" to "2", model.row(0x747).active to model.row(0x747).history)
    }

    @Test
    fun aSystemWithoutAReadMemorySaysSo() = runTest {
        val model = scanned()
        assertEquals("не читали", model.page(CarSystem.CLIMATE).dtcFootnote)
        assertTrue(model.page(CarSystem.CLIMATE).dtc.isEmpty())
        // ECM counted zero: the count alone was asked.
        assertTrue(model.page(CarSystem.ENGINE).dtcFootnote.startsWith("19 01 09 · снимок 13:31:"))
    }

    @Test
    fun statusWordsFollowTheBits() {
        assertEquals("активный, подтверждённый", SystemPagesBuilder.statusWord(DtcStatus(0x09)))
        assertEquals("подтверждённый", SystemPagesBuilder.statusWord(DtcStatus(0x08)))
        assertEquals("активный", SystemPagesBuilder.statusWord(DtcStatus(0x01)))
        assertEquals("ожидающий", SystemPagesBuilder.statusWord(DtcStatus(0x04)))
        assertEquals("1 блок", SystemPagesBuilder.blocksWord(1))
        assertEquals("3 блока", SystemPagesBuilder.blocksWord(3))
        assertEquals("11 блоков", SystemPagesBuilder.blocksWord(11))
        assertEquals("25 блоков", SystemPagesBuilder.blocksWord(25))
    }

    @Test
    fun theHeadsSayWhatTheBoardsSay() = runTest {
        val model = scanned()
        fun counters(system: CarSystem) = model.page(system).counters.map { it.label to it.value }
        val hv = model.page(CarSystem.HV)
        assertEquals("· ВВБ, привод, мотор-генератор", hv.aside)
        assertEquals("BMS 7E7 · FMCU 7E3 · SMCU 744 · SMCU3 745 · ISG 7E6 · ECM 7E0 · DCOBC 7E4 · PTC 766 · LBMS 762", hv.line)
        assertEquals(listOf("ПИТАНИЕ" to "ON", "U ВВБ" to "553 В", "ТОК" to "−4,5 А", "ИЗОЛЯЦИЯ" to "14,4 МОм"), counters(CarSystem.HV))
        // no charge port in the hub here: «н/д», unlit
        assertEquals(Scheme.Hv("н/д", plugged = false), hv.scheme)

        val pack = model.page(CarSystem.PACK)
        assertEquals("· BMS 7E7", pack.aside)
        assertEquals("напряжение и ток 1009 · SOC и SOH 1014 · ячейки и модули 1014 · изоляция 1039", pack.line)
        assertEquals(listOf("SOH" to "99 %", "Δ ЯЧЕЕК" to "4 мВ", "ИЗОЛЯЦИЯ" to "14,4 МОм", "Δ T" to "5 °C"), counters(CarSystem.PACK))

        assertEquals("· 6 блоков", model.page(CarSystem.DRIVES).aside)
        assertEquals("VCU 7E2 · PDC-VCU 743 · FMCU 7E3 · SMCU 744 · SMCU3 745 · ISG 7E6", model.page(CarSystem.DRIVES).line)
        assertEquals(listOf("СЕЛЕКТОР" to "P", "ШИНА ПЕРЕД" to "555 В", "ШИНА ЗАД" to "556 В"), counters(CarSystem.DRIVES))

        assertEquals("· ECM 7E0 · UAES", model.page(CarSystem.ENGINE).aside)
        assertEquals("7E0 → 7E8 · OBD-II (SAE J1979) + UDS · Mode 01", model.page(CarSystem.ENGINE).line)
        assertEquals(listOf("ОБОРОТЫ" to "0", "MIL" to "ВЫКЛ", "ГОТОВН." to "4/9"), counters(CarSystem.ENGINE))

        assertEquals("· 6 блоков", model.page(CarSystem.CHASSIS).aside)
        assertEquals("ESP/IPB 782 · BSCM 7E5 · EPS 783 · RWS 582 796 · IDC 597 · шины: давление 1016, температура 1007",
            model.page(CarSystem.CHASSIS).line)
        assertEquals(listOf("ДАВЛЕНИЕ" to "2,60–2,72 бар"), counters(CarSystem.CHASSIS))

        assertEquals("· 7 блоков", model.page(CarSystem.ADAS).aside)
        assertEquals("ADC 712 · MRR 7F2 · угловые радары 760 761 7C2 7D4 · IPA 701", model.page(CarSystem.ADAS).line)
        // no records: nothing to say
        assertEquals(emptyList<Pair<String, String>>(), counters(CarSystem.ADAS))

        assertEquals("ACC 7B3 · TMIM 7B4 · PTC 766 · AQDM 590 · температуры 1000 · PM2.5 1008", model.page(CarSystem.CLIMATE).line)
        // none of the climate's memories holds a record: no count
        assertEquals(listOf("САЛОН" to "21 °C", "УЛИЦА" to "12 °C"), counters(CarSystem.CLIMATE))

        assertEquals("· LBMS 762", model.page(CarSystem.LV).aside)
        assertEquals("DC-DC в DCOBC 7E4 · АКБ LBMS 762 · напряжение 1001", model.page(CarSystem.LV).line)
        assertEquals(listOf("ПИТАНИЕ" to "ON", "12 В" to "13,7 В"), counters(CarSystem.LV))

        // Without a board: the count, the blocks, the generic counters.
        val safety = model.page(CarSystem.SAFETY)
        assertEquals("· 4 блока", safety.aside)
        assertEquals("SRS 7F1 · MSRZ 742 · MSRF 713 · CPD 5E0", safety.line)
        assertEquals(emptyList<Pair<String, String>>(), counters(CarSystem.SAFETY))
        val cabin = model.page(CarSystem.CABIN)
        assertEquals("· 18 блоков", cabin.aside)
        // every block by what it serves, as the board's head writes it
        assertEquals("приборы 723 · HUD 7B0 · руль 711 725 · ГУ 741 · пасс. ПК 5E7 · сиденья 750 7B5 7D2 · селектор 7A7 · " +
            "холодильник 5A0 5F4 7C6 · WCT 733 · MCPA 5E4 · FRL 772 · AMP 751 · ETC 790", cabin.line)
        assertEquals(cabin.blocks.map { Figures.address(it.request!!) }.toSet(),
            Regex("\\b[0-9A-F]{3}\\b").findAll(cabin.line).map { it.value }.toSet())
        // «Машина сейчас» read none of the cabin's memories: no count, not zero.
        assertEquals(emptyList<Pair<String, String>>(), counters(CarSystem.CABIN))
    }

    @Test
    fun theParkedSnapshotDrawsTheBoardsSchemes() = runTest {
        val model = scanned()

        val pack = model.page(CarSystem.PACK).scheme as Scheme.Pack
        assertEquals(74f, pack.soc)
        assertEquals("74 %", pack.socText)
        assertEquals("99 %", pack.soh)
        assertEquals("166 ячеек · 14 датчиков", pack.cells)
        assertEquals("553 В", pack.volts)
        assertEquals("${m}4,5 А", pack.current)
        assertEquals(listOf(Field("канал BMC", "14,4 МОм"), Field("канал 2", "19,7 МОм")), pack.insulation)
        assertEquals("норма ≥ 500 Ом/В · 0,28 МОм", pack.isoNorm)
        assertEquals(RangeView("ЯЧЕЙКИ, мВ", "3 333–3 337", 3000f..3700f, 3333f..3337f, null, "3 000", "3 700", "разброс 4 мВ"), pack.ranges[0])
        assertEquals(RangeView("ТЕМПЕРАТУРА МОДУЛЕЙ, °C", "13–18", -20f..60f, 13f..18f, 14f, "${m}20", "60", "средняя 14"), pack.ranges[1])

        val drives = model.page(CarSystem.DRIVES).scheme as Scheme.Drives
        assertEquals(Callout("ПЕРЕДНИЙ МОТОР · FMCU 7E3", listOf("статор 8 °C · IGBT 9 °C", "0 об/мин · 0,0 Н·м · 0,0 А")), drives.front)
        // ISG's F195 is read with its screen; until then the software is its F194.
        // Parked, nothing about generation was read: the energy system's state is «н/д».
        assertEquals(Callout("МОТОР-ГЕНЕРАТОР · ISG 7E6", listOf("DTC 0 · ПО 48370011E0EA000000", "ДВС не работает", "энергосистема: н/д")), drives.isg)
        // The rear callouts name their side and the block that drives it: SMCU the left, SMCU3 the right.
        assertEquals(Callout("ЗАДНИЙ ЛЕВЫЙ · SMCU 744", listOf("статор 7 °C · IGBT 5 °C", "н/д об/мин · 0,0 Н·м · 0,0 А")), drives.rearLeft)
        assertEquals(Callout("ЗАДНИЙ ПРАВЫЙ · SMCU3 745", listOf("статор 7 °C · IGBT 5 °C", "0 об/мин · 0,0 Н·м · 0,0 А")), drives.rearRight)
        assertEquals(0x7E2, drives.vcu?.request)
        assertEquals(0x743, drives.powerDomain?.request)
        assertFalse(drives.engineRunning)

        val chassis = model.page(CarSystem.CHASSIS).scheme as Scheme.Chassis
        // the TPMS's judgements are this snapshot's to leave out: «н/д» (SchemeReadingsTest says them)
        val unread = listOf(CabinRow(listOf(CabinRun("давление: н/д")), true), CabinRow(listOf(CabinRun("утечка: н/д")), true))
        assertEquals(listOf(
            Tyre(Corner.FL, "ШИНА ПЛ", "2,72", "19 °C", unread), Tyre(Corner.FR, "ШИНА ПП", "2,60", "8 °C", unread),
            Tyre(Corner.RL, "ШИНА ЗЛ", "2,70", "17 °C", unread), Tyre(Corner.RR, "ШИНА ЗП", "2,62", "8 °C", unread),
        ), chassis.tyres)
        assertEquals(listOf(0x782, 0x783, 0x7E5), listOf(chassis.esp, chassis.eps, chassis.bscm).map { it?.request })
        assertEquals(listOf(0x582, 0x796, 0x597), listOf(chassis.rearSteerLeft, chassis.rearSteerRight, chassis.motion).map { it?.request })

        val body = model.page(CarSystem.BODY).scheme as Scheme.Body
        assertEquals(listOf("ДВЕРЬ ПЛ", "ДВЕРЬ ПП", "ДВЕРЬ ЗЛ", "ДВЕРЬ ЗП"), body.doors.map { it.label })
        assertTrue(body.doors.all { it.state == "закрыта" && it.detail == "не заперта · окно 0 %" && !it.open })
        assertEquals(listOf(0x722, 0x726, 0x720, 0x747, 0x770, 0x771, 0x7F3),
            listOf(body.front, body.back, body.left, body.right, body.lampLeft, body.lampRight, body.uwb).map { it?.request })
        assertEquals(ModuleStatus.HISTORY, body.left?.status)
        assertEquals("8", body.left?.history)
        assertEquals("2", body.right?.history)
        assertEquals(List(4) { ModuleStatus.UNREAD }, body.anchors)
        // The door radars by their route names: FL_SDR 5B2, FR_SDR 5B1, RL_SDR 5B0, RR_SDR 5A7.
        assertEquals(mapOf(Corner.FL to 0x5B2, Corner.FR to 0x5B1, Corner.RL to 0x5B0, Corner.RR to 0x5A7),
            body.doorRadars.mapValues { it.value.request })
        assertEquals(listOf("FL_SDR", "FR_SDR", "RL_SDR", "RR_SDR"), Corner.entries.map { body.doorRadars.getValue(it).abbreviation })
        assertEquals(listOf(0x754, 0x7D6, 0x595, 0x724),
            listOf(body.keyReceiver, body.smartEntry, body.nfc, body.tailgate).map { it?.request })

        val safety = model.page(CarSystem.SAFETY).scheme as Scheme.Safety
        assertEquals(listOf(0x7F1, 0x742, 0x713, 0x5E0),
            listOf(safety.airbags, safety.driverBelt, safety.passengerBelt, safety.childRadar).map { it?.request })
        assertEquals(listOf("SRS", "MSRZ", "MSRF", "CPD"),
            listOf(safety.airbags, safety.driverBelt, safety.passengerBelt, safety.childRadar).map { it?.abbreviation })

        val adas = model.page(CarSystem.ADAS).scheme as Scheme.Adas
        assertEquals(0x7F2, adas.front?.request)
        assertEquals(mapOf(Corner.FL to 0x760, Corner.FR to 0x761, Corner.RR to 0x7C2, Corner.RL to 0x7D4), adas.corners.mapValues { it.value.request })
        assertEquals(0x712, adas.controller?.request)
        assertEquals(0x701, adas.parking?.request)

        val climate = model.page(CarSystem.CLIMATE).scheme as Scheme.Climate
        assertEquals(ClimateZone("УЛИЦА", "12 °C", "18", inside = false), climate.outside)
        assertEquals(ClimateZone("САЛОН", "21 °C", "5", inside = true), climate.cabin)
        assertEquals(listOf(0x7B3, 0x766, 0x7B4, 0x590), listOf(climate.unit, climate.heater, climate.thermal, climate.air).map { it?.request })

        val lv = model.page(CarSystem.LV).scheme as Scheme.Lv
        assertEquals("553 В", lv.hv)
        assertEquals("13,7 В", lv.lv)
        assertEquals(0x7E4, lv.dcdc?.request)
        assertEquals("понижение", lv.dcdcMode)
        assertEquals(0x762, lv.battery?.request)
        assertEquals(listOf(0x722, 0x720, 0x747, 0x726), lv.loads.map { it.request })
    }

    @Test
    fun aMissingReadingIsNotZero() = runTest {
        val gone = setOf(DiagnosticExtra.FL_PRESSURE, DiagnosticExtra.FL_TYRE_TEMP, DiagnosticExtra.LV, DiagnosticExtra.SOC,
            DiagnosticExtra.FR_POSITION, DiagnosticExtra.FR_LOCK, DiagnosticExtra.FR_WINDOW, DiagnosticExtra.PM_OUT, DiagnosticExtra.INSULATION_2)
        val model = scanned(parked.copy(extras = parked.extras - gone, hub = parked.hub - VehicleSignal.CELL_MIN_MV))

        val chassis = model.page(CarSystem.CHASSIS).scheme as Scheme.Chassis
        assertEquals(Tyre(Corner.FL, "ШИНА ПЛ", "н/д", "н/д"), chassis.tyres[0].copy(states = emptyList()))
        assertEquals("2,60–2,70 бар", model.page(CarSystem.CHASSIS).counters[0].value)
        assertEquals("н/д", (model.page(CarSystem.LV).scheme as Scheme.Lv).lv)
        assertEquals("н/д", model.page(CarSystem.LV).counters.single { it.label == "12 В" }.value)
        val pack = model.page(CarSystem.PACK).scheme as Scheme.Pack
        assertNull(pack.soc)
        assertEquals("н/д", pack.socText)
        assertEquals(Field("канал 2", "н/д", muted = true), pack.insulation[1])
        assertEquals("н/д", pack.ranges[0].value)
        assertNull(pack.ranges[0].span)
        assertEquals("разброс н/д мВ", pack.ranges[0].note)
        val door = (model.page(CarSystem.BODY).scheme as Scheme.Body).doors[1]
        assertEquals(Door(Corner.FR, "ДВЕРЬ ПП", "н/д", "замок н/д · окно н/д", open = false), door)
        assertEquals("н/д", (model.page(CarSystem.CLIMATE).scheme as Scheme.Climate).outside.pm)

        // A stale batch counts as none.
        val stale = scanned(parked.copy(extrasFresh = false))
        assertEquals("н/д", (stale.page(CarSystem.LV).scheme as Scheme.Lv).lv)
        assertEquals(List(4) { "н/д" }, (stale.page(CarSystem.CHASSIS).scheme as Scheme.Chassis).tyres.map { it.pressure })

        // Before any stream the engine's points have nothing.
        val engine = model.page(CarSystem.ENGINE).scheme as Scheme.Engine
        assertEquals(listOf("IAT н/д"), engine.points[0].rows)
        assertEquals(listOf("н/д об/мин · LOAD н/д", "УОЗ н/д · ОЖ н/д"), engine.points[3].rows)
        assertEquals(listOf("н/д"), engine.points[6].rows)
    }

    @Test
    fun anInsulationCodeAboveTheStockCapIsNoValueAndNotJudged() = runTest {
        // FFFE on the BMC channel as the firmware gives it, through the batch's own decoding; 19675 on the second.
        val read = DiagnosticExtraReader.decode(
            "@@extra:insulation\nResult: Parcel(00000000 0000fffe   '........')\n" +
                "@@extra:insulation_2\nResult: Parcel(00000000 00004cdb   '........')",
            1, 1,
        ).values
        val model = scanned(parked.copy(extras = parked.extras - DiagnosticExtra.INSULATION + read))

        val pack = model.page(CarSystem.PACK).scheme as Scheme.Pack
        assertEquals(listOf(Field("канал BMC", "н/д", muted = true), Field("канал 2", "19,7 МОм")), pack.insulation)
        assertEquals("н/д · 19,7 МОм", model.systems.hv.pack.single { it.key == "Изоляция" }.value)
        val bmc = model.systems.pack.rows.single { it.name == "Изоляция, канал BMC" }
        assertEquals("н/д", bmc.value)
        assertFalse("a code is not a resistance: not out of norm", bmc.outOfNorm)
        val second = model.systems.pack.rows.single { it.name == "Изоляция, канал 2" }
        assertEquals("19,7", second.value)
        assertEquals("500 Ω/V of 553 V, in MΩ rounded up", "≥ 0,28", second.norm)
        assertFalse(second.outOfNorm)

        // 254 and 255 are not counts.
        val probes = DiagnosticExtraReader.decode("@@extra:probe_count\nResult: Parcel(00000000 000000ff   '........')", 1, 1).values
        val counts = scanned(parked.copy(extras = parked.extras - DiagnosticExtra.PROBE_COUNT + probes))
        assertEquals("166 ячеек · н/д датчиков", (counts.page(CarSystem.PACK).scheme as Scheme.Pack).cells)
        assertEquals("н/д", counts.systems.pack.rows.single { it.name == "Датчиков температуры" }.value)
    }

    @Test
    fun theGeneratorsCalloutSaysTheEnergySystemsGenerationState() = runTest {
        // BYDAutoEnergyDevice: 1 GENERATING, 2 END, 3 ERROR; 0 is INVALID, not «off».
        fun isg(state: Double?): String {
            val hub = if (state == null) parked.hub else parked.hub + (VehicleSignal.GENERATION_STATE to state)
            return (scanned(parked.copy(hub = hub)).page(CarSystem.DRIVES).scheme as Scheme.Drives).isg.rows.last()
        }
        assertEquals("энергосистема: генерирует", isg(1.0))
        assertEquals("энергосистема: завершено", isg(2.0))
        assertEquals("энергосистема: ошибка", isg(3.0))
        assertEquals("энергосистема: н/д", isg(0.0))
        assertEquals("энергосистема: н/д", isg(4.0))
        assertEquals("энергосистема: н/д", isg(null))
        assertEquals(listOf("н/д", "генерирует", "завершено", "ошибка", "н/д", "н/д"),
            listOf(0.0, 1.0, 2.0, 3.0, 31.0, 255.0).map(SystemPagesBuilder::generation))
        assertEquals("н/д", SystemPagesBuilder.generation(null))
        // Polled with the hub's cold sweep, as the energy device reads it.
        assertEquals(1006 to 0x34F0000A, VehicleSignal.GENERATION_STATE.device to VehicleSignal.GENERATION_STATE.fid)
    }

    @Test
    fun anOpenAndOpenedDoor() = runTest {
        val model = scanned(parked.copy(extras = parked.extras + mapOf(DiagnosticExtra.FL_POSITION to 1.0, DiagnosticExtra.RR_POSITION to 3.0,
            DiagnosticExtra.RL_LOCK to 2.0, DiagnosticExtra.FL_WINDOW to 35.0)))
        val doors = (model.page(CarSystem.BODY).scheme as Scheme.Body).doors
        assertEquals(Door(Corner.FL, "ДВЕРЬ ПЛ", "открыта", "не заперта · окно 35 %", open = true), doors[0])
        assertEquals("заперта · окно 0 %", doors[2].detail)
        assertEquals(Door(Corner.RR, "ДВЕРЬ ЗП", "приоткрыта", "не заперта · окно 0 %", open = true), doors[3])
    }

    @Test
    fun theEnginesSchemeRunsOnTheStreamItAsksFor() = runTest {
        // The warm idle of the board «Двигатель · Состояние», byte for byte.
        val idle = Fixtures.ECM_DATA + mapOf(
            0x0F to "40", 0x11 to "10", 0x0B to "26", 0x0C to "1388", 0x04 to "47", 0x0E to "9C", 0x05 to "83",
            0x06 to "84", 0x07 to "7D", 0x03 to "0200", 0x3C to "1590", 0x15 to "88FF",
        )
        val bus = carNow()
        CarNow.scriptEcmIdent(bus)
        Fixtures.ecmAnswers(bus) { idle }
        bus.delayMs = 50
        val s = session(bus, car = FakeCar(parked))
        s.start()
        runCurrent()
        // The scheme on screen asks for the stream as «Потоковые данные» does; the block's own screen is not open.
        s.setLiveVisible(0x7E0, true)
        advanceTimeBy(20_000)
        runCurrent()

        val page = s.model.value.page(CarSystem.ENGINE)
        val engine = page.scheme as Scheme.Engine
        assertEquals(EnginePlace.entries, engine.points.map { it.place })
        assertEquals(listOf(
            EnginePoint(EnginePlace.INLET, "ВОЗДУХ НА ВПУСКЕ", listOf("IAT 24 °C")),
            EnginePoint(EnginePlace.THROTTLE, "ДРОССЕЛЬ", listOf("TPS 6,3 %")),
            EnginePoint(EnginePlace.MANIFOLD, "КОЛЛЕКТОР", listOf("MAP 38 кПа")),
            EnginePoint(EnginePlace.BLOCK_TOP, "ДВС", listOf("1 250 об/мин · LOAD 28 %", "УОЗ 14,0° · ОЖ 91 °C")),
            EnginePoint(EnginePlace.BLOCK_BOTTOM, "ТОПЛИВО", listOf("STFT +3,1 % · LTFT ${m}2,3 %", "петля замкнута (CL)")),
            EnginePoint(EnginePlace.CATALYST, "КАТАЛИЗАТОР", listOf("B1S1 512 °C")),
            EnginePoint(EnginePlace.OXYGEN, "ДАТЧИК O2 B1S2", listOf("0,68 В")),
        ), engine.points)
        assertEquals("1 250", page.counters.first { it.label == "ОБОРОТЫ" }.value)
        assertTrue(page.line, Regex("^7E0 → 7E8 · OBD-II \\(SAE J1979\\) \\+ UDS · Mode 01 · опрос \\d+ Гц$").matches(page.line))
        // The stream is ECM's: no other module asked while it runs (after ECM's scan step, 7 with the VIN, and its identification, 18).
        assertTrue(bus.requests.drop(25).all { it.startsWith("7E0 01") })

        // Hidden, it stops, and the scheme keeps the last values it had.
        s.setLiveVisible(0x7E0, false)
        advanceTimeBy(1_000)
        runCurrent()
        val after = s.model.value.page(CarSystem.ENGINE)
        assertEquals("7E0 → 7E8 · OBD-II (SAE J1979) + UDS · Mode 01", after.line)
        assertEquals(listOf("IAT 24 °C"), (after.scheme as Scheme.Engine).points[0].rows)
        s.stop()
    }

    @Test
    fun beforeAnythingTheRailStandsAndThePagesWait() {
        val clock = ScanClock(java.time.ZoneOffset.UTC)
        val model = ModelBuilder(clock).build(SessionSnapshot(
            link = LinkState.IDLE, phase = ScanPhase.NEVER, done = 0, finishedAtMillis = null, notice = null, polling = null,
            results = emptyMap(), idents = emptyMap(), open = emptySet(), streams = emptyMap(), live = null, streaming = false,
            recording = null, car = CarReadings(), nowMillis = Fixtures.EPOCH,
        ))
        // «Автоскан» and the eleven systems with blocks: «Прочие блоки» is empty.
        assertEquals(12, model.rail.size)
        assertTrue(model.rail.none { it.system == CarSystem.OTHER })
        assertEquals("0/68", model.rail.first().mark)
        assertTrue(model.systemPages.isEmpty())
    }
}
