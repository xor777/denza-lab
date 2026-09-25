package dev.denza.apps.feature.cloud

import org.junit.Assert.*
import org.junit.Test

/** Failure injection at IO boundaries. No assumed SIM/modem transition is being simulated. */
class CloudLinkRecoveryTest {
    private val adapted = CloudCarState("double_apn", "triple_apn", true, connected = false)
    private val stock = adapted.copy(profile = "triple_apn", apn1Disabled = false)

    @Test fun binderExceptionsAndUnexpectedRepliesAreNotAcknowledgements() {
        for (reply in listOf("Result: Parcel(ffffffff 00000000 '........')", "Result: Parcel()",
            "warning\nResult: Parcel(NULL)", "Result: Parcel(00000000)")) {
            assertFalse(reply, CloudLinkProtocol.notifyAccepted(reply))
        }
        assertTrue(CloudLinkProtocol.notifyAccepted("Result: Parcel(NULL)\n"))
    }

    @Test fun settingsReadFailureCannotVerifyThatWifiRetentionIsOff() {
        assertNull(CloudLinkProtocol.parseRead("@@wifi\njava.lang.SecurityException: denied").wifiRetained)
        assertNull(CloudLinkProtocol.parseRead("@@wifi\nunknown").wifiRetained)
        assertEquals(false, CloudLinkProtocol.parseRead("@@wifi\n0").wifiRetained)
        assertEquals(false, CloudLinkProtocol.parseRead("@@wifi\nnull").wifiRetained)
    }

    @Test fun offlineStartAndFailedCloseAreRetriedWithoutAnotherNetworkEdge() {
        val core = CloudLinkCore()
        val tcpStillUp = adapted.copy(connected = true)
        assertTrue(core.reconcile(tcpStillUp, false, 0).isEmpty())
        assertEquals(listOf(CloudStep.AnnounceGone), core.reconcile(tcpStillUp, false, 30_000))
        // No goneSent: inject command failure. Recovery must be bounded but not forgotten.
        assertTrue(core.reconcile(adapted, false, 31_000).isEmpty())
        assertEquals(listOf(CloudStep.AnnounceGone), core.reconcile(adapted, false, 60_000))
        core.goneSent()
        assertTrue(core.reconcile(adapted, false, 90_000).isEmpty())
    }

    @Test fun staleNetworkReturnCannotOpenTheGate() {
        val core = CloudLinkCore()
        core.switchedOn(stock, false, 0)
        assertTrue(core.networkReturned(stock, 10_000, network = false).isEmpty())
    }

    @Test fun profileDriftInvalidatesTheClaimWithoutBypassingTheRetryBudget() {
        val core = CloudLinkCore()
        core.readySent(0)
        assertTrue(core.reconcile(stock, true, 25_000).isEmpty())
        assertEquals(CloudLinkCore.Gate.UNKNOWN, core.gate)
        assertTrue(core.networkReturned(stock, 48_000).isEmpty())
        assertEquals(listOf(CloudStep.UseWifiProfile, CloudStep.AnnounceReady), core.reconcile(stock, true, 300_000))
    }

    @Test fun offRequestSurvivesReenableAndCannotBeAcknowledgedWhileTcpIsStillUp() {
        val persisted = CloudLinkRequest(enabled = true).request(false).copy(awaitingTcpDown = true)
        assertTrue(persisted.needsService)
        assertTrue(persisted.pendingDisable)
        // New process reads the same persisted pair; on cannot drop an unfinished off.
        val restarted = persisted.copy().request(true)
        assertTrue(restarted.pendingDisable)
        assertThrows(IllegalStateException::class.java) { restarted.disabled(stock.copy(connected = true)) }
        val finished = restarted.disabled(stock)
        assertTrue(finished.enabled)
        assertFalse(finished.pendingDisable)
        assertFalse(CloudLinkRequest().request(false).needsService)
        // Off must not wait forever on an untouched real stock connection.
        val stockOwner = CloudLinkRequest(enabled = true).request(false)
        assertFalse(stockOwner.disabled(stock.copy(connected = true)).needsService)
    }

