package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reads of the owner's decision 2026-10-23 (architecture, 4): `59 04`, `59 06`, Mode 02, Mode 06 and the VIN.
 * Real bytes where the car has sent them - the BMS's `19 04 1A 3A 22 01` → `59 04 1A 3A 22 00` of 2026-10-21
 * (`research/vehicle-diagnostics/test_decode_snapshot_record.py`) - and otherwise answers laid out by ISO 14229-1
 * and SAE J1979.
 */
class ReadsCodecTest {
    private fun answer(address: Int, request: String, reply: String) =
        DiagReply.Answer(DiagRequest(address, hexBytes(request)), hexBytes(reply), 1, 1_000)

    private fun <T> ok(d: Decoded<T>): T {
        assertTrue("expected Ok, got $d", d is Decoded.Ok)
        return (d as Decoded.Ok).value
    }

    private fun malformed(d: Decoded<*>) = assertTrue("expected Malformed, got $d", d is Decoded.Malformed)

    // ------------------------------------------------------------------ 59 04

    @Test
    fun theBmsSnapshotOfTheCarIsTheCodeAndItsStatusAndNoRecord() {
        val r = ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A2200")))
        assertEquals(0x1A3A22, r.dtc)
        assertEquals(0x00, r.status.byte)
        assertTrue(r.records.isEmpty())
        assertNull(r.unsplitFrom)
        assertTrue(r.isEmpty)
        // The same short answer to «every record».
        assertTrue(ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A22FF", "59041A3A2208"))).isEmpty)
        assertEquals(DataQuality.COMPLETE_EMPTY,
            Polled.of(answer(0x7E7, "19041A3A22FF", "59041A3A2208")) { DiagDecoder.snapshots(it) }.quality(1_000, 60_000))
    }

    @Test
    fun aRecordWithOneDidIsSplitAndOneWithSeveralStaysRaw() {
        // Record 01: one identifier, DID F190, data 31 32 33 34.
        val one = ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A22080101F19031323334"))).records.single()
        assertEquals(SnapshotRecord(0x01, 1, 0xF190, "31323334"), one)
        // Two identifiers: their lengths are the module's, the bytes stay whole.
        val two = ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A22080102F190313232F1A0FF"))).records.single()
        assertEquals(SnapshotRecord(0x01, 2, null, "F190313232F1A0FF"), two)
        // No identifiers.
        assertEquals(SnapshotRecord(0x01, 0, null, ""), ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A22080100"))).records.single())
    }

    @Test
    fun everyRecordIsSplitOnlyWhereTheAnswerGivesTheBounds() {
        // Two records without identifiers, then one with data: from there the answer stays whole.
        val r = ok(DiagDecoder.snapshots(answer(0x7E7, "19041A3A22FF", "59041A3A22080100020003010102AABB")))
        assertEquals(listOf(SnapshotRecord(1, 0, null, ""), SnapshotRecord(2, 0, null, "")), r.records)
        assertEquals(0x03, r.unsplitFrom)
        assertEquals("03010102AABB", r.unsplit)
        assertFalse(r.isEmpty)
    }

    @Test
    fun aSnapshotAnswerToAnotherCodeOrRecordIsNotOne() {
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A392200")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A22080201F190")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A220801")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A22080101F1")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59041A3A")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19041A3A2201", "59061A3A2200")))
        malformed(DiagDecoder.snapshots(answer(0x7E7, "19061A3A2201", "59061A3A2200")))
        // FMCU and ISG refused 19 03 with 12, the rear drives with 31 (2026-10-21): a refusal, not «no records».
        val refused = Polled.of(answer(0x744, "19041A3A22FF", "7F1931")) { DiagDecoder.snapshots(it) }
        assertTrue(refused is Polled.Unsupported)
        assertEquals(0x31, (refused as Polled.Unsupported).nrc!!.code)
    }

    // ------------------------------------------------------------------ 59 06

    @Test
    fun extendedDataIsTheStatusAloneOrRecordsByNumber() {
        assertTrue(ok(DiagDecoder.extendedData(answer(0x7E7, "19061A3A22FF", "59061A3A2228"))).isEmpty)
        val one = ok(DiagDecoder.extendedData(answer(0x7E7, "19061A3A2201", "59061A3A222801050A")))
        assertEquals(listOf(ExtendedRecord(0x01, "050A")), one.records)
        assertEquals(0x28, one.status.byte)
        // To FF: no length on the wire, the records stay whole from the first.
        val all = ok(DiagDecoder.extendedData(answer(0x7E7, "19061A3A22FF", "59061A3A222801050A0203")))
        assertTrue(all.records.isEmpty())
        assertEquals(0x01, all.unsplitFrom)
        assertEquals("01050A0203", all.unsplit)
        malformed(DiagDecoder.extendedData(answer(0x7E7, "19061A3A2201", "59061A3A222802050A")))
        malformed(DiagDecoder.extendedData(answer(0x7E7, "19061A3A2201", "59041A3A2228")))
    }

    // ------------------------------------------------------------------ Mode 02

    @Test
    fun theFreezeFrameReadsAsMode01DoesWithItsFrameNumber() {
        // The code that stored frame 0, J1979: two bytes as J2012.
        val dtc = ok(DiagDecoder.freezeFrame(answer(0x7E0, "020200", "4202000420"))).single()
        assertEquals(0, dtc.frame)
        assertEquals("P0420", (dtc.reading.value as ObdValue.Text).text)
        assertEquals("0000", ok(DiagDecoder.freezeFrame(answer(0x7E0, "020200", "4202000000"))).single().reading.rawHex)
        // The frame's map, then three PIDs in one answer: rpm 1 250, coolant 91 °C, speed 0.
        val map = ok(DiagDecoder.freezeFrame(answer(0x7E0, "020000", "42000018180000"))).single()
        assertEquals(listOf(0x04, 0x05, 0x0C, 0x0D), (map.reading.value as ObdValue.Supported).pids)
        val values = ok(DiagDecoder.freezeFrame(answer(0x7E0, "020C0005000D00", "420C001388050083")))
        assertEquals(listOf(0x0C, 0x05), values.map { it.reading.pid })
        assertEquals("1250 об/мин", (values[0].reading.value as ObdValue.Numbers).quantities.single().text)
        assertEquals("91 °C", (values[1].reading.value as ObdValue.Numbers).quantities.single().text)
        // PID 56 alone, one byte, as in Mode 01.
        assertEquals(1, (ok(DiagDecoder.freezeFrame(answer(0x7E0, "025600", "42560080"))).single().reading.value as ObdValue.Numbers).quantities.size)
        // A PID of unknown length is read alone, raw; inside several it is not guessed.
        assertEquals(ObdValue.Raw, ok(DiagDecoder.freezeFrame(answer(0x7E0, "02FD00", "42FD00AABB"))).single().reading.value)
        malformed(DiagDecoder.freezeFrame(answer(0x7E0, "020C00FD00", "420C001388FD00AA")))
        // A PID or a frame not asked, a short value, a missing frame byte.
        malformed(DiagDecoder.freezeFrame(answer(0x7E0, "020C00", "420D0000")))
        malformed(DiagDecoder.freezeFrame(answer(0x7E0, "020C00", "420C011388")))
        malformed(DiagDecoder.freezeFrame(answer(0x7E0, "020C00", "420C0013")))
        malformed(DiagDecoder.freezeFrame(answer(0x7E0, "020C00", "420C")))
        assertTrue(Polled.of(answer(0x7E0, "020200", "7F0212")) { DiagDecoder.freezeFrame(it) } is Polled.Unsupported)
    }

    // ------------------------------------------------------------------ Mode 06

    @Test
    fun theTestMapsListTheMonitors() {
        val map = ok(DiagDecoder.obdTests(answer(0x7E0, "0600", "4600C0000001"))) as ObdTestAnswer.Supported
        assertEquals(listOf(0x01, 0x02, 0x20), map.mids)
        assertTrue(ObdTests.isBitmap(0x20))
        malformed(DiagDecoder.obdTests(answer(0x7E0, "0600", "4620C0000001")))
        malformed(DiagDecoder.obdTests(answer(0x7E0, "0600", "4600C000")))
    }

    @Test
    fun aMonitorsTestsScaleByTheirUnitAndScalingId() {
        // MID 01 (O2 B1S1): TID 01, UASID 0B (0.001 V) 0,450 V within 0,000…0,900; TID 05, UASID 10 (1 ms)
        // 120 ms within 0…300.
        val r = ok(DiagDecoder.obdTests(answer(0x7E0, "0601", "4601010B01C200000384" + "01051000780000012C"))) as ObdTestAnswer.Results
        assertEquals(2, r.tests.size)
        val (volts, time) = r.tests
        assertEquals("0,450 В", volts.text(volts.value))
        assertEquals("0,900 В", volts.text(volts.max))
        assertEquals(true, volts.pass)
        assertEquals("120 мс", time.text(time.value))
        assertEquals(true, time.pass)
        assertEquals("Датчик кислорода B1S1", ObdTests.name(0x01))
        assertEquals("Катализатор, ряд 1", ObdTests.name(0x21))
        assertEquals("Пропуски воспламенения, цилиндр 4", ObdTests.name(0xA5))
        assertEquals("MID E5 · производителя", ObdTests.name(0xE5))

        // Signed: UASID 96 (0.1 °C), −5,0 °C below a minimum of 0: fails.
        val cold = ObdTest(0x21, 0x80, 0x96, 0xFFCE, 0x0000, 0x0064)
        assertEquals("-5,0 °C", cold.text(cold.value))
        assertEquals(false, cold.pass)
        // Temperature with its −40 °C offset: UASID 16.
        assertEquals("20,0 °C", ObdTest(0x21, 0x80, 0x16, 600, 0, 0).text(600))
        // Not run since the codes were cleared: all zero, not judged.
        assertNull(ObdTest(0x21, 0x80, 0x0B, 0, 0, 0).pass)
        // An unknown scaling stays the raw word.
        val raw = ObdTest(0x21, 0x80, 0x7E, 1234, 0, 2000)
        assertNull(raw.scale)
        assertEquals("1234", raw.text(raw.value))

        malformed(DiagDecoder.obdTests(answer(0x7E0, "0601", "4602010B01C200000384")))
        malformed(DiagDecoder.obdTests(answer(0x7E0, "0601", "4601010B01C2000003")))
    }

    // ------------------------------------------------------------------ VIN

    @Test
    fun theVinIsSeventeenCharactersByUdsAndByObd() {
        val vin = "LC0C76C40N0000001"
        val ascii = vin.toByteArray().joinToString("") { "%02X".format(it) }
        assertEquals(vin, ok(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190$ascii"))).text)
        assertEquals(vin, (ok(DiagDecoder.vehicleInfo(answer(0x7E0, "0902", "490201$ascii"))) as VehicleInfo.VinNumber).vin.text)
        // Padded in front with NUL to 20 bytes, as some modules send it.
        assertEquals(vin, (ok(DiagDecoder.vehicleInfo(answer(0x7E0, "0902", "490201000000$ascii"))) as VehicleInfo.VinNumber).vin.text)
        // An unwritten field is empty, not a VIN.
        assertTrue(ok(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190" + "00".repeat(17)))).isEmpty)
        assertTrue(ok(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190" + "FF".repeat(17)))).isEmpty)
        // Another length, a byte that does not print, another DID.
        malformed(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190" + ascii.drop(2))))
        malformed(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190" + ascii.dropLast(2) + "07")))
        malformed(DiagDecoder.vin(answer(0x7E2, "22F190", "62F194$ascii")))
        malformed(DiagDecoder.vin(answer(0x7E2, "22F190", "62F190")))
        malformed(DiagDecoder.vehicleInfo(answer(0x7E0, "0902", "490202$ascii")))
        assertTrue(Polled.of(answer(0x7E2, "22F190", "7F2231")) { DiagDecoder.vin(it) } is Polled.Unsupported)
    }
}
