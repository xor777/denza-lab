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
            0x7E0 to "22F194", 0x7E0 to "22F195", 0x7E0 to "22F18A", 0x720 to "22F197",
            0x782 to "190109", 0x782 to "190209", 0x720 to "1901FF", 0x7E0 to "190208",
            0x7E0 to "0100", 0x7E0 to "0101", 0x7E0 to "010C0D050B110F",
            0x7E5 to "03", 0x7E5 to "07", 0x7E5 to "0A",
            0x7E5 to "0900", 0x7E5 to "0904", 0x7E5 to "090A",
            0x700 to "22F194", 0x7F7 to "22F194",
        ).forEach { (address, uds) -> assertNull("%03X %s".format(address, uds), allowed(address, uds)) }
    }

    @Test
    fun nothingThatChangesAnEcuOrReadsTheVinIsAllowed() {
        listOf(
            "1003", "1001", "1101", "1103", "14FFFFFF", "2701", "2702", "280000", "2EF19000",
            "2F000000", "3101FF00", "3E00", "8502", "3D00", "04", "08", "0902", "22F190", "22F18C",
            "1904FFFFFF01", "1906FFFFFFFF", "190A", "1902", "19", "22F1", "0300", "090C",
        ).forEach { uds -> assertNotNull(uds, allowed(0x7E0, uds)) }
    }

    @Test
    fun onlyPhysicalRequestIdsAreAllowed() {
        assertNotNull(allowed(0x7DF, "0101"))
        (0x7E8..0x7EF).forEach { assertNotNull("%03X".format(it), allowed(it, "0101")) }
        assertNotNull(allowed(0x6FF, "22F194"))
        assertNotNull(allowed(0x7F8, "22F194"))
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
