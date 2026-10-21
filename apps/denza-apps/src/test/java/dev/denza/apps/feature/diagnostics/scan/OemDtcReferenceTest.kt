package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.protocol.DtcCode
import dev.denza.apps.feature.diagnostics.protocol.DtcFormat
import dev.denza.apps.feature.diagnostics.protocol.LookupSkip
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import dev.denza.apps.feature.diagnostics.protocol.OemHeading
import dev.denza.apps.feature.diagnostics.protocol.OemSection
import dev.denza.apps.feature.diagnostics.protocol.RearSide
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public DTC catalog of the Z9GT PHEV manual as candidate descriptions (architecture, 4): the
 * research files are the app's own assets, the translation covers every heading, the lookup keeps to
 * its rules on real codes of the index, and the engine puts the description on a read record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OemDtcReferenceTest {
    private val research = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }
        .resolve("research/vehicle-diagnostics")
    private val indexFile = research.resolve(OemDtcAssets.INDEX)
    private val ruFile = research.resolve(OemDtcAssets.TRANSLATION)
    private val index = OemDtcAssets.parse(indexFile.readText(), ruFile.readText())
    private val supported by lazy { JSONObject(research.resolve("supported-dtc-20261021.json").readText()) }

    private fun j2012(raw: Int) = DtcCode(raw, DtcFormat.SAE_J2012_00)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    // ------------------------------------------------------------------ the files

    @Test
    fun theBuildCopiesTheResearchFilesTheAppOpens() {
        val script = File("build.gradle.kts").readText()
        val copy = Regex("""register<ResearchAssets>\((.*?)addGeneratedSourceDirectory""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1).orEmpty()
        val copied = Regex(""""(oem-dtc-[\w-]+\.json)"""").findAll(copy).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(OemDtcAssets.INDEX, OemDtcAssets.TRANSLATION), copied)
        assertTrue(copy, copy.contains("""directory.set("${OemDtcAssets.DIRECTORY}")"""))
        assertTrue(indexFile.isFile && ruFile.isFile)
    }

    @Test
    fun theTranslationCoversEveryHeadingOfTheIndexAndNothingElse() {
        val source = JSONObject(indexFile.readText())
        val ru = JSONObject(ruFile.readText())
        fun keys(o: JSONObject) = o.getJSONArray("records").let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { Triple(it.getString(0), it.getString(1), it.getString(2)) } }
        }
        val indexKeys = keys(source)
        val ruKeys = keys(ru)
        assertEquals(829, indexKeys.size)
        assertEquals(indexKeys.size, indexKeys.toSet().size)
        assertEquals(indexKeys.toSet(), ruKeys.toSet())
        assertEquals(ruKeys.size, ruKeys.toSet().size)
        assertEquals(829, ru.getInt("recordCount"))
        // The translation names the exact index it was made from: a new index needs a new translation.
        val sha = MessageDigest.getInstance("SHA-256").digest(indexFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(sha, ru.getString("sourceSha256"))
        assertEquals("машинный перевод заголовков, не заводской текст", ru.getString("statusRu"))
        assertFalse(ru.getBoolean("eligibleForProductDictionary"))

        assertEquals(829, index.headings.size)
        val han = Regex("""\p{IsHan}""")
        index.headings.forEach { h ->
            assertTrue(h.toString(), h.titleRu.isNotBlank() && !han.containsMatchIn(h.titleRu))
            assertTrue(h.toString(), h.titleZh.isNotBlank() && !h.titleZh.startsWith(h.displayCode))
            // An abbreviation of the heading stays in its translation.
            ABBREVIATIONS.filter { Regex("""(?<![A-Za-z])$it(?![A-Za-z])""").containsMatchIn(h.titleZh) }.forEach { a ->
                assertTrue("$a lost: $h", h.titleRu.contains(a))
            }
        }
    }

    @Test
    fun aJoinThatIsNotOneToOneIsNoCatalog() {
        val row = listOf("engine_ecm", "P222729", "outline_26_a", "P222729 环境压力传感器信号故障")
        val ru = listOf("engine_ecm", "P222729", "outline_26_a", "Датчик")
        assertEquals(1, OemDtcIndex.join(listOf(row), listOf(ru)).headings.size)
        listOf(
            { OemDtcIndex.join(listOf(row), emptyList()) },
            { OemDtcIndex.join(emptyList(), listOf(ru)) },
            { OemDtcIndex.join(listOf(row, row), listOf(ru)) },
            { OemDtcIndex.join(listOf(row.toMutableList().also { it[0] = "rear_center" }), listOf(ru)) },
        ).forEach { join ->
            assertTrue(runCatching(join).isFailure)
        }
    }

    // ------------------------------------------------------------------ the lookup

    @Test
    fun anEngineCodeFindsItsHeading() {
        // P2227-29 as format 00 reads it: base P2227, failure type 29.
        val code = j2012(0x222729)
        assertEquals("P2227-29", code.display)
        val found = index.lookup(0x7E0, code)
        assertNull(found.skipped)
        assertFalse(found.conflict)
        val c = found.candidates.single()
        assertEquals("P222729", c.displayCode)
        assertEquals("环境压力传感器信号故障", c.titleZh)
        assertEquals("Датчик атмосферного давления: неисправность сигнала", c.titleRu)
        assertEquals("outline_26_a", c.outlineId)
        assertEquals(OemSection.ENGINE_ECM, c.section)
        assertEquals("Датчик атмосферного давления: неисправность сигнала", CatalogText.description(found.candidates))
    }

    @Test
    fun twoHeadingsOfOneSectionAreBothKeptAndFlagged() {
        val engine = index.lookup(0x7E0, j2012(0x222800))
        assertTrue(engine.conflict)
        assertEquals(listOf("outline_29_a", "outline_419_a"), engine.candidates.map { it.outlineId })
        assertEquals(
            "Датчик атмосферного давления: замыкание на массу / " +
                "Встроенная в ЭБУ микросхема атмосферного давления: самодиагностика, ниже минимума",
            CatalogText.description(engine.candidates),
        )
        assertTrue(index.lookup(0x7E0, j2012(0x222900)).conflict)

        val bms = index.lookup(0x7E7, j2012(0x2BAE00))
        assertTrue(bms.conflict)
        assertEquals(
            listOf("Контактор повышения напряжения: сваривание контактов", "Общее напряжение батареи предельно занижено"),
            bms.candidates.map { it.titleRu },
        )
        // P1A2000 repeats with the same heading (one copy carries a zero-width space): one candidate.
        val bic = index.lookup(0x7E7, j2012(0x1A2000))
        assertFalse(bic.conflict)
        assertEquals(listOf("outline_764_a", "outline_950_a"), bic.candidates.single().locators.map { it.outlineId })
    }

    @Test
    fun theRearDrivesAreSearchedInBothRearSections() {
        assertEquals(listOf(OemSection.REAR_LEFT, OemSection.REAR_RIGHT), OemDtcIndex.sections(0x744))
        assertEquals(listOf(OemSection.REAR_LEFT, OemSection.REAR_RIGHT), OemDtcIndex.sections(0x745))
        // The index gives the two sides different codes: one is found from either module, its own words name the side.
        listOf(0x744, 0x745).forEach { smcu ->
            val left = index.lookup(smcu, j2012(0x1B0301)).candidates.single()
            assertEquals(OemSection.REAR_LEFT, left.section)
            assertEquals(RearSide.LEFT, left.side)
            assertEquals("Контроллер левого заднего электродвигателя: перенапряжение (программный контроль)", CatalogText.description(listOf(left)))
            assertEquals(OemSection.REAR_RIGHT, index.lookup(smcu, j2012(0x1B0420)).candidates.single().section)
        }
        // Not in the index, but the rule: equal headings on both sides are one, different ones are both, sided.
        fun h(section: OemSection, title: String, outline: String) = OemHeading(section, "P1B0001", outline, title, title)
        val same = OemDtcIndex(listOf(h(OemSection.REAR_LEFT, "A", "l"), h(OemSection.REAR_RIGHT, "A", "r")))
            .lookup(0x744, j2012(0x1B0001))
        assertEquals(1, same.candidates.size)
        assertNull(same.candidates.single().side)
        assertFalse(same.conflict)
        assertEquals("A", CatalogText.description(same.candidates))
        val differ = OemDtcIndex(listOf(h(OemSection.REAR_LEFT, "A", "l"), h(OemSection.REAR_RIGHT, "B", "r")))
            .lookup(0x745, j2012(0x1B0001))
        assertFalse(differ.conflict)
        assertEquals("Л: A / П: B", CatalogText.description(differ.candidates))
    }

    @Test
    fun fmcuFormatZeroOneRegistryFindsEveryFrontDriveHeading() {
        // FMCU's live `19 0A` registry of 2026-10-21: 32 codes in format 01, every one it can report.
        val fmcu = supported.getJSONArray("modules").objects().single { it.getString("requestIdHex") == "7E3" }
        assertEquals(1, fmcu.getJSONObject("formatRead").getInt("formatId"))
        val registry = fmcu.getJSONArray("records").objects().map { it.getString("raw24Hex") }
        assertEquals(32, registry.size)
        assertEquals(registry.size, registry.toSet().size)

        val found = registry.associateWith { raw ->
            val code = DtcCode(raw.toInt(16), DtcFormat.ISO_14229_1)
            assertEquals("the code is shown as it came", raw, code.display)
            index.lookup(0x7E3, code).also { r ->
                assertNull(raw, r.skipped)
                assertFalse(raw, r.conflict)
            }.candidates
        }
        // Read as J2012, the registry holds the front drive's 31 headings, each once; 1BE201 alone has none.
        assertEquals(listOf("1BE201"), found.filterValues { it.isEmpty() }.keys.toList())
        assertTrue(found.values.all { it.size <= 1 })
        val frontDrive = index.headings.filter { it.section == OemSection.FRONT_DRIVE }
        assertEquals(31, frontDrive.size)
        assertEquals(frontDrive.map { it.outlineId }.sorted(), found.values.flatten().map { it.outlineId }.sorted())

        // The layout: the letter from the first byte's top two bits, the code's digits, the third byte.
        assertEquals("P1B0019", OemDtcIndex.catalogCode(0x7E3, DtcCode(0x1B0019, DtcFormat.ISO_14229_1)))
        val network = index.lookup(0x7E3, DtcCode(0xC1D487, DtcFormat.ISO_14229_1)).candidates.single()
        assertEquals("U01D487", network.displayCode)
        assertEquals("outline_571_a", network.outlineId)
        // The research's own reverse of the 31 headings agrees code for code.
        val rows = supported.getJSONObject("fmcuReverseNotationHypothesis").getJSONArray("rows").objects()
        assertEquals(31, rows.size)
        rows.forEach { r ->
            val code = DtcCode(r.getString("hypotheticalRaw24").toInt(16), DtcFormat.ISO_14229_1)
            assertEquals(r.getString("publicDisplayCode"), OemDtcIndex.catalogCode(0x7E3, code))
        }
    }

    @Test
    fun formatZeroOneElsewhereAndUnknownFormatsAreNotSearched() {
        // ISG reports format 01 too: its mapping is not established, so its codes are not searched.
        val isg = index.lookup(0x7E6, DtcCode(0x180219, DtcFormat.ISO_14229_1))
        assertEquals(LookupSkip.FORMAT_01, isg.skipped)
        assertTrue(isg.candidates.isEmpty())
        assertEquals("сопоставление формата 01 с каталогом не установлено", isg.skipped!!.reason)
        assertNull(OemDtcIndex.catalogCode(0x7E6, DtcCode(0x180219, DtcFormat.ISO_14229_1)))
        assertEquals(setOf(0x7E3), OemDtcIndex.FORMAT_01_AS_J2012)
        // Format 01 from any other module with a section is not laid out as J2012 either.
        assertEquals(LookupSkip.FORMAT_01, index.lookup(0x7E0, DtcCode(0x222729, DtcFormat.ISO_14229_1)).skipped)
        assertEquals(LookupSkip.FORMAT_UNKNOWN, index.lookup(0x7E0, DtcCode(0x222729, DtcFormat.OTHER, 0x7F)).skipped)
        assertEquals(LookupSkip.FORMAT_UNKNOWN, index.lookup(0x7E3, DtcCode(0x1B0019, DtcFormat.OTHER, 0x7F)).skipped)
    }

    @Test
    fun aModuleWithoutASectionGetsNothing() {
        listOf(0x720, 0x747, 0x782, 0x7E4, 0x7E2, 0x7E5).forEach { request ->
            val r = index.lookup(request, j2012(0x222729))
            assertEquals(LookupSkip.NO_SECTION, r.skipped)
            assertTrue(r.candidates.isEmpty())
        }
        // A section that does not list the code: searched, nothing found, no reason.
        val absent = index.lookup(0x7E0, j2012(0x000000))
        assertNull(absent.skipped)
        assertTrue(absent.candidates.isEmpty())
        assertNull(CatalogText.description(absent.candidates))
    }

    @Test
    fun everyCodeOfASectionIsFoundFromItsModule() {
        val modules = mapOf(
            OemSection.ENGINE_ECM to 0x7E0, OemSection.BATTERY_MANAGEMENT to 0x7E7, OemSection.FRONT_DRIVE to 0x7E3,
            OemSection.GENERATOR to 0x7E6, OemSection.REAR_LEFT to 0x744, OemSection.REAR_RIGHT to 0x745,
        )
        index.headings.forEach { h ->
            val raw = rawOf(h.displayCode)
            val module = modules.getValue(h.section)
            // FMCU reports format 01; every other module with a section, format 00.
            val code = if (module == 0x7E3) DtcCode(raw, DtcFormat.ISO_14229_1) else j2012(raw)
            assertEquals(h.displayCode, OemDtcIndex.catalogCode(module, code))
            val found = index.lookup(module, code)
            assertTrue(h.toString(), found.candidates.any { c -> c.locators.any { it.outlineId == h.outlineId } })
        }
    }

    // ------------------------------------------------------------------ the engine

    @Test
    fun theEngineDescribesARecordAndSaysTheSourceOnce() = runTest {
        val bus = carNow {
            on(0x7E0, "190109").answer("5901FF000001")
            on(0x7E0, "190209").answer("5902FF22272909")
        }
        val s = session(bus, catalog = { index })
        s.start()
        runCurrent()
        val m = s.model.value

        val ecm = m.modules.getValue(0x7E0).dtc
        val row = ecm.records.single()
        assertEquals("P2227-29", row.code)
        assertEquals("Датчик атмосферного давления: неисправность сигнала", row.description)
        assertEquals("", row.note)
        assertEquals("Недостоверный сигнал", row.failureName)
        assertEquals(listOf("环境压力传感器信号故障"), row.reference.map { it.titleZh })
        assertEquals(listOf(listOf("engine_ecm · outline_26_a")), row.reference.map { it.locators })
        assertEquals(ModelBuilder.CATALOG_SOURCE, ecm.source)

        // R_ZCU's two records: no section, so no description, no source, and no sentence instead.
        val body = m.modules.getValue(0x747).dtc
        assertEquals(2, body.records.size)
        assertTrue(body.records.all { it.description == null && it.reference.isEmpty() && it.note.isEmpty() })
        assertNull(body.source)
        s.stop()
    }

    @Test
    fun theEngineDescribesAnFmcuRecordAndKeepsItsCodeAsItCame() = runTest {
        val bus = carNow {
            on(0x7E3, "190109").answer("590109010001")
            on(0x7E3, "190209").answer("5902091B001909")
        }
        val s = session(bus, catalog = { index })
        s.start()
        runCurrent()

        val fmcu = s.model.value.modules.getValue(0x7E3).dtc
        val row = fmcu.records.single()
        assertEquals("1B0019", row.code)
        assertEquals("Формат 01: тип отказа не выделяется", row.failureName)
        assertEquals("Передний тяговый электродвигатель: перегрузка по току", row.description)
        assertEquals(listOf("P1B0019"), row.reference.map { it.code })
        assertEquals(listOf(listOf("front_drive · outline_542_a")), row.reference.map { it.locators })
        assertEquals(ModelBuilder.CATALOG_SOURCE, fmcu.source)
        s.stop()
    }

    private fun rawOf(display: String): Int {
        val base = ("PCBU".indexOf(display[0]) shl 14) or (display[1].digitToInt() shl 12) or display.substring(2, 5).toInt(16)
        return (base shl 8) or display.substring(5, 7).toInt(16)
    }

    private companion object {
        val ABBREVIATIONS = listOf(
            "LSU", "APE", "IPE", "RE", "MES", "IGBT", "AFE", "BIC", "HVSU", "CVO", "DMTL", "EGR", "VVT", "EVVT",
            "GPF", "FTIV", "FITV", "CPLD", "EEPROM", "LOT", "DOS", "CPU0", "CPU1", "CPU2", "PLL1", "DVE", "HVD", "ABE",
        )
    }
}
