package dev.denza.apps.feature.cloud

import dev.denza.apps.feature.cloud.CloudLinkCore.Companion.SETTLE_MS
import dev.denza.apps.feature.cloud.CloudStep.AnnounceGone
import dev.denza.apps.feature.cloud.CloudStep.AnnounceReady
import dev.denza.apps.feature.cloud.CloudStep.RestoreProfile
import dev.denza.apps.feature.cloud.CloudStep.UseWifiProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the adapter speaks to the stock cloud client, and what it says.
 *
 * The car states are the ones the live run of 2026-09-23 read: the stock baseline (`triple_apn`,
 * APN1 enabled, TCP 0) and the car the owner left running (`double_apn`, APN1 disabled, TCP 1,
 * `cloudmanager` 113). What the gate does with 4 and -5, and that the stock receiver sends -5 on
 * its own, is the firmware's (docs/telematics-findings.md, "Stock-client Wi-Fi adaptation").
 */
class CloudLinkCoreTest {

    private val stock = CloudCarState(
        profile = "triple_apn",
        buildProfile = "triple_apn",
        apn1Disabled = false,
        cloudPid = "113",
        connected = false,
    )
    private val adapted = stock.copy(profile = "double_apn", apn1Disabled = true)
    private val online = adapted.copy(connected = true)

    @Test
    fun switchingOnFromStockTakesTheProfileAndSaysReadyOnce() {
        val core = CloudLinkCore()
        assertEquals(listOf(UseWifiProfile, AnnounceReady), core.switchedOn(stock, wifi = true, nowMs = 0))
        core.readySent(0)
        // The follow-up readings while the client logs in say nothing more.
        assertEquals(emptyList<CloudStep>(), core.reconcile(adapted, wifi = true, nowMs = 30_000))
    }

    /**
     * The car as the owner left it: the link is up. Taking it over is a press, and a press over a
     * connected client says nothing - no -5, no second ready, no profile flicker.
     */
    @Test
    fun switchingOnOverALiveLinkTouchesNothing() {
        val core = CloudLinkCore()
        assertEquals(emptyList<CloudStep>(), core.switchedOn(online, wifi = true, nowMs = 0))
        assertEquals(CloudLinkCore.Gate.OPENED, core.gate)
    }

    @Test
    fun switchingOnAwayFromWifiWaitsForIt() {
        val core = CloudLinkCore()
        assertEquals(emptyList<CloudStep>(), core.switchedOn(stock, wifi = false, nowMs = 0))
        // Wi-Fi comes back: the gate is known closed, so there is nothing to wait for.
        assertEquals(listOf(UseWifiProfile, AnnounceReady), core.wifiReturned(stock, nowMs = 10_000))
    }

    /**
     * The stock receiver can close the gate behind us, so a car on Wi-Fi and off the cloud is told
     * again - but only once the disconnection has settled, and then on a backoff that doubles with
     * every ready that did not bring the link back: 5, 10, 20, 40, then an hour.
     */
    @Test
    fun aLinkThatDoesNotComeBackIsAskedAgainLessAndLessOften() {
        val core = CloudLinkCore()
        core.switchedOn(adapted, wifi = true, nowMs = 0)
        core.readySent(0)

        var at = 0L
        val gaps = mutableListOf<Long>()
        var last = 0L
        while (gaps.size < 6) {
            at += 1_000
            if (core.reconcile(adapted, wifi = true, nowMs = at) == listOf(AnnounceReady)) {
                core.readySent(at)
                gaps += (at - last) / 60_000
                last = at
            }
        }
        assertEquals(listOf(5L, 10L, 20L, 40L, 60L, 60L), gaps)
    }

    @Test
    fun seeingTheClientConnectedClearsTheBackoff() {
        val core = CloudLinkCore()
        core.switchedOn(adapted, wifi = true, nowMs = 0)
        core.readySent(0)
        core.reconcile(online, wifi = true, nowMs = 40_000)
        assertEquals(0, core.attempts)

        // Dropped later - by the stock -5 or anything else: settle, then one ready, no backoff.
        val dropAt = 3_600_000L
        assertEquals(emptyList<CloudStep>(), core.reconcile(adapted, wifi = true, nowMs = dropAt))
        assertEquals(emptyList<CloudStep>(), core.reconcile(adapted, wifi = true, nowMs = dropAt + SETTLE_MS - 1))
        assertEquals(listOf(AnnounceReady), core.reconcile(adapted, wifi = true, nowMs = dropAt + SETTLE_MS))
    }

