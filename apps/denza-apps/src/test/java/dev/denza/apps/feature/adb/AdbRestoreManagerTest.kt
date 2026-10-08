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
        override var trustedBefore = true
        override var lastWriteNetwork: String? = null
        override var lastWriteAtMs = 0L
        override var lastOutcome: String? = null
        override var lastOutcomeAtMs = 0L
        override var autoAllow: String? = null
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
        override suspend fun classicConnect(): Boolean { probes++; probe?.invoke(); return live }
        override fun permissionHeld() = held
        override suspend fun selfGrant(abandoned: () -> Boolean) { assertFalse(abandoned()); grants++; held = true }
        override fun wifiNetwork() = wifi
        override fun readAdbWifiEnabled() = wireless
        override fun writeAdbWifiEnabled(enabled: Boolean) {
            writes += elapsedMs() to enabled
            wireless = enabled && !revert
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
    private class Fixture(scope: TestScope) {
        val system = System(scope)
        val store = Store()
        val manager = AdbRestoreManager(system, store, scope.backgroundScope)
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
    @Test fun missingPermissionNeedsActivationWithoutEnablingWireless() = runTest {
        val f = Fixture(this); f.system.held = false
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation, f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun noPriorTrustCannotActivateWirelessEvenWithPermission() = runTest {
        val f = Fixture(this); f.store.trustedBefore = false
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation, f.manager.snapshot().state)
        assertTrue(f.system.writes.isEmpty())
    }
    @Test fun refusedPreviouslyTrustedKeyDoesNotCreateWirelessDialog() = runTest {
        val f = Fixture(this); f.system.probe = { throw AdbRestoreKeyUntrustedException() }
        f.manager.attemptIfNeeded("startup"); runCurrent()
        assertEquals(AdbRestoreState.NeedsActivation, f.manager.snapshot().state)
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
    @Test fun dialogWaveUsesShortCooldownAndStopsAtFiveMinutes() = runTest {
        val f = Fixture(this); f.system.revert = true
        val probeTimes = mutableListOf<Long>(); f.system.probe = { probeTimes += f.system.elapsedMs() }
        f.manager.attemptIfNeeded("startup"); advanceTimeBy(310_000); runCurrent()
        assertEquals(listOf(0L, 3_500L, 8_500L, 18_500L), probeTimes.take(4))
        assertTrue(f.system.writes.size > 1)
        assertTrue(f.system.writes.zipWithNext().all { (a, b) -> b.first - a.first >= 15_000 })
        assertTrue(f.manager.snapshot().retryBudgetExhausted)
        val before = f.system.writes.size
        f.manager.attemptIfNeeded("screen-on"); advanceTimeBy(320_000); runCurrent()
        assertEquals(before, f.system.writes.size)
        f.manager.attemptIfNeeded("settings"); runCurrent()
        assertEquals(before + 1, f.system.writes.size)
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
}
