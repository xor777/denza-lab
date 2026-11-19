package dev.denza.apps.feature.adb

import dev.denza.apps.DenzaUiState
import dev.denza.apps.ui.ServiceModel
import dev.denza.apps.ui.components.DenzaTileTone
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStartupGatePolicyTest {
    private val start = AdbRescueSnapshot()

    @Test
    fun wifiWaitHasANeutralGateAndNoPointlessAction() {
        val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
            AdbRestoreSnapshot(state = AdbRestoreState.WaitingWifi))
        assertTrue(model.visible)
        assertEquals("Ожидание Wi-Fi", model.title)
        assertNull(model.primaryLabel)
        assertEquals(AdbStartupPrimaryAction.NONE, model.primaryAction)
        assertTrue(model.explainerAvailable)
    }

    @Test
    fun recoveryDoesNotCoverTrustedRuntimeAndTechnicalFailureStaysOutOfTheGate() {
        val waiting = AdbRestoreSnapshot(state = AdbRestoreState.WaitingWifi)
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED), waiting).visible)
        val failed = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE),
            AdbRestoreSnapshot(state = AdbRestoreState.Failed("mDNS timeout", 1)))
        assertFalse(failed.message.contains("mDNS"))
        assertFalse(failed.title.contains("не удалось", true))
    }

    @Test
    fun `fast passive check does not flash a startup overlay`() {
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.CHECKING)).visible)
    }

    @Test
    fun `opening the app looks again in every unsettled phase, never over a check or a request`() {
        listOf(
            AdbRescuePhase.UNKNOWN,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.ERROR,
        ).forEach { phase ->
            assertEquals(phase.name, AdbStartupEntryAction.CHECK_ACCESS, AdbStartupGatePolicy.entryAction(phase))
        }
        listOf(AdbRescuePhase.CHECKING, AdbRescuePhase.AWAITING_CONFIRMATION).forEach { phase ->
            assertEquals(phase.name, AdbStartupEntryAction.NONE, AdbStartupGatePolicy.entryAction(phase))
        }
        assertEquals(AdbStartupEntryAction.START_RUNTIME, AdbStartupGatePolicy.entryAction(AdbRescuePhase.TRUSTED))
    }

    /**
     * A wake looks at a refused key too: a prompt answered with «always allow» after its request was
     * closed trusts the key with nobody at the screen. A look never offers the key.
     */
    @Test
    fun `autoload looks at every unsettled phase and never over a check or a request`() {
        listOf(
            AdbRescuePhase.UNKNOWN,
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.ERROR,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
        ).forEach { phase ->
            assertEquals(phase.name, AdbAutostartRetryAction.CHECK_ACCESS, AdbAutostartRetryPolicy.action(phase))
        }
        listOf(AdbRescuePhase.CHECKING, AdbRescuePhase.AWAITING_CONFIRMATION).forEach { phase ->
            assertEquals(phase.name, AdbAutostartRetryAction.NONE, AdbAutostartRetryPolicy.action(phase))
        }
        assertEquals(AdbAutostartRetryAction.START_RUNTIME, AdbAutostartRetryPolicy.action(AdbRescuePhase.TRUSTED))
    }

    @Test
    fun `trusted access removes the startup overlay`() {
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED)).visible)
    }

    @Test
    fun `a car never opened is sent to service, and only that car`() {
        val never = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE))

        assertTrue(never.visible)
        assertEquals("ADB недоступен", never.title)
        assertEquals(AdbStartupGatePolicy.SERVICE_INSTRUCTION, never.message)
        assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, never.primaryAction)
        assertEquals("Проверить снова", never.primaryLabel)
    }

    /**
     * A car that trusted this app before, its switch not off, with a restore that can act: the
     * firmware closed the port at start-up and Wi-Fi brings it back. That sentence is said only
     * there; it is a promise, and where nothing will keep it the gate says what it read instead.
     */
    @Test
    fun `a car that trusted this app before is told the port closed at start only where the restore can reopen it`() {
        val closedAtStart = "Новые прошивки закрывают ADB при запуске машины. Доступ возвращается через Wi-Fi"
        val able = AdbRestoreSnapshot(enabled = true, state = AdbRestoreState.NotNeeded)
        val unable = listOf(
            null,
            AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.Disabled),
            AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.NotNeeded),
            AdbRestoreSnapshot(enabled = true, state = AdbRestoreState.Unsupported),
            AdbRestoreSnapshot(enabled = true, state = AdbRestoreState.NeedsActivation(refused = true)),
            AdbRestoreSnapshot(enabled = true, state = AdbRestoreState.NeedsActivation(refused = false)),
        )
        listOf(AdbSystemSwitch.ENABLED, AdbSystemSwitch.UNKNOWN).forEach { switch ->
            val snapshot = AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, systemSwitch = switch, trustedBefore = true)
            val model = AdbStartupGatePolicy.overlay(snapshot, able)
            assertEquals("ADB недоступен", model.title)
            assertEquals(closedAtStart, model.message)
            assertEquals(AdbRescuePolicy.switchReading(switch), model.details)
            assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
            // And the check the button runs keeps those words.
            assertEquals(closedAtStart, AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(snapshot), able).message)
            unable.forEach { restore ->
                val quiet = AdbStartupGatePolicy.overlay(snapshot, restore)
                assertEquals("$switch under $restore", "", quiet.message)
                assertEquals(AdbRescuePolicy.switchReading(switch), quiet.details)
                assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, quiet.primaryAction)
            }
        }
        // A switch read off is the answer, whatever the car trusted before and the restore can do.
        (unable + able).forEach { restore ->
            val off = AdbStartupGatePolicy.overlay(
                AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, systemSwitch = AdbSystemSwitch.DISABLED, trustedBefore = true),
                restore,
            )
            assertEquals("$restore", AdbStartupGatePolicy.SERVICE_INSTRUCTION, off.message)
            assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, off.details)
        }
        // A car never trusted is the service's, with the restore able or not.
        assertEquals(AdbStartupGatePolicy.SERVICE_INSTRUCTION, AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, systemSwitch = AdbSystemSwitch.ENABLED), able).message)
    }

    @Test
    fun `a car whose adb switch is off is sent to service, never to a prompt`() {
        // adbd answers, the key is refused, and the only thing that tells this apart from a car that
        // can still show the dialog is the system flag.
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.DISABLED)
        val model = AdbStartupGatePolicy.overlay(checked)

        assertEquals("ADB недоступен", model.title)
        assertEquals(AdbStartupGatePolicy.SERVICE_INSTRUCTION, model.message)
        assertEquals(AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
        assertFalse(checked.canAsk)
        assertEquals(AdbStartupEntryAction.CHECK_ACCESS, AdbStartupGatePolicy.entryAction(checked.phase))
    }

    /**
     * No request out, so nothing on the car to confirm: the title is the state, and one control asks
     * now - «Запросить доступ» while none went out from this process (the day's spent at the
     * opening), «Запросить снова» after one ended unanswered or denied.
     */
    @Test
    fun `a refused key with nothing out names the state and offers one control that asks`() {
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)
        val first = AdbStartupGatePolicy.overlay(checked)

        assertEquals("Доступ не выдан", first.title)
        assertEquals("", first.message)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL, first.details)
        assertEquals("Запросить доступ", first.primaryLabel)
        assertEquals(AdbStartupPrimaryAction.ASK, first.primaryAction)
        assertTrue(first.attention)
        assertFalse(first.busy)
        assertNull(first.checkbox)
        assertTrue(checked.canAsk)

        val sent = AdbRescuePolicy.sent(checked, AdbSystemSwitch.ENABLED, AdbAskLog(listOf(1L), 1, 1L))
        val unanswered = AdbStartupGatePolicy.overlay(AdbRescuePolicy.refused(sent).copy(lastFailure = AdbRescuePolicy.NO_ANSWER))
        assertEquals("Доступ не выдан", unanswered.title)
        assertEquals("Запросить снова", unanswered.primaryLabel)
        assertEquals("Запросить снова", AdbStartupGatePolicy.askLabel(AdbRescuePolicy.refused(sent)))
        assertEquals("Запросить доступ", AdbStartupGatePolicy.askLabel(checked))
        // A trusted key starts the count of «again» over.
        val trusted = AdbRescuePolicy.afterCheck(sent, AdbCheckOutcome.TRUSTED, AdbSystemSwitch.ENABLED)
        assertFalse(trusted.asked)
    }

    /** A request is held: the ring, the instruction, and nothing to press but the explanation. */
    @Test
    fun `a request on the car's screen is the ring and no buttons`() {
        val refused = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)
        val model = AdbStartupGatePolicy.overlay(AdbRescuePolicy.asking(refused))

        assertEquals("Подтвердите доступ к ADB", model.title)
        assertEquals("Разрешите системный запрос на экране автомобиля", model.message)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL, model.details)
        assertTrue(model.busy)
        assertNull(model.primaryLabel)
        assertEquals(AdbStartupPrimaryAction.NONE, model.primaryAction)
        assertTrue(model.explainerAvailable)
    }

    /** An approval without «always allow»: the gate is the box, in the car's words, and nothing else. */
    @Test
    fun `an approval without the tick shows only the car's box`() {
        val missed = AdbRescuePolicy.refused(start.copy(systemSwitch = AdbSystemSwitch.ENABLED)).copy(alwaysAllowMissed = true)
        val waiting = AdbStartupGatePolicy.overlay(AdbRescuePolicy.asking(missed), carLocale = Locale("ru"))
        val idle = AdbStartupGatePolicy.overlay(missed, carLocale = Locale("ru"))

        for (model in listOf(waiting, idle)) {
            assertEquals("Всегда разрешать отладку с этого компьютера", model.checkbox)
            assertEquals("", model.message)
            assertNull(model.details)
        }
        // A request on the screen is confirmed; with none out, the title is the state.
        assertEquals("Подтвердите доступ к ADB", waiting.title)
        assertEquals("Доступ не выдан", idle.title)
        assertTrue(waiting.busy)
        assertNull(waiting.primaryLabel)
        assertEquals(AdbStartupPrimaryAction.ASK, idle.primaryAction)
    }

    /** The prompt is drawn in the car's language, which is not necessarily this app's. */
    @Test
    fun `the box is named in the car's language`() {
        assertEquals("Always allow from this computer", AdbAlwaysAllowLabel.of(Locale.ENGLISH))
        assertEquals("Always allow from this computer", AdbAlwaysAllowLabel.of(Locale.GERMAN))
        assertEquals("Всегда разрешать отладку с этого компьютера", AdbAlwaysAllowLabel.of(Locale("ru", "RU")))
        assertEquals("一律允许使用这台计算机进行调试", AdbAlwaysAllowLabel.of(Locale.SIMPLIFIED_CHINESE))
        assertEquals("一律允許透過這台電腦進行", AdbAlwaysAllowLabel.of(Locale.TRADITIONAL_CHINESE))
        assertEquals("一律允許透過這部電腦進行", AdbAlwaysAllowLabel.of(Locale("zh", "HK")))
    }

    @Test
    fun `every state that blocks offers the way to the explanation`() {
        // The gate covers the dashboard; the dashboard holds the only other door to diagnostics; so a
        // blocked gate with no door of its own means the owner's readings are unreachable exactly
        // when something is wrong with the car. A phase added later fails here until it decides.
        AdbRescuePhase.entries.forEach { phase ->
            val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = phase))
            assertEquals("$phase blocks ${model.visible} but offers the explainer ${model.explainerAvailable}",
                model.visible, model.explainerAvailable)
        }
    }

    @Test
    fun `a car whose switch is off says so on the gate itself`() {
        val off = AdbStartupGatePolicy.overlay(
            AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.DISABLED),
        )
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, off.details)
    }

    @Test
    fun `an unreadable switch still invents no cause`() {
        val unknown = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, systemSwitch = AdbSystemSwitch.UNKNOWN),
        )
        val on = AdbStartupGatePolicy.overlay(
            AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, systemSwitch = AdbSystemSwitch.ENABLED),
        )
        assertNotEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, unknown.details)
        assertNotEquals(on.details, unknown.details)
    }

    @Test
    fun `every stuck screen says what it read, and the three readings differ`() {
        // An owner reports this screen with a photograph, and the photograph has to answer which of
        // the three cars it is. The box replaces the reading only where the box is the whole answer.
        listOf(
            AdbRescuePhase.UNAVAILABLE,
            AdbRescuePhase.AUTHORIZATION_REQUIRED,
            AdbRescuePhase.AWAITING_CONFIRMATION,
            AdbRescuePhase.ERROR,
        ).forEach { phase ->
            val readings = AdbSystemSwitch.entries.map { switch ->
                AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = phase, systemSwitch = switch)).details
            }
            readings.forEach { reading -> assertTrue("$phase left a switch state unsaid", !reading.isNullOrBlank()) }
            assertEquals("$phase must tell its three readings apart", AdbSystemSwitch.entries.size, readings.toSet().size)
        }
    }

    @Test
    fun `the gate never repeats a failure label at the owner`() {
        val noisy = AdbRescueSnapshot(phase = AdbRescuePhase.ERROR, details = "ConnectException",
            systemSwitch = AdbSystemSwitch.ENABLED, lastFailure = "ConnectException")
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL, AdbStartupGatePolicy.overlay(noisy).details)
        assertFalse(AdbStartupGatePolicy.overlay(noisy).message.contains("Exception"))
    }

    /**
     * Every blocked state after the first check has a visible gate with a way forward: a button that
     * acts, the ring of something that ends by itself, or both - and always the explainer's door.
     * Walked over every phase, its re-check, the box the last approval missed, the switch's three
     * readings, a car trusted before or not, and every state of the restore, on or off or absent.
     */
    @Test
    fun `every blocked state after the first check has a gate with a way forward`() {
        val restores = listOf(null, AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.Disabled)) + listOf(
            AdbRestoreState.Disabled,
            AdbRestoreState.Unsupported,
            AdbRestoreState.NotNeeded,
            AdbRestoreState.NeedsActivation(refused = true),
            AdbRestoreState.NeedsActivation(refused = false),
            AdbRestoreState.WaitingWifi,
            AdbRestoreState.NeedsDialog,
            AdbRestoreState.Connecting,
            AdbRestoreState.Restored(1L),
            AdbRestoreState.Failed("mDNS timeout", 1L),
        ).map { AdbRestoreSnapshot(enabled = true, state = it) }
        val fallsThrough = setOf(
            AdbRestoreState.Disabled,
            AdbRestoreState.Unsupported,
            AdbRestoreState.NotNeeded,
            AdbRestoreState.NeedsActivation(refused = true),
            AdbRestoreState.NeedsActivation(refused = false),
        )
        val standing = listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.AUTHORIZATION_REQUIRED, AdbRescuePhase.ERROR)
        val snapshots = AdbSystemSwitch.entries.flatMap { switch ->
            listOf(false, true).flatMap { trustedBefore ->
                listOf(false, true).flatMap { missed ->
                    val base = AdbRescueSnapshot(systemSwitch = switch, trustedBefore = trustedBefore, alwaysAllowMissed = missed)
                    AdbRescuePhase.entries.filter { it != AdbRescuePhase.TRUSTED }.map { base.copy(phase = it) } +
                        standing.map { AdbRescuePolicy.checking(base.copy(phase = it)) }
                }
            }
        }
        var walked = 0
        snapshots.forEach { snapshot ->
            val firstCheck = snapshot.recheckOf == null &&
                (snapshot.phase == AdbRescuePhase.UNKNOWN || snapshot.phase == AdbRescuePhase.CHECKING)
            restores.forEach { restore ->
                val model = AdbStartupGatePolicy.overlay(snapshot, restore)
                val name = "${snapshot.phase} (re-check of ${snapshot.recheckOf}, missed ${snapshot.alwaysAllowMissed}) under $restore"
                val outranked = restore?.enabled == true && restore.state !in fallsThrough
                assertEquals(name, !firstCheck || outranked, model.visible)
                if (!model.visible) return@forEach
                walked++
                assertTrue(name, model.explainerAvailable)
                val acts = model.primaryLabel != null && model.primaryAction != AdbStartupPrimaryAction.NONE
                assertTrue("$name has no way forward", acts || model.busy || restore?.state == AdbRestoreState.WaitingWifi ||
                    restore?.state == AdbRestoreState.NeedsDialog)
                // A button never promises an action the gate does not take.
                assertEquals(name, model.primaryLabel == null, model.primaryAction == AdbStartupPrimaryAction.NONE)
                // Nothing to press but the explainer while a request or a check turns.
                if (model.busy && model.primaryLabel != null) assertTrue(name, snapshot.phase == AdbRescuePhase.CHECKING)
            }
        }
        assertTrue(walked > 500)
    }

    @Test
    fun `a restore under way has the working gate, not nothing`() {
        // A request on the car's screen is the one phase above it (below).
        AdbRescuePhase.entries.filter { it != AdbRescuePhase.TRUSTED && it != AdbRescuePhase.AWAITING_CONFIRMATION }.forEach { phase ->
            val model = AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = phase),
                AdbRestoreSnapshot(state = AdbRestoreState.Connecting))
            assertTrue(phase.name, model.visible)
            assertTrue(phase.name, model.busy)
            assertEquals("Восстановление доступа", model.title)
            assertEquals("Возвращаем доступ через Wi-Fi", model.message)
            assertNull(model.details)
            assertNull(model.primaryLabel)
            assertEquals(AdbStartupPrimaryAction.NONE, model.primaryAction)
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
        val stale = AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE)
        assertEquals(connecting, AdbStartupGatePolicy.overlay(stale, restored))
        assertEquals(connecting, AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(stale), restored))
        assertEquals(connecting, AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.ERROR), restored))
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED), restored).visible)
        // A request on the car's screen is that phase's gate, never the restore's ring.
        val awaiting = AdbRescuePolicy.asking(AdbRescuePolicy.refused(start))
        assertEquals(AdbStartupGatePolicy.overlay(awaiting), AdbStartupGatePolicy.overlay(awaiting, restored))
    }

    /**
     * The request follows the look that found the refusal on the same worker step, so the restore
     * may still be in a state from before it - failed, waiting for Wi-Fi or the network dialog,
     * connecting, restored. None of them may cover the car's prompt.
     */
    @Test
    fun `a request on the car's screen outranks every restore state`() {
        val refused = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)
        listOf(false, true).forEach { missed ->
            val awaiting = AdbRescuePolicy.asking(refused.copy(alwaysAllowMissed = missed))
            val own = AdbStartupGatePolicy.overlay(awaiting)
            assertTrue(own.busy)
            listOf(
                AdbRestoreState.Failed("mDNS timeout", 1L),
                AdbRestoreState.WaitingWifi,
                AdbRestoreState.NeedsDialog,
                AdbRestoreState.Connecting,
                AdbRestoreState.Restored(1L),
                AdbRestoreState.NeedsActivation(refused = true),
                AdbRestoreState.NotNeeded,
            ).forEach { state ->
                assertEquals("$state, missed $missed", own,
                    AdbStartupGatePolicy.overlay(awaiting, AdbRestoreSnapshot(enabled = true, state = state)))
            }
        }
    }

    @Test
    fun `a re-check keeps the standing gate and only turns its glyph into the ring`() {
        listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.AUTHORIZATION_REQUIRED, AdbRescuePhase.ERROR).forEach { phase ->
            AdbSystemSwitch.entries.forEach { switch ->
                listOf(false, true).forEach { missed ->
                    val standing = AdbRescueSnapshot(phase = phase, systemSwitch = switch, alwaysAllowMissed = missed)
                    val checking = AdbRescuePolicy.checking(standing)
                    val settled = AdbStartupGatePolicy.overlay(standing)
                    assertFalse(settled.busy)
                    assertEquals("$phase $switch $missed", settled.copy(busy = true), AdbStartupGatePolicy.overlay(checking))
                }
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
        assertNull(AdbRescuePolicy.asking(checking).recheckOf)
        assertNull(AdbRescuePolicy.refused(checking).recheckOf)
        assertFalse(AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(start)).visible)
    }

    /**
     * Review finding 9: Android's switch read off while the restore waits for Wi-Fi. The switch is
     * the answer - nothing Wi-Fi brings back will open a port the system closed - and the gate said
     * «waiting for Wi-Fi» with no control instead.
     */
    @Test
    fun `a switch read off outranks every restore state`() {
        val off = AdbRescuePolicy.systemSwitchOff(AdbRescueSnapshot(phase = AdbRescuePhase.UNAVAILABLE, trustedBefore = true))
        listOf(
            AdbRestoreState.WaitingWifi,
            AdbRestoreState.Connecting,
            AdbRestoreState.NeedsDialog,
            AdbRestoreState.Failed("mDNS timeout", 1L),
            AdbRestoreState.Restored(1L),
        ).forEach { state ->
            val restore = AdbRestoreSnapshot(enabled = true, state = state)
            val model = AdbStartupGatePolicy.overlay(off, restore)
            assertEquals("$state", AdbAccessView.SWITCH_OFF, AdbStartupGatePolicy.view(off, restore))
            assertEquals("$state", AdbStartupGatePolicy.SERVICE_INSTRUCTION, model.message)
            assertEquals("$state", AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, model.details)
            assertEquals("$state", AdbStartupPrimaryAction.CHECK_ACCESS, model.primaryAction)
            // Its own check keeps the gate, with the ring.
            assertEquals("$state", model.copy(busy = true), AdbStartupGatePolicy.overlay(AdbRescuePolicy.checking(off), restore))
        }
    }

    /**
     * The normative table of what access is (docs/adb-authorization-recovery.md, «State and
     * presentation»), walked over every phase and its re-check, the switch's three readings, the
     * missed box, a car trusted before or not, and every state of the restore - on, off or absent.
     * The gate and the service row are both checked against the one answer.
     */
    @Test
    fun `one decision says what the gate and the service row show, in one order`() {
        val restores = listOf(null, AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.WaitingWifi)) + listOf(
            AdbRestoreState.Disabled,
            AdbRestoreState.Unsupported,
            AdbRestoreState.NotNeeded,
            AdbRestoreState.NeedsActivation(refused = true),
            AdbRestoreState.NeedsActivation(refused = false),
            AdbRestoreState.WaitingWifi,
            AdbRestoreState.NeedsDialog,
            AdbRestoreState.Connecting,
            AdbRestoreState.Restored(1L),
            AdbRestoreState.Failed("mDNS timeout", 1L),
        ).map { AdbRestoreSnapshot(enabled = true, state = it) }
        val standing = setOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.AUTHORIZATION_REQUIRED, AdbRescuePhase.ERROR)
        val snapshots = AdbSystemSwitch.entries.flatMap { switch ->
            listOf(false, true).flatMap { trustedBefore ->
                listOf(false, true).flatMap { missed ->
                    val base = AdbRescueSnapshot(systemSwitch = switch, trustedBefore = trustedBefore, alwaysAllowMissed = missed)
                    AdbRescuePhase.entries.map { base.copy(phase = it) } +
                        standing.map { AdbRescuePolicy.checking(base.copy(phase = it)) }
                }
            }
        }
        val blocked = AdbAccessView.entries.toSet() -
            setOf(AdbAccessView.TRUSTED, AdbAccessView.FIRST_CHECK, AdbAccessView.REQUEST_ON_SCREEN)
        var walked = 0
        snapshots.forEach { s ->
            restores.forEach { r ->
                val name = "${s.phase} (re-check of ${s.recheckOf}, switch ${s.systemSwitch}, missed ${s.alwaysAllowMissed}) under $r"
                val shown = s.recheckOf ?: s.phase
                val on = r?.takeIf { it.enabled }?.state
                val expected = when {
                    s.phase == AdbRescuePhase.TRUSTED -> AdbAccessView.TRUSTED
                    s.phase == AdbRescuePhase.AWAITING_CONFIRMATION -> AdbAccessView.REQUEST_ON_SCREEN
                    shown in standing && s.systemSwitch == AdbSystemSwitch.DISABLED -> AdbAccessView.SWITCH_OFF
                    on == AdbRestoreState.WaitingWifi -> AdbAccessView.RESTORE_WIFI
                    on == AdbRestoreState.Connecting -> AdbAccessView.RESTORE_CONNECTING
                    on == AdbRestoreState.NeedsDialog -> AdbAccessView.RESTORE_DIALOG
                    on is AdbRestoreState.Failed -> AdbAccessView.RESTORE_FAILED
                    on is AdbRestoreState.Restored && shown != AdbRescuePhase.AUTHORIZATION_REQUIRED ->
                        AdbAccessView.RESTORE_CONNECTING
                    shown == AdbRescuePhase.UNAVAILABLE -> AdbAccessView.UNAVAILABLE
                    shown == AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbAccessView.NOT_GRANTED
                    shown == AdbRescuePhase.ERROR -> AdbAccessView.NOT_CONFIRMED
                    else -> AdbAccessView.FIRST_CHECK
                }
                val view = AdbStartupGatePolicy.view(s, r)
                assertEquals(name, expected, view)

                val gate = AdbStartupGatePolicy.overlay(s, r)
                assertEquals(name, view != AdbAccessView.TRUSTED && view != AdbAccessView.FIRST_CHECK, gate.visible)
                if (view == AdbAccessView.REQUEST_ON_SCREEN) assertTrue(name, gate.busy && gate.primaryLabel == null)
                if (view == AdbAccessView.SWITCH_OFF) {
                    assertEquals(name, AdbStartupGatePolicy.SERVICE_INSTRUCTION, gate.message)
                    assertEquals(name, AdbStartupPrimaryAction.CHECK_ACCESS, gate.primaryAction)
                }
                if (view == AdbAccessView.NOT_GRANTED) assertEquals(name, AdbStartupPrimaryAction.ASK, gate.primaryAction)
                // A check keeps a gate with a control, and only turns its ring.
                if (s.phase == AdbRescuePhase.CHECKING && gate.primaryLabel != null) assertTrue(name, gate.busy)

                val row = ServiceModel.of(
                    DenzaUiState(adbRescue = s, adbRestore = r ?: AdbRestoreSnapshot(enabled = false, state = AdbRestoreState.Disabled)),
                )
                assertEquals(name, view == AdbAccessView.TRUSTED, row.allWorking)
                assertEquals(name, view != AdbAccessView.TRUSTED, row.accessActions)
                assertEquals(name, view in blocked, row.accessTone == DenzaTileTone.BROKEN)
                assertEquals(name, view in blocked, row.status == ServiceModel.NO_ACCESS)
                // The gate's ring and the row's waiting buttons are the same moment.
                if (gate.busy && gate.primaryLabel != null) assertTrue(name, row.accessBusy)
                walked++
            }
        }
        assertTrue(walked > 1_000)
    }
}
