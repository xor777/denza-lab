package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStartupGatePolicyTest {
    @Test
    fun `fast passive check does not flash a startup overlay`() {
        assertFalse(
            AdbStartupGatePolicy.overlay(
                AdbRescueSnapshot(phase = AdbRescuePhase.CHECKING),
            ).visible,
        )
    }

    @Test
    fun `unavailable startup does not trigger another internal probe`() {
        assertEquals(
            AdbStartupEntryAction.NONE,
            AdbStartupGatePolicy.entryAction(AdbRescuePhase.UNAVAILABLE),
        )
        assertEquals(
            AdbStartupEntryAction.NONE,
            AdbStartupGatePolicy.entryAction(AdbRescuePhase.AUTHORIZATION_REQUIRED),
        )
    }

    @Test
    fun `trusted access removes the startup overlay`() {
        val model = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED),
        )

        assertFalse(model.visible)
    }

    @Test
    fun `unavailable adb is blocking and points only to service`() {
        val model = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
        )

        assertTrue(model.visible)
        assertEquals("ADB недоступен", model.title)
        assertEquals(AdbStartupGatePolicy.SERVICE_INSTRUCTION, model.message)
        assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
        assertFalse(model.recoveryAvailable)
    }

    @Test
    fun `untrusted key offers one shot authorization and rescue`() {
        val model = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.AUTHORIZATION_REQUIRED),
        )

        assertTrue(model.visible)
        assertEquals(AdbStartupPrimaryAction.REQUEST_AUTHORIZATION, model.primaryAction)
        assertTrue(model.recoveryAvailable)
    }

    @Test
    fun `a car whose adb switch is off is sent to service, never to a prompt`() {
        // The reported defect, end to end: adbd answers, the key is refused, and the only thing
        // that tells this apart from a car that can still show the dialog is the system flag.
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.DISABLED,
        )

        val model = AdbStartupGatePolicy.overlay(checked)

        assertTrue(model.visible)
        assertEquals("ADB недоступен", model.title)
        assertEquals(AdbStartupGatePolicy.SERVICE_INSTRUCTION, model.message)
        assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
        assertFalse(model.recoveryAvailable)
        assertFalse(checked.canRequest)
        assertEquals(
            AdbStartupEntryAction.NONE,
            AdbStartupGatePolicy.entryAction(checked.phase),
        )
    }

    @Test
    fun `a car that can still show the prompt keeps the confirmation copy`() {
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.initial(false, 0, 0L),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )

        val model = AdbStartupGatePolicy.overlay(checked)

        assertEquals("Подтвердите доступ к ADB", model.title)
        assertEquals(AdbStartupPrimaryAction.REQUEST_AUTHORIZATION, model.primaryAction)
        assertTrue(checked.canRequest)
    }

    @Test
    fun `pending request checks trust without automatically submitting another key`() {
        val model = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(
                phase = AdbRescuePhase.AWAITING_CONFIRMATION,
                requestPending = true,
            ),
        )

        assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
        assertEquals("Я подтвердил — проверить", model.primaryLabel)
        assertTrue(model.recoveryAvailable)
    }
}
