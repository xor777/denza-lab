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

    /**
     * The field report of 2026-11-19: a blocked dashboard with nothing on it. Every phase that blocks,
     * under every state of an enabled restore, has a gate - except the first passive check, which
     * must not flash one.
     */
    @Test
    fun `a blocked dashboard has a gate once the first check is over`() {
        val restoreStates = listOf(
            AdbRestoreState.Disabled,
            AdbRestoreState.Unsupported,
            AdbRestoreState.NotNeeded,
            AdbRestoreState.NeedsActivation,
            AdbRestoreState.WaitingWifi,
            AdbRestoreState.NeedsDialog,
            AdbRestoreState.Connecting,
            AdbRestoreState.Restored(1L),
            AdbRestoreState.Failed("mDNS timeout", 1L),
        )
        val fallsThrough = setOf(
            AdbRestoreState.Disabled,
            AdbRestoreState.Unsupported,
            AdbRestoreState.NotNeeded,
            AdbRestoreState.NeedsActivation,
        )
        val standing = listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        )
        val snapshots = AdbRescuePhase.entries.filter { it != AdbRescuePhase.TRUSTED }
            .map { AdbRescueSnapshot(phase = it) } +
            standing.map { AdbRescuePolicy.checking(AdbRescueSnapshot(phase = it)) }
        snapshots.forEach { snapshot ->
            val firstCheck = snapshot.phase == AdbRescuePhase.UNKNOWN ||
                (snapshot.phase == AdbRescuePhase.CHECKING && snapshot.recheckOf == null)
            restoreStates.forEach { state ->
                val model = AdbStartupGatePolicy.overlay(snapshot, AdbRestoreSnapshot(enabled = true, state = state))
                assertEquals(
                    "${snapshot.phase} (re-check of ${snapshot.recheckOf}) under $state",
                    !(firstCheck && state in fallsThrough),
                    model.visible,
                )
                if (model.visible) assertTrue("${snapshot.phase} under $state", model.explainerAvailable)
            }
        }
    }

    @Test
    fun `a restore under way has the working gate, not nothing`() {
        AdbRescuePhase.entries.filter { it != AdbRescuePhase.TRUSTED }.forEach { phase ->
            val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = phase),
                AdbRestoreSnapshot(state = AdbRestoreState.Connecting))
            assertTrue(phase.name, model.visible)
            assertTrue(phase.name, model.busy)
            assertEquals("Восстановление доступа", model.title)
            assertEquals("Возвращаем доступ через Wi-Fi", model.message)
            assertNull(model.details)
            assertNull(model.primaryLabel)
            assertEquals(AdbStartupPrimaryAction.NONE, model.primaryAction)
            assertFalse(model.recoveryAvailable)
            assertTrue(model.explainerAvailable)
        }
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED),
            AdbRestoreSnapshot(state = AdbRestoreState.Connecting)).visible)
    }

    @Test
    fun `a restore that succeeded keeps the working gate until the check says trusted`() {
        val restored = AdbRestoreSnapshot(state = AdbRestoreState.Restored(1L))
        val connecting = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
            AdbRestoreSnapshot(state = AdbRestoreState.Connecting))
        // The phase the closed port left, then the check that follows the restore: no «ADB недоступен».
        val stale = AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE)
        assertEquals(connecting, AdbStartupGatePolicy.overlay(stale, restored))
        assertEquals(connecting, AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(stale), restored))
        assertEquals(connecting, AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.ERROR), restored))
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED), restored).visible)
        // A car that answered and wants the driver is that phase's gate, never a ring that stays.
        val awaiting = AdbRescueSnapshot(phase = AdbRescuePhase.AWAITING_CONFIRMATION, requestPending = true)
        assertEquals(AdbStartupGatePolicy.overlay(awaiting), AdbStartupGatePolicy.overlay(awaiting, restored))
    }

    @Test
    fun `a re-check keeps the standing gate and only turns its glyph into the ring`() {
        listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        ).forEach { phase ->
            AdbSystemSwitch.entries.forEach { switch ->
                val standing = AdbRescueSnapshot(phase = phase, systemSwitch = switch, requestPending =
                    phase == AdbRescuePhase.AWAITING_CONFIRMATION)
                val checking = AdbRescuePolicy.checking(standing)
                assertEquals(AdbRescuePhase.CHECKING, checking.phase)
                assertEquals(phase, checking.recheckOf)
                val settled = AdbStartupGatePolicy.overlay(standing)
                assertFalse(settled.busy)
                assertEquals("$phase $switch", settled.copy(busy = true), AdbStartupGatePolicy.overlay(checking))
                // Pressed again during the check: still the same gate.
                assertEquals(phase, AdbRescuePolicy.checking(checking).recheckOf)
            }
        }
    }

    @Test
    fun `the re-check mark lives only while the check does`() {
        val checking = AdbRescuePolicy.checking(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE))
        AdbCheckOutcome.entries.forEach { outcome ->
            assertNull(outcome.name, AdbRescuePolicy.afterCheck(checking, outcome, AdbSystemSwitch.ENABLED).recheckOf)
        }
        assertNull(AdbRescuePolicy.systemSwitchOff(checking).recheckOf)
        assertNull(AdbRescuePolicy.requesting(checking, 1L, AdbSystemSwitch.ENABLED).recheckOf)
        AdbCheckOutcome.entries.forEach { outcome ->
            val after = AdbRescuePolicy.afterCheck(checking, outcome, AdbSystemSwitch.ENABLED)
            assertNull(outcome.name, after.recheckMessage); assertNull(outcome.name, after.recheckDetails)
        }
        // The first check of a process has no gate to keep. A check after a reset keeps the gate the
        // reset was pressed on (`a reset keeps the gate it was pressed on`).
        assertNull(AdbRescuePolicy.checking(AdbRescuePolicy.initial(false, 0, 0L)).recheckOf)
        assertNull(AdbRescuePolicy.checking(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED)).recheckOf)
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(AdbRescuePolicy.initial(false, 0, 0L))).visible)
    }

    /**
     * The walk above with no restore to outrank the phase: switched off, or not there at all. Every
     * blocked state after the first check has a gate with the explainer's door - a reset and the
     * check after it included.
     */
    @Test
    fun `a blocked dashboard has a gate with the restore off or absent`() {
        val standing = listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        )
        val snapshots = AdbRescuePhase.entries.filter { it != AdbRescuePhase.TRUSTED }
            .map { AdbRescueSnapshot(phase = it) } +
            standing.map { AdbRescuePolicy.checking(AdbRescueSnapshot(phase = it)) } +
            standing.map { AdbRescuePolicy.resetAttempt(AdbRescueSnapshot(phase = it, requestPending = true)) } +
            standing.map { AdbRescuePolicy.checking(AdbRescuePolicy.resetAttempt(AdbRescueSnapshot(phase = it, requestPending = true))) }
        val restores = listOf(null, AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.Disabled))
        snapshots.forEach { snapshot ->
            val firstCheck = snapshot.recheckOf == null &&
                (snapshot.phase == AdbRescuePhase.UNKNOWN || snapshot.phase == AdbRescuePhase.CHECKING)
            restores.forEach { restore ->
                val model = AdbStartupGatePolicy.overlay(snapshot, restore)
                val name = "${snapshot.phase} (re-check of ${snapshot.recheckOf}) under $restore"
                assertEquals(name, !firstCheck, model.visible)
                if (model.visible) assertTrue(name, model.explainerAvailable)
            }
        }
        // The resets are not first checks: each of them is a gate in that walk.
        assertTrue(snapshots.filter { it.phase == AdbRescuePhase.UNKNOWN }.any { it.recheckOf != null })
    }

    @Test
    fun `a reset keeps the gate it was pressed on`() {
        listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        ).forEach { phase ->
            val standing = AdbRescueSnapshot(phase = phase, requestPending = true)
            assertTrue(phase.name, standing.canResetAttempt)
            val gate = AdbStartupGatePolicy.overlay(standing)
            val reset = AdbRescuePolicy.resetAttempt(standing)
            // What the reset does to the latch is unchanged: released, and nothing to request until
            // a check has said the key is refused.
            assertEquals(AdbRescuePhase.UNKNOWN, reset.phase)
            assertFalse(reset.requestPending)
            assertFalse(reset.canRequest)
            assertFalse(reset.settled.canRequest)
            assertFalse(reset.settled.canResetAttempt)
            // The gate stays, and stays through the check that follows, with the ring.
            assertEquals(phase, reset.recheckOf)
            assertEquals(gate, AdbStartupGatePolicy.overlay(reset))
            val checking = AdbRescuePolicy.checking(reset)
            assertEquals(phase, checking.recheckOf)
            assertEquals(gate.copy(busy = true), AdbStartupGatePolicy.overlay(checking))
            // The recovery dialog says what the reset did, through the check.
            assertEquals("Новая попытка разрешена вручную", checking.settled.message)
            assertEquals("Сначала проверьте доступ", checking.settled.details)
            // And the check's answer is the gate from then on, the request offered on a refusal.
            val refused = AdbRescuePolicy.afterCheck(checking, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)
            assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, refused.phase)
            assertTrue(refused.canRequest)
            assertNull(refused.recheckOf)
        }
    }

    @Test
    fun `a re-check keeps the recovery dialog's words`() {
        val start = AdbRescuePolicy.initial(false, 0, 0L)
        listOf(
            AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.UNAVAILABLE, AdbSystemSwitch.ENABLED),
            AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED),
            AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.ERROR, AdbSystemSwitch.UNKNOWN),
            AdbRescuePolicy.initial(true, 1, 42L),
        ).forEach { standing ->
            val checking = AdbRescuePolicy.checking(standing)
            // The access row on «Сервис» says a check is running; the dialog keeps the gate's words.
            assertEquals("Проверяю доступ…", checking.message)
            assertEquals(standing.phase.name, standing.message, checking.settled.message)
            assertEquals(standing.phase.name, standing.details, checking.settled.details)
            assertEquals(standing.canRequest, checking.settled.canRequest)
            assertEquals(standing.canResetAttempt, checking.settled.canResetAttempt)
            // Pressed again during the check: the same words.
            assertEquals(standing.message, AdbRescuePolicy.checking(checking).settled.message)
            assertEquals(standing.details, AdbRescuePolicy.checking(checking).settled.details)
        }
        // Outside a re-check the snapshot is what it shows.
        assertEquals(start, start.settled)
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
