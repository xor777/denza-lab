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
    private class Fixture(scope: TestScope, held: AdbBootRecord? = null, boots: Int? = 5) {
        val system = System(scope).also { it.boots = boots }
        val store = Store().also { it.boot = held }
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
    @Test fun turningTheSwitchOnLooksAtOnceLikeOpeningService() = runTest {
        val f = Fixture(this); f.manager.setEnabled(false); f.system.live = true
        f.manager.setEnabled(true); runCurrent()
        assertEquals(AdbRestoreManager.SWITCH, f.manager.snapshot().lastTrigger)
        assertEquals(AdbRestoreState.NotNeeded, f.manager.snapshot().state)
    }
}
