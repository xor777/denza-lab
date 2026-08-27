package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `every state that blocks offers the way to the explanation`() {
        // The reason this feature exists, stated as an invariant rather than as two examples. The
        // gate covers the dashboard; the dashboard holds the only other door to diagnostics; so a
        // blocked gate with no door of its own means the owner's readings are unreachable exactly
        // when something is wrong with the car. A phase added later fails here until it decides.
        AdbRescuePhase.entries.forEach { phase ->
            val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = phase))
            assertEquals(
                "$phase blocks ${model.visible} but offers the explainer ${model.explainerAvailable}",
                model.visible,
                model.explainerAvailable,
            )
        }
    }

    @Test
    fun `both named gates carry the door, whatever else they carry`() {
        // The two the owner asked for by name, pinned by title so a copy change cannot quietly move
        // the door off one of them. The unavailable gate is the one with no recovery button at all,
        // and it is therefore the one where this is the only thing to press besides a retry.
        val unavailable = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
        )
        val confirm = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.AUTHORIZATION_REQUIRED),
        )

        assertEquals("ADB недоступен", unavailable.title)
        assertTrue(unavailable.explainerAvailable)
        assertFalse(unavailable.recoveryAvailable)

        assertEquals("Подтвердите доступ к ADB", confirm.title)
        assertTrue(confirm.explainerAvailable)
    }

    @Test
    fun `a car whose switch is off says so on the gate itself`() {
        // Ф4: the two unavailable gates were the same screen. `message` is the service instruction,
        // which by construction has to hold for a car that merely stopped answering, so the one
        // thing this app actually read about this car had nowhere to appear.
        val off = AdbStartupGatePolicy.overlay(
            AdbRescuePolicy.afterCheck(
                AdbRescuePolicy.initial(false, 0, 0L),
                AdbCheckOutcome.AUTHORIZATION_REQUIRED,
                AdbSystemSwitch.DISABLED,
            ),
        )

        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, off.details)
    }

    @Test
    fun `an unreadable switch still invents no cause`() {
        // Absence of evidence is not evidence of an off switch - that part never changed. What
        // changed is that saying "could not be read" is itself a reading, not an invention, and the
        // gate used to stay silent in exactly the two cases where we do not know the answer.
        val unknown = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(
                phase = AdbRescuePhase.UNAVAILABLE,
                systemSwitch = AdbSystemSwitch.UNKNOWN,
            ),
        )
        val on = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(
                phase = AdbRescuePhase.UNAVAILABLE,
                systemSwitch = AdbSystemSwitch.ENABLED,
            ),
        )

        assertNotEquals(
            "an unreadable flag must never be reported as a switched-off one",
            AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL,
            unknown.details,
        )
        assertNotEquals(
            "nor may it be reported as a switched-on one",
            on.details,
            unknown.details,
        )
    }

    @Test
    fun `every stuck screen says what it read, and the three readings differ`() {
        // The whole point: an owner reports this screen with a photograph, and the photograph has
        // to answer which of the three cars it is - there is no access to that car and no way to
        // ask it anything afterwards. Silence on any one of them makes the picture useless.
        val stuck = listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        )
        stuck.forEach { phase ->
            val readings = AdbSystemSwitch.entries.map { switch ->
                AdbStartupGatePolicy.overlay(
                    AdbRescueSnapshot(phase = phase, systemSwitch = switch),
                ).details
            }
            readings.forEach { reading ->
                assertTrue("$phase left a switch state unsaid", !reading.isNullOrBlank())
            }
            assertEquals(
                "$phase must tell its three readings apart",
                AdbSystemSwitch.entries.size,
                readings.toSet().size,
            )
        }
    }

    @Test
    fun `the gate never repeats a failure label at the owner`() {
        // The coordinator stores exception names in `details` - "ConnectException" and the like -
        // and those are worth having on the service screen, where the reader knows what they mean.
        // Forwarding the snapshot's details wholesale would put them on the blocking gate instead,
        // and would also print the two phases whose details merely restate their own message.
        val noisy = AdbRescueSnapshot(
            phase = AdbRescuePhase.ERROR,
            details = "ConnectException",
            systemSwitch = AdbSystemSwitch.ENABLED,
        )

        // The gate carries a reading of the car, never the snapshot's own words.
        assertEquals(
            AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL,
            AdbStartupGatePolicy.overlay(noisy).details,
        )
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
