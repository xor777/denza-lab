package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `last-scan.json` there and back: the bytes survive, and the outcome is judged again from them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanStoreTest {
    private fun describe(r: DiagReply): String = when (r) {
        is DiagReply.Answer -> "${r.request} ${r.request.timeoutMs} → ${hex(r.uds)} ×${r.frames} @${r.atMillis} ${r.latencyMs}ms ${r.receivedNanos}ns"
        is DiagReply.NoReply -> "${r.request} ${r.request.timeoutMs} → none ${r.gatewayResult} @${r.atMillis} ${r.latencyMs}ms ${r.receivedNanos}ns"
        is DiagReply.GatewayBusy -> "${r.request} ${r.request.timeoutMs} → busy @${r.atMillis} ${r.latencyMs}ms ${r.receivedNanos}ns"
    }

    private fun carNowScan(): StoredScan = kotlinx.coroutines.test.TestScope().run {
        val store = MemoryStore()
        val bus = carNow()
        bus.on(0x7B3, "190109").busy()
        val s = session(bus, store = store)
        s.start()
        testScheduler.runCurrent()
        s.stop()
        store.saved.single()
    }

    @Test
    fun theStoredScanComesBackByteForByte() {
        val scan = carNowScan()
        val back = ScanJson.decode(ScanJson.encode(scan))!!
        assertEquals(scan.finishedAtMillis, back.finishedAtMillis)
        assertEquals(scan.reads.map { it.request }, back.reads.map { it.request })
        assertEquals(scan.reads.flatMap { it.replies.map(::describe) }, back.reads.flatMap { it.replies.map(::describe) })
        assertEquals(scan.reads.map { it.atMillis }, back.reads.map { it.atMillis })
        // And says the same about every module.
        scan.reads.zip(back.reads).forEach { (a, b) ->
            val ecu = EcuCatalog.byRequest(a.request)!!
            assertEquals(ecu.abbreviation, ModuleResult(ecu, a).status, ModuleResult(ecu, b).status)
        }
        // The file names the outcome for whoever opens it.
        val json = JSONObject(ScanJson.encode(scan))
        val modules = json.getJSONArray("modules")
        val lbcm = (0 until modules.length()).map { modules.getJSONObject(it) }.first { it.getString("request") == "720" }
        assertEquals("HISTORY", lbcm.getString("status"))
        assertEquals(8, lbcm.getInt("history"))
        assertEquals("5901FF000008", lbcm.getJSONArray("exchanges").getJSONObject(0).getString("answer"))
    }

    @Test
    fun aFileStoreKeepsTheLastScanAndRefusesWhatItCannotTrust() {
        val dir = Files.createTempDirectory("diag-store").toFile()
        val store = FileScanStore(File(dir, "diagnostics"))
        assertNull(store.load())
        val scan = carNowScan()
        store.save(scan)
        val back = store.load()!!
        assertEquals(scan.reads.flatMap { it.replies.map(::describe) }, back.reads.flatMap { it.replies.map(::describe) })
        val file = File(dir, "diagnostics/${FileScanStore.FILE}")

        // Cut short by a power cut that the rename did not prevent, or written by hand: no scan.
        file.writeText(file.readText().take(200))
        assertNull(store.load())
        // A request the whitelist refuses is no scan either: the file does not get to ask for a clear.
        store.save(scan)
        file.writeText(file.readText().replaceFirst("\"uds\": \"22F194\"", "\"uds\": \"14FFFFFF\""))
        assertNull(store.load())
        dir.deleteRecursively()
    }
}
