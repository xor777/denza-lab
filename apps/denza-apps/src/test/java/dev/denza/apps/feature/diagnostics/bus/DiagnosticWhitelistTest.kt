package dev.denza.apps.feature.diagnostics.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticWhitelistTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun allowed(address: Int, uds: String) = DiagnosticWhitelist.refusal(address, bytes(uds))

    @Test
    fun everyReadTheSectionUsesIsAllowed() {
        listOf(
            0x7E0 to "22F194", 0x7E0 to "22F195", 0x7E0 to "22F18A", 0x720 to "22F197", 0x7E7 to "22F193", 0x5E7 to "22F193",
            0x782 to "190109", 0x782 to "190209", 0x720 to "1901FF", 0x7E0 to "190208",
            0x7E0 to "0100", 0x7E0 to "0101", 0x7E0 to "010C0D050B110F",
            0x7E5 to "03", 0x7E5 to "07", 0x7E5 to "0A",
            0x7E5 to "0900", 0x7E5 to "0904", 0x7E5 to "090A",
            0x7E0 to "0900", 0x7E0 to "0904", 0x7E0 to "090A", 0x7E0 to "0180", 0x7E0 to "01A0", 0x7E0 to "01A6",
            0x700 to "22F194", 0x7F7 to "22F194",
        ).forEach { (address, uds) -> assertNull("%03X %s".format(address, uds), allowed(address, uds)) }
    }

    /** The reads of the owner's decision 2026-10-23 (architecture, 3.4, 7.3), each in its own shape only. */
    @Test
    fun theVinACodesRecordsAndTheObdFreezeFrameAndTestsAreReadOnlyInTheirShape() {
        // The VIN at every address the list lets through, by UDS and by OBD.
        val addresses = (0x000..0x7FF).filter { allowed(it, "22F194") == null }
        addresses.forEach { assertNull("%03X".format(it), allowed(it, "22F190")) }
        assertNull(allowed(0x7E0, "0902"))
        listOf("22F19000", "22F1", "0902FF", "0901", "0903", "0906", "0908").forEach { assertNotNull(it, allowed(0x7E0, it)) }

        // 19 04 / 19 06: a three-byte code and one record number, FF for all; nothing shorter or longer.
        listOf("19041A3A2201", "19041A3A22FF", "1906C11709FF", "190600000000", "1904FFFFFF01").forEach {
            assertNull(it, allowed(0x7E7, it))
        }
        listOf("1903", "19031A3A22", "19041A3A22", "19041A3A220100", "19061A3A22", "1905000000FF", "1907FF0D",
            "1914", "190A", "19010D00", "190209FF", "1904", "1906").forEach { assertNotNull(it, allowed(0x7E7, it)) }
        // Every 19 sub-function but 01, 02, 04 and 06 is refused, at every length the list allows.
        (0x00..0xFF).filter { it !in setOf(0x01, 0x02, 0x04, 0x06) }.forEach { sub ->
            listOf("", "0D", "1A3A22FF", "1A3A2201").forEach { tail ->
                val uds = "19%02X%s".format(sub, tail)
                if (uds.length <= 14) assertNotNull(uds, allowed(0x7E7, uds))
            }
        }

        // OBD 02: one to three PID and frame pairs.
        listOf("020200", "020000", "020C000D00", "020C000D000500").forEach { assertNull(it, allowed(0x7E0, it)) }
        listOf("02", "0202", "02020000", "020C000D", "020C000D0005", "020C000D0005000B").forEach { assertNotNull(it, allowed(0x7E0, it)) }
        // OBD 06: one MID.
        listOf("0600", "0601", "06A1", "06E0").forEach { assertNull(it, allowed(0x7E0, it)) }
        listOf("06", "060001", "06000102").forEach { assertNotNull(it, allowed(0x7E0, it)) }

        // The same shapes stay closed at the addresses the list refuses.
        listOf("22F190", "0902", "19041A3A22FF", "020200", "0600").forEach { uds ->
            assertNotNull(allowed(0x7DF, uds))
            assertNotNull(allowed(0x7E8, uds))
            assertNotNull(allowed(0x6FF, uds))
        }
    }

    /** The parents' child codes, approved by the owner 2026-10-21 (architecture, 3.4). */
    private val children = mapOf(
        0x750 to setOf(0xF8C4), 0x7B5 to setOf(0xF8D4), 0x7D2 to setOf(0xF8E4, 0xF8F4),
        0x726 to setOf(0xF204, 0xF214, 0xF064, 0xF234),
    )

    @Test
    fun aChildCodeIsReadOnlyAtItsParentAndNoOtherDidIsOpened() {
        val identification = setOf(0xF190, 0xF193, 0xF194, 0xF195, 0xF18A, 0xF197)
        fun read(address: Int, did: Int) = allowed(address, "22%04X".format(did))
        // At each parent: the identification DIDs and the VIN, its own children, and of all 65 536 nothing else.
        children.forEach { (parent, dids) ->
            assertEquals("%03X".format(parent), identification + dids, (0x0000..0xFFFF).filter { read(parent, it) == null }.toSet())
        }
        // Nowhere else: every other address the whitelist lets through refuses all eight.
        val addresses = (0x000..0x7FF).filter { allowed(it, "22F194") == null }
        assertEquals(248 - 1 - 8 + 13, addresses.size)
        addresses.forEach { a ->
            children.forEach { (parent, dids) -> dids.forEach { d -> if (a != parent) assertNotNull("%03X %04X".format(a, d), read(a, d)) } }
        }
        // A child code is still one DID, read only.
        assertNotNull(allowed(0x750, "22F8C4F8D4"))
        assertNotNull(allowed(0x750, "2EF8C400"))
        assertNotNull(allowed(0x750, "22F8C5"))
    }

    @Test
    fun thePassengerComputersCodeAndBuildVersionAreReadOnlyThere() {
        val identification = setOf(0xF190, 0xF193, 0xF194, 0xF195, 0xF18A, 0xF197)
        fun read(address: Int, did: Int) = allowed(address, "22%04X".format(did))
        // At 5E7: the identification DIDs and the VIN, its combined code F294 and its build version F295, nothing else.
        assertEquals(identification + setOf(0xF294, 0xF295), (0x0000..0xFFFF).filter { read(0x5E7, it) == null }.toSet())
        // Nowhere else, and only as a read of one DID.
        (0x000..0x7FF).filter { it != 0x5E7 && allowed(it, "22F194") == null }.forEach { a ->
            listOf(0xF294, 0xF295).forEach { d -> assertNotNull("%03X %04X".format(a, d), read(a, d)) }
        }
        assertNotNull(allowed(0x5E7, "22F294F295"))
        assertNotNull(allowed(0x5E7, "2EF29400"))
    }

    @Test
    fun nothingThatChangesAnEcuIsAllowed() {
        listOf(
            "1003", "1001", "1101", "1103", "14FFFFFF", "2701", "2702", "280000", "2EF19000",
            "2F000000", "3101FF00", "3E00", "8502", "3D00", "04", "08", "0805", "05", "0500", "22F18C",
            "190A", "1902", "19", "22F1", "0300", "090C", "22F8C4", "22F204", "2EF190", "2E", "1903",
        ).forEach { uds -> assertNotNull(uds, allowed(0x7E0, uds)) }
        // Of the 256 first bytes only the read services open: 01, 02, 03, 06, 07, 09, 0A, 19, 22.
        val tails = listOf("", "00", "01", "02", "0D", "0109", "0209", "F190", "F194", "0200", "01FFFFFF01", "04FFFFFF01",
            "000000", "0000000000", "000000000000")
        val open = (0x00..0xFF).filter { sid -> tails.any { allowed(0x7E0, "%02X".format(sid) + it) == null } }
        assertEquals(listOf(0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A, 0x19, 0x22), open)
    }

    @Test
    fun onlyPhysicalRequestIdsAreAllowed() {
        assertNotNull(allowed(0x7DF, "0101"))
        (0x7E8..0x7EF).forEach { assertNotNull("%03X".format(it), allowed(it, "0101")) }
        assertNotNull(allowed(0x6FF, "22F194"))
        assertNotNull(allowed(0x7F8, "22F194"))
        // 5xx only route by route: the factory-map routes that answered F194, not their neighbours.
        listOf(0x582, 0x5B2, 0x5E7, 0x5F4).forEach { assertNull("%03X".format(it), allowed(it, "190109")) }
        listOf(0x500, 0x581, 0x591, 0x5C4, 0x5F5, 0x5FF).forEach { assertNotNull("%03X".format(it), allowed(it, "22F194")) }
        assertNotNull(allowed(0x582, "1101"))
        assertNotNull(allowed(0x18DA00F1, "22F194"))
    }

    @Test
    fun anOverlongOrEmptyPayloadIsRefused() {
        assertNotNull(allowed(0x7E0, "010C0D050B110F04"))
        assertNotNull(DiagnosticWhitelist.refusal(0x7E0, ByteArray(0)))
        assertNotNull(DiagnosticWhitelist.refusal(0x7E0, null))
    }

    @Test
    fun aRequestCannotBeMadeOutsideTheList() {
        assertThrows(IllegalArgumentException::class.java) { DiagRequest(0x7E0, bytes("1401FFFF")) }
        assertThrows(IllegalArgumentException::class.java) { DiagRequest(0x7DF, bytes("0101")) }
        assertThrows(IllegalArgumentException::class.java) { DiagRequest(0x7E0, bytes("0101"), timeoutMs = 7_000) }
        assertEquals("7E0 190109 4000", DiagRequest(0x7E0, bytes("190109")).line)
        assertEquals(0x7E8, DiagRequest(0x7E0, bytes("190109")).reply)
    }

    @Test
    fun aNegativeAnswerCarriesItsCode() {
        val request = DiagRequest(0x7E5, bytes("22F194"))
        assertEquals(0x11, DiagReply.Answer(request, bytes("7F2211"), 1, 0).nrc)
        assertNull(DiagReply.Answer(request, bytes("62F19432"), 1, 0).nrc)
    }
}
