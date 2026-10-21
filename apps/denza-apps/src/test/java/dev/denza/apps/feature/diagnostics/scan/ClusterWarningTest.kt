package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import java.io.File
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instrument cluster's OEM warning (architecture 7.2): the GET `1007·26100008` in the batch,
 * the eight IDs with a text from research/vehicle-diagnostics/oem-warning-mappings-20261021.json,
 * and the words the model carries for a screen to take.
 */
class ClusterWarningTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private fun model(extras: Map<DiagnosticExtra, Double>, fresh: Boolean = true) = ModelBuilder(ScanClock(ZoneOffset.UTC)).build(SessionSnapshot(
        link = LinkState.IDLE, phase = ScanPhase.NEVER, done = 0, finishedAtMillis = null, notice = null, polling = null,
        results = emptyMap(), idents = emptyMap(), open = emptySet(), streams = emptyMap(), live = null, streaming = false,
        recording = null, car = CarReadings(extras = extras, extrasFresh = fresh), nowMillis = Fixtures.EPOCH,
    ))

    private fun word(id: Int) = "@@extra:oem_warning\nResult: Parcel(00000000 %08x   '........')".format(id)

    @Test
    fun theGetIsTheResearchsIntegerOnFiveUnscaledTenBits() {
        val e = DiagnosticExtra.OEM_WARNING
        assertEquals(1007 to 0x26100008, e.device to e.fid)
        assertEquals(5, e.transact)
        assertTrue(DiagnosticExtraReader.command.contains("echo @@extra:oem_warning; service call autoservice 5 i32 1007 i32 638582792"))
        assertEquals(0.0, e.decode(0)!!, 0.0)
        assertEquals(70.0, e.decode(0x46)!!, 0.0)
        assertEquals(1023.0, e.decode(0x3FF)!!, 0.0)
        listOf(1024, 0xFFFF, -1, -10011, -10013).forEach { assertNull("$it", e.decode(it)) }
        assertEquals(mapOf(DiagnosticExtra.OEM_WARNING to 856.0), DiagnosticExtraReader.decode(word(0x358), 1, 1).values)
        assertTrue(DiagnosticExtraReader.decode(word(0x400), 1, 1).values.isEmpty())
        assertTrue("a failed read is no ID", DiagnosticExtraReader.decode("@@extra:oem_warning\nResult: Parcel(ffffffff 00000046)", 1, 1).values.isEmpty())
    }

    @Test
    fun theEightTextsAreTheResearchs() {
        val mappings = JSONObject(File(root, "research/vehicle-diagnostics/oem-warning-mappings-20261021.json").readText())
            .getJSONArray("mappings")
        val research = (0 until mappings.length()).associate { i ->
            mappings.getJSONObject(i).let { it.getInt("warningIdDecimal") to it.getString("ruTranslation") }
        }
        assertEquals(listOf(0x1B, 0x1E, 0x20, 0x21, 0x46, 0x47, 0x5D, 0x358), research.keys.sorted())
        assertEquals(research, SystemsBuilder.OEM_WARNING_WORDS)
        research.forEach { (id, text) -> assertEquals(ClusterWarningView(text, id), SystemsBuilder.clusterWarning(id.toDouble())) }
    }

    @Test
    fun zeroIsNoMessageAnotherIdIsItsNumberAndNothingIsNa() {
        assertEquals(ClusterWarningView("нет сообщений", 0), SystemsBuilder.clusterWarning(0.0))
        assertEquals(ClusterWarningView("сообщение 0x02A", 0x2A), SystemsBuilder.clusterWarning(42.0))
        assertEquals(ClusterWarningView("сообщение 0x247", 0x247), SystemsBuilder.clusterWarning(0x247.toDouble()))
        assertEquals(ClusterWarningView("сообщение 0x3FF", 1023), SystemsBuilder.clusterWarning(1023.0))
        assertEquals(ClusterWarningView("н/д", null), SystemsBuilder.clusterWarning(1024.0))
        assertEquals(ClusterWarningView("н/д", null), SystemsBuilder.clusterWarning(null))
    }

    @Test
    fun theModelCarriesTheFreshReading() {
        assertEquals(ClusterWarningView("Проверьте систему ABS.", 0x46), model(mapOf(DiagnosticExtra.OEM_WARNING to 70.0)).systems.clusterWarning)
        assertEquals(ClusterWarningView("нет сообщений", 0), model(mapOf(DiagnosticExtra.OEM_WARNING to 0.0)).systems.clusterWarning)
        assertEquals(ClusterWarningView("н/д", null), model(emptyMap()).systems.clusterWarning)
        assertEquals("an old batch is no reading", ClusterWarningView("н/д", null),
            model(mapOf(DiagnosticExtra.OEM_WARNING to 70.0), fresh = false).systems.clusterWarning)
    }
}