    /** A TCP of 0 does not say why; the client's own reconnect gets the settle period first. */
    @Test
    fun aFreshProcessDoesNotRaceTheClientsOwnReconnect() {
        val core = CloudLinkCore()
        assertEquals(emptyList<CloudStep>(), core.reconcile(adapted, wifi = true, nowMs = 0))
        assertEquals(emptyList<CloudStep>(), core.reconcile(adapted, wifi = true, nowMs = 60_000))
        assertEquals(listOf(AnnounceReady), core.reconcile(adapted, wifi = true, nowMs = SETTLE_MS))
    }

    /** A restarted native client has a fresh, closed gate; the framework replays only real APNs. */
    @Test
    fun aRestartedClientIsToldAtOnce() {
        val core = CloudLinkCore()
        core.reconcile(online, wifi = true, nowMs = 0)
        val restarted = adapted.copy(cloudPid = "4711")
        assertEquals(listOf(AnnounceReady), core.reconcile(restarted, wifi = true, nowMs = 1_000))
    }

    @Test
    fun aCarThatDidNotAnswerIsNotACarThatIsOffline() {
        val core = CloudLinkCore()
        core.switchedOn(online, wifi = true, nowMs = 0)
        val unread = CloudCarState()
        assertEquals(emptyList<CloudStep>(), core.reconcile(unread, wifi = true, nowMs = 10 * 60_000))
    }

    /** Ready and gone are a pair: Wi-Fi that stays gone closes the gate, and coming back opens it. */
    @Test
    fun wifiThatStaysGoneClosesTheGateAndItsReturnOpensIt() {
        val core = CloudLinkCore()
        core.switchedOn(online, wifi = true, nowMs = 0)
        assertEquals(listOf(AnnounceGone), core.wifiGone(online))
        core.goneSent()
        // Said once.
        assertEquals(emptyList<CloudStep>(), core.wifiGone(adapted))
        // Back: the gate is known closed, so no settle and no backoff.
        assertEquals(listOf(AnnounceReady), core.wifiReturned(adapted, nowMs = 20_000))
    }

    /** A gate opened by a previous process is still ours to close. */
    @Test
    fun aFreshProcessStillClosesTheGateItsPredecessorOpened() {
        val core = CloudLinkCore()
        assertEquals(listOf(AnnounceGone), core.wifiGone(adapted))
    }

    /** Outside the adapter's profile the gate is not a synthetic APN of ours. */
    @Test
    fun wifiLossOnTheStockProfileSaysNothing() {
        val core = CloudLinkCore()
        assertEquals(emptyList<CloudStep>(), core.wifiGone(stock))
    }

    @Test
    fun aBriefFlickerLeavesTheClientsOwnReconnectAlone() {
        val core = CloudLinkCore()
        core.switchedOn(online, wifi = true, nowMs = 0)
        // Wi-Fi blinked and came back inside the grace period: no gone was said, the gate is open,
        // and a dropped socket gets the settle period to come back by itself.
        assertEquals(emptyList<CloudStep>(), core.wifiReturned(adapted, nowMs = 10_000))
    }

    /** The driver's off: close the gate while the profile still honours it, then the stock profile. */
    @Test
    fun switchingOffClosesTheGateAndRestoresTheCarsProfile() {
        val core = CloudLinkCore()
        assertEquals(listOf(AnnounceGone, RestoreProfile("triple_apn")), core.switchedOff(online))
        // Already stock: nothing to say.
        assertEquals(emptyList<CloudStep>(), core.switchedOff(stock))
        // Stock profile left with APN1 disabled is not stock: put it back.
        assertEquals(
            listOf(RestoreProfile("triple_apn")),
            core.switchedOff(stock.copy(apn1Disabled = true)),
        )
    }

    @Test
    fun theBackoffIsFiveMinutesDoublingToAnHour() {
        assertEquals(0L, CloudLinkCore.backoff(0))
        assertEquals(5 * 60_000L, CloudLinkCore.backoff(1))
        assertEquals(10 * 60_000L, CloudLinkCore.backoff(2))
        assertEquals(40 * 60_000L, CloudLinkCore.backoff(4))
        assertEquals(60 * 60_000L, CloudLinkCore.backoff(5))
        assertEquals(60 * 60_000L, CloudLinkCore.backoff(50))
    }
}