    @Test fun freshStockTcpSuccessDoesNotRequireDefaultNetworkValidation() {
        val old = adapted.copy(connected = true)
        assertEquals("Нет свежих данных", CloudLinkStatus.words(CloudLinkStatus.snapshot(true, old, true, null, readingFailed = true)))
        assertEquals("На связи", CloudLinkStatus.words(CloudLinkStatus.snapshot(true, old, false, null)))
        assertEquals("Выключение не завершено", CloudLinkStatus.words(CloudLinkStatus.snapshot(false, old, true, null, pendingDisable = true)))
        assertEquals("Нет связи с облаком", CloudLinkStatus.words(CloudLinkStatus.snapshot(true, adapted, true, null, stalled = true)))
    }

    private inner class Boundary {
        val core = CloudLinkCore()
        var car = adapted
        var online = true
        var reply = "Result: Parcel(NULL)"
        var afterNotify: (() -> Unit)? = null
        var afterPause: (() -> Unit)? = null
        val writes = mutableListOf<String>()
        val operations = CloudLinkOperations(core, { car }, { cmd ->
            writes += cmd
            if (cmd.startsWith("am broadcast")) {
                car = if (cmd.endsWith("double_apn")) adapted else stock
                "Broadcast completed: result=0"
            } else { afterNotify?.invoke(); reply }
        }, { online }, { 0L }, { afterPause?.invoke() }, {})
    }

    @Test fun stockApnAppearingAfterPlanningCannotBeDisabledByProfileWrite() {
        val b = Boundary()
        val plan = b.core.switchedOn(stock, true, 0)
        b.car = stock.copy(cellular = true, apn3State = "connect")
        b.operations.run(plan)
        assertTrue(b.writes.isEmpty())
    }

    @Test fun stockApnTransitionsDeferPublicProfileUntilTheySettle() {
        for (state in listOf("connecting", "disconnecting")) {
            val b = Boundary()
            b.car = stock.copy(apn1State = state)
            assertTrue(b.core.switchedOn(b.car, true, 0).isEmpty())
        }
    }

    @Test fun stockApnTransitionDefersOffAndNetworkLossWithoutClosingTheStockGate() {
        for (state in listOf("connecting", "disconnecting")) {
            for (car in listOf(adapted.copy(apn1State = state), adapted.copy(apn3State = state))) {
                val core = CloudLinkCore()
                core.readySent(0)
                assertTrue(core.switchedOff(car).isEmpty())
                assertTrue(core.networkGone(car, 30_000).isEmpty())
                val pending = CloudLinkRequest(enabled = true).request(false)
                assertTrue(pending.pendingDisable)
                assertThrows(IllegalStateException::class.java) {
                    pending.disabled(car.copy(profile = "triple_apn", apn1Disabled = false))
                }
                // A real APN that finishes connecting owns its TCP. OFF restores the profile,
                // but does not wait for that connection to disappear or send a synthetic -5.
                val connected = adapted.copy(cellular = true, apn3State = "connect", connected = true)
                assertEquals(listOf(CloudStep.RestoreProfile("triple_apn")), core.switchedOff(connected))
                assertFalse(pending.copy(awaitingTcpDown = true).disabled(
                    connected.copy(profile = "triple_apn", apn1Disabled = false),
                ).needsService)
                // A transition that ends disconnected resumes the ordinary teardown.
                assertEquals(listOf(CloudStep.AnnounceGone, CloudStep.WaitDisconnected,
                    CloudStep.RestoreProfile("triple_apn")), core.switchedOff(adapted))
            }
        }
    }

    @Test fun apnTransitionAfterOffPlanningPreventsGoneAndProfileWrites() {
        for (state in listOf("connecting", "disconnecting")) {
            val b = Boundary()
            val steps = b.core.switchedOff(b.car)
            b.car = adapted.copy(apn3State = state)
            assertThrows(IllegalStateException::class.java) { b.operations.run(steps) }
            assertTrue(b.writes.isEmpty())
            // Also fence a transition that starts after TCP has already dropped.
            assertThrows(IllegalStateException::class.java) {
                b.operations.run(listOf(CloudStep.RestoreProfile("triple_apn")))
            }
            assertTrue(b.writes.isEmpty())
        }
    }

