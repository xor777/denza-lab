package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.row
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.diagnostics.ui.ScannerWords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reads of the owner's decision 2026-10-23 on the scripted bus (architecture, 5.2, 6): the memory mask `0D`
 * and its fallback to `09`, pending records, the VIN, an opened code's `19 04` / `19 06`, ECM's Mode 02 and 06.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadsSessionTest {
    private val vin = "LC0C76C40N0000001"
    private fun ascii(text: String) = text.toByteArray().joinToString("") { "%02X".format(it) }

    // ------------------------------------------------------------------ 0D and its fallback

    @Test
    fun aBlockThatLeavesZeroDUnansweredIsReadWithNineAndNotAskedAgain() = runTest {
        // ESP 782 lets 0D time out; LA_BCM 720 counts with 0D and leaves its list unanswered.
        val store = MemoryStore()
        val bus = carNow {
            on(0x782, "19010D").noReply(4)
            on(0x720, "19020D").noReply(3)
        }
        val s = session(bus, store = store)
        s.start()
        runCurrent()

        assertEquals(listOf("782 22F194", "782 190109", "782 19010D"), bus.requests.filter { it.startsWith("782 ") })
        // The count of 09 came first (the probe) and said zero: no list.
        assertEquals(ModuleStatus.OK, s.model.value.row(0x782).status)
        assertEquals("19 01 09", s.model.value.modules.getValue(0x782).dtc.asks[0].service)
        // 720: 0D counted, its list did not come; the read goes on with 09, the eight records stand.
        assertEquals(listOf("720 22F194", "720 190109", "720 19010D", "720 19020D", "720 190209"), bus.requests.filter { it.startsWith("720 ") })
        assertEquals("8", s.model.value.row(0x720).history)
        assertEquals(ModuleStatus.HISTORY, s.model.value.row(0x720).status)
        assertEquals(setOf(0x720, 0x782), store.saved.single().pendingMaskRefused)

        // «Опросить снова»: both at once with 09; the others with 0D, without the probe.
        val before = bus.requests.size
        s.rescan()
        runCurrent()
        val again = bus.requests.drop(before)
        assertEquals(listOf("782 22F194", "782 190109"), again.filter { it.startsWith("782 ") })
        assertEquals(listOf("720 22F194", "720 190109", "720 190209"), again.filter { it.startsWith("720 ") })
        assertEquals(listOf("747 22F194", "747 19010D", "747 19020D"), again.filter { it.startsWith("747 ") })
        s.stop()

        // The next opening reads last-scan.json - through its file form - and remembers the same.
        val text = ScanJson.encode(store.scan!!)
        val decoded = ScanJson.decode(text)!!
        assertEquals(setOf(0x720, 0x782), decoded.pendingMaskRefused)
        val next = carNow()
        val t = session(next, store = MemoryStore(decoded), epoch = decoded.finishedAtMillis + 11 * 60_000L)
        t.start()
        runCurrent()
        assertEquals(listOf("782 22F194", "782 190109"), next.requests.filter { it.startsWith("782 ") })
        assertEquals(listOf("720 22F194", "720 190109", "720 190209"), next.requests.filter { it.startsWith("720 ") })
        assertEquals(listOf("726 22F194", "726 19010D"), next.requests.filter { it.startsWith("726 ") })
        t.stop()
    }

    @Test
    fun aBusyGatewayIsNoReasonToGiveUpZeroD() = runTest {
        val store = MemoryStore()
        val bus = carNow { on(0x782, "19010D").busy() }
        val s = session(bus, store = store)
        s.start()
        runCurrent()
        assertTrue(store.saved.single().pendingMaskRefused.isEmpty())
        s.stop()
    }

    @Test
    fun ecmAndVcuStayOnNineWhateverTheirAvailability() = runTest {
        val bus = carNow()
        val s = session(bus)
        s.start()
        runCurrent()
        // Availability FF at both (2026-10-20), and 19 01 04 unanswered for 4 s there: never 0D.
        assertFalse(bus.requests.any { it == "7E0 19010D" || it == "7E2 19010D" })
        assertFalse(bus.requests.any { it == "7E4 19020D" || it == "7E4 19010D" })
        s.stop()
    }

    // ------------------------------------------------------------------ pending

    @Test
    fun aPendingRecordMakesTheBlockPendingAndIsCountedApart() = runTest {
        // R_ZCU with 0D: C1170-09 confirmed (28), B130E-01 pending (24: pending, failed since clear).
        val bus = carNow {
            on(0x747, "19010D").answer("5901FF000002")
            on(0x747, "19020D").answer("5902FF51700928930E0124")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val m = s.model.value
        val row = m.row(0x747)
        assertEquals(ModuleStatus.PENDING, row.status)
        assertEquals(Triple("0", "1", "1"), Triple(row.active, row.pending, row.history))
        assertEquals(1, m.scan.pending)
        assertEquals(8 + 1, m.scan.history)
        val dtc = m.modules.getValue(0x747).dtc
        assertEquals("19 01 0D", dtc.asks[0].service)
        assertEquals(listOf("Ожидающие", "19 02 0D", "1"), dtc.asks[2].let { listOf(it.name, it.service, it.count) })
        assertTrue(m.groups.first { it.id == "body" }.summary.contains("ожидающие 1"))
        // v4 draws it with the pending sign ◌, its records counted together (7.3)
        assertEquals(dev.denza.apps.feature.diagnostics.scan.Mark.PENDING, ScannerWords.mark(row.status))
        assertEquals(2, ScannerWords.records(row))
        assertEquals(dev.denza.apps.feature.diagnostics.scan.MarkCount(dev.denza.apps.feature.diagnostics.scan.Mark.PENDING, 2), ScannerWords.tileRight(row).mark)
        s.stop()
    }

    @Test
    fun activeOutranksPendingAndPendingOutranksHistory() = runTest {
        val bus = carNow {
            on(0x747, "19010D").answer("5901FF000002")
            on(0x747, "19020D").answer("5902FF51700929930E0124")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val row = s.model.value.row(0x747)
        assertEquals(ModuleStatus.ACTIVE, row.status)
        assertEquals(Triple("1", "1", "0"), Triple(row.active, row.pending, row.history))
        s.stop()
    }

    @Test
    fun anObdBlocksPendingAreModeSevensCodesTheMemoryDoesNotHold() = runTest {
        // ECM: P0420 pending by Mode 07; the brake interface (OBD only): one code in Mode 07.
        val bus = carNow {
            on(0x7E0, "07").answer("47010420")
            on(0x7E5, "07").answer("47010171")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val m = s.model.value
        assertEquals(ModuleStatus.PENDING, m.row(0x7E0).status)
        assertEquals("1", m.row(0x7E0).pending)
        assertEquals(ModuleStatus.PENDING, m.row(0x7E5).status)
        assertEquals("1", m.row(0x7E5).pending)
        assertEquals(2, m.scan.pending)
        s.stop()
    }

    // ------------------------------------------------------------------ the VIN

    @Test
    fun theVinIsReadAtVcuAndEcmAndTheyAgree() = runTest {
        val bus = carNow {
            on(0x7E2, "22F190").answer("62F190" + ascii(vin))
            on(0x7E0, "0902").answer("490201" + ascii(vin))
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val v = s.model.value.scan.vin!!
        assertEquals(vin, v.vin)
        assertFalse(v.mismatch)
        assertEquals(listOf("VCU 7E2" to "22 F190", "ECM 7E0" to "09 02"), v.sources.map { it.module to it.service })
        assertTrue(v.sources.all { it.outcome == ReadOutcome.READ && it.vin == vin })
        assertEquals("49 02 01 " + ascii(vin).chunked(2).joinToString(" "), v.sources[1].exchange!!.answer)
        assertEquals("7E0 → 7E8", v.sources[1].exchange!!.route)
        // After the memory and the OBD lists, one exchange each.
        assertEquals("7E2 22F190", bus.requests.filter { it.startsWith("7E2 ") }.last())
        assertEquals("7E0 0902", bus.requests.filter { it.startsWith("7E0 ") }.last())
        s.stop()
    }

    @Test
    fun twoDifferentVinsAreAMismatchAndASilentSourceIsSaid() = runTest {
        val other = "LC0C76C40N0000002"
        val bus = carNow {
            on(0x7E2, "22F190").answer("62F190" + ascii(vin))
            on(0x7E0, "0902").answer("490201" + ascii(other))
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val v = s.model.value.scan.vin!!
        assertTrue(v.mismatch)
        assertEquals(vin, v.vin)

        // Unanswered, refused, unwritten: no VIN, and no mismatch to claim.
        val quiet = carNow {
            on(0x7E2, "22F190").answer("7F2231")
            on(0x7E0, "0902").answer("490201" + "00".repeat(17))
        }
        val t = session(quiet)
        t.start()
        runCurrent()
        val q = t.model.value.scan.vin!!
        assertNull(q.vin)
        assertFalse(q.mismatch)
        assertEquals(listOf(ReadOutcome.REFUSED, ReadOutcome.EMPTY), q.sources.map { it.outcome })
        assertEquals("NRC 31 · вне диапазона", q.sources[0].why)
        s.stop()
        t.stop()
    }

    @Test
    fun ecmsVinIsNotAskedOnceItsInfoTypesLeaveItOut() = runTest {
        // A 09 00 without type 02 (04, 06, 08, 0A, 12), ahead of the car's own answer the ident script holds.
        val bus = carNow {
            on(0x7E0, "0900").answer("490015404000")
            CarNow.scriptEcmIdent(this)
        }
        val s = session(bus)
        s.start()
        runCurrent()
        assertEquals(1, bus.requests.count { it == "7E0 0902" })
        s.setModuleOpen(0x7E0, true)
        runCurrent()
        val before = bus.requests.size
        s.rescan()
        runCurrent()
        assertFalse(bus.requests.drop(before).contains("7E0 0902"))
        s.stop()
    }

    // ------------------------------------------------------------------ an opened code

    @Test
    fun anOpenedCodeReadsItsSnapshotsAndExtendedData() = runTest {
        // R_ZCU's C1170-09 (51 70 09): every snapshot - one record with a DID, asked alone after FF -
        // and its extended data, none.
        val bus = carNow {
            on(0x747, "1904517009FF").answer("5904517009280101F19031320201")
            on(0x747, "190451700901").answer("5904517009280101F1903132")
            on(0x747, "1906517009FF").answer("590651700928")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val before = bus.requests.size
        s.openCode(0x747, "C1170-09")
        assertEquals(ReadOutcome.READING, s.model.value.modules.getValue(0x747).codeReads.getValue("C1170-09").snapshots.outcome)
        runCurrent()
        assertEquals(listOf("747 1904517009FF", "747 190451700901", "747 1906517009FF"), bus.requests.drop(before))
        val reads = s.model.value.modules.getValue(0x747).codeReads.getValue("C1170-09")
        assertEquals("517009", reads.raw)
        assertEquals(ReadOutcome.READ, reads.snapshots.outcome)
        assertEquals(0x28, reads.snapshots.status)
        assertEquals(listOf(SnapshotRecordData("01", 1, "F190", "31 32")), reads.snapshots.records)
        // What FF held beyond the first record stays raw.
        assertEquals("02 01", reads.snapshots.rest)
        assertEquals(listOf("19 04 51 70 09 FF", "19 04 51 70 09 01"), reads.snapshots.exchanges.map { it.request })
        assertEquals(ReadOutcome.EMPTY, reads.extended.outcome)
        assertEquals(listOf("59 06 51 70 09 28"), reads.extended.exchanges.map { it.answer })

        // A code the memory does not hold asks nothing; closing is no read.
        s.openCode(0x747, "P0000-00")
        s.openCode(0x747, null)
        runCurrent()
        assertEquals(before + 3, bus.requests.size)
        s.stop()
    }

    @Test
    fun aCodeAnsweredAsTheBmsAnsweredHasNoRecord() = runTest {
        // BMS 7E7 with one record P1A3A-22, and the car's own short answer of 2026-10-21 (to record 01; here to FF).
        val bus = carNow {
            on(0x7E7, "190109").answer("590109000001")
            on(0x7E7, "190209").answer("5902091A3A2208")
            on(0x7E7, "19041A3A22FF").answer("59041A3A2200")
            on(0x7E7, "19061A3A22FF").answer("7F1931")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        val code = s.model.value.modules.getValue(0x7E7).dtc.records.single().code
        s.openCode(0x7E7, code)
        runCurrent()
        val reads = s.model.value.modules.getValue(0x7E7).codeReads.getValue(code)
        assertEquals(ReadOutcome.EMPTY, reads.snapshots.outcome)
        assertEquals(0x00, reads.snapshots.status)
        assertTrue(reads.snapshots.records.isEmpty())
        assertEquals(1, reads.snapshots.exchanges.size)
        assertEquals(ReadOutcome.REFUSED, reads.extended.outcome)
        assertEquals("NRC 31 · вне диапазона", reads.extended.why)
        // By its six raw digits too.
        s.openCode(0x7E7, "1A3A22")
        runCurrent()
        assertEquals(setOf(code), s.model.value.modules.getValue(0x7E7).codeReads.keys)
        s.stop()
    }

    // ------------------------------------------------------------------ ECM: Mode 02 and Mode 06

    @Test
    fun ecmsScreenReadsItsFreezeFrameAndTestsOncePerOpening() = runTest {
        val bus = carNow {
            CarNow.scriptEcmIdent(this)
            on(0x7E0, "020200").answer("4202000420")
            on(0x7E0, "020000").answer("42000018180000")
            on(0x7E0, "02040005000C00").answer("4204004005008B0C001388")
            on(0x7E0, "020D00").answer("420D0000")
            on(0x7E0, "0600").answer("4600C0000000")
            on(0x7E0, "0601").answer("4601010B01C200000384")
            on(0x7E0, "0602").answer("7F0612")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        assertEquals(ReadOutcome.READING, s.model.value.modules.getValue(0x7E0).obd!!.freezeFrame.outcome)
        runCurrent()
        val obd = s.model.value.modules.getValue(0x7E0).obd!!

        val frame = obd.freezeFrame
        assertEquals(ReadOutcome.READ, frame.outcome)
        assertEquals("P0420", frame.dtc)
        assertEquals(listOf(0x04, 0x05, 0x0C, 0x0D), frame.values.map { it.pid })
        assertEquals("Обороты коленвала", frame.values[2].name)
        assertEquals("1250 об/мин", frame.values[2].value)
        assertEquals("13 88", frame.values[2].raw)
        assertEquals("99 °C", frame.values[1].value)
        assertTrue(frame.missing.isEmpty())
        assertEquals(listOf("02 02 00", "02 00 00", "02 04 00 05 00 0C 00", "02 0D 00"), frame.exchanges.map { it.request })

        val tests = obd.tests
        assertEquals(ReadOutcome.READ, tests.outcome)
        assertEquals(listOf("01", "02"), tests.supported)
        val o2 = tests.monitors[0]
        assertEquals("Датчик кислорода B1S1", o2.name)
        assertEquals(ReadOutcome.READ, o2.outcome)
        assertEquals(ObdTestData("01", "0B", "0,450 В", "0,000 В", "0,900 В", true, true, "01 01 0B 01 C2 00 00 03 84"), o2.tests.single())
        assertEquals(ReadOutcome.REFUSED, tests.monitors[1].outcome)

        // Once per opening: shown again without closing, nothing more; closed and opened, read again.
        val asked = bus.requests.size
        s.setModuleOpen(0x7E0, true)
        runCurrent()
        assertEquals(asked, bus.requests.size)
        s.setModuleOpen(0x7E0, false)
        s.setModuleOpen(0x7E0, true)
        runCurrent()
        assertEquals(2, bus.requests.count { it == "7E0 020200" })
        assertEquals(2, bus.requests.count { it == "7E0 0601" })
        // Never at another OBD block.
        s.setModuleOpen(0x7E5, true)
        runCurrent()
        assertFalse(bus.requests.any { it.startsWith("7E5 02") || it.startsWith("7E5 06") })
        s.stop()
    }

    @Test
    fun readAgainAsksTheTestsAloneAndOnlyWhileTheScreenIsOpen() = runTest {
        val bus = carNow {
            CarNow.scriptEcmIdent(this)
            on(0x7E0, "020200").answer("4202000000")
            on(0x7E0, "0600").answer("4600C0000000")
            on(0x7E0, "0601").answer("4601010B01C200000384")
            on(0x7E0, "0602").answer("4602810B03E800000384")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        // not open: nothing
        s.rereadTests(0x7E0)
        runCurrent()
        assertFalse(bus.requests.any { it.startsWith("7E0 06") })
        s.setModuleOpen(0x7E0, true)
        runCurrent()
        val view = s.model.value.modules.getValue(0x7E0).tests!!
        assertEquals(ReadState.READ, view.state)
        assertEquals(1, view.outOfNorm)
        assertEquals(listOf("02", "01"), view.groups.map { it.mid })
        // «Прочитать снова»: the maps and the monitors again, nothing else
        val asked = bus.requests.size
        s.rereadTests(0x7E0)
        assertEquals(ReadState.READING, s.model.value.modules.getValue(0x7E0).tests!!.state)
        runCurrent()
        assertEquals(listOf("7E0 0600", "7E0 0601", "7E0 0602"), bus.requests.drop(asked))
        assertEquals(ReadState.READ, s.model.value.modules.getValue(0x7E0).tests!!.state)
        // closed: nothing again
        s.setModuleOpen(0x7E0, false)
        val closed = bus.requests.size
        s.rereadTests(0x7E0)
        runCurrent()
        assertEquals(closed, bus.requests.size)
        // a block whose screen does not read Mode 06 has no tests
        assertNull(s.model.value.modules[0x7E5]?.tests)
        s.stop()
    }

    @Test
    fun noStoredFrameIsOneExchange() = runTest {
        val bus = carNow {
            on(0x7E0, "020200").answer("4202000000")
            on(0x7E0, "0600").answer("7F0611")
        }
        val s = session(bus)
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        runCurrent()
        val obd = s.model.value.modules.getValue(0x7E0).obd!!
        assertEquals(ReadOutcome.EMPTY, obd.freezeFrame.outcome)
        assertNull(obd.freezeFrame.dtc)
        assertEquals(ReadOutcome.REFUSED, obd.tests.outcome)
        assertEquals(1, bus.requests.count { it.startsWith("7E0 02") })
        assertEquals(1, bus.requests.count { it.startsWith("7E0 06") })
        s.stop()
    }
}
