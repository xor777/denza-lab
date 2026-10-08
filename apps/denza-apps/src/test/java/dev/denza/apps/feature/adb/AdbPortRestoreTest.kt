package dev.denza.apps.feature.adb

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPortRestoreTest {
    private val pkg = "dev.denza.apps"
    private val grant = "pm grant dev.denza.apps android.permission.WRITE_SECURE_SETTINGS"

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

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState())

        assertEquals(SecureSettingsGrant.ALREADY_HELD, state.grant)
        assertEquals(emptyList<String>(), car.asked)
    }

    @Test
    fun `a missing permission is granted over the shell once, and only that is sent`() {
        val car = Car {
            held = true
            ""
        }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState())

        assertEquals(SecureSettingsGrant.GRANTED, state.grant)
        assertNull(state.grantFailure)
        assertEquals(listOf(grant), car.asked)
    }

    @Test
    fun `a grant from an earlier pass stays the answer on the next one`() {
        val car = Car().apply { held = true }

        val state = AdbPortRestorePass.run(
            pkg,
            { car.held },
            car::shell,
            AdbPortRestoreState(grant = SecureSettingsGrant.GRANTED),
        )

        assertEquals(SecureSettingsGrant.GRANTED, state.grant)
        assertEquals(emptyList<String>(), car.asked)
    }

    @Test
    fun `a refusal is recorded with what pm said and never raised`() {
        val car = Car {
            "\nException occurred while executing 'grant':\n" +
                "java.lang.SecurityException: Package dev.denza.apps has not requested permission\n"
        }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState())

        assertEquals(SecureSettingsGrant.FAILED, state.grant)
        assertEquals("Exception occurred while executing 'grant':", state.grantFailure)
    }

    @Test
    fun `a shell that times out is a failed grant, not a failed runtime pass`() {
        val car = Car { throw SocketTimeoutException("Read timed out") }

        val state = AdbPortRestorePass.run(pkg, { car.held }, car::shell, AdbPortRestoreState())

        assertEquals(SecureSettingsGrant.FAILED, state.grant)
        assertEquals("SocketTimeoutException", state.grantFailure)
    }

    @Test
    fun `the report says whether the permission is held and how it came to be`() {
        fun permission(held: Boolean, state: AdbPortRestoreState) =
            AdbPortRestoreReport.rows(held, state).toMap().getValue("WRITE_SECURE_SETTINGS")

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
