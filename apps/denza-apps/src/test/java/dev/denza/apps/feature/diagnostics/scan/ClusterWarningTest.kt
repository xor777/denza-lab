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
 * the eight IDs with a text from research/vehicle-diagnostics/oem-warning-mappings-20261021.json and the
 * three more the research names from the same dictionary (13F, 7D, 1DD), and the words the model
 * carries for a screen to take.
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
    fun theElevenTextsAreTheResearchs() {
        val mappings = JSONObject(File(root, "research/vehicle-diagnostics/oem-warning-mappings-20261021.json").readText())
            .getJSONArray("mappings")
        val research = (0 until mappings.length()).associate { i ->
            mappings.getJSONObject(i).let { it.getInt("warningIdDecimal") to it.getString("ruTranslation") }
        }
        assertEquals(listOf(0x1B, 0x1E, 0x20, 0x21, 0x46, 0x47, 0x5D, 0x358), research.keys.sorted())
        // The eight priority mappings word for word; the other three are ours, over the same dictionary's Chinese.
        assertEquals(research, SystemsBuilder.OEM_WARNING_WORDS.filterKeys { it in research })
        assertEquals(setOf(0x7D, 0x13F, 0x1DD), SystemsBuilder.OEM_WARNING_WORDS.keys - research.keys)
        SystemsBuilder.OEM_WARNING_WORDS.forEach { (id, text) ->
            assertEquals(ClusterWarningView(text, id), SystemsBuilder.clusterWarning(id.toDouble()))
        }
    }

    @Test
    fun theThreeWithoutAMappingAreTheDictionarysEntriesTheResearchNames() {
        // 13F and 7D came live on this car with a door (the direct listener of 2026-10-21); 1DD is admitted
        // by the native warning set. The Chinese is the research's copy of popup_nads.json.
        val live = JSONObject(File(root, "research/vehicle-diagnostics/washer-direct-listener-live-20261021.json").readText())
        val dictionary = live.getJSONObject("retainedDictionary")
        assertEquals(setOf("13F", "7D", "1DD"), dictionary.keys().asSequence().toSet())
        assertEquals(ORIGINALS, dictionary.keys().asSequence().associate { it.toInt(16) to dictionary.getString(it) })
        val washer = JSONObject(File(root, "research/vehicle-diagnostics/washer-warning-conditions-20261021.json").readText())
        assertEquals(0x1DD, washer.getString("warningId").removePrefix("0x").toInt(16))
        assertEquals(ORIGINALS.getValue(0x1DD), washer.getJSONObject("dictionary").getJSONObject("original").getString("text"))

        assertEquals(ClusterWarningView("Состояние кузова: люк, двери, капот, багажник, окна, ремни.", 0x13F), SystemsBuilder.clusterWarning(319.0))
        assertEquals(ClusterWarningView("Ключ не обнаружен; проверьте, что он в машине.", 0x7D), SystemsBuilder.clusterWarning(125.0))
        assertEquals(ClusterWarningView("Долейте омывающую жидкость.", 0x1DD), SystemsBuilder.clusterWarning(477.0))
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

    private companion object {
        /** The stock Chinese of the three texts without a priority mapping, as `popup_nads.json` holds it. */
        val ORIGINALS = mapOf(
            0x13F to "车体状态（天窗、车门、前舱盖后备箱、车窗、安全带）",
            0x7D to "未检测到钥匙，请确认钥匙是否在车内",
            0x1DD to "请加注洗涤液",
        )
    }
}
