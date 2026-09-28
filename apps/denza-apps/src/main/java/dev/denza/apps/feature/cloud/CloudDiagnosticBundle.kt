package dev.denza.apps.feature.cloud

import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixed, bounded read operations. No Binder transactions, property writes or network probes. */
internal class CloudDiagnosticBundle(private val shell: (String) -> String) {
    data class Result(val files: Map<String, ByteArray>, val unavailable: Int)

    fun collect(report: String, progress: (String) -> Unit = {}): Result {
        val files = linkedMapOf<String, ByteArray>()
        val notes = mutableListOf<String>()
        var unavailable = 0
        files["app-report.txt"] = CloudDiagnosticPrivacy.clean(report).toByteArray()
        for ((name, command) in READS) {
            progress(name)
            val output = runCatching { shell(command) }
            val raw = output.getOrNull()
            val complete = raw?.lineSequence()?.lastOrNull { it.isNotBlank() } == "@@exit:0"
            if (!complete) unavailable++
            notes += "$name: ${if (complete) "OK" else "UNAVAILABLE_OR_PARTIAL"}"
            files[name] = CloudDiagnosticPrivacy.clean(
                raw ?: "Read failed: ${output.exceptionOrNull()?.javaClass?.simpleName}",
            ).toByteArray()
        }
        progress("cloudmanager")
        val binary = runCatching { readExecutable(shell(EXECUTABLE)) }.getOrNull()
        if (binary != null) {
            files["firmware/cloudmanager"] = binary
            notes += "firmware/cloudmanager: ${binary.size} bytes sha256=${sha256(binary)}"
        } else {
            unavailable++
            notes += "firmware/cloudmanager: unavailable, unreadable or exceeds $MAX_EXECUTABLE bytes"
        }
        files["README.txt"] = ("""
            Yangwang U9 cloud diagnostic bundle, format 1.
            Collected locally by Denza Apps using its existing trusted shell access.
            Collection does not change cloud settings, SIM, Wi-Fi, logging levels or services.
            app-report.txt is a snapshot; each shell read has its own device timestamp.
            Logs are bounded snapshots, not a complete recording. Payload/identity lines are removed.
            No log tag allowlist is applied to the native process. Related framework logs are separate.
            Missing/partial data is not a zero value or proof that a component is absent.
            No cloud connection success is inferred by this collector.
            The optional firmware/cloudmanager is the installed executable, not process memory.
            The archive is local only; send this file to the researcher using a USB drive.

        """.trimIndent() + "\n" + notes.joinToString("\n") + "\n").toByteArray()
        return Result(files, unavailable)
    }

