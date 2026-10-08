package dev.denza.apps.feature.adb

import java.io.File
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class AdbPortRestoreTest {
    private val pkg = "dev.denza.apps"
    private val grant = "pm grant dev.denza.apps android.permission.WRITE_SECURE_SETTINGS"
    private val read = AdbPortReadout.COMMAND
    private val clock = { 90_000L }

    /** An invented answer, not a reading: a port held open by BYD's own switch, nothing else set. */
    private val answer = listOf(
        "persist.adb.tcp.port=",
        "service.adb.tcp.port=5555",
        "sys.connect.adb.wiress=1",
        "persist.sys.adb.wiress.enable=1",
        "adb_wifi_enabled=null",
    ).joinToString("\n", postfix = "\n")

    /** A car's shell: [answer] decides what each command does; everything asked is recorded. */
    private class Car(private val answer: Car.(String) -> String = { "" }) {
        val asked = mutableListOf<String>()
        var held = false

        fun shell(command: String): String {
            asked += command
            return answer(command)
        }
    }

    @Test
    fun `a held permission is left alone and nothing is granted`() {
        val car = Car().apply { held = true }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)

        assertEquals(SecureSettingsGrant.ALREADY_HELD, state.grant)
        assertEquals("nothing but the read", listOf(read), car.asked)
    }

    @Test
    fun `a missing permission is granted over the shell once, then the port is read`() {
        val car = Car { command ->
            if (command == grant) held = true
            ""
        }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)

        assertEquals(SecureSettingsGrant.GRANTED, state.grant)
        assertNull(state.grantFailure)
        assertEquals(listOf(grant, read), car.asked)
    }

    @Test
    fun `a grant from an earlier pass stays the answer on the next one`() {
        val car = Car().apply { held = true }

        val state = AdbPortRestorePass.run(
            pkg,
            { car.held },
            car::shell,
            AdbPortRestoreState(grant = SecureSettingsGrant.GRANTED),
            clock,
        )

        assertEquals(SecureSettingsGrant.GRANTED, state.grant)
        assertEquals(listOf(read), car.asked)
    }

    @Test
    fun `a refusal is recorded with what pm said and never raised`() {
        val car = Car { command ->
            if (command != grant) return@Car ""
            "\nException occurred while executing 'grant':\n" +
                "java.lang.SecurityException: Package dev.denza.apps has not requested permission\n"
        }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)

        assertEquals(SecureSettingsGrant.FAILED, state.grant)
        assertEquals("Exception occurred while executing 'grant':", state.grantFailure)
    }

    @Test
    fun `a shell that times out is a failed grant, not a failed runtime pass`() {
        val car = Car { throw SocketTimeoutException("Read timed out") }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)

        assertEquals(SecureSettingsGrant.FAILED, state.grant)
        assertEquals("SocketTimeoutException", state.grantFailure)
    }

    @Test
    fun `the readout says what keeps the port open, and unset reads as unset`() {
        val car = Car { command -> if (command == read) answer else "" }.apply { held = true }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)

        assertEquals(
            AdbPortReadout(
                persistTcpPort = null,
                serviceTcpPort = "5555",
                wirelessSwitch = "1",
                wirelessSwitchPersisted = "1",
                adbWifiEnabled = null,
            ),
            state.readout,
        )
        assertEquals(90_000L, state.readAtMs)
        assertNull(state.readFailure)
    }

    @Test
    fun `a failed grant does not skip the read, and a failed read keeps the last answer`() {
        var readWorks = true
        val car = Car { command ->
            when {
                command == grant -> throw SocketTimeoutException("Read timed out")
                readWorks -> answer
                else -> throw SocketTimeoutException("Read timed out")
            }
        }

        val first = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState(), clock)
        assertEquals(SecureSettingsGrant.FAILED, first.grant)
        assertEquals("5555", first.readout?.serviceTcpPort)

        readWorks = false
        val second = AdbPortRestorePass.run(pkg, { car.held }, car::shell, first) { 210_000L }

        assertEquals("SocketTimeoutException", second.readFailure)
        assertEquals(first.readout, second.readout)
        assertEquals(90_000L, second.readAtMs)
        assertEquals(
            "ошибка: SocketTimeoutException; показано прочитанное 2 мин назад",
            AdbPortRestoreReport.rows(false, second, nowMs = 210_000L).toMap().getValue("Прочитано"),
        )
    }

    /**
     * The command through a real shell, with the car's two readers stubbed: it is valid `sh`, it
     * reads only, and what it prints is what [AdbPortReadout.parse] reads.
     */
    @Test
    fun `the read command runs in a shell and reads back`() {
        assumeTrue("this host has no /bin/sh", File("/bin/sh").canExecute())
        val stubs = """
            getprop() { case "${'$'}1" in service.adb.tcp.port) echo 5555 ;; sys.connect.adb.wiress) echo 1 ;; esac; }
            settings() { [ "${'$'}*" = "get global adb_wifi_enabled" ] && echo 1 || echo "unexpected: ${'$'}*"; }
            setprop() { echo "setprop ${'$'}*" >&2; exit 9; }
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", stubs + "\n" + read)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())

        assertEquals(
            AdbPortReadout(
                persistTcpPort = null,
                serviceTcpPort = "5555",
                wirelessSwitch = "1",
                wirelessSwitchPersisted = null,
                adbWifiEnabled = "1",
            ),
            AdbPortReadout.parse(output),
        )
    }

    @Test
    fun `before any pass the section has its rows and says nothing was read`() {
        val rows = AdbPortRestoreReport.rows(false, AdbPortRestoreState(), nowMs = 0L)

        assertEquals(
            listOf(
                "WRITE_SECURE_SETTINGS",
                "persist.adb.tcp.port",
                "service.adb.tcp.port",
                "sys.connect.adb.wiress",
                "persist.sys.adb.wiress.enable",
                "adb_wifi_enabled",
                "Прочитано",
            ),
            rows.map { it.first },
        )
        assertEquals("—", rows.toMap().getValue("service.adb.tcp.port"))
        assertEquals("ещё не было", rows.toMap().getValue("Прочитано"))
    }

    @Test
    fun `after a read an unset value says so and a set one is shown as read`() {
        val state = AdbPortRestoreState(
            readout = AdbPortReadout.parse(answer),
            readAtMs = 60_000L,
        )

        val rows = AdbPortRestoreReport.rows(true, state, nowMs = 72_000L).toMap()

        assertEquals("не задано", rows.getValue("persist.adb.tcp.port"))
        assertEquals("5555", rows.getValue("service.adb.tcp.port"))
        assertEquals("не задано", rows.getValue("adb_wifi_enabled"))
        assertEquals("12 с назад", rows.getValue("Прочитано"))
    }

    @Test
    fun `the report says whether the permission is held and how it came to be`() {
        fun permission(held: Boolean, state: AdbPortRestoreState) =
            AdbPortRestoreReport.rows(held, state, nowMs = 0L).toMap().getValue("WRITE_SECURE_SETTINGS")

        assertEquals("не выдано, ждёт доступа к ADB", permission(false, AdbPortRestoreState()))
        assertEquals("выдано", permission(true, AdbPortRestoreState()))
        assertEquals(
            "выдано приложением",
            permission(true, AdbPortRestoreState(grant = SecureSettingsGrant.GRANTED)),
        )
        assertEquals(
            "не выдано: SocketTimeoutException",
            permission(
                false,
                AdbPortRestoreState(
                    grant = SecureSettingsGrant.FAILED,
                    grantFailure = "SocketTimeoutException",
                ),
            ),
        )
        // Revoked from outside after a pass found it held.
        assertEquals(
            "не выдано",
            permission(false, AdbPortRestoreState(grant = SecureSettingsGrant.ALREADY_HELD)),
        )
    }
}
