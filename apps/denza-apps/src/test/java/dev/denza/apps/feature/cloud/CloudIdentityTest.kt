package dev.denza.apps.feature.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudIdentityTest {
    @Test fun generationHasExactAsciiLengthsAndPrefixes() {
        repeat(100) {
            val pair = CloudIdentity.generate()
            assertTrue(pair.iccid.startsWith("898607"))
            assertTrue(pair.imsi.startsWith("46001"))
            assertTrue(pair.valid())
            assertFalse(pair.toString().contains(pair.iccid))
            assertFalse(pair.toString().contains(pair.imsi))
        }
    }

    @Test fun manualPairRequiresExactAsciiDigitsWithoutChecksumRule() {
        assertTrue(CloudIdentity("89860712345678901234", "460011234567890").valid())
        assertFalse(CloudIdentity("8986071234567890123", "460011234567890").valid())
        assertFalse(CloudIdentity("89860712345678901234", "46001123456789a").valid())
        assertFalse(CloudIdentity("89860712345678901234", "46001123456789١").valid())
        assertEquals("CloudIdentity(redacted)", CloudIdentity("123", "456").toString())
    }
}
