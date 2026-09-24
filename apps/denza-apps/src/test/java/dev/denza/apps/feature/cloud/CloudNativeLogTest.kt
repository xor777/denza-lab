package dev.denza.apps.feature.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudNativeLogTest {
    private val prefix = """
        @@native:1
        @@pid:113
        @@registration
        0
        @@token
        0
        @@imsi
        length=15 alphabet=DIGITS trailingF=0
        @@iccid
        length=20 alphabet=DIGITS trailingF=0
        @@log
    """.trimIndent() + "\n"
    private val suffix = "\n@@logExit:0\n@@pidAfter:113\n@@done\n"
    private fun line(message: String, tag: String = "[BYDCLOUD]main", pid: Int = 113, time: String = "1790163602.210") =
        "$time  $pid  1078 D $tag: $message"

    @Test fun completionAndRegistrationReplyAreSeparateTimestampedEvents() {
        // Literal formats come from the reference firmware, not a claimed live rejection trace.
        val log = listOf(line("send211"), line("send_complete status :1, key_id :211"),
            line("211 reg_status 3"), line("211 register fail! fail code = 3, mRetryCount = 1"))
        val snapshot = CloudNativeLog.parse(prefix + log.joinToString("\n") + suffix)
        assertEquals("OK", snapshot.status)
        assertEquals(listOf("registration_start", "send_complete command=211 success=1",
            "registration_reply code=3", "registration_failed code=3 retry=1"), snapshot.events.map { it.message })
        assertTrue(snapshot.events.all { it.at.startsWith("2026-09-23T") })
    }

    @Test fun failedSendIsNotCalledARegistrationRejection() {
        val event = CloudNativeLog.parse(prefix + line("send_complete status :0, key_id :211") + suffix).events.single()
        assertEquals("send_complete command=211 success=0", event.message)
        assertFalse(event.message.contains("reply"))
    }

    @Test fun knownTransportFailuresAreClassifiedWithoutTheirFreeText() {
        val log = listOf(
            line("send211,but can not parse domian ip;"),
            line("connect error:110: arbitrary private data", "[BYDCLOUD]socket"),
            line("SSL_connect failed", "[BYDCLOUD]SSLUtils"),
            line("sslerr is:5:", "[BYDCLOUD]SSLUtils"),
            line("SSL Error: certificate verify failed; private certificate subject", "[BYDCLOUD]SSLUtils"),
        )
        val result = CloudNativeLog.parse(prefix + log.joinToString("\n") + suffix)
        assertEquals(listOf("dns_failed registration", "socket_connect_failed errno=110",
            "tls_handshake_failed", "tls_error code=5", "tls_certificate_verification_failed"), result.events.map { it.message })
        assertFalse(result.toString().contains("private"))
    }

    @Test fun rawIdentifiersPayloadsAndUnknownFieldsCannotEnterTheReport() {
        val secret = "TEST_PRIVATE_VALUE_0123456789"
        val log = listOf(
            line("imsi=$secret iccid=$secret token=$secret"),
            line("SSL Error: $secret", "[BYDCLOUD]SSLUtils"),
            line("getSSLIPByDomainName is $secret"),
            line("function_apn address: $secret", "c_ares_dns"),
            line("send_complete status :1, key_id :211 $secret"),
            line("211 reg_status 123456789012345"),
        )
        val result = CloudNativeLog.parse(prefix.replace("\n0\n", "\n$secret\n") + log.joinToString("\n") + suffix)
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains("123456789012345"))
        assertEquals("UNKNOWN", result.registration)
        assertEquals("UNKNOWN", result.token)
        assertEquals(3, result.unclassified)
    }

    @Test fun wrongPidAndTagCannotMasqueradeAsANativeReply() {
        val log = listOf(line("211 reg_status 3", pid = 114), line("211 reg_status 3", tag = "OtherApp"))
        val result = CloudNativeLog.parse(prefix + log.joinToString("\n") + suffix)
        assertTrue(result.events.isEmpty())
        assertEquals(2, result.unclassified)
    }

    @Test fun emptySuccessfulWindowDiffersFromUnavailableOrInterruptedLogging() {
        assertEquals("OK", CloudNativeLog.parse(prefix + suffix).status)
        assertEquals("INCOMPLETE", CloudNativeLog.parse(prefix).status)
        assertEquals("COMMAND_FAILED", CloudNativeLog.parse(prefix + suffix.replace("Exit:0", "Exit:1")).status)
        assertEquals("TIMEOUT", CloudNativeLog.parse(prefix + suffix.replace("Exit:0", "Exit:124")).status)
        assertEquals("NO_PROCESS", CloudNativeLog.parse(prefix.replace("@@pid:113", "@@pid:") + suffix.replace("Exit:0", "Exit:NO_PROCESS")).status)
    }

    @Test fun processChangeIsExplicitAndAlreadyReadEventsKeepTheirOriginalPid() {
        val result = CloudNativeLog.parse(prefix + line("send211") + suffix.replace("pidAfter:113", "pidAfter:114"))
        assertEquals("PROCESS_CHANGED", result.status)
        assertEquals("113", result.events.single().pid)
    }

    @Test fun collectionIsRateLimitedAndOverlappingWindowsDoNotDuplicateEvents() {
        val first = CloudNativeLog.parse(prefix + line("send211") + suffix).events
        val cursor = CloudNativeCursor()
        assertTrue(cursor.due(0))
        assertFalse(cursor.due(1_000))
        assertTrue(cursor.due(15_000))
        assertEquals(first, cursor.fresh(first))
        assertTrue(cursor.fresh(first).isEmpty())
        val restarted = CloudNativeCursor(first.single().line())
        assertTrue(restarted.fresh(first).isEmpty())
        assertEquals(1, cursor.fresh(listOf(first.single().copy(pid = "114"))).size)
        assertEquals(1, cursor.fresh(listOf(first.single().copy(at = "2026-09-23T11:40:02.211Z"))).size)
    }

    @Test fun cappedWindowsAreVisibleAndNativeNoiseCannotExhaustAdapterHistory() {
        val log = List(CloudNativeLog.WINDOW) { line("not an exported event") }.joinToString("\n")
        val result = CloudNativeLog.parse(prefix + log + suffix)
        assertTrue(result.windowFull)
        assertEquals(CloudNativeLog.WINDOW, result.unclassified)
        assertEquals("OK", result.status)
        val native = CloudLinkTrace()
        repeat(2000) { native.add("T$it", "tls_handshake_failed") }
        assertEquals(512, native.text().lines().size)
    }

    @Test fun capturedWifiTraceReplaysDnsSocketAndTlsProgress() {
        val fixture = javaClass.getResource("/cloud/native-wifi.log")!!.readText()
        val result = CloudNativeLog.parse(prefix + fixture + suffix)
        assertEquals("OK", result.status)
        val events = result.events.map { it.message }
        assertTrue(events.contains("dns_failed registration"))
        assertTrue(events.contains("network_notification value=4"))
        assertTrue(events.contains("socket_connect status=1"))
        assertTrue(events.contains("tls_handshake_complete"))
        assertTrue(events.contains("dns_address_returned"))
        assertFalse(events.any { it.contains("139.") || it.contains("dilink") })
    }

    @Test fun snapshotCommandIsFinitePassiveAndDoesNotPrintSimIdentity() {
        val command = CloudNativeLog.command()
        assertTrue(command.contains("timeout 3 logcat"))
        assertTrue(command.contains("-d -t 1200 -v epoch --pid="))
        assertTrue(command.contains("@@logExit:${'$'}?"))
        assertFalse(command.contains("logcat -c"))
        assertFalse(command.contains("setprop"))
        assertFalse(command.contains("service call"))
        assertFalse(command.contains("echo ${'$'}denza_diag_value"))
    }

    @Test fun shellClassifiesActualSyntheticPropertiesWithoutPrintingThem() {
        val imsi = "001010123456789"
        val values = listOf(
            "89010000000000000001" to "length=20 alphabet=DIGITS trailingF=0",
            "8901000000000000000" to "length=19 alphabet=DIGITS trailingF=0",
            "8901000000000000000F" to "length=20 alphabet=HEX trailingF=1",
            "890100000000000000ff" to "length=20 alphabet=HEX trailingF=2",
            "F".repeat(20) to "length=20 alphabet=HEX trailingF=20",
            "89-private-value" to "length=16 alphabet=OTHER trailingF=0",
            "" to "MISSING",
            "1".repeat(92) to "UNKNOWN",
        )
        for ((iccid, expected) in values) {
            // Stub only Android commands; execute the production POSIX shell classifier itself.
            val stubs = """
                getprop() { case "${'$'}1" in
                  ril.imsi) printf '%s' "${'$'}TEST_IMSI";;
                  ril.csim.iccid) printf '%s' "${'$'}TEST_ICCID";;
                  *) echo 0;; esac; }
                pidof() { echo 113; }
                timeout() { return 0; }
            """.trimIndent()
            val process = ProcessBuilder("/bin/sh", "-c", stubs + "\n" + CloudNativeLog.command())
                .apply { environment()["TEST_IMSI"] = imsi; environment()["TEST_ICCID"] = iccid }.start()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue())
            assertFalse(output.contains(imsi))
            if (iccid.isNotEmpty()) assertFalse(output.contains(iccid))
            val result = CloudNativeLog.parse(output)
            assertEquals("OK", result.status)
            assertEquals(expected, result.iccid)
            assertEquals("length=15 alphabet=DIGITS trailingF=0", result.imsi)
        }
    }

    @Test fun shapeParserRejectsExtraContentAndImpossibleCounts() {
        for (invalid in listOf("length=20 alphabet=DIGITS trailingF=0 PRIVATE",
            "length=20 alphabet=PRIVATE trailingF=0", "length=20 alphabet=HEX trailingF=21",
            "length=20 alphabet=DIGITS trailingF=1", "length=99 alphabet=HEX trailingF=0")) {
            val result = CloudNativeLog.parse(prefix.replace("length=20 alphabet=DIGITS trailingF=0", invalid) + suffix)
            assertEquals("UNKNOWN", result.iccid)
            assertFalse(result.toString().contains("PRIVATE"))
        }
    }
}
