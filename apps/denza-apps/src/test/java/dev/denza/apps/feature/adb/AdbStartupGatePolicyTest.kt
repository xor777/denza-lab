package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStartupGatePolicyTest {
    @org.junit.Test
    fun wifiWaitHasANeutralGateAndNoPointlessAction() {
        val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
            AdbRestoreSnapshot(state = AdbRestoreState.WaitingWifi))
        org.junit.Assert.assertTrue(model.visible)
        org.junit.Assert.assertEquals("Ожидание Wi-Fi", model.title)
        org.junit.Assert.assertNull(model.primaryLabel)
        org.junit.Assert.assertEquals(AdbStartupPrimaryAction.NONE, model.primaryAction)
        org.junit.Assert.assertTrue(model.explainerAvailable)
    }

    @org.junit.Test
    fun recoveryDoesNotCoverTrustedRuntimeAndTechnicalFailureStaysOutOfTheGate() {
        val waiting = AdbRestoreSnapshot(state = AdbRestoreState.WaitingWifi)
        org.junit.Assert.assertFalse(AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED), waiting).visible)
        val failed = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
            AdbRestoreSnapshot(state = AdbRestoreState.Failed("mDNS timeout", 1)))
        org.junit.Assert.assertFalse(failed.message.contains("mDNS"))
        org.junit.Assert.assertFalse(failed.title.contains("не удалось", true))
    }
    @Test
    fun `fast passive check does not flash a startup overlay`() {
        assertFalse(
            AdbStartupGatePolicy.overlay(
                AdbRescueSnapshot(phase = AdbRescuePhase.CHECKING),
            ).visible,
        )
    }

    @Test
    fun `opening the app looks again in every unsettled phase, never over a check or a request`() {
        listOf(
            AdbRescuePhase.UNKNOWN,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.ERROR,
        ).forEach { phase ->
            assertEquals(
                phase.name,
                AdbStartupEntryAction.CHECK_ACCESS,
                AdbStartupGatePolicy.entryAction(phase),
            )
        }
        listOf(AdbRescuePhase.CHECKING, AdbRescuePhase.REQUESTING).forEach { phase ->
            assertEquals(phase.name, AdbStartupEntryAction.NONE, AdbStartupGatePolicy.entryAction(phase))
        }
        assertEquals(
            AdbStartupEntryAction.START_RUNTIME,
            AdbStartupGatePolicy.entryAction(AdbRescuePhase.TRUSTED),
        )
    }

    @Test
    fun `autoload retries passive failures without requesting authorization`() {
        listOf(
            AdbRescuePhase.UNKNOWN,
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.ERROR,
            AdbRescuePhase.AWAITING_CONFIRMATION,
        ).forEach { phase ->
            assertEquals(
                phase.name,
                AdbAutostartRetryAction.CHECK_ACCESS,
                AdbAutostartRetryPolicy.action(phase),
            )
        }

        listOf(
            AdbRescuePhase.CHECKING,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.REQUESTING,
        ).forEach { phase ->
            assertEquals(
                phase.name,
                AdbAutostartRetryAction.NONE,
                AdbAutostartRetryPolicy.action(phase),
            )
        }

        assertEquals(
            AdbAutostartRetryAction.START_RUNTIME,
            AdbAutostartRetryPolicy.action(AdbRescuePhase.TRUSTED),
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
        // Opening the app looks again, passively; it never turns into a request.
        assertEquals(
            AdbStartupEntryAction.CHECK_ACCESS,
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
        // Exception names - "ConnectException" and the like - belong on «Технические сведения»,
        // where the reader knows what they mean. Forwarding the snapshot's details wholesale would
        // put whatever a snapshot carries on the blocking gate, and would also print the two phases
        // whose details merely restate their own message.
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

    /**
     * The defect: the owner approved the one request with «always allow», and the car slept before
     * «Я подтвердил — проверить» was pressed. Every later process came up waiting for that press,
     * and no wake, no screen-on and no opening of the app ever looked, although the key was trusted.
     */
    @Test
    fun `a restart with an approved but unchecked request reaches the runtime on its own`() {
        val restarted = AdbRescuePolicy.initial(
            requestPending = true,
            attemptCount = 1,
            lastAttemptAtMillis = 1_000L,
        )
        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, restarted.phase)

        // The wake's autoload looks, and so does opening the app.
        assertEquals(
            AdbAutostartRetryAction.CHECK_ACCESS,
            AdbAutostartRetryPolicy.action(restarted.phase),
        )
        assertEquals(
            AdbStartupEntryAction.CHECK_ACCESS,
            AdbStartupGatePolicy.entryAction(restarted.phase),
        )

        // adbd now answers the signed token with CNXN.
        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.checking(restarted),
            AdbCheckOutcome.TRUSTED,
            AdbSystemSwitch.ENABLED,
        )

        assertEquals(AdbRescuePhase.TRUSTED, checked.phase)
        assertFalse(checked.requestPending)
        assertEquals(1, checked.attemptCount)
        assertEquals(
            AdbAutostartRetryAction.START_RUNTIME,
            AdbAutostartRetryPolicy.action(checked.phase),
        )
        assertFalse(AdbStartupGatePolicy.overlay(checked).visible)
    }

    @Test
    fun `a request still unanswered is looked at again and never sent again`() {
        val restarted = AdbRescuePolicy.initial(
            requestPending = true,
            attemptCount = 1,
            lastAttemptAtMillis = 1_000L,
        )

        val checked = AdbRescuePolicy.afterCheck(
            AdbRescuePolicy.checking(restarted),
            AdbCheckOutcome.AUTHORIZATION_REQUIRED,
            AdbSystemSwitch.ENABLED,
        )

        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, checked.phase)
        assertTrue(checked.requestPending)
        assertEquals(1, checked.attemptCount)
        assertFalse(checked.canRequest)
        // The next wake looks again; still a look, not a request.
        assertEquals(
            AdbAutostartRetryAction.CHECK_ACCESS,
            AdbAutostartRetryPolicy.action(checked.phase),
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
