package dev.denza.apps.feature.diagnostics.ecu

import dev.denza.apps.feature.diagnostics.bus.DiagnosticWhitelist
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog against what the car answered (research/vehicle-diagnostics, 2026-10-18/20). */
class EcuCatalogContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }
    private fun research(name: String) = JSONObject(File(root, "research/vehicle-diagnostics/$name").readText())
    private val obd = research("obd-20261020.json")
    private val remaining = research("remaining-ecus-20261020.json")
    private val cache = research("ecu-inventory-cache-20261018.json")
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun address(s: String) = s.toInt(16)

    /** Every address whose F194 came back, with the code. */
    private val f194: Map<Int, String> by lazy {
        val fromRemaining = remaining.getJSONArray("identities").objects()
            .associate { address(it.getString("request")) to it.getString("softwareCode") }
        val fromRun4 = obd.getJSONArray("run4_ident2").objects().filter { it.getString("result") == "ok" }
            .associate { address(it.getString("request")) to it.getString("value") }
        fromRemaining.keys.intersect(fromRun4.keys).forEach { assertEquals(fromRemaining[it], fromRun4[it]) }
        fromRemaining + fromRun4
    }

    /** Every address that answered anything at all, positive or negative. */
    private val answered: Set<Int> by lazy {
        val obdRows = listOf("run1", "run2_detail", "run3_ident", "run4_ident2").flatMap { obd.getJSONArray(it).objects() }
        val remainingRows = remaining.getJSONArray("runs").objects().flatMap { it.getJSONArray("rows").objects() }
        (obdRows + remainingRows).filter { it.optString("result") in setOf("ok", "negative") }
            .map { address(it.getString("request")) }.toSet()
    }

    @Test
    fun fortySixAddressesFortyThreeRoutedSixteenQualified() {
        assertEquals(46, EcuCatalog.all.size)
        assertEquals(43, EcuCatalog.all.count { it.request != null })
        assertEquals(43, EcuCatalog.routed.size)
        assertEquals(16, EcuCatalog.all.count { it.dtcQualified })
        assertEquals(46, EcuCatalog.all.map { it.request ?: it.abbreviation }.toSet().size)
        assertEquals(37, EcuCatalog.all.count { it.identity == IdentityBasis.F194 })
        assertEquals(5, EcuCatalog.all.count { it.identity == IdentityBasis.STOCK_ROUTE })
        assertEquals(1, EcuCatalog.all.count { it.identity == IdentityBasis.OBD_ECU_NAME })
        assertEquals(3, EcuCatalog.all.count { it.identity == IdentityBasis.NONE })
        assertEquals(
            mapOf(EcuGroup.POWERTRAIN to 8, EcuGroup.CHASSIS to 4, EcuGroup.ADAS to 7, EcuGroup.BODY to 6,
                EcuGroup.CABIN to 4, EcuGroup.CLIMATE to 3, EcuGroup.LOW_VOLTAGE to 1, EcuGroup.UNKNOWN to 13),
            EcuGroup.entries.associateWith { EcuCatalog.inGroup(it).size },
        )
        assertEquals("ECM", EcuCatalog.routed.first().abbreviation)
    }

    @Test
    fun everyF194TheCarReturnedIsInTheCatalogAndAgrees() {
        assertEquals(37, f194.size)
        f194.forEach { (request, code) ->
            val ecu = EcuCatalog.byRequest(request)
            assertNotNull("%03X answered F194 but is not in the catalog".format(request), ecu)
            assertEquals("%03X".format(request), code, ecu!!.expectedF194)
            assertEquals(IdentityBasis.F194, ecu.identity)
        }
        EcuCatalog.all.filter { it.identity == IdentityBasis.F194 }.forEach {
            assertEquals(it.address, f194[it.request], it.expectedF194)
        }
        // 741 is the one code the cache does not hold.
        assertEquals(listOf(0x741), remaining.getJSONArray("identities").objects()
            .filter { it.getJSONArray("cacheIdentities").length() == 0 }.map { address(it.getString("request")) })
        assertEquals("нет в кэше", EcuCatalog.byRequest(0x741)!!.shortName)
    }

    @Test
    fun noAddressThatNeverAnsweredIsCatalogued() {
        EcuCatalog.all.mapNotNull { it.request }.forEach {
            assertTrue("%03X never answered".format(it), it in answered)
        }
        // The timeouts stay out.
        listOf(0x7E1, 0x7E4, 0x780, 0x785, 0x797).forEach { assertNull("%03X".format(it), EcuCatalog.byRequest(it)) }
    }

    @Test
    fun cachedAliasesAndVersionsAreTheCachesOwn() {
        remaining.getJSONArray("identities").objects().forEach { row ->
            val ecu = EcuCatalog.byRequest(address(row.getString("request")))!!
            val expected = row.getJSONArray("cacheIdentities").objects()
                .map { CachedIdentity(it.getString("ecuId"), it.getString("ecuSoftVer"), it.getString("ecuHardVer")) }
            assertEquals(ecu.address, expected, ecu.aliases)
        }
        // Powertrain codes the 2026-10-18 cache projection holds: VCU, FMCU, ISG, BMS, SMCU, SMCU3.
        val records = cache.getJSONArray("focusedRecords").objects()
        EcuCatalog.inGroup(EcuGroup.POWERTRAIN).filter { it.abbreviation != "DCOBC" }.forEach { ecu ->
            val code = ecu.expectedF194 ?: ecu.cachedSoftwareCode
            val expected = records.filter { it.getString("ecuSoftCode") == code }
                .map { CachedIdentity(it.getString("ecuId"), it.getString("ecuSoftVer"), it.getString("ecuHardVer")) }
            assertTrue(ecu.abbreviation, expected.isNotEmpty())
            assertEquals(ecu.abbreviation, expected, ecu.aliases)
        }
        // Role-unknown ECUs show their cached software version as the short name.
        EcuCatalog.inGroup(EcuGroup.UNKNOWN).filter { it.aliases.isNotEmpty() }.forEach {
            assertEquals("ПО ${it.aliases.first().softwareVersion}", it.shortName)
            assertTrue(it.aliases.all { a -> a.softwareVersion == it.aliases.first().softwareVersion })
        }
    }

    @Test
    fun threeEcusWithoutARouteKeepTheirCachedCodes() {
        val expected = remaining.getJSONArray("remaining").strings().associate { it.substringBefore(':') to it.substringAfter(':') }
        val unrouted = EcuCatalog.all.filter { it.request == null }
        assertEquals(expected.keys, unrouted.map { it.abbreviation }.toSet())
        unrouted.forEach {
            assertEquals(expected[it.abbreviation], it.cachedSoftwareCode)
            assertEquals(IdentityBasis.NONE, it.identity)
            assertEquals("нет адреса", it.address)
            assertNull(it.reply)
            assertTrue(!it.uds && !it.obd && !it.dtcQualified && it.inferred)
        }
    }

    @Test
    fun dtcQualifiedAreExactlyTheResearchReadsPlusTheBrakeObdInterface() {
        val read = remaining.getJSONArray("researchDtcReadAddresses").strings().map(::address).toSet()
        val brake = address(remaining.getJSONObject("obdBrakeIdentity").getString("request"))
        assertEquals(read + brake, EcuCatalog.all.filter { it.dtcQualified }.mapNotNull { it.request }.toSet())
        // The format each one reported in 59 01 (run 1 count rows, remaining dtc run, 720 and LBMS of 10-18).
        val formats = mutableMapOf<Int, Int>()
        (obd.getJSONArray("run1").objects() + obd.getJSONArray("run2_detail").objects())
            .filter { it.optString("result") == "ok" && it.getString("uds").startsWith("1901") }
            .forEach { formats[address(it.getString("request"))] = it.getInt("format") }
        remaining.getJSONArray("runs").objects().first { it.getString("name") == "dtc" }.getJSONArray("rows").objects()
            .filter { it.getString("uds") == "190109" }.forEach { formats[address(it.getString("request"))] = it.getInt("format") }
        formats[address(research("dtc-20261018.json").getString("request_id").removePrefix("0x"))] =
            research("dtc-20261018.json").getInt("format_identifier")
        formats[address(research("lbms-dtc-20261018.json").getString("request_id").removePrefix("0x"))] =
            research("lbms-dtc-20261018.json").getInt("format_identifier")
        assertEquals(read, formats.keys)
        formats.forEach { (request, format) -> assertEquals("%03X".format(request), format, EcuCatalog.byRequest(request)!!.dtcFormat) }
        assertNull(EcuCatalog.byRequest(brake)!!.dtcFormat)
    }

    @Test
    fun theBrakeInterfaceIsKnownByItsObdName() {
        val identity = remaining.getJSONObject("obdBrakeIdentity")
        val bscm = EcuCatalog.byRequest(address(identity.getString("request")))!!
        assertEquals(IdentityBasis.OBD_ECU_NAME, bscm.identity)
        assertEquals(identity.getString("ecuName"), bscm.expectedObdName)
        assertTrue(bscm.obd && !bscm.uds)
        assertEquals(0x7ED, bscm.reply)
        assertEquals(EcuGroup.CHASSIS, bscm.group)
    }

    @Test
    fun obdIsWhereThePidBitmapAnswered() {
        val bitmaps = obd.getJSONArray("run1").objects().filter { it.getString("uds") == "0100" && it.getString("result") == "ok" }
            .map { address(it.getString("request")) }.toSet()
        assertEquals(bitmaps, EcuCatalog.all.filter { it.obd }.mapNotNull { it.request }.toSet())
    }

    @Test
    fun bodyNamesRestOnStockRoutes() {
        val routes = mutableSetOf<Pair<String, String>>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("ecuId") && node.has("ecuDiaId")) routes += node.getString("ecuId") to node.getString("ecuDiaId")
                    node.keys().forEach { walk(node.get(it)) }
                }
                is JSONArray -> (0 until node.length()).forEach { walk(node.get(it)) }
            }
        }
        walk(research("ota-ecu-prefix-20261018.json"))
        EcuCatalog.all.filter { it.identity == IdentityBasis.STOCK_ROUTE }.forEach { ecu ->
            assertNull(ecu.expectedF194)
            ecu.aliases.forEach { alias ->
                assertNull(alias.softwareVersion)
                val dia = "%03X/%03X".format(ecu.request, ecu.reply)
                val inRouteMap = (alias.ecuId to dia) in routes
                val corrected = remaining.getJSONObject("bodyCorrection").let {
                    it.getString("ecuId") == alias.ecuId && address(it.getString("request")) == ecu.request
                }
                assertTrue("${alias.ecuId} at $dia", inRouteMap || corrected)
            }
        }
    }

    @Test
    fun everyCataloguedReadPassesTheWhitelist() {
        fun allowed(address: Int, vararg uds: Int) = DiagnosticWhitelist.allowed(address, ByteArray(uds.size) { uds[it].toByte() })
        EcuCatalog.all.forEach { ecu ->
            val request = ecu.request ?: return@forEach
            assertEquals(Ecu.DTC_MASK, ecu.dtcMask)
            if (ecu.uds) {
                assertTrue(ecu.address, allowed(request, 0x22, 0xF1, 0x94))
                assertTrue(ecu.address, allowed(request, 0x19, 0x01, ecu.dtcMask))
                assertTrue(ecu.address, allowed(request, 0x19, 0x02, ecu.dtcMask))
            }
            if (ecu.obd) {
                listOf(intArrayOf(0x01, 0x01), intArrayOf(0x03), intArrayOf(0x07), intArrayOf(0x0A), intArrayOf(0x09, 0x0A))
                    .forEach { assertTrue(ecu.address, allowed(request, *it)) }
            }
            assertEquals(request + 8, ecu.reply)
        }
    }

    @Test
    fun namesAndInferenceMarksAreFilled() {
        EcuCatalog.all.forEach {
            assertTrue(it.abbreviation.isNotBlank() && it.name.isNotBlank() && it.shortName.isNotBlank())
        }
        assertEquals("ECM (UAES)", EcuCatalog.byRequest(0x7E0)!!.title)
        assertEquals(
            setOf("SMCU", "SMCU3", "DCOBC", "LFCR", "RFCR", "RRCR", "LRCR", "IPA", "R_ZCU", "F_ZCU", "FL_L", "FL_R",
                "INS", "AMP", "WCT", "SWS", "ACC", "TMIM", "PTC"),
            EcuCatalog.all.filter { it.inferred }.map { it.abbreviation }.toSet(),
        )
    }
}
