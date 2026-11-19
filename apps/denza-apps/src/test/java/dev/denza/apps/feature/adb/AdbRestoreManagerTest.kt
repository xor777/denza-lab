package dev.denza.apps.feature.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

@OptIn(ExperimentalCoroutinesApi::class)
class AdbRestoreManagerTest {
    private class Store : AdbRestoreStore {
        override var enabled = true
        /** The disk refuses the next writes of the switch. */
        var refuseSwitch = false
        override fun saveEnabled(enabled: Boolean): Boolean {
            if (refuseSwitch) return false
            this.enabled = enabled
            return true
        }
        override var trustedBefore = true
        override var lastWriteNetwork: String? = null
        override var lastWriteAtMs = 0L
        override var lastOutcome: String? = null
        override var lastOutcomeAtMs = 0L
        override var autoAllow: String? = null
        override var dialogService: String? = null
        override var boot: AdbBootRecord? = null
    }
    private class System(private val scope: TestScope) : AdbRestoreSystem {
        override var sdk = 33
        var live = false
        var held = true
        var wifi: AdbRestoreWifi? = AdbRestoreWifi("router", "wifi")
        var wireless = false
        var revert = false
        var property: String? = "37111"
        var discovered: AdbTlsEndpoint? = AdbTlsEndpoint("127.0.0.1", 37112)
        val writes = mutableListOf<Pair<Long, Boolean>>()
        val endpoints = mutableListOf<Int>()
        var probes = 0
        var grants = 0
        var accessible = 0
        var recovered = 0
        var discoveries = 0
        var probe: (suspend () -> Unit)? = null
        var discover: (suspend () -> Unit)? = null
        var restart: (suspend () -> Unit)? = null
        override fun nowMs() = 1_000_000L + scope.testScheduler.currentTime
        override fun elapsedMs() = scope.testScheduler.currentTime
        var boots: Int? = 5
        override fun bootCount() = boots
        override suspend fun classicConnect(): Boolean { probes++; probe?.invoke(); return live }
        override fun permissionHeld() = held
        override suspend fun selfGrant(abandoned: () -> Boolean) { assertFalse(abandoned()); grants++; held = true }
        override fun wifiNetwork() = wifi
        var reads = 0
        override fun readAdbWifiEnabled(): Boolean { reads++; return wireless }
        override fun writeAdbWifiEnabled(enabled: Boolean) {
            writes += elapsedMs() to enabled
            if (enabled) { steps += "write@${elapsedMs()}"; onWrite?.invoke() }
            wireless = enabled && !revert
        }
        /** The dialog's service, and when it binds by the elapsed clock (null: never). */
        var service = AdbDialogService.BOUND
        var bindsAtMs: Long? = null
        /** After an in-process enable the service binds this much later; null: it never does. */
        var bindAfterEnableMs: Long? = 1_000L
        var enable: (suspend () -> Unit)? = null
        var onWrite: (() -> Unit)? = null
        var enables = 0
        /** The order of the enable and the dialog's write, each with its elapsed time. */
        val steps = mutableListOf<String>()
        override fun dialogService(): AdbDialogService {
            if (bindsAtMs?.let { elapsedMs() >= it } == true) service = AdbDialogService.BOUND
            return service
        }
        override suspend fun enableDialogService(abandoned: () -> Boolean) {
            assertFalse(abandoned()); enables++; steps += "enable@${elapsedMs()}"
            enable?.invoke()
            service = AdbDialogService.BINDING
            bindsAtMs = bindAfterEnableMs?.let { elapsedMs() + it }
        }
        override fun tlsPortProperty() = property
        override suspend fun discoverTlsPort(timeoutMs: Long): AdbTlsEndpoint? {
            discoveries++; discover?.invoke(); return discovered
        }
        override suspend fun restartTcpip(endpoint: AdbTlsEndpoint, abandoned: () -> Boolean) {
            assertFalse(abandoned()); endpoints += endpoint.port
            if (restart == null) live = true else restart?.invoke()
        }
        override suspend fun ensureAccessibilityForDialog(abandoned: () -> Boolean) { assertFalse(abandoned()); accessible++ }
        override suspend fun recoverRuntime() { recovered++ }
    }
    private class Fixture(scope: TestScope, held: AdbBootRecord? = null, boots: Int? = 5) {
        val system = System(scope).also { it.boots = boots }
        val store = Store().also { it.boot = held }
        /** Every state the manager published, in order, as its listener heard them. */
        val states = mutableListOf<AdbRestoreState>()
        lateinit var manager: AdbRestoreManager
        init {
            manager = AdbRestoreManager(system, store, scope.backgroundScope) {
                val state = manager.snapshot().state
                if (states.lastOrNull() != state) states += state
            }
        }
    }
    private fun TestScope.settle() { advanceTimeBy(1_501); runCurrent() }

