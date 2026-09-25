package dev.denza.apps.feature.cloud

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Stateful guardian model: ownership survives every bridge and application instance. */
class CloudCustomLifecycleTest {
    private class Fixture {
        val log = mutableListOf<String>()
        var time = 100L
        var nativeOwner: String? = null
        var ownerCount = 0
        var generationMatches = true
        var installationMatches = true
        var raceAtStart = false
        val store = FakeStore(log)
        val pending = ArrayDeque<FakeBackend>()
        val identity = CloudIdentity("89860700000000000001", "460010000000001")
        fun backend(nonce: String): FakeBackend = FakeBackend(this, nonce).also { pending.add(it) }
        fun lifecycle() = CloudCustomLifecycle(store, {
            pending.removeFirst().also { log += "open:${it.nonce}" }
        }, { time }, { "f".repeat(32) })
        fun nextRetry() { time += CloudCustomLifecycle.RETRY_MS }
        fun count(operation: String) = log.count { it.startsWith("$operation:") }
    }

    private class FakeStore(private val log: MutableList<String>) : CloudCustomLifecycle.Store {
        var owner: String? = null
        var failClaimBefore = false
        var failClaimAfter = false
        var failAdopt = false
        var failClear = false
        var terminal: String? = null
        var durableTerminal: String? = null
        var failTerminalAfterMemory = false
        var terminalWrites = 0
        override fun ownerNonce(): String? = owner
        override fun claim(nonce: String) {
            log += "claim:$nonce"
            check(owner == null)
            if (failClaimBefore) throw IOException()
            owner = nonce
            if (failClaimAfter) throw IOException()
        }
        override fun adopt(expected: String?, owner: String) {
            log += "adopt:$owner"
            check(this.owner == expected)
            if (failAdopt) throw IOException()
            this.owner = owner
        }
        override fun clear(nonce: String) {
            log += "clear:$nonce"
            check(owner == nonce)
            if (failClear) throw IOException()
            owner = null
        }
        override fun terminalCode() = terminal
        override fun saveTerminal(code: String) {
            terminalWrites++
            terminal = code
            if (failTerminalAfterMemory) throw IOException("disk refused after RAM update")
            durableTerminal = code
        }
        override fun clearTerminal() { terminal = null; durableTerminal = null }
    }

    private class FakeBackend(private val f: Fixture, override val nonce: String) : CloudCustomLifecycle.Backend {
        var running = true
        var startAfterEffectFailure = false
        var startRejection: CloudCustomRejected? = null
        var stopBeforeEffectFailure = false
        var stopAfterEffectFailure = false
        var statusFailure = false
        var probeFailure = false
        var probeRejection: CloudCustomRejected? = null
        var probeAnswer: CloudCustomStatus? = null
        var statusAnswer: CloudCustomStatus? = null
        var stopAnswer: CloudCustomStatus? = null
        var startIdentity: CloudIdentity? = null
        override fun isRunning(): Boolean = running
        override fun probe(): CloudCustomStatus {
            f.log += "probe:$nonce"
            if (probeFailure) throw IllegalStateException("private reply must not escape")
            probeRejection?.let { throw it }
            probeAnswer?.let { return it }
            return if (f.nativeOwner == null) value("stopped", "owner_absent_confirmed")
                else value("connected", "owner_present", true)
        }
        override fun attach(ownerId: String): CloudCustomStatus {
            f.log += "attach:$nonce"
            check(ownerId == f.nativeOwner)
            if (!f.installationMatches) throw CloudCustomRejected("owner_changed", true)
            if (!f.generationMatches) throw CloudCustomRejected("config_changed", true)
            check(f.nativeOwner != null)
            return value("connected", "connected", true)
        }
        override fun start(identity: CloudIdentity): CloudCustomStatus {
            f.log += "start:$nonce"
            check(f.store.owner == nonce) { "START without durable intent" }
            startRejection?.let { throw it }
            if (f.raceAtStart) {
                f.nativeOwner = "e".repeat(32)
                throw CloudCustomRejected("owner_present", true)
            }
            check(f.nativeOwner == null)
            startIdentity = identity
            f.nativeOwner = (++f.ownerCount).toString(16).padStart(32, '0')
            if (startAfterEffectFailure) throw IOException("reply lost")
            return value("connected", "connected", true)
        }
        override fun status(): CloudCustomStatus {
            f.log += "status:$nonce"
            if (statusFailure) throw IOException("reply lost")
            statusAnswer?.let { return it }
            return value("connected", "connected", true)
        }
        override fun renew(ownerId: String, renewSeq: Long): CloudCustomStatus {
            check(ownerId == f.nativeOwner && renewSeq > 0)
            return value("connected", "connected", true)
        }
        override fun stop(ownerId: String): CloudCustomStatus {
            f.log += "stop:$nonce"
            check(ownerId == f.nativeOwner.orEmpty())
            if (!f.installationMatches) throw CloudCustomRejected("owner_changed", true)
            if (stopBeforeEffectFailure) throw IOException()
            stopAnswer?.let { return it }
            val old = f.nativeOwner
            f.nativeOwner = null
            if (stopAfterEffectFailure) throw IOException()
            return status("stopped", "stopped", now = f.time, owner = old.orEmpty())
        }
        override fun close() { f.log += "close:$nonce"; running = false }
        private fun value(stage: String, code: String, live: Boolean = false) =
            status(stage, code, live, f.time, f.nativeOwner.orEmpty())
    }

