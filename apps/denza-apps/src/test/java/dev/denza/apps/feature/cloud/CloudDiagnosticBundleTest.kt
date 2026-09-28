package dev.denza.apps.feature.cloud

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class CloudDiagnosticBundleTest {
    @Test
    fun `unknown firmware log wording survives but identifiers and packet dumps do not`() {
        val cleaned = CloudDiagnosticPrivacy.clean("""
            09-28 14:29:31.100 750 753 E U9Cloud: waiting for APN1, status=0
            IMSI=460011234567890
            ICCID=89860112345678901234
            received value: LGXC14CFXS1234567X
            [BYDCLOUD]pack: 8c 09 12 34 56 78 90 ab cd ef
            packet={"secret":"example"}
            native value=460011234567890, peer=192.168.88.109
            TLS private_key=unprintable
            value=abcdefabcdefabcdefabcdefabcdefab
            app_reg_status=1
        """.trimIndent())
        assertTrue(cleaned.contains("waiting for APN1, status=0"))
        assertTrue(cleaned.contains("app_reg_status=1"))
        for (secret in listOf("460011234567890", "89860112345678901234", "LGXC14CFXS1234567X", "8c 09", "example", "unprintable", "192.168.88.109", "abcdefabcdefabcdefabcdefabcdefab")) {
            assertFalse(secret, cleaned.contains(secret))
        }
    }

    @Test
    fun `one inaccessible read does not discard the remaining diagnostics`() {
        val commands = mutableListOf<String>()
        val result = CloudDiagnosticBundle { command ->
            commands += command
            when (command) {
                CloudDiagnosticBundle.EXECUTABLE -> "Permission denied"
                CloudDiagnosticBundle.READS.getValue("native-maps.txt") -> throw java.io.IOException("secret")
                else -> "fixture status\n@@exit:0\n"
            }
        }.collect("retained log")
        assertEquals(2, result.unavailable)
        assertEquals(CloudDiagnosticBundle.READS.size + 1, commands.size)
        assertTrue(String(result.files.getValue("native-maps.txt")).contains("IOException"))
        assertFalse(String(result.files.getValue("native-maps.txt")).contains("secret"))
        assertTrue(String(result.files.getValue("README.txt")).contains("UNAVAILABLE_OR_PARTIAL"))
        assertFalse(result.files.containsKey("firmware/cloudmanager"))
    }

    @Test
    fun `zip preserves complete binary and text entries without external archiver`() {
        val elf = byteArrayOf(127, 69, 76, 70, 0, 1, -1, 2)
        val encoded = "@@size:${elf.size}\n${Base64.getEncoder().encodeToString(elf)}\n@@end\n"
        val result = CloudDiagnosticBundle { command ->
            if (command == CloudDiagnosticBundle.EXECUTABLE) encoded else "@@exit:0\n"
        }.collect("U9 firmware")
        assertEquals(0, result.unavailable)
        val output = ByteArrayOutputStream()
        CloudDiagnosticBundle.writeZip(result.files, output)
        val unpacked = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                unpacked[entry.name] = zip.readBytes()
            }
        }
        assertEquals(result.files.keys, unpacked.keys)
        assertArrayEquals(elf, unpacked["firmware/cloudmanager"])
        assertEquals("U9 firmware", String(unpacked.getValue("app-report.txt")))
        assertTrue(String(unpacked.getValue("README.txt")).contains(CloudDiagnosticBundle.sha256(elf)))
    }

    @Test
    fun `partial or invalid executable output is never exported as firmware`() {
        for (value in listOf("@@size:8\nAAAA\n@@end", "@@size:999999999\n@@end", "@@size:8\nBAD", "denied")) {
            assertNull(CloudDiagnosticBundle.readExecutable(value))
        }
    }

    @Test
    fun `fixed shell commands parse and do not contain vehicle mutations`() {
        for (command in CloudDiagnosticBundle.READS.values + CloudDiagnosticBundle.EXECUTABLE) {
            val process = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
            process.outputStream.use { it.write(command.toByteArray()) }
            val error = process.inputStream.bufferedReader().readText()
            assertEquals(error, 0, process.waitFor())
            assertFalse(command, Regex("setprop|service call|am broadcast|reboot|logcat -c|settings (put|delete)|kill").containsMatchIn(command))
        }
    }
}