    companion object {
        const val MAX_EXECUTABLE = 2 * 1024 * 1024
        private const val MAX_LOG = 2 * 1024 * 1024

        private fun read(command: String): String = "date -u; $command; echo @@exit:\$?"

        // Values here are status/configuration, never the full property store or telephony dump.
        private val properties = listOf(
            "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
            "ro.product.model", "ro.product.device", "ro.build.byd.apn_type",
            "persist.sys.byd.apn_type", "persist.radio.net.lte.apn1.disable",
            "net.lte.apn1.state", "net.lte.apn3.state", "net.lte.apn1.ifname",
            "net.lte.apn3.ifname", "net.apn3.wifi", "persist.radio.net.lte.apn3.onwifi",
            "persist.sys.cloud.app_reg_status", "sys.tcp_step", "sys.tcp_reg_errcode",
            "init.svc.cloudmanager",
        )
        private val nativePid = """
            u9_pid=${'$'}(pidof cloudmanager)
            case "${'$'}u9_pid" in ""|*[!0-9]*) echo NO_SINGLE_CLOUDMANAGER_PID; exit 4;; esac
            echo "cloudmanager_pid=${'$'}u9_pid"
        """.trimIndent()

        internal val READS = linkedMapOf(
            "properties.txt" to read(properties.joinToString("; ") {
                "echo '$it'; getprop '$it'"
            } + "; settings get global byd_off_wifi_switch"),
            "interfaces.txt" to read("timeout 5 sh -c 'service list | grep -iE \"cloud|mqtt|autoservice|byd\"'"),
            "native-log.txt" to read("timeout 6 sh -c 'set -o pipefail; " + nativePid +
                "\nlogcat -b main -b system -b crash -d -t 6000 -v threadtime --pid=\"\$u9_pid\" \"*:V\" | head -c $MAX_LOG'"),
            "framework-log.txt" to read("timeout 6 sh -c 'set -o pipefail; " +
                "logcat -b main -b system -d -t 12000 -v threadtime \"*:V\" | " +
                "grep -iE \"bydcloud|cloudmanager|BYDMultiApn|BYDConnectManager|BYDTCP|CONNECTIVITY_CHANGE_FUNCTION|apn_type\" | " +
                "head -c $MAX_LOG'"),
            "native-maps.txt" to read("timeout 5 sh -c '" + nativePid +
                "\nhead -c 262144 /proc/\$u9_pid/maps'"),
            "firmware-files.txt" to read("timeout 8 sh -c 'for u9_file in " +
                "/system/bin/cloudmanager /system/bin/cloudmanager.sh /system/etc/init/cloudmanager.rc " +
                "/system/framework/framework.jar /system/framework/services.jar; do " +
                "echo \"FILE \$u9_file\"; ls -l \"\$u9_file\"; " +
                "if [ -r \"\$u9_file\" ]; then sha256sum \"\$u9_file\"; else echo UNREADABLE; fi; done'"),
        )

        internal val EXECUTABLE = """
            timeout 10 sh -c '
            u9_file=/system/bin/cloudmanager
            [ -r "${'$'}u9_file" ] || exit 4
            u9_size=${'$'}(wc -c < "${'$'}u9_file")
            [ "${'$'}u9_size" -gt 0 ] && [ "${'$'}u9_size" -le $MAX_EXECUTABLE ] || exit 5
            echo "@@size:${'$'}u9_size"
            base64 "${'$'}u9_file" || exit 6
            echo @@end'
        """.trimIndent()

        internal fun readExecutable(output: String): ByteArray? {
            val lines = output.trim().lines()
            val size = lines.firstOrNull()?.removePrefix("@@size:")?.toIntOrNull() ?: return null
            if (size !in 4..MAX_EXECUTABLE || lines.lastOrNull() != "@@end") return null
            val bytes = runCatching { Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString("")) }
                .getOrNull() ?: return null
            return bytes.takeIf { it.size == size && it.take(4) == listOf<Byte>(127, 69, 76, 70) }
        }

        internal fun writeZip(files: Map<String, ByteArray>, output: OutputStream) {
            ZipOutputStream(output.buffered()).use { zip ->
                files.forEach { (name, bytes) ->
                    require(!name.startsWith('/') && name.split('/').none { it == ".." })
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Keep unknown diagnostic wording, but omit identity, location, credentials and packet dumps. */
internal object CloudDiagnosticPrivacy {
    private val sensitive = Regex(
        "(?i)imsi|iccid|imei|\\bvin\\b|uuid|guid|token|password|secret|private.?key|certificate|" +
            "gps|latitude|longitude|location|payload|\\[BYDCLOUD]pack|\\b(?:send|recv|receive)[ _-]*(?:data|buf|packet)|" +
            "\\b(?:key|pin|credential|authorization)\\b|[{}]",
    )
    private val digits = Regex("(?<![0-9])[0-9]{12,}(?![0-9])")
    private val vin = Regex("\\b[A-HJ-NPR-Z0-9]{17,20}\\b")
    private val blob = Regex("[A-Za-z0-9+/=_-]{40,}")
    private val hex = Regex("\\b[0-9a-fA-F]{24,}\\b")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")
    private val hexDump = Regex("(?:\\b[0-9a-fA-F]{2}[ :,-]+){8,}")
    private val ip = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")

    fun clean(text: String): String = text.lineSequence().take(16000).joinToString("\n") { line ->
        if (Regex("APK SHA-256: [a-f0-9]{64}").matches(line)) {
            line
        } else if (line.length > 4096 || sensitive.containsMatchIn(line) || hexDump.containsMatchIn(line)) {
            "[sensitive or payload line omitted]"
        } else {
            val identityFree = uuid.replace(vin.replace(digits.replace(line, "[identifier]"), "[identifier]"), "[identifier]")
            ip.replace(blob.replace(hex.replace(identityFree, "[blob]"), "[blob]"), "[ip]")
        }
    }
}
