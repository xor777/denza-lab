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

    /** The parents' child codes, approved by the owner 2026-10-21 (architecture, 3.4). */
    private val children = mapOf(
        0x750 to setOf(0xF8C4), 0x7B5 to setOf(0xF8D4), 0x7D2 to setOf(0xF8E4, 0xF8F4),
        0x726 to setOf(0xF204, 0xF214, 0xF064, 0xF234),
    )

    @Test
    fun aChildCodeIsReadOnlyAtItsParentAndNoOtherDidIsOpened() {
        val identification = setOf(0xF193, 0xF194, 0xF195, 0xF18A, 0xF197)
        fun read(address: Int, did: Int) = allowed(address, "22%04X".format(did))
        // At each parent: the four identification DIDs and its own children, and of all 65 536 nothing else.
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
    fun nothingThatChangesAnEcuOrReadsTheVinIsAllowed() {
        listOf(
            "1003", "1001", "1101", "1103", "14FFFFFF", "2701", "2702", "280000", "2EF19000",
            "2F000000", "3101FF00", "3E00", "8502", "3D00", "04", "08", "0902", "22F190", "22F18C",
            "1904FFFFFF01", "1906FFFFFFFF", "190A", "1902", "19", "22F1", "0300", "090C", "22F8C4", "22F204",
        ).forEach { uds -> assertNotNull(uds, allowed(0x7E0, uds)) }
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