    @Test fun disableWaitsForTcpBeforeRestoringTheProfile() {
        val b = Boundary()
        b.car = adapted.copy(connected = true)
        val steps = b.core.switchedOff(b.car)
        assertThrows(IllegalStateException::class.java) { b.operations.run(steps) }
        assertEquals(listOf(CloudLinkProtocol.notifyCommand(-5)), b.writes)
        // Later observation confirms teardown. Retry can now finish both operations.
        b.car = adapted
        b.operations.run(b.core.switchedOff(b.car))
        assertTrue(b.car.onStockProfile)
    }

    @Test fun profileMustStillMatchImmediatelyBeforeAndAfterReady() {
        val before = Boundary()
        before.car = stock
        assertThrows(IllegalStateException::class.java) { before.operations.run(listOf(CloudStep.AnnounceReady)) }
        assertTrue(before.writes.isEmpty())
        val after = Boundary()
        after.afterNotify = { after.car = stock }
        assertThrows(IllegalStateException::class.java) { after.operations.run(listOf(CloudStep.AnnounceReady)) }
        assertNotEquals(CloudLinkCore.Gate.OPENED, after.core.gate)
    }

    @Test fun aFailedAcknowledgementDoesNotOpenTheGateOrContinueThePlan() {
        val b = Boundary()
        b.reply = "Result: Parcel(ffffffff 00000000 '........')"
        assertThrows(IllegalStateException::class.java) { b.operations.run(listOf(CloudStep.AnnounceReady)) }
        assertNotEquals(CloudLinkCore.Gate.OPENED, b.core.gate)
    }

    @Test fun networkCanDisappearAfterPlanningBeforeTheWrite() {
        val b = Boundary()
        val steps = b.core.switchedOn(adapted, true, 0)
        b.online = false
        assertThrows(IllegalStateException::class.java) { b.operations.run(steps) }
        assertTrue(b.writes.isEmpty())
    }

    @Test fun historyIsBoundedAndSurvivesReloadWithoutMultilineRecords() {
        val trace = CloudLinkTrace()
        repeat(600) { trace.add("t$it", "entry $it\nextra") }
        val reloaded = CloudLinkTrace(trace.text())
        assertEquals(512, reloaded.text().lines().size)
        assertTrue(reloaded.text().startsWith("t88 "))
        assertTrue(reloaded.text().endsWith("entry 599 extra"))
    }

    @Test fun historyByteLimitAppliesToUtf8AfterReloadAndFurtherEvents() {
        val saved = (0 until 600).joinToString("\n") { "t$it ${"界".repeat(250)}" }
        val trace = CloudLinkTrace(saved)
        assertTrue(trace.text().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        assertTrue(trace.text().lines().size in 300..400)
        assertTrue(trace.text().lines().last().startsWith("t599 "))
        repeat(600) { trace.add("new$it", "я".repeat(700)) }
        assertTrue(trace.text().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        assertTrue(trace.text().lines().last().startsWith("new599 "))
        assertEquals(trace.text(), CloudLinkTrace(trace.text()).text())
    }

    @Test fun build55HistorySurvivesTheLargerLimit() {
        val previous = (0 until 96).joinToString("\n") { "t$it previous event" }
        val trace = CloudLinkTrace(previous)
        assertEquals(previous, trace.text())
        trace.add("t96", "new event")
        assertEquals("$previous\nt96 new event", trace.text())
    }

    @Test fun lateNetworkLossCannotDisconnectAReturnedNetworkButExplicitOffCan() {
        val b = Boundary()
        b.operations.run(listOf(CloudStep.AnnounceGone), lossOnly = true)
        assertTrue(b.writes.isEmpty())
        b.operations.run(listOf(CloudStep.AnnounceGone))
        assertEquals(listOf(CloudLinkProtocol.notifyCommand(-5)), b.writes)
    }
}