    private fun CloudCustomLifecycle.on(f: Fixture, pending: Boolean = false, network: Boolean = true, force: Boolean = false) =
        reconcile(true, pending, network, f.identity, force)

    @Test fun startsOnlyAfterDurableIntentThenAdoptsGuardianIdentity() {
        val f = Fixture(); val b = f.backend("bridge"); val result = f.lifecycle().on(f)
        assertTrue(result.completed)
        assertSame(f.identity, b.startIdentity)
        assertEquals(f.nativeOwner, f.store.owner)
        assertEquals(f.nativeOwner, result.processNonce)
        assertNotEquals(b.nonce, result.processNonce)
        assertTrue(f.log.indexOf("claim:bridge") < f.log.indexOf("start:bridge"))
    }

    @Test fun customStartRequiresAnActualForegroundServiceInstance() {
        val f = Fixture(); f.backend("unused")
        val lifecycle = CloudCustomLifecycle(f.store, { error("must not open") }, { f.time }, { null })
        val result = lifecycle.reconcile(true, false, true, f.identity)
        assertFalse(result.completed)
        assertTrue(result.events.any { it.operation == CloudCustomLifecycle.Operation.WAIT_SERVICE })
        assertEquals(0, f.count("start"))
    }

    @Test fun legacyOwnerIsCleanedBeforeAnyAlphaStart() {
        val f = Fixture()
        f.nativeOwner = "a".repeat(32)
        f.backend("legacy").probeAnswer = status("connected", "owner_present", true,
            owner = f.nativeOwner.orEmpty()).copy(protocol = 2)
        val lifecycle = f.lifecycle()
        assertTrue(lifecycle.on(f).completed)
        assertNull(f.nativeOwner)
        assertEquals(1, f.count("stop"))
        assertEquals(0, f.count("start"))
        f.backend("alpha")
        assertTrue(lifecycle.on(f).completed)
        assertEquals(1, f.count("start"))
    }

    @Test fun lostStartReplyAttachesToOwnerWithoutRepeatingRegistration() {
        val f = Fixture(); f.backend("first").startAfterEffectFailure = true
        val lifecycle = f.lifecycle(); val failed = lifecycle.on(f)
        assertFalse(failed.completed); assertNull(failed.status)
        assertEquals("first", f.store.owner)
        assertNotNull(f.nativeOwner)
        assertFalse(lifecycle.on(f).completed)
        f.nextRetry(); f.backend("second")
        assertTrue(lifecycle.on(f).completed)
        assertEquals(1, f.count("start")); assertEquals(1, f.count("attach"))
        assertEquals(f.nativeOwner, f.store.owner)
    }

    @Test fun journalCommitFailureBeforeStartCannotMutateGuardian() {
        for (after in listOf(false, true)) {
            val f = Fixture(); f.backend("one")
            f.store.failClaimBefore = !after; f.store.failClaimAfter = after
            assertFalse(f.lifecycle().on(f).completed)
            assertNull(f.nativeOwner); assertEquals(0, f.count("start"))
            assertEquals(if (after) "one" else null, f.store.owner)
        }
    }

    @Test fun lostAdoptionCommitRetainsIntentAndRecoversByAttach() {
        val f = Fixture(); f.backend("first"); f.store.failAdopt = true
        val lifecycle = f.lifecycle(); assertFalse(lifecycle.on(f).completed)
        assertEquals("first", f.store.owner); assertNotNull(f.nativeOwner)
        f.store.failAdopt = false; f.nextRetry(); f.backend("second")
        assertTrue(lifecycle.on(f).completed)
        assertEquals(f.nativeOwner, f.store.owner); assertEquals(1, f.count("start"))
    }