    @Test fun livePortIsQuietAndPreparesTheExistingIdentity() = runTest {
        val f = Fixture(this); f.system.live = true; f.system.held = false; f.store.trustedBefore = false
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NotNeeded, f.manager.snapshot().state)
        assertTrue(f.store.trustedBefore); assertEquals(1, f.system.grants)
        assertEquals(1, f.system.accessible); assertTrue(f.system.writes.isEmpty())
        f.manager.attemptIfNeeded("watchdog"); runCurrent()
        assertEquals(1, f.system.grants)
    }
    @Test fun disabledAndUnsupportedNeverProbeOrWrite() = runTest {
        val f = Fixture(this); f.manager.setEnabled(false); f.system.writes.clear()
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
        assertEquals(0, f.system.probes); assertTrue(f.system.writes.isEmpty())
        f.system.sdk = 29; f.manager.setEnabled(true); runCurrent()
        assertEquals(AdbRestoreState.Unsupported, f.manager.snapshot().state)
        assertEquals(0, f.system.probes)
    }
    /**
     * Review finding 10: the switch's write was a commit on the main thread that threw when the
     * disk refused it - the app crashed on a tap. A refused write now changes nothing: the switch,
     * which shows the snapshot, stays where it was, and nothing acts on the setting it did not get.
     */
    @Test fun aSwitchTheDiskRefusedChangesNothing() = runTest {
        val f = Fixture(this); f.system.wireless = true
        var heard = 0
        val manager = AdbRestoreManager(f.system, f.store, backgroundScope) { heard++ }
        f.store.refuseSwitch = true

        assertFalse(manager.setEnabled(false))

        assertTrue(f.store.enabled)
        assertTrue(manager.snapshot().enabled)
        assertNotEquals(AdbRestoreState.Disabled, manager.snapshot().state)
        assertTrue("wireless debugging left as it was", f.system.writes.isEmpty())
        assertEquals("the switch reads the snapshot again", 1, heard)

        f.store.refuseSwitch = false
        assertTrue(manager.setEnabled(false))
        assertEquals(AdbRestoreState.Disabled, manager.snapshot().state)
        assertFalse(f.store.enabled)
    }
    @Test fun missingPermissionNeedsActivationWithoutEnablingWireless() = runTest {
        val f = Fixture(this); f.system.held = false
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation(refused = false), f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun noPriorTrustCannotActivateWirelessEvenWithPermission() = runTest {
        val f = Fixture(this); f.store.trustedBefore = false
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation(refused = false), f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun refusedPreviouslyTrustedKeyDoesNotCreateWirelessDialog() = runTest {
        val f = Fixture(this); f.system.probe = { throw AdbRestoreKeyUntrustedException() }
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation(refused = true), f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun missingWifiWaitsAndTheNextWifiHintRestores() = runTest {
        val f = Fixture(this); f.system.wifi = null
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.WaitingWifi, f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
        f.system.wifi = AdbRestoreWifi("router", "wifi")
        f.manager.attemptIfNeeded("wifi"); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(1, f.system.recovered)
    }
    @Test fun propertyPrecedesDiscoveryAndRestartsRuntimeOnce() = runTest {
        val f = Fixture(this)
        f.manager.attemptIfNeeded("startup"); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(listOf(37111), f.system.endpoints)
        assertEquals(0, f.system.discoveries); assertEquals(1, f.system.recovered)
        assertEquals(1, f.system.accessible)
    }
    @Test fun restartEofStillPollsClassic() = runTest {
        val f = Fixture(this); f.system.restart = { f.system.live = true; throw EOFException() }
        f.manager.attemptIfNeeded("startup"); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(1, f.system.recovered)
    }
    @Test fun invalidPropertyGoesToDiscovery() = runTest {
        for (value in listOf(null, "", "-1", "0", "65536", "bad")) {
            val f = Fixture(this); f.system.property = value
            f.manager.attemptIfNeeded("startup"); settle()
            assertEquals(listOf(37112), f.system.endpoints)
            assertEquals(1, f.system.discoveries)
        }
    }
    @Test fun propertyFailureFallsBackToDiscovery() = runTest {
        val f = Fixture(this); f.system.restart = {
            if (f.system.endpoints.last() == 37112) f.system.live = true
        }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(16_501); runCurrent()
        assertEquals(listOf(37111, 37112), f.system.endpoints)
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
    }
    @Test fun mdnsTimeoutIsTechnicalFailure() = runTest {
        val f = Fixture(this); f.system.property = null; f.system.discovered = null
        f.system.discover = { delay(45_000) }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(46_501); runCurrent()
        assertEquals("mDNS timeout", (f.manager.snapshot().state as AdbRestoreState.Failed).reason)
        assertEquals(0, f.system.recovered)
    }
    @Test fun lateClassicSuccessOutranksMdnsTimeout() = runTest {
        val f = Fixture(this); f.system.property = null; f.system.discovered = null
        f.system.discover = { f.system.live = true }
        f.manager.attemptIfNeeded("startup"); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
    }
    @Test fun silentClassicAfterBothTlsEndpointsFails() = runTest {
        val f = Fixture(this); f.system.restart = { }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(31_501); runCurrent()
        assertEquals("classic port silent after tcpip", (f.manager.snapshot().state as AdbRestoreState.Failed).reason)
    }
    @Test fun recentPersistedWriteDoesNotStartAFreshRetryWave() = runTest {
        val f = Fixture(this); f.store.lastWriteNetwork = "router"; f.store.lastWriteAtMs = f.system.nowMs()
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(60_000); runCurrent()
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun settingsOverridesCooldownAndDifferentNetworkWritesImmediately() = runTest {
        val f = Fixture(this); f.store.lastWriteNetwork = "router"; f.store.lastWriteAtMs = f.system.nowMs()
        f.manager.attemptIfNeeded("settings"); settle()
        assertEquals(1, f.system.writes.size)
        val other = Fixture(this); other.store.lastWriteNetwork = "old"; other.store.lastWriteAtMs = other.system.nowMs()
        other.manager.attemptIfNeeded("wifi"); settle()
        assertEquals(1, other.system.writes.size)
    }
    @Test fun alreadyEnabledWirelessNeedsNoWrite() = runTest {
        val f = Fixture(this); f.system.wireless = true
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun dialogWaveStopsAtFiveMinutes() = runTest {
        val f = Fixture(this); f.system.revert = true
        val probeTimes = mutableListOf<Long>(); f.system.probe = { probeTimes += f.system.elapsedMs() }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertEquals(listOf(0L, 3_500L, 8_500L, 18_500L), probeTimes.take(4))
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        val before = f.system.writes.size
        f.manager.attemptIfNeeded("screen-on"); advanceTimeBy(250_000); runCurrent()
        assertEquals(before, f.system.writes.size)
        f.manager.attemptIfNeeded("settings"); runCurrent()
        assertEquals(before + 1, f.system.writes.size)
    }

    /**
     * Every write raises one more network dialog on top of the one waiting. Until 2026-11-19 the
     * wave's retries wrote every 15 s - about twenty dialogs in five minutes; now the wave's one
     * write is the attempt's, and its retries read.
     */
    @Test fun aDialogWaveWritesOnceAndItsRetriesOnlyRead() = runTest {
        val f = Fixture(this); f.system.revert = true
        var retries = 0; f.system.probe = { retries++ }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertTrue("the wave ran its retries", retries > 10)
        assertEquals(listOf(true), f.system.writes.map { it.second })
        assertTrue("each retry read the setting", f.system.reads >= retries)
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
    }
    @Test fun anApprovalMidWaveConnectsWithoutASecondWrite() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(25_000); runCurrent()
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        // The dialog is approved: the system sets the setting itself, and the next retry reads it.
        f.system.wireless = true
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(listOf(true), f.system.writes.map { it.second })
        assertEquals(listOf(37111), f.system.endpoints)
    }
    @Test fun aForcingRequestDuringTheWaveWritesAgain() = runTest {
        for (trigger in listOf(AdbRestoreManager.GATE, AdbRestoreManager.SETTINGS)) {
            val f = Fixture(this); f.system.revert = true
            f.manager.attemptIfNeeded("startup"); advanceTimeBy(40_000); runCurrent()
            assertEquals(trigger, 1, f.system.writes.size)
            f.manager.attemptIfNeeded(trigger); runCurrent()
            assertEquals(trigger, 2, f.system.writes.size)
            // The forced write has its own wave, and that wave reads too.
            advanceTimeBy(60_000); runCurrent()
            assertEquals(trigger, 2, f.system.writes.size)
            assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        }
    }
    /**
     * The exhausted wave's dialog still stands unanswered: one more every ten minutes would be a
     * slower stack. The network waits for a forcing request, another network or a success.
     */
    @Test fun anExhaustedNetworkIsNotWrittenByPassiveHintsHoweverLate() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        assertEquals(1, f.system.writes.size)
        advanceTimeBy(AdbRestoreManager.WRITE_COOLDOWN_MS * 3); runCurrent()
        for (hint in listOf(AdbRestoreManager.WIFI, AdbRestoreManager.SCREEN_ON, "recovery")) {
            f.manager.attemptIfNeeded(hint); runCurrent()
        }
        assertEquals(1, f.system.writes.size)
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
    }
    @Test fun aForcingRequestOnAnExhaustedNetworkWritesOnceWithAWaveOfItsOwn() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        f.manager.attemptIfNeeded(AdbRestoreManager.GATE); settle()
        assertEquals(2, f.system.writes.size)
        assertFalse(f.manager.snapshot().retryBudgetExhausted)
        // Its wave reads: approved now, the next retry connects with no third write.
        f.system.wireless = true
        advanceTimeBy(3_000); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(2, f.system.writes.size)
    }
    @Test fun anotherNetworkAfterAnExhaustedWaveWritesAsBefore() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        f.system.wifi = AdbRestoreWifi("hotspot", "phone")
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent()
        assertEquals(2, f.system.writes.size)
        assertEquals("hotspot", f.store.lastWriteNetwork)
    }

    // The service that presses the network dialog, made ready before the write that raises it.

    @Test fun aBoundServiceIsLeftAloneAndTheWriteFollows() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("process-start"); runCurrent()
        assertEquals(0, f.system.enables)
        assertEquals(listOf("write@0"), f.system.steps)
        assertEquals("bound", f.manager.snapshot().dialogService)
        assertEquals("bound", f.store.dialogService)
    }
    @Test fun aMissingServiceIsEnabledAndAwaitedBeforeTheWrite() = runTest {
        val f = Fixture(this); f.system.revert = true; f.system.service = AdbDialogService.MISSING
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("enable@0", "write@1000"), f.system.steps)
        assertEquals("enabled, bound", f.manager.snapshot().dialogService)
    }
    @Test fun aServiceOnItsWayIsAwaitedAndNeverRewritten() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.system.service = AdbDialogService.BINDING; f.system.bindsAtMs = 400L
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, f.system.enables)
        assertEquals(listOf("write@400"), f.system.steps)
        assertEquals("binding, bound", f.manager.snapshot().dialogService)
    }
    @Test fun aFailedEnableStillWrites() = runTest {
        val f = Fixture(this); f.system.revert = true; f.system.service = AdbDialogService.MISSING
        f.system.enable = { throw IllegalStateException("enabled_accessibility_services was not written") }
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("enable@0", "write@0"), f.system.steps)
        assertEquals("enable failed: IllegalStateException", f.manager.snapshot().dialogService)
    }
    @Test fun aServiceThatNeverBindsStillGetsTheWriteAfterTheWait() = runTest {
        val f = Fixture(this); f.system.revert = true; f.system.service = AdbDialogService.MISSING
        f.system.bindAfterEnableMs = null
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("enable@0", "write@${AdbRestoreManager.DIALOG_SERVICE_BIND_WAIT_MS}"), f.system.steps)
        assertEquals("enabled, not bound in 3 s", f.manager.snapshot().dialogService)
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
    }
    @Test fun aSuppressedWritePreparesNothing() = runTest {
        val f = Fixture(this); f.system.service = AdbDialogService.MISSING
        f.store.lastWriteNetwork = "router"; f.store.lastWriteAtMs = f.system.nowMs()
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        assertEquals(0, f.system.enables)
        assertTrue(f.system.steps.isEmpty())
        assertNull(f.manager.snapshot().dialogService)
    }
    @Test fun abandonedBetweenTheEnableAndTheWriteWritesNothing() = runTest {
        val f = Fixture(this); f.system.service = AdbDialogService.MISSING
        f.system.enable = { f.manager.setEnabled(false) }
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.system.enables)
        assertTrue("no dialog raised", f.system.writes.none { it.second })
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
    }

    // While the service is made ready the gate shows the restore at work, and never for good.

    @Test fun aServiceNotReadyPublishesTheRestoreBeforeTheEnable() = runTest {
        val f = Fixture(this); f.system.revert = true; f.system.service = AdbDialogService.MISSING
        var atEnable: AdbRestoreState? = null
        f.system.enable = { atEnable = f.manager.snapshot().state }
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(5_000); runCurrent()
        assertEquals(AdbRestoreState.Connecting, atEnable)
        // The reverted write ends it.
        assertEquals(listOf(AdbRestoreState.Connecting, AdbRestoreState.NeedsDialog), f.states)
    }
    @Test fun aServiceOnItsWayPublishesTheRestoreDuringTheWait() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.system.service = AdbDialogService.BINDING; f.system.bindsAtMs = 2_000L
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(1_000); runCurrent()
        assertEquals(AdbRestoreState.Connecting, f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
    }
    @Test fun aBoundServicePublishesNothingEarly() = runTest {
        val f = Fixture(this); f.system.revert = true
        var atWrite: AdbRestoreState? = null
        f.system.onWrite = { atWrite = f.manager.snapshot().state }
        f.manager.attemptIfNeeded("process-start"); settle()
        assertEquals(AdbRestoreState.NotNeeded, atWrite)
        assertFalse(AdbRestoreState.Connecting in f.states)
        assertEquals(AdbRestoreState.NeedsDialog, f.states.last())
        assertEquals(listOf("write@0"), f.system.steps)
    }
    @Test fun everyExitAfterTheEarlyPublishEndsInAFinalState() = runTest {
        // The enable throws, or the bind never comes: the write is reverted - NeedsDialog.
        for (setup in listOf<(System) -> Unit>(
            { it.enable = { throw IllegalStateException() } },
            { it.bindAfterEnableMs = null },
        )) {
            val f = Fixture(this); f.system.revert = true; f.system.service = AdbDialogService.MISSING
            setup(f.system)
            f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf(AdbRestoreState.Connecting, AdbRestoreState.NeedsDialog), f.states)
        }
        // The write holds: Connecting proper, then the restore's own end.
        val held = Fixture(this); held.system.service = AdbDialogService.MISSING
        held.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertTrue(held.manager.snapshot().state is AdbRestoreState.Restored)
        // Abandoned: what the switch put there.
        val off = Fixture(this); off.system.service = AdbDialogService.MISSING
        off.system.enable = { off.manager.setEnabled(false) }
        off.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(AdbRestoreState.Disabled, off.manager.snapshot().state)
        // Wi-Fi gone during the wait: WaitingWifi, not the ring.
        val lost = Fixture(this); lost.system.service = AdbDialogService.MISSING; lost.system.bindAfterEnableMs = null
        lost.system.enable = { lost.system.wifi = null }
        lost.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(AdbRestoreState.WaitingWifi, lost.manager.snapshot().state)
        assertTrue(lost.system.writes.isEmpty())
        // Another network during the wait: the attempt starts again there and gets to its own end.
        val moved = Fixture(this); moved.system.service = AdbDialogService.MISSING
        moved.system.enable = { if (moved.system.enables == 1) moved.system.wifi = AdbRestoreWifi("next", "wifi") }
        moved.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertTrue(moved.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals("next", moved.store.lastWriteNetwork)
    }
    @Test fun aSuppressedWriteNeverShowsTheRing() = runTest {
        val f = Fixture(this); f.system.service = AdbDialogService.MISSING
        f.store.lastWriteNetwork = "router"; f.store.lastWriteAtMs = f.system.nowMs()
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(AdbRestoreState.NeedsDialog), f.states)
    }
    @Test fun approvingDialogBetweenRetriesRestoresAndEndsWave() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); settle()
        assertEquals(AdbRestoreState.NeedsDialog, f.manager.snapshot().state)
        f.system.wireless = true
        advanceTimeBy(2_001); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        val probes = f.system.probes
        advanceTimeBy(60_000); runCurrent(); assertEquals(probes, f.system.probes)
    }
    @Test fun switchingWifiEndsOldWaveAndWritesForNewNetwork() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); settle()
        f.system.wifi = AdbRestoreWifi("new", "hotspot")
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals("new", f.store.lastWriteNetwork)
        assertEquals(2, f.system.writes.size)
    }
    @Test fun disablingDuringSettleRollsBackAndStopsRetryWave() = runTest {
        val f = Fixture(this)
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(listOf(true), f.system.writes.map { it.second })
        f.manager.setEnabled(false); advanceTimeBy(320_000); runCurrent()
        assertEquals(listOf(true, false), f.system.writes.map { it.second })
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
        assertTrue(f.system.endpoints.isEmpty()); assertEquals(0, f.system.recovered)
    }
    @Test fun disablingDuringDiscoveryNeverSendsTcpip() = runTest {
        val f = Fixture(this); f.system.property = null
        f.system.discover = { f.manager.setEnabled(false) }
        f.manager.attemptIfNeeded("startup"); settle()
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
        assertTrue(f.system.endpoints.isEmpty()); assertEquals(0, f.system.recovered)
    }
    @Test fun disablingDuringTcpipDoesNotStartRuntimeOrPublishRestored() = runTest {
        val f = Fixture(this); f.system.restart = { f.manager.setEnabled(false); f.system.live = true }
        f.manager.attemptIfNeeded("startup"); settle()
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
        assertEquals(0, f.system.recovered)
    }
    @Test fun burstsDuringAttemptCoalesceIntoOneRepeat() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); f.system.live = true
        f.system.probe = { release.await() }
        f.manager.attemptIfNeeded("startup"); runCurrent()
        repeat(10) { f.manager.attemptIfNeeded("wifi") }
        release.complete(Unit); runCurrent()
        assertEquals(2, f.system.probes); assertTrue(f.system.writes.isEmpty())
    }
    @Test fun disableThenEnableCannotLeaveOldAttemptInCharge() = runTest {
        val f = Fixture(this)
        f.manager.attemptIfNeeded("startup"); runCurrent()
        f.manager.setEnabled(false); f.manager.setEnabled(true); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(listOf(true, false, true), f.system.writes.map { it.second })
        assertEquals(1, f.system.recovered)
    }
    @Test fun verdictSeparatesFlightTrustAndRuntime() {
        assertNull(evaluateAdbVerdict(true, null, false, true))
        assertEquals(AdbRestoreVerdict.OK, evaluateAdbVerdict(true, true, true, true))
        assertEquals(AdbRestoreVerdict.HELPER_DOWN, evaluateAdbVerdict(true, true, false, true))
        assertEquals(AdbRestoreVerdict.NO_ACCESS, evaluateAdbVerdict(true, false, false, false))
        assertEquals(AdbRestoreVerdict.OFF_AFTER_REBOOT, evaluateAdbVerdict(true, false, false, true))
        assertEquals(AdbRestoreVerdict.NOT_ENABLED, evaluateAdbVerdict(false, false, false, true))
    }
    @Test fun disabledWhileProbingCannotGrantOrEnableAnything() = runTest {
        val f = Fixture(this); f.system.live = true; f.system.held = false
        f.system.probe = { f.manager.setEnabled(false) }
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.Disabled, f.manager.snapshot().state)
        assertEquals(0, f.system.grants); assertEquals(0, f.system.accessible)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun confirmedSuccessClearsExhaustedNetwork() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        f.system.live = true; f.manager.attemptIfNeeded("screen-on"); runCurrent()
        f.system.live = false; f.system.revert = false
        advanceTimeBy(600_001)
        f.manager.attemptIfNeeded("screen-on"); settle()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
    }

    @Test fun aLaterOpenCheckKeepsTheRestoreThisBootNeeded() = runTest {
        val f = Fixture(this)
        f.manager.attemptIfNeeded("process-start"); settle()
        val restored = f.manager.snapshot().boot!!
        assertEquals(AdbBootLook.CLOSED, restored.look)
        assertEquals(AdbBootOutcome.RESTORED, restored.outcome)
        assertTrue(restored.closedAtMs in 1..restored.outcomeAtMs)
        advanceTimeBy(60_000)
        f.manager.attemptIfNeeded(AdbRestoreManager.SETTINGS); runCurrent()
        val after = f.manager.snapshot()
        // The check that opening «Сервис» makes is the last check, and nothing more.
        assertEquals(AdbRestoreState.NotNeeded, after.state)
        assertEquals("not-needed", after.lastOutcome)
        assertEquals(AdbBootOutcome.RESTORED, after.boot!!.outcome)
        assertEquals(restored.outcomeAtMs, after.boot!!.outcomeAtMs)
        assertEquals(after.boot, f.store.boot)
    }
    @Test fun aPortOpenAtTheFirstLookNeededNothingUntilItCloses() = runTest {
        val f = Fixture(this); f.system.live = true
        f.manager.attemptIfNeeded("process-start"); runCurrent()
        assertEquals(AdbBootLook.OPEN, f.manager.snapshot().boot!!.look)
        assertNull(f.manager.snapshot().boot!!.outcome)
        advanceTimeBy(600_000); f.system.live = false
        f.manager.attemptIfNeeded("watchdog"); runCurrent()
        val closedAt = f.manager.snapshot().boot!!.closedAtMs
        assertEquals(f.system.nowMs(), closedAt)
        settle()
        val boot = f.manager.snapshot().boot!!
        // The first look stays the boot's; the restore is the later closure's.
        assertEquals(AdbBootLook.OPEN, boot.look)
        assertEquals(AdbBootOutcome.RESTORED, boot.outcome)
        assertEquals(closedAt, boot.closedAtMs)
    }
    @Test fun aClosureLastsUntilTheRestoreEndsIt() = runTest {
        val f = Fixture(this); f.system.wifi = null
        f.manager.attemptIfNeeded("process-start"); runCurrent()
        val first = f.manager.snapshot().boot!!
        assertEquals(AdbBootOutcome.WAITING_WIFI, first.outcome)
        advanceTimeBy(30_000)
        f.manager.attemptIfNeeded("screen-on"); runCurrent()
        assertEquals(first.closedAtMs, f.manager.snapshot().boot!!.closedAtMs)
        f.system.wifi = AdbRestoreWifi("router", "wifi")
        f.manager.attemptIfNeeded("wifi"); settle()
        val boot = f.manager.snapshot().boot!!
        assertEquals(AdbBootOutcome.RESTORED, boot.outcome)
        assertEquals(first.closedAtMs, boot.closedAtMs)
        assertTrue(boot.outcomeAtMs - boot.closedAtMs >= 30_000)
    }
    @Test fun accessThatComesBackWhileWaitingIsNotCalledARestore() = runTest {
        val f = Fixture(this); f.system.wifi = null
        f.manager.attemptIfNeeded("process-start"); runCurrent()
        f.system.live = true
        f.manager.attemptIfNeeded("screen-on"); runCurrent()
        assertEquals(AdbBootOutcome.RETURNED, f.manager.snapshot().boot!!.outcome)
    }
    @Test fun aRefusedKeyIsTheBootsFirstLook() = runTest {
        val f = Fixture(this); f.system.probe = { throw AdbRestoreKeyUntrustedException() }
        f.manager.attemptIfNeeded("process-start"); runCurrent()
        val boot = f.manager.snapshot().boot!!
        assertEquals(AdbBootLook.REFUSED, boot.look)
        assertEquals(AdbBootOutcome.NEEDS_ACTIVATION, boot.outcome)
    }
    @Test fun theSameBootsRecordOutlivesTheProcessAndAnotherBootsDoesNot() = runTest {
        val earlier = AdbBootRecord(bootCount = 5, bootAtMs = 900_000L, seenAtElapsedMs = 0L,
            look = AdbBootLook.CLOSED, closedAtMs = 900_100L, outcome = AdbBootOutcome.RESTORED, outcomeAtMs = 900_200L)
        assertEquals(earlier, Fixture(this, held = earlier, boots = 5).manager.snapshot().boot)
        val next = Fixture(this, held = earlier, boots = 6)
        assertNull(next.manager.snapshot().boot)
        next.system.live = true
        next.manager.attemptIfNeeded("process-start"); runCurrent()
        val boot = next.manager.snapshot().boot!!
        assertEquals(6, boot.bootCount)
        assertEquals(AdbBootLook.OPEN, boot.look)
        assertNull(boot.outcome)
    }
    @Test fun withoutABootCountAnElapsedClockBelowTheLastSeenIsANewBoot() {
        val held = AdbBootRecord(bootCount = null, bootAtMs = 0L, seenAtElapsedMs = 100_000L)
        assertTrue(held.isBoot(null, 100_000L))
        assertFalse(held.isBoot(null, 5_000L))
        assertFalse(held.copy(bootCount = 5).isBoot(6, 200_000L))
    }
    @Test fun theRecordIsOnePreferenceValue() {
        val record = AdbBootRecord(bootCount = 7, bootAtMs = 1L, seenAtElapsedMs = 2L, look = AdbBootLook.REFUSED,
            closedAtMs = 3L, outcome = AdbBootOutcome.FAILED, outcomeAtMs = 4L)
        assertEquals(record, AdbBootRecord.decode(record.encode()))
        val bare = AdbBootRecord(bootCount = null, bootAtMs = 1L, seenAtElapsedMs = 2L)
        assertEquals(bare, AdbBootRecord.decode(bare.encode()))
        assertNull(AdbBootRecord.decode("broken"))
        assertNull(AdbBootRecord.decode(null))
    }
    /** An attempt that ends `Failed` at once: no TLS port to read, and discovery finds none. */
    private fun TestScope.failedOnce(): Fixture {
        val f = Fixture(this); f.system.property = null; f.system.discovered = null
        f.manager.attemptIfNeeded("process-start"); settle()
        assertEquals("mDNS timeout", (f.manager.snapshot().state as AdbRestoreState.Failed).reason)
        assertEquals(1, f.system.discoveries)
        return f
    }

    @Test fun aFailureIsNotRestartedByPassiveHintsOnTheSameNetwork() = runTest {
        val f = failedOnce(); val failed = f.manager.snapshot()
        val probes = f.system.probes; val writes = f.system.writes.size
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        f.manager.attemptIfNeeded("recovery"); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        f.manager.attemptIfNeeded("watchdog"); runCurrent()
        // The port is still looked at on every hint; nothing past it runs.
        assertEquals(probes + 3, f.system.probes)
        assertEquals(1, f.system.discoveries); assertTrue(f.system.endpoints.isEmpty())
        assertEquals(writes, f.system.writes.size)
        assertEquals(failed.state, f.manager.snapshot().state)
        assertEquals("process-start", f.manager.snapshot().lastTrigger)
    }
    @Test fun aFailureOnOneNetworkDoesNotHoldAnother() = runTest {
        val f = failedOnce(); f.system.wifi = AdbRestoreWifi("hotspot", "phone")
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(2, f.system.discoveries)
    }
    @Test fun theScreenComingOnTriesAgainAfterAFailure() = runTest {
        val f = failedOnce()
        f.manager.attemptIfNeeded(AdbRestoreManager.SCREEN_ON); runCurrent()
        assertEquals(2, f.system.discoveries)
        assertEquals(AdbRestoreManager.SCREEN_ON, f.manager.snapshot().lastTrigger)
    }
    @Test fun aPressAndServiceTryAgainAfterAFailure() = runTest {
        val f = failedOnce()
        f.manager.attemptIfNeeded(AdbRestoreManager.GATE); runCurrent()
        assertEquals(2, f.system.discoveries)
        f.manager.attemptIfNeeded(AdbRestoreManager.SETTINGS); runCurrent()
        assertEquals(3, f.system.discoveries)
        // And the failures they end in hold the passive hints again.
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(3, f.system.discoveries)
    }
    @Test fun aFailureHoldsForTheCooldownOnly() = runTest {
        val f = failedOnce()
        advanceTimeBy(AdbRestoreManager.FAILED_COOLDOWN_MS - 1_000); runCurrent()
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(1, f.system.discoveries)
        advanceTimeBy(1_000); runCurrent()
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(2, f.system.discoveries)
    }
    @Test fun accessThatComesBackDuringTheHoldIsStillNoticed() = runTest {
        val f = failedOnce(); f.system.live = true
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(AdbRestoreState.NotNeeded, f.manager.snapshot().state)
        // And the hold went with the failure.
        f.system.live = false
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(2, f.system.discoveries)
    }
    @Test fun wifiLostAndBackIsANewConnection() = runTest {
        val f = failedOnce(); val router = f.system.wifi
        f.system.wifi = null
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI_LOST); runCurrent()
        assertEquals(AdbRestoreState.WaitingWifi, f.manager.snapshot().state)
        f.system.wifi = router
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(2, f.system.discoveries)

        // The loss heard only as `wifi-lost`, with Wi-Fi back by the time it is looked at: no
        // `WaitingWifi` comes between, so forgetting the hold at the hint is what lets it through.
        val late = failedOnce()
        late.manager.attemptIfNeeded(AdbRestoreManager.WIFI_LOST); runCurrent()
        assertEquals(2, late.system.discoveries)

        // The loss seen only by an attempt (`WaitingWifi`, no hint), and the next attempt's failure
        // published with Wi-Fi gone again, so it holds nothing of its own: only the forgetting at
        // `WaitingWifi` keeps the first failure's hold from coming back with that failure.
        val seen = failedOnce(); val home = seen.system.wifi
        seen.system.wifi = null
        seen.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent()
        assertEquals(AdbRestoreState.WaitingWifi, seen.manager.snapshot().state)
        seen.system.wifi = home
        seen.system.discover = { seen.system.wifi = null; throw EOFException() }
        seen.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent()
        assertEquals("EOFException", (seen.manager.snapshot().state as AdbRestoreState.Failed).reason)
        seen.system.discover = null; seen.system.wifi = home
        seen.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent()
        assertEquals(3, seen.system.discoveries)
    }
    @Test fun aWifiBounceDuringAFailingAttemptIsANewConnection() = runTest {
        val f = Fixture(this); f.system.property = null; f.system.discovered = null
        f.system.discover = { delay(45_000) }
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        assertEquals(AdbRestoreState.Connecting, f.manager.snapshot().state)
        // Dropped and back on the same router between two of the attempt's looks at the network.
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI_LOST); f.manager.attemptIfNeeded(AdbRestoreManager.WIFI)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(2, f.system.discoveries)
    }
    @Test fun hintsQueuedDuringAFailingAttemptAreHeldAfterIt() = runTest {
        val f = Fixture(this); f.system.property = null; f.system.discovered = null
        f.system.discover = { delay(45_000) }
        f.manager.attemptIfNeeded("process-start"); advanceTimeBy(10_000); runCurrent()
        f.manager.attemptIfNeeded("recovery"); f.manager.attemptIfNeeded(AdbRestoreManager.WIFI)
        advanceTimeBy(40_000); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Failed)
        // Looked at the port, and nothing past it: the same connection, the same failure.
        assertEquals(1, f.system.discoveries)
        assertEquals("process-start", f.manager.snapshot().lastTrigger)
    }
    @Test fun theScreenComingOnIsNotLostToAPassiveHintAfterIt() = runTest {
        val f = failedOnce(); val release = CompletableDeferred<Unit>()
        f.system.probe = { release.await() }
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent() // held, still at its probe
        f.manager.attemptIfNeeded(AdbRestoreManager.SCREEN_ON)
        f.manager.attemptIfNeeded("recovery")
        release.complete(Unit); runCurrent()
        assertEquals(2, f.system.discoveries)
    }
    @Test fun aRetryMergedWithAPassiveHintStillOnlyReads() = runTest {
        val f = Fixture(this); f.system.revert = true
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(9_000); runCurrent()
        assertEquals(1, f.system.writes.size)
        val release = CompletableDeferred<Unit>(); f.system.probe = { release.await() }
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI); runCurrent() // an attempt held at its probe
        advanceTimeBy(10_000); runCurrent() // the wave's retry at 18.5 s waits behind it
        f.manager.attemptIfNeeded("recovery") // and a passive hint merges into the retry
        release.complete(Unit); runCurrent()
        assertEquals(1, f.system.writes.size)
    }
    @Test fun aNetworkChangeAfterConnectingStartsAgainOnTheNewOne() = runTest {
        val f = Fixture(this); f.system.restart = { if (f.system.wifi?.id == "next") f.system.live = true }
        f.manager.attemptIfNeeded("startup"); settle()
        assertEquals(AdbRestoreState.Connecting, f.manager.snapshot().state)
        // Roamed to another access point of the same Wi-Fi, and no hint came with it.
        f.system.wifi = AdbRestoreWifi("next", "wifi")
        advanceTimeBy(2_000); runCurrent()
        assertTrue(f.manager.snapshot().state is AdbRestoreState.Restored)
        assertEquals(listOf(37111, 37111), f.system.endpoints)
    }
    @Test fun aNetworkThatNeverSettlesEndsFailedNotInAnEndlessRing() = runTest {
        val f = Fixture(this); val a = AdbRestoreWifi("a", "wifi"); val b = AdbRestoreWifi("b", "wifi")
        f.system.wifi = a
        // Every attempt sees the id change under it: A, B, A, B...
        f.system.restart = { delay(1_000); f.system.wifi = if (f.system.wifi == a) b else a }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(60_000); runCurrent()
        val state = f.manager.snapshot().state
        assertEquals(AdbRestoreManager.NETWORK_UNSETTLED, (state as AdbRestoreState.Failed).reason)
        // The first attempt and three restarts, no more.
        val bounded = 1 + AdbRestoreManager.MAX_NETWORK_RESTARTS
        assertEquals(bounded, f.system.endpoints.size)
        // Held on the network seen last, like any failure.
        f.manager.attemptIfNeeded(AdbRestoreManager.WIFI); advanceTimeBy(10_000); runCurrent()
        assertEquals(bounded, f.system.endpoints.size)
        // A press starts again, with the count from zero.
        f.manager.attemptIfNeeded(AdbRestoreManager.GATE); advanceTimeBy(60_000); runCurrent()
        assertEquals(2 * bounded, f.system.endpoints.size)
    }
    @Test fun wifiGoneAfterConnectingWaitsForWifi() = runTest {
        val f = Fixture(this); f.system.restart = { }
        f.manager.attemptIfNeeded("startup"); settle()
        assertEquals(AdbRestoreState.Connecting, f.manager.snapshot().state)
        f.system.wifi = null
        advanceTimeBy(600_000); runCurrent()
        assertEquals(AdbRestoreState.WaitingWifi, f.manager.snapshot().state)
    }
    @Test fun aRefusedKeyDuringTheHoldStillNeedsActivation() = runTest {
        val f = failedOnce(); f.system.probe = { throw AdbRestoreKeyUntrustedException() }
        f.manager.attemptIfNeeded("wifi"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation(refused = true), f.manager.snapshot().state)
    }

    @Test fun turningTheSwitchOnLooksAtOnceLikeOpeningService() = runTest {
        val f = Fixture(this); f.manager.setEnabled(false); f.system.live = true
        f.manager.setEnabled(true); runCurrent()
        assertEquals(AdbRestoreManager.SWITCH, f.manager.snapshot().lastTrigger)
        assertEquals(AdbRestoreState.NotNeeded, f.manager.snapshot().state)
    }
}
