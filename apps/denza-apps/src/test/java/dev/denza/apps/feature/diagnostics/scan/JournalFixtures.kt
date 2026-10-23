package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import java.time.Instant

/**
 * The journal's fixtures: the six modules with records and the ECM as the car answered the autoscan of
 * 2026-10-20 19:31 (`captures/diagnostics-scanner-20261020/last-scan-1931.json`), byte for byte, and
 * scans made of them. What later scans change - a code gone, a code new, a software code - is
 * illustrative.
 */
internal object JournalFixtures {
    fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** One module's replies: request hex, answer hex, latency ms. */
    fun result(address: Int, vararg exchanges: Triple<String, String, Int>, atMillis: Long = 0): ModuleResult {
        val ecu = EcuCatalog.byRequest(address)!!
        val replies = exchanges.map { (uds, answer, ms) ->
            DiagReply.Answer(DiagRequest(address, hexBytes(uds)), hexBytes(answer), 1, atMillis, ms, 0)
        }
        return ModuleResult(ecu, ModuleRead(address, atMillis, replies))
    }

    private fun x(uds: String, answer: String, ms: Int) = Triple(uds, answer, ms)

    // The 20.10 19:31 answers. 720 and 747 were scanned before their ungated F194 (2026-10-21).
    fun lbcm(list: String = LBCM_LIST, count: Int = 8) =
        result(0x720, x("190109", "5901090000%02X".format(count), 59), x("190209", list, 110))
    fun rzcu() = result(0x747, x("190109", "590109000002", 63), x("190209", "59020951700908930E0108", 70))
    fun ins(f194: String = "62F1944B450011E0250037B1") =
        result(0x723, x("22F194", f194, 17), x("190109", "590109000002", 13), x("190209", "590209A3D90208C2668708", 16))
    fun media() = result(0x741, x("22F194", "62F1944B450011E0930034E2", 5), x("190109", "590109000001", 14), x("190209", "5902099AC80008", 15))
    fun ccs() = result(0x7A7, x("22F194", "62F19448490011DF55000100", 19), x("190109", "590109000001", 13), x("190209", "590209230E0708", 14))
    fun idc() = result(0x597, x("22F194", "62F1944B4D0011DE01140600", 25), x("190109", "590129000004", 23), x("190209", "5902295E20492852D01C2852D01D2872007B28", 48))
    fun ecm() = result(
        0x7E0, x("22F194", "62F19432440011E004000000", 36), x("190109", "5901FF000000", 63),
        x("0101", "41010007F5F4", 19), x("03", "4300", 21), x("07", "4700", 12), x("0A", "4A00", 18),
    )
    /** A module that did not answer: the gateway's 3 to its gate. */
    fun silent(address: Int): ModuleResult {
        val ecu = EcuCatalog.byRequest(address)!!
        return ModuleResult(ecu, ModuleRead(address, 0, listOf(DiagReply.NoReply(DiagRequest(address, hexBytes("22F194")), 3, 0, 4000, 0))))
    }

    /** LA_BCM's eight records of 20.10: C1160-06 U029D-87 U0121-87 B2148-02 B2250-00 B225B-00 U0229-87 U022A-87. */
    const val LBCM_LIST = "59020951600608C29D8708C1218708A1480208A2500008A25B0008C2298708C22A8708"

    val VITALS = JournalVitals(
        lvVolts = 13.7, soc = 74.0, soh = 99.0, packCurrentA = -0.4, cellMinMv = 3280.0, cellMaxMv = 3284.0,
        insulationMohm = 14.4, insulation2Mohm = 19.7, probeMinC = 10.0, probeMaxC = 16.0, mil = false,
    )

    fun scan(
        atMillis: Long,
        results: List<ModuleResult>,
        kind: JournalScanKind = JournalScanKind.FULL,
        vitals: JournalVitals = VITALS,
        odometerKm: Double? = 13_199.7,
    ): JournalScan = JournalEntries.scan(kind, results, emptyMap(), CarReadings(), atMillis)
        .copy(vitals = vitals, odometerKm = odometerKm)

    /** The car as it answered on 20.10 19:31: six modules with 18 records, the ECM clean. */
    fun car2010(): List<ModuleResult> = listOf(ecm(), lbcm(), rzcu(), ins(), media(), ccs(), idc())

    /** A snapshot: [cells] mV, the lowest at [min] (from 1). */
    fun cells(atMillis: Long, min: Int, soc: Double = 74.0, spread: Int = 5): JournalCells {
        val mv = MutableList<Int?>(166) { 3282 }
        mv[min - 1] = 3282 - spread + 1
        mv[(min % 166)] = 3283
        return JournalCells(
            atMillis = atMillis, soc = soc, currentA = 0.0, odometerKm = 13_199.7,
            cellsMv = mv, probesC = List(14) { 10 + it % 7 },
            minCell = min, maxCell = (min % 166) + 1, minProbe = 1, maxProbe = 7,
            fullCharge = soc >= JournalCells.FULL_SOC,
        )
    }
}