    @Test fun restartAndCloseOnlyDetachIncludingWhileNetworkIsAbsent() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle()
        lifecycle.on(f); val owner = f.nativeOwner
        assertTrue(lifecycle.serviceClosed().completed)
        assertEquals(owner, f.nativeOwner)
        f.backend("second"); val restarted = f.lifecycle()
        assertTrue(restarted.on(f, network = false).completed)
        assertEquals(owner, f.store.owner); assertEquals(1, f.count("start")); assertEquals(1, f.count("attach"))
    }

    @Test fun failedStatusDetachesAndReattachesWithoutRestart() {
        val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle()
        lifecycle.on(f); b.statusFailure = true
        assertFalse(lifecycle.on(f).completed); assertFalse(b.running)
        f.nextRetry(); f.backend("second")
        assertTrue(lifecycle.on(f).completed); assertEquals(1, f.count("start"))
    }

    @Test fun deadBridgeDoesNotImplyDeadGuardian() {
        val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle()
        lifecycle.on(f); b.running = false; f.backend("second")
        assertTrue(lifecycle.on(f).completed); assertEquals(1, f.count("start")); assertEquals(1, f.count("attach"))
    }

    @Test fun coldRestartWithoutNetworkDefersStartAfterProvingAbsence() {
        val f = Fixture(); f.store.owner = "stale"; f.backend("offline")
        assertTrue(f.lifecycle().on(f, network = false).completed)
        assertNull(f.store.owner); assertEquals(0, f.count("start")); assertEquals(0, f.count("stop"))
    }

    @Test fun networkLossStillReadsExistingSessionAndNeverStopsIt() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle()
        lifecycle.on(f); val owner = f.nativeOwner
        assertTrue(lifecycle.on(f, network = false).completed)
        assertEquals(owner, f.nativeOwner); assertEquals(1, f.count("status")); assertEquals(0, f.count("stop"))
    }

    @Test fun offFromNewApplicationStopsSameInstallDespiteNewGenerationWithoutAttach() {
        val f = Fixture(); f.backend("first"); f.lifecycle().on(f)
        f.generationMatches = false; f.backend("second")
        val result = f.lifecycle().reconcile(false, true, false, null)
        assertTrue(result.stopConfirmed); assertNull(f.nativeOwner); assertNull(f.store.owner)
        assertEquals(0, f.count("attach")); assertEquals(1, f.count("stop"))
    }

    @Test fun explicitAppOpenAfterFencedGenerationStopsBeforeNewStart() {
        val f = Fixture(); f.backend("old"); f.lifecycle().on(f)
        val oldOwner = f.nativeOwner
        f.generationMatches = false // The prior service's generation is fenced.
        f.backend("cleanup")
        val restarted = f.lifecycle()
        assertTrue(restarted.reconcile(false, true, true, null, force = true).stopConfirmed)
        assertNull(f.nativeOwner)
        assertEquals(1, f.count("start"))
        f.generationMatches = true // OFF/ON published the new permitted generation.
        f.backend("new")
        assertTrue(restarted.on(f).completed)
        assertNotEquals(oldOwner, f.nativeOwner)
        assertEquals(2, f.count("start"))
        assertEquals(0, f.count("attach"))
        assertTrue(f.log.indexOf("stop:cleanup") < f.log.indexOf("start:new"))
    }

    @Test fun offOnCannotStartUntilCallerDurablyClearsPendingDisable() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        val old = f.nativeOwner; f.backend("off-proof")
        val result = lifecycle.on(f, pending = true, force = true)
        assertTrue(result.completed); assertTrue(result.stopConfirmed)
        assertNull(f.nativeOwner); assertEquals(1, f.count("start"))
        // Simulated application death or failed pendingDisable commit repeats only cleanup.
        val restarted = f.lifecycle()
        assertTrue(restarted.on(f, pending = true).stopConfirmed)
        assertNull(f.nativeOwner); assertEquals(1, f.count("start"))
        // Only after a successful caller commit may a fresh cycle start again.
        val second = f.backend("second")
        assertTrue(restarted.on(f).completed)
        assertNotEquals(old, f.nativeOwner); assertSame(f.identity, second.startIdentity)
        assertTrue(f.log.indexOf("stop:first") < f.log.indexOf("start:second"))
    }

    @Test fun unconfirmedStopPreventsNewStartEvenWithForceOrNoNetwork() {
        val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        b.stopBeforeEffectFailure = true
        assertFalse(lifecycle.on(f, pending = true, network = false, force = true).stopConfirmed)
        assertEquals(1, f.count("start")); assertNotNull(f.nativeOwner); assertNotNull(f.store.owner)
    }

    @Test fun stopReplyLostKeepsJournalUntilCleanupConfirmed() {
        val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        b.stopAfterEffectFailure = true
        assertFalse(lifecycle.reconcile(false, true, false, null).stopConfirmed)
        assertNull(f.nativeOwner); assertNotNull(f.store.owner)
        b.stopAfterEffectFailure = false; f.nextRetry()
        assertTrue(lifecycle.reconcile(false, true, false, null).stopConfirmed); assertNull(f.store.owner)
    }

    @Test fun failedClearCommitPreventsReplacementStart() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        f.store.failClear = true
        assertFalse(lifecycle.on(f, pending = true).stopConfirmed)
        assertNotNull(f.store.owner); assertNull(f.nativeOwner); assertEquals(1, f.count("start"))
    }

    @Test fun ambiguousReplacementStartDoesNotPublishOldStopAsCurrentStatus() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        f.backend("second").startAfterEffectFailure = true
        assertTrue(lifecycle.on(f, pending = true).stopConfirmed)
        val result = lifecycle.on(f)
        assertFalse(result.completed); assertFalse(result.stopConfirmed); assertNull(result.status)
        assertNull(result.processNonce); assertEquals("second", f.store.owner)
    }

    @Test fun reinstalledApplicationCannotAdoptOrStopForeignInstallation() {
        val f = Fixture(); f.nativeOwner = "e".repeat(32); f.installationMatches = false
        f.backend("attach")
        assertFalse(f.lifecycle().on(f).completed); assertNull(f.store.owner)
        f.backend("stop")
        assertFalse(f.lifecycle().resolveOwnership().stopConfirmed)
        assertNotNull(f.nativeOwner); assertEquals(0, f.count("start"))
        // The old guardian stops itself only once its marker deletion is confirmed.
        f.nativeOwner = null; f.installationMatches = true; f.backend("new")
        assertTrue(f.lifecycle().on(f).completed)
    }

    @Test fun erasedJournalStillStopsDetachedOwnerOnOffAndBeforeFactoryActivation() {
        for (factory in listOf(false, true)) {
            val f = Fixture(); f.nativeOwner = "e".repeat(32); f.backend("management")
            val lifecycle = f.lifecycle()
            val result = if (factory) lifecycle.resolveOwnership()
                else lifecycle.reconcile(false, true, false, null)
            assertTrue(result.stopConfirmed)
            assertNull(f.nativeOwner)
            assertEquals(1, f.count("probe")); assertEquals(1, f.count("stop"))
            assertEquals(0, f.count("start"))
        }
    }

    @Test fun erasedJournalCannotHideAnUnreachableDetachedOwner() {
        val f = Fixture(); f.nativeOwner = "e".repeat(32)
        f.backend("management").probeFailure = true
        assertFalse(f.lifecycle().resolveOwnership().stopConfirmed)
        assertNotNull(f.nativeOwner)
    }

    @Test fun changedGenerationCannotAttachButCanBeLocallyStopped() {
        val f = Fixture(); f.nativeOwner = "e".repeat(32); f.generationMatches = false
        f.backend("first"); val lifecycle = f.lifecycle()
        assertFalse(lifecycle.on(f).completed)
        f.nextRetry(); f.backend("second")
        assertTrue(lifecycle.resolveOwnership().stopConfirmed)
        assertEquals(0, f.count("start"))
    }

    @Test fun permanentStartRefusalIsNotRetriedUntilOff() {
        val f = Fixture(); f.backend("first").startRejection = CloudCustomRejected("unsupported_firmware", false)
        val lifecycle = f.lifecycle(); val result = lifecycle.on(f)
        assertFalse(result.completed); assertEquals("unsupported_firmware", result.failure?.code)
        repeat(5) { f.nextRetry(); assertFalse(lifecycle.on(f, force = true).completed) }
        // Ordinary application restart/wake must not turn a terminal refusal into another START.
        val restarted = f.lifecycle()
        assertFalse(restarted.on(f).completed)
        assertEquals(1, f.count("start")); assertEquals(1, f.count("open"))
        f.backend("off"); assertTrue(restarted.reconcile(false, true, true, null).stopConfirmed)
        f.backend("new"); assertTrue(restarted.on(f).completed)
    }

    @Test fun expiredAndFailedSessionsLatchUntilConfirmedOff() {
        for (code in listOf("lease_expired", "session_failed", "worker_stalled", "config_changed")) {
            val f = Fixture()
            val bridge = f.backend("bridge")
            val lifecycle = f.lifecycle()
            assertTrue(lifecycle.on(f).completed)
            bridge.statusAnswer = status("failed", code, now = f.time,
                owner = f.nativeOwner.orEmpty()).copy(retryable = false)
            lifecycle.on(f)
            assertEquals(code, f.store.durableTerminal)
            assertFalse(lifecycle.on(f, force = true).completed)
            assertEquals(1, f.count("start"))
        }
    }

    @Test fun startArbitratesRaceAfterProbeAndDoesNotClearActualOwner() {
        val f = Fixture(); f.raceAtStart = true; f.backend("first")
        val lifecycle = f.lifecycle(); assertFalse(lifecycle.on(f).completed)
        assertEquals("first", f.store.owner); assertNotNull(f.nativeOwner)
        f.raceAtStart = false; f.nextRetry(); f.backend("second")
        assertTrue(lifecycle.on(f).completed); assertEquals(1, f.count("start"))
    }

    @Test fun terminalCommitFailureAfterRamUpdateRetriesDurabilityWithoutStart() {
        val f = Fixture(); f.backend("first").startRejection = CloudCustomRejected("registration_rejected", false)
        f.store.failTerminalAfterMemory = true
        val lifecycle = f.lifecycle()
        assertEquals(CloudCustomLifecycle.Operation.SAVE_TERMINAL, lifecycle.on(f).failure?.operation)
        assertEquals("registration_rejected", f.store.terminal)
        assertNull(f.store.durableTerminal)
        f.store.failTerminalAfterMemory = false
        lifecycle.on(f)
        assertEquals(2, f.store.terminalWrites)
        assertEquals("registration_rejected", f.store.durableTerminal)
        f.store.terminal = f.store.durableTerminal // Application recreated from disk.
        assertFalse(f.lifecycle().on(f).completed)
        assertEquals(1, f.count("start"))
    }

    @Test fun absentGuardianCleanupDebtIsResolvedBeforeSeparateStartCycle() {
        val f = Fixture(); f.store.owner = "old"
        f.backend("cleanup").probeRejection = CloudCustomRejected("cleanup_uncertain", true)
        val lifecycle = f.lifecycle()
        val cleaned = lifecycle.on(f)
        assertTrue(cleaned.completed); assertFalse(cleaned.stopConfirmed)
        assertNull(f.store.owner); assertEquals(1, f.count("stop")); assertEquals(0, f.count("start"))
        f.backend("start"); assertTrue(lifecycle.on(f).completed)
        assertEquals(1, f.count("start"))
    }

    @Test fun debtOnlyStopFailureCannotClearJournal() {
        val f = Fixture(); f.store.owner = "old"
        f.backend("cleanup").apply {
            probeRejection = CloudCustomRejected("cleanup_uncertain", true)
            stopBeforeEffectFailure = true
        }
        assertFalse(f.lifecycle().reconcile(false, true, false, null).stopConfirmed)
        assertEquals("old", f.store.owner); assertEquals(0, f.count("start"))
    }

    @Test fun acceptedStatusWithTerminalRegistrationFailureAlsoSurvivesAppDeath() {
        val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        b.statusAnswer = status("failed", "registration_rejected", owner = f.nativeOwner.orEmpty())
        assertTrue(lifecycle.on(f).completed)
        assertEquals("registration_rejected", f.store.durableTerminal)
        f.nativeOwner = null // Guardian later exits; only the app's durable outcome remains.
        assertFalse(f.lifecycle().on(f).completed)
        assertEquals(1, f.count("start"))
    }

    @Test fun unfamiliarFailedStatusUsesGuardianRetryability() {
        val f = Fixture(); val bridge = f.backend("first"); val lifecycle = f.lifecycle()
        assertTrue(lifecycle.on(f).completed)
        bridge.statusAnswer = status("failed", "native_incompatible", owner = f.nativeOwner.orEmpty())
            .copy(retryable = true)
        assertTrue(lifecycle.on(f).completed)
        assertNull(f.store.durableTerminal)
        bridge.statusAnswer = bridge.statusAnswer?.copy(retryable = false)
        assertTrue(lifecycle.on(f).completed)
        assertEquals("native_incompatible", f.store.durableTerminal)
        f.nativeOwner = null
        assertFalse(f.lifecycle().on(f, force = true).completed)
        assertEquals(1, f.count("start"))
    }

    @Test fun malformedProofKeepsJournalAndNeverLeaksRawReply() {
        for (throws in listOf(false, true)) {
            val f = Fixture(); f.store.owner = "old"
            f.backend("probe").apply {
                if (throws) probeFailure = true else probeAnswer = status("stopped", "unknown")
            }
            val result = f.lifecycle().resolveOwnership()
            assertFalse(result.completed); assertEquals("old", f.store.owner)
            assertFalse(result.toString().contains("private reply")); assertEquals(0, f.count("start"))
        }
    }

    @Test fun futureStatusAndMismatchingOwnerCannotBecomeConnected() {
        val f = Fixture(); f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
        f.nativeOwner = "e".repeat(32)
        val result = lifecycle.on(f)
        assertFalse(result.completed); assertNull(result.status)
        assertEquals(CloudCustomLifecycle.Reason.OWNER_CHANGED, result.failure?.reason)
        val g = Fixture(); g.backend("bad").probeAnswer = status("stopped", "owner_absent_confirmed", now = 6000)
        assertEquals(CloudCustomLifecycle.Reason.FUTURE_STATUS, g.lifecycle().on(g).failure?.reason)
    }

    @Test fun nonStoppedReplyCannotDischargeJournal() {
        for (stage in listOf("failed", "stopped")) {
            val f = Fixture(); val b = f.backend("first"); val lifecycle = f.lifecycle(); lifecycle.on(f)
            b.stopAnswer = status(stage, "cleanup_uncertain")
            assertFalse(lifecycle.resolveOwnership().stopConfirmed); assertNotNull(f.store.owner)
        }
    }

    @Test fun deterministicOffOnBridgeAndNetworkFaultSequencesNeverOverlapOwners() {
        val random = java.util.Random(0xC10D)
        repeat(20) { sequence ->
            val f = Fixture(); var lifecycle = f.lifecycle()
            var enabled = false; var pending = false
            var currentBackend: FakeBackend? = null
            repeat(100) { step ->
                when (random.nextInt(7)) {
                    0 -> { enabled = false; pending = true }
                    1 -> enabled = true // Can happen while a previous OFF is unresolved.
                    2 -> { lifecycle.serviceClosed(); lifecycle = f.lifecycle(); currentBackend = null }
                    3 -> currentBackend?.running = false
                    4 -> currentBackend?.statusFailure = true
                }
                if (f.pending.isEmpty()) currentBackend = f.backend("$sequence-$step").apply {
                    startAfterEffectFailure = random.nextInt(8) == 0
                }
                val oldOwner = f.nativeOwner
                val stops = f.count("stop")
                val result = lifecycle.reconcile(enabled, pending, random.nextBoolean(), f.identity)
                if (result.stopConfirmed) pending = false
                if (oldOwner != null && f.nativeOwner != null && oldOwner != f.nativeOwner) {
                    assertTrue("replacement without STOP at $sequence/$step", f.count("stop") > stops)
                }
                if (result.stopConfirmed && !enabled) {
                    assertNull(f.nativeOwner); assertNull(f.store.owner)
                }
                if (result.status?.sessionLive == true) {
                    assertEquals(f.nativeOwner, result.status.ownerId)
                    assertEquals(f.nativeOwner, f.store.owner)
                }
                f.nextRetry()
            }
        }
    }

    companion object {
        private fun status(stage: String, code: String, live: Boolean = false, now: Long = 100, owner: String = "") = CloudCustomStatus(
            pid = 1, sessionLive = live, stage = stage, code = code, updatedElapsedMs = now,
            connectedElapsedMs = 0, lastRxElapsedMs = 0, lastTxElapsedMs = 0, lastReportElapsedMs = 0,
            nextRetryElapsedMs = 0, attempts = 0, reportsSent = 0, statusReplies = 0,
            commandsForwarded = 0, commandsCompleted = 0, reconnects = 0, callbackAgeMs = -1, events = emptyList(),
            ownerId = owner, runtimeId = "a".repeat(12) + "-" + "b".repeat(12), configGeneration = if (owner.isEmpty()) 0 else 1,
        )
    }
}
