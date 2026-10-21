package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTC memory against the bytes the car returned (research/vehicle-diagnostics, captures of 2026-10-18/20). */
class DtcCodecTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }
    private fun research(name: String) = JSONObject(File(root, "research/vehicle-diagnostics/$name").readText())

    private fun answer(address: Int, request: String, reply: String) =
        DiagReply.Answer(DiagRequest(address, hexBytes(request)), hexBytes(reply), 1, 1_000)

    private fun <T> ok(d: Decoded<T>): T {
        assertTrue("expected Ok, got $d", d is Decoded.Ok)
        return (d as Decoded.Ok).value
    }

    private fun malformed(d: Decoded<*>) = assertTrue("expected Malformed, got $d", d is Decoded.Malformed)

    @Test
    fun ecmMaskFfCountIs258AndNotAFaultCount() {
        // Run 1, 7E0 19 01 FF.
        val c = ok(DiagDecoder.dtcCount(answer(0x7E0, "1901FF", "5901FF000102")))
        assertEquals(DtcStatus(0xFF), c.availability)
        assertEquals(DtcFormat.SAE_J2012_00, c.format)
        assertEquals(258, c.count)
        // A count alone is not a memory.
        malformed(DtcCodec.emptyMemory(0xFF, c))
    }

    @Test
    fun rightZoneHoldsTwoHistoricalRecordsInJ2012Form() {
        val memory = ok(DiagDecoder.dtcMemory(
            answer(0x747, "1901FF", "5901FF000002"),
            answer(0x747, "1902FF", "5902FF51700928930E0128"),
        ))
        assertEquals(listOf("C1170-09", "B130E-01"), memory.records.map { it.code.display })
        assertEquals(listOf("517009", "930E01"), memory.records.map { it.code.rawHex })
        assertEquals(listOf("C1170", "B130E"), memory.records.map { it.code.base })
        assertEquals("отказ компонента", memory.records[0].code.failureTypeName)
        assertEquals("общий электрический отказ", memory.records[1].code.failureTypeName)
        memory.records.forEach { r ->
            assertEquals(listOf(StatusBit.CDTC, StatusBit.TFSLC), r.status.bits)
            assertFalse(StatusBit.TF in r.status)
        }
        // The decoded run in research says the same.
        val run1 = research("obd-20261020.json").getJSONArray("run1")
        val row = (0 until run1.length()).map { run1.getJSONObject(it) }.first { it.getString("request") == "0747" && it.getString("uds") == "1902FF" }
        val records = row.getJSONArray("records")
        assertEquals((0 until records.length()).map { records.getJSONObject(it).getString("code") }, memory.records.map { it.code.display })
        assertEquals(DataQuality.COMPLETE, Polled.Got(memory, 1_000).quality(1_000, 60_000))
    }

    @Test
    fun leftBodyEightRecordsMatchTheResearchSnapshot() {
        val json = research("dtc-20261018.json")
        val frames = json.getJSONObject("frames")
        val memory = ok(DiagDecoder.dtcMemory(
            answer(0x720, "1901FF", frames.getJSONObject("count").getString("uds_hex")),
            answer(0x720, "1902FF", frames.getJSONObject("list").getString("uds_hex")),
        ))
        val entries = json.getJSONArray("entries")
        assertEquals(json.getInt("read_count"), memory.records.size)
        assertEquals(8, memory.records.size)
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            assertEquals(e.getString("dtc_raw"), memory.records[i].code.rawHex)
            assertEquals(e.getString("status_raw"), memory.records[i].status.hex)
        }
        assertEquals(
            listOf("C1160-06", "U029D-87", "U0121-87", "B2148-02", "B2250-00", "B225B-00", "U0229-87", "U022A-87"),
            memory.records.map { it.code.display },
        )
        // dtc-reference-20261018.json holds the same conversions.
        val reference = research("dtc-reference-20261018.json").getJSONObject("observed_conversions")
        memory.records.forEach { assertEquals(reference.getString(it.code.rawHex), it.code.display) }
        assertEquals(json.getInt("confirmed_count"), memory.records.count { StatusBit.CDTC in it.status })
        assertEquals(json.getInt("test_failed_count"), memory.records.count { StatusBit.TF in it.status })
        assertEquals(json.getInt("pending_count"), memory.records.count { StatusBit.PDTC in it.status })
        assertEquals(json.getInt("warning_requested_count"), memory.records.count { StatusBit.WIR in it.status })
        val ftb = memory.records.map { it.code.failureTypeName }
        assertEquals(listOf("отказ по алгоритму", "нет сообщения", "нет сообщения", "общий отказ сигнала", "подтип не указан"), ftb.take(5))
    }

    @Test
    fun lbmsSupportsOnlyCurrentAndConfirmedAndHoldsNothing() {
        val json = research("lbms-dtc-20261018.json")
        val memory = ok(DiagDecoder.dtcMemory(
            answer(0x762, "190109", json.getString("count_payload")),
            answer(0x762, "190209", json.getString("list_payload")),
        ))
        assertTrue(memory.isEmpty)
        assertEquals(DtcStatus(0x09), memory.availability)
        assertEquals(json.getString("status_availability_mask"), memory.availability.hex)
        val any = DtcStatus(0)
        assertEquals(BitState.CLEAR, any.state(StatusBit.TF, memory.availability))
        assertEquals(BitState.CLEAR, any.state(StatusBit.CDTC, memory.availability))
        assertEquals(BitState.UNSUPPORTED, any.state(StatusBit.PDTC, memory.availability))
        assertEquals(BitState.UNSUPPORTED, any.state(StatusBit.WIR, memory.availability))
        val got = Polled.Got(memory, 1_000)
        assertEquals(DataQuality.COMPLETE_EMPTY, got.quality(2_000, 60_000))
        assertTrue(got.quality(2_000, 60_000).provesNone)
        assertEquals(DataQuality.STALE, got.quality(70_000, 60_000))
    }

    @Test
    fun adsFormat01IsSixHexDigitsNeverALetter() {
        // remaining-ecus dtc run, 712: 59 01 09 01 0000 then 59 02 09.
        val memory = ok(DiagDecoder.dtcMemory(answer(0x712, "190109", "590109010000"), answer(0x712, "190209", "590209")))
        assertEquals(DtcFormat.ISO_14229_1, memory.format)
        assertTrue(memory.isEmpty)
        val code = DtcCode(0x517009, DtcFormat.ISO_14229_1)
        assertEquals("517009", code.display)
        assertEquals(null, code.base)
        assertEquals(null, code.failureTypeName)
        val raw = DtcCode(0xC12187, DtcFormat.of(0x02))
        assertEquals("C12187", raw.display)
        val unknown = DtcCode(0xC12187, DtcFormat.of(0x09), 0x09)
        assertEquals(DtcFormat.OTHER, unknown.format)
        assertEquals("C12187", unknown.display)
    }

    @Test
    fun everyChassisZeroReadIsCompleteAndEmpty() {
        val rows = research("remaining-ecus-20261020.json").getJSONArray("runs").let { runs ->
            (0 until runs.length()).map { runs.getJSONObject(it) }.first { it.getString("name") == "dtc" }.getJSONArray("rows")
        }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val byEcu = rows.filter { it.getString("uds").startsWith("19") }.groupBy { it.getString("request") }
        assertEquals(setOf("0782", "0783", "07F1", "0712", "07F2"), byEcu.keys)
        byEcu.forEach { (request, pair) ->
            val address = request.toInt(16)
            val count = pair.first { it.getString("uds") == "190109" }
            val list = pair.first { it.getString("uds") == "190209" }
            val memory = ok(DiagDecoder.dtcMemory(
                answer(address, "190109", count.getString("payload")),
                answer(address, "190209", list.getString("payload")),
            ))
            assertTrue(request, memory.isEmpty)
            assertEquals(request, count.getString("mask"), memory.availability.hex)
            assertEquals(request, count.getInt("format"), memory.format.id)
        }
    }

    @Test
    fun countAndListThatDisagreeAreNeverZeroCodes() {
        // Count says 2, list holds 1.
        malformed(DiagDecoder.dtcMemory(answer(0x747, "1901FF", "5901FF000002"), answer(0x747, "1902FF", "5902FF51700928")))
        // Count says 0, list holds 1.
        malformed(DiagDecoder.dtcMemory(answer(0x747, "1901FF", "5901FF000000"), answer(0x747, "1902FF", "5902FF51700928")))
        // Status bit 0x20 outside availability 09.
        malformed(DiagDecoder.dtcMemory(answer(0x762, "190109", "590109000001"), answer(0x762, "190209", "59020951700928")))
        // Availability differs between the two answers.
        malformed(DiagDecoder.dtcMemory(answer(0x762, "190109", "590109000000"), answer(0x762, "190209", "5902FF")))
        // A list of a torn length.
        malformed(DiagDecoder.dtcList(answer(0x747, "1902FF", "5902FF517009")))
        // Count and list of different masks or ECUs.
        malformed(DiagDecoder.dtcMemory(answer(0x747, "190109", "590109000000"), answer(0x747, "1902FF", "5902FF")))
        malformed(DiagDecoder.dtcMemory(answer(0x747, "190109", "590109000000"), answer(0x726, "190209", "590209")))
        // An inconsistent answer is a quality of its own, never «кодов нет».
        val polled = Polled.of(answer(0x747, "1902FF", "5902FF517009")) { DiagDecoder.dtcList(it) }
        assertEquals(DataQuality.INCONSISTENT, polled.quality(1_000, 60_000))
    }

    @Test
    fun aRepeatedCodeIsARecordPerOccurrenceInTheListsOrder() {
        // Two records of one code with their own status bytes: both kept, in order, nothing merged.
        val memory = ok(DiagDecoder.dtcMemory(
            answer(0x747, "1901FF", "5901FF000003"), answer(0x747, "1902FF", "5902FF51700928930E01285170092C"),
        ))
        assertEquals(listOf("C1170-09", "B130E-01", "C1170-09"), memory.records.map { it.code.display })
        assertEquals(listOf("28", "28", "2C"), memory.records.map { it.status.hex })
        assertEquals(DataQuality.COMPLETE, Polled.Got(memory, 1_000).quality(1_000, 60_000))
        // The list alone (DCOBC's way) keeps them too.
        val alone = ok(DiagDecoder.dtcMemoryFromList(answer(0x7E4, "190209", "590209C1D48709C1D48709"), null))
        assertEquals(listOf("C1D487", "C1D487"), alone.records.map { it.code.rawHex })
    }

    @Test
    fun isgsCompleteRegistryAsAListKeepsAll57RecordsAndTheRepeat() {
        // ISG 7E6's live `19 0A` of 2026-10-21: 57 records, 56 distinct codes, C1D487 at 28 and 40. The
        // product reads `19 02`; the same records as a list of mask 09 are one memory of 57 records.
        val isg = research("isg-supported-dictionary-candidates-20261021.json")
        val payload = isg.getJSONObject("outerResponse").getString("payloadHex")
        assertTrue(payload.startsWith("590A09"))
        val memory = ok(DiagDecoder.dtcMemory(
            answer(0x7E6, "190109", "590109010039"), answer(0x7E6, "190209", "590209" + payload.substring(6)),
        ))
        val records = isg.getJSONObject("rawRegistryObservation").getJSONArray("records")
        assertEquals(57, memory.records.size)
        assertEquals((0 until records.length()).map { records.getJSONObject(it).getString("raw24Hex") }, memory.records.map { it.code.rawHex })
        assertEquals(56, memory.records.map { it.code.raw }.toSet().size)
        assertEquals(listOf(27, 39), memory.records.indices.filter { memory.records[it].code.rawHex == "C1D487" })
        assertTrue(memory.records.all { it.status.byte == 0 })
        // ISG's format 01 laid out as J2012: both occurrences read U01D4-87.
        assertEquals(listOf("U01D4-87", "U01D4-87"), listOf(27, 39).map { memory.records[it].code.display })
    }

    @Test
    fun ecmMaskFfListIsRefusedWithNrc13() {
        val polled = Polled.of(answer(0x7E0, "1902FF", "7F1913")) { DiagDecoder.dtcList(it) }
        assertTrue(polled is Polled.Denied)
        assertEquals("incorrectMessageLengthOrInvalidFormat", (polled as Polled.Denied).nrc.name)
        assertEquals(DataQuality.DENIED, polled.quality(1_000, 60_000))
    }

    @Test
    fun j2012LettersCoverAllFourSystems() {
        assertEquals("P0420", J2012.base(0x0420))
        assertEquals("C1170", J2012.base(0x5170))
        assertEquals("B130E", J2012.base(0x930E))
        assertEquals("U0121", J2012.base(0xC121))
        assertEquals("U3FFF", J2012.base(0xFFFF))
        listOf(0x00, 0x01, 0x02, 0x06, 0x09, 0x87).forEach { assertTrue("%02X".format(it), FailureTypes.name(it) != null) }
        assertEquals(null, FailureTypes.name(0xFE))
    }

    @Test
    fun formatZeroOneIsLaidOutAsJ2012AtFmcuAndIsg() {
        // FMCU 7E3 (architecture 4): `1B0019` → P1B00-19, its failure type named; `C1D487` → U01D4-87.
        val fmcu = (DiagDecoder.dtcMemory(answer(0x7E3, "190109", "590109010002"), answer(0x7E3, "190209", "5902091B001909C1D48709")) as Decoded.Ok).value
        assertEquals(listOf("P1B00-19", "U01D4-87"), fmcu.records.map { it.code.display })
        assertEquals(listOf(0x19, 0x87), fmcu.records.map { it.code.failureType })
        // ISG 7E6 the same way since 2026-10-21 (owner): `180219` → P1802-19, `2B1E01` → P2B1E-01.
        val isg = (DiagDecoder.dtcMemory(answer(0x7E6, "190109", "590109010002"), answer(0x7E6, "190209", "59020918021909" + "2B1E0108")) as Decoded.Ok).value
        assertEquals(listOf("P1802-19", "P2B1E-01"), isg.records.map { it.code.display })
        assertEquals(listOf(0x19, 0x01), isg.records.map { it.code.failureType })
        assertEquals(listOf("180219", "2B1E01"), isg.records.map { it.code.rawHex })
        assertEquals(setOf(0x7E3, 0x7E6), J2012.FORMAT_01_LAID_OUT)
        // Another module's format 01 keeps its six digits: no registry has established its layout.
        val ads = (DiagDecoder.dtcMemory(answer(0x712, "190109", "590109010001"), answer(0x712, "190209", "59020918021909")) as Decoded.Ok).value
        assertEquals("180219", ads.records.single().code.display)
        assertEquals(null, ads.records.single().code.failureType)
    }

    @Test
    fun statusBitsAreEightIndependentFlags() {
        assertEquals(listOf("TF", "TFTOC", "PDTC", "CDTC", "TNCSLC", "TFSLC", "TNCTOC", "WIR"), StatusBit.entries.map { it.short })
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128), StatusBit.entries.map { it.mask })
        assertEquals(StatusBit.entries.toList(), DtcStatus(0xFF).bits)
        assertEquals(listOf(StatusBit.TF, StatusBit.WIR), DtcStatus(0x81).bits)
        assertEquals("отказ сейчас", StatusBit.TF.label)
        assertEquals("подтверждённый", StatusBit.CDTC.label)
        assertEquals("запрос лампы", StatusBit.WIR.label)
    }
}
