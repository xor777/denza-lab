package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.vehicle.VehicleTelemetry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticDescriptionsTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"research/vehicle-diagnostics").exists() }
    private fun parcel(word: Int) = "Result: Parcel(00000000 %08x   '........')".format(word)

    @Test fun capturedCodesKeepTheirRawIdentityAndHaveFormat00Rendering() {
        val refs = JSONObject(File(root,"research/vehicle-diagnostics/dtc-reference-20261018.json").readText()).getJSONObject("observed_conversions")
        val capture = JSONObject(File(root,"research/vehicle-diagnostics/dtc-20261018.json").readText()).getJSONArray("entries")
        for (i in 0 until capture.length()) {
            val r = capture.getJSONObject(i)
            val record = DtcRecord(r.getString("dtc_raw"),r.getString("status_raw").toInt(16))
            assertEquals(refs.getString(record.code),DtcDescriptions.describe(record,0).display)
            assertFalse(record.active); assertTrue(record.confirmed)
            assertEquals(r.getString("dtc_raw"),record.code)
        }
    }
    @Test fun generalMeaningNeverBecomesAnOemDefinitionOrAnotherFormatDefinition() {
        val r = DtcRecord("C12187",0x28)
        val generic = DtcDescriptions.describe(r,0)
        assertEquals(listOf("Потеря связи с блоком ABS"),generic.meaning)
        assertEquals("Нет сообщения",generic.subtype)
        assertTrue(generic.source.any { "BYD не проверена" in it })
        for (format in listOf(1,2,3,4,255)) {
            val unknown = DtcDescriptions.describe(r,format)
            assertEquals(r.code,unknown.display)
            assertFalse(unknown.meaning.any { "ABS" in it })
        }
        assertTrue(DtcDescriptions.describe(DtcRecord("C22A87",0x28),0).meaning.any { "не установлено" in it })
    }
    @Test fun replayWarningGetsPreservesUnavailableAndDoesNotMakeItOff() {
        val rows = JSONObject(File(root,"research/vehicle-diagnostics/lamps-20261018.json").readText()).getJSONArray("rows")
        val batch = (0 until rows.length()).joinToString("\n") { i ->
            val row=rows.getJSONObject(i); val lamp=DiagnosticLamp.valueOf(row.getString("key"))
            assertEquals(row.getInt("fid"),lamp.fid)
            "@@${lamp.ordinal}\n${parcel(row.getInt("raw"))}"
        }
        val lamps=DiagnosticSignals.indicators(batch,10)
        assertEquals(7,lamps.values.size); assertTrue(lamps.lit(DiagnosticLamp.ENGINE))
        assertEquals("ВЫКЛ",lamps.label(DiagnosticLamp.ABS))
        for (lamp in listOf(DiagnosticLamp.COOLANT_HIGH,DiagnosticLamp.OIL_PRESSURE,DiagnosticLamp.GPF,DiagnosticLamp.PACK_HEAT,DiagnosticLamp.PACK_FAULT,DiagnosticLamp.POWER_FAULT)) {
            assertEquals("Нет данных",lamps.label(lamp)); assertFalse(lamps.lit(lamp))
        }
        val frame=DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(indicators=lamps,extras=DiagnosticExtras(atElapsed=10)),11)
        assertEquals("1 включено",frame.nodes.getValue("engine_warn").value)
        assertEquals("Нет данных",frame.nodes.getValue("power_warn").value)
        assertTrue(frame.nodes.getValue("power_warn").rows.all { it.second == "Недоступно · 0" })
    }
    @Test fun flashingWarningIsLitAndNeverLostAsUnavailable() {
        for (value in 3..7) {
            val lamps=DiagnosticSignals.indicators("@@${DiagnosticLamp.ABS.ordinal}\n${parcel(value)}",1)
            assertTrue(lamps.lit(DiagnosticLamp.ABS)); assertTrue(lamps.label(DiagnosticLamp.ABS).startsWith("Мигает"))
            val frame=DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(indicators=lamps,extras=DiagnosticExtras(atElapsed=1)),2)
            assertEquals("amber",frame.nodes.getValue("abs").tone)
        }
        for (value in listOf(0,8,-10011,-10013)) assertTrue(DiagnosticSignals.indicators("@@0\n${parcel(value)}",1).values.isEmpty())
    }
    @Test fun selectingUnqualifiedSubsystemNeverReadsBodyEcuInstead() {
        for (node in listOf("engine","engine_warn","pack","power_warn","abs","motor","rearL","rearR","unread")) assertNull(DtcEcu.forNode(node))
        assertEquals(DtcEcu.BODY,DtcEcu.forNode("body"))
        assertEquals(DtcEcu.LBMS,DtcEcu.forNode("lbms")); assertEquals(DtcEcu.LBMS,DtcEcu.forNode("lowv"))
    }
    @Test fun absReferenceOnlyAppearsForTheActualSnapshotAndMatchingFormat() {
        fun notes(snapshot: DtcSnapshot?) = DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(dtc=snapshot)).nodes.getValue("abs").notes
        assertFalse(notes(null).any { "U0121" in it })
        assertFalse(notes(DtcSnapshot(emptyList(),255,0,10)).any { "U0121" in it })
        assertFalse(notes(DtcSnapshot(listOf(DtcRecord("C22987",40)),255,0,10)).any { "U0121" in it })
        assertFalse(notes(DtcSnapshot(listOf(DtcRecord("C12187",40)),255,4,10)).any { "U0121" in it })
        assertTrue(notes(DtcSnapshot(listOf(DtcRecord("C12187",40)),255,0,10)).any { "U0121" in it })
    }
    @Test fun autelReferenceDoesNotInventOemMeaningOrAdditionalCoverage() {
        val r = DtcRecord("C22987",0x28)
        val d = DtcDescriptions.describe(r,0)
        assertEquals("U0229-87",d.display)
        assertEquals(listOf("Потеря связи с блоком","обогрева руля"),d.meaning)
        assertTrue(d.summary.contains("справочно"))
        assertTrue(d.source.any { "Autel" in it })
        assertTrue(d.source.any { "BYD не проверена" in it })
        assertEquals("C22987",r.code)
        assertEquals(2,DtcEcu.entries.size)
        assertNull(DtcEcu.forNode("steering_heat"))
        assertEquals("Не опрошены",DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(
            dtc = DtcSnapshot(listOf(r),255,0,10))).nodes.getValue("unread").value)
    }
    @Test fun eachStatusBitIsIndependentAndUnsupportedDoesNotMeanNo() {
        assertEquals((0..7).map { 1 shl it },DtcStatusLabel.entries.map { it.mask })
        for (bit in DtcStatusLabel.entries) {
            assertEquals("Не поддержан",bit.value(DtcRecord("C12187",255),255 xor bit.mask))
            assertEquals("Да",bit.value(DtcRecord("C12187",bit.mask),255))
            assertEquals("Нет",bit.value(DtcRecord("C12187",0),255))
        }
        val saved = DtcRecord("C12187",0x28)
        assertEquals(listOf("Нет","Нет","Нет","Да","Нет","Да","Нет","Нет"),
            DtcStatusLabel.entries.map { it.value(saved,255) })
    }
    @Test fun lampQualityKeepsInvalidZeroSeparateFromNoReplyAndOff() {
        val lamps = DiagnosticSignals.indicators("@@0\n${parcel(0)}\n@@1\n${parcel(2)}",10)
        assertEquals("Недоступно · 0",lamps.detailLabel(DiagnosticLamp.ENGINE))
        assertEquals("0",lamps.raw(DiagnosticLamp.ENGINE))
        assertEquals("Выключен",lamps.detailLabel(DiagnosticLamp.ABS))
        assertEquals("Нет ответа",lamps.detailLabel(DiagnosticLamp.ESP))
        assertFalse(lamps.values.containsKey(DiagnosticLamp.ENGINE))
        assertFalse(lamps.lit(DiagnosticLamp.ENGINE))
    }
    @Test fun cachedIdentityAndRoutesAreDatedCandidatesNeverReadTargets() {
        val inventory = JSONObject(File(root,"research/vehicle-diagnostics/ecu-inventory-cache-20261018.json").readText()).getJSONObject("counts")
        assertEquals("${inventory.getInt("cachedRecords")} записей",DiagnosticEvidence.inventory.value)
        assertEquals((inventory.getInt("allFieldsNegativeMarker")+inventory.getInt("allFieldsDefaultZero")).toString(),DiagnosticEvidence.inventory.rows[1].second)
        val map = JSONObject(File(root,"research/vehicle-diagnostics/ota-ecu-prefix-20261018.json").readText()).getJSONArray("responses").getJSONObject(0)
        assertEquals(map.getInt("completeObjects"),DiagnosticEvidence.routes.rows.size)
        assertTrue(DiagnosticEvidence.identity.rows.any { it.first == "Точное имя" && it.second == "Не доказано" })
        assertEquals(setOf("F195 / F194","C295 / C294","C495 / C494"),DiagnosticEvidence.identity.rows.drop(1).take(3).map { it.second }.toSet())
        for (node in listOf("ecu_identity","ecu_inventory","ecu_routes")) assertNull(DtcEcu.forNode(node))
    }
}
