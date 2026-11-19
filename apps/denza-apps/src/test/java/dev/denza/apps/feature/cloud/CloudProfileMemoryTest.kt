package dev.denza.apps.feature.cloud

import dev.denza.apps.feature.cloud.CloudLinkCore.Companion.NETWORK_LOSS_GRACE_MS
import dev.denza.apps.feature.cloud.CloudLinkCore.Companion.PROFILE_HOLD_MS
import dev.denza.apps.feature.cloud.CloudStep.AnnounceGone
import dev.denza.apps.feature.cloud.CloudStep.AnnounceReady
import dev.denza.apps.feature.cloud.CloudStep.RestoreProfile
import dev.denza.apps.feature.cloud.CloudStep.UseWifiProfile
import dev.denza.apps.feature.cloud.CloudStep.WaitDisconnected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile the adapter takes is given back: on the switch-off, and when Wi-Fi has stayed gone
 * (docs/telematics/cloud-tile.md, "Three ways to break a working car").
 *
 * The car is a fake at the shell: the profile broadcast moves it, `notify_nw` answers, and the
 * client's TCP is whatever the test says. The memory is the persisted [CloudLinkRequest.remembered].
 */
class CloudProfileMemoryTest {

    private val stock = CloudCarState(
        profile = "triple_apn",
        buildProfile = "triple_apn",
        apn1Disabled = false,
        cloudPid = "113",
        connected = false,
        apn1State = "disconnected",
        apn3State = "disconnected",
    )
    private val adapted = stock.copy(profile = "double_apn", apn1Disabled = true)

    private inner class Car {
        val core = CloudLinkCore()
        var car = stock
        var wifi = true
        var request = CloudLinkRequest().request(true)
        /** The broadcast is answered, and the car moves only while this holds. */
        var takesProfile = true
        val writes = mutableListOf<String>()
        val profileWrites get() = writes.filter { it.startsWith("am broadcast") }
        val operations = CloudLinkOperations(
            core,
            { car },
            { cmd ->
                writes += cmd
                if (cmd.startsWith("am broadcast")) {
                    if (takesProfile) {
                        car = if (cmd.endsWith("double_apn")) {
                            car.copy(profile = "double_apn", apn1Disabled = true)
                        } else {
                            car.copy(profile = "triple_apn", apn1Disabled = false)
                        }
                    }
                    "Broadcast completed: result=0"
                } else {
                    "Result: Parcel(NULL)"
                }
            },
            { wifi },
            { 0L },
            {},
            {},
            { request.remembered },
            { request = request.copy(remembered = it) },
        )

        /** One pass of the controller: plan from the reading, carry it out. */
        fun pass(nowMs: Long, entry: (CloudLinkCore, CloudCarState, CloudProfileMemory) -> List<CloudStep> =
            { core, car, memory -> core.reconcile(car, wifi, nowMs, memory) }): List<CloudStep> {
            val steps = entry(core, car, request.remembered)
            operations.run(steps, lossOnly = !wifi)
            return steps
        }
    }

    /**
     * The reported case end to end: switched on at home, on Wi-Fi; the car drives away; its own path
     * comes back; mobile internet alone never moves anything; Wi-Fi at home switches it again.
     */
    @Test
    fun wifiThatStaysGoneGivesTheCarItsProfileBackAndWifiTakesItAgain() {
        val c = Car()
        // At home: the profile, committed as owed before its write, and one ready.
        assertEquals(listOf(UseWifiProfile, AnnounceReady), c.pass(0) { core, car, m -> core.switchedOn(car, true, 0, m) })
        assertEquals(CloudProfileMemory.Owed("triple_apn"), c.request.remembered)
        assertTrue(c.car.wifiProfile)
        c.car = c.car.copy(connected = true)
        c.pass(60_000)

        // Away: the grace, then the paired -5, which the client answers by disconnecting.
        c.wifi = false
        val lost = 120_000L
        assertEquals(emptyList<CloudStep>(), c.pass(lost))
        assertEquals(listOf(AnnounceGone), c.pass(lost + NETWORK_LOSS_GRACE_MS))
        c.car = c.car.copy(connected = false)
        var at = lost + NETWORK_LOSS_GRACE_MS
        while (at + 30_000 < lost + PROFILE_HOLD_MS) {
            at += 30_000
            assertEquals(emptyList<CloudStep>(), c.pass(at))
        }
        assertEquals(1, c.profileWrites.size)

        // Past the hold: the disconnect awaited, then the profile it was on, read back.
        assertEquals(listOf(WaitDisconnected, RestoreProfile("triple_apn")), c.pass(lost + PROFILE_HOLD_MS))
        assertTrue(c.car.on("triple_apn"))
        assertEquals(CloudProfileMemory.Clear, c.request.remembered)

        // On the road, mobile internet only, the car's own APNs idle between their own states:
        // nothing, for hours.
        val writes = c.writes.size
        at = lost + PROFILE_HOLD_MS
        repeat(6 * 60 * 2) {
            at += 30_000
            assertEquals(emptyList<CloudStep>(), c.pass(at))
        }
        assertEquals(writes, c.writes.size)

        // Home again: Wi-Fi validated, the client not connected - the ordinary path switches again.
        c.wifi = true
        at += 30_000
        assertEquals(listOf(UseWifiProfile, AnnounceReady), c.pass(at) { core, car, m -> core.networkReturned(car, at, true, m) })
        assertEquals(CloudProfileMemory.Owed("triple_apn"), c.request.remembered)
        assertTrue(c.car.wifiProfile)
    }

    /** The edge of coverage: Wi-Fi gone for less than the hold writes no profile, either way. */
    @Test
    fun aWifiFlickerShorterThanTheHoldWritesNoProfile() {
        val c = Car()
        c.pass(0) { core, car, m -> core.switchedOn(car, true, 0, m) }
        c.car = c.car.copy(connected = true)
        val before = c.profileWrites.size

        repeat(3) { round ->
            val lost = 600_000L + round * 600_000L
            c.wifi = false
            c.pass(lost)
            c.pass(lost + NETWORK_LOSS_GRACE_MS)
            c.car = c.car.copy(connected = false)
            var at = lost + NETWORK_LOSS_GRACE_MS
            while (at < lost + PROFILE_HOLD_MS - 30_000) {
                at += 30_000
                c.pass(at)
            }
            c.wifi = true
            val back = lost + PROFILE_HOLD_MS - 1
            assertEquals(listOf(AnnounceReady), c.pass(back) { core, car, m -> core.networkReturned(car, back, true, m) })
            c.car = c.car.copy(connected = true)
        }
        assertEquals(before, c.profileWrites.size)
        assertEquals(CloudProfileMemory.Owed("triple_apn"), c.request.remembered)
    }

    /** The switch-off gives back what the car was on, not what its build names. */
    @Test
    fun switchingOffRestoresTheRememberedProfileNotTheBuilds() {
        val core = CloudLinkCore()
        // A build that names double_apn, a car that was on triple_apn before the adapter.
        val car = adapted.copy(buildProfile = "double_apn")
        assertEquals(
            listOf(AnnounceGone, WaitDisconnected, RestoreProfile("triple_apn")),
            core.switchedOff(car, CloudProfileMemory.Owed("triple_apn")),
        )
        // Remembered the adapter's own profile: nothing is written.
        assertEquals(listOf(AnnounceGone, WaitDisconnected), core.switchedOff(adapted, CloudProfileMemory.Owed("double_apn")))
        // Switched on and never written: nothing to give back.
        assertEquals(listOf(AnnounceGone, WaitDisconnected), core.switchedOff(adapted, CloudProfileMemory.Clear))
    }

    /**
     * A request 0.7.1 saved - `enabled`, `pending_disable`, `awaiting_tcp_down` and no remembered
     * profile - falls back to the build's profile, as it always did.
     */
    @Test
    fun aRequestSavedBy071FallsBackToTheBuildsProfile() {
        val memory = CloudProfileMemory.read(null)
        assertEquals(CloudProfileMemory.Legacy, memory)
        val legacy = CloudLinkRequest(enabled = true, pendingDisable = false, awaitingTcpDown = false, remembered = memory)
        // Pressed on again: still remembers nothing, still the build's.
        assertEquals(CloudProfileMemory.Legacy, legacy.request(true).remembered)
        assertEquals(
            listOf(AnnounceGone, WaitDisconnected, RestoreProfile("triple_apn")),
            CloudLinkCore().switchedOff(adapted, legacy.request(false).remembered),
        )
        // A fresh on remembers from scratch; a finished off leaves nothing owed.
        assertEquals(CloudProfileMemory.Clear, CloudLinkRequest().request(true).remembered)
        assertEquals(CloudProfileMemory.Clear, legacy.request(false).disabled(stock).remembered)
    }

    @Test
    fun thePersistedFormKeepsEveryStateAndOnlyTheTwoProfiles() {
        for (memory in listOf(CloudProfileMemory.Legacy, CloudProfileMemory.Clear,
            CloudProfileMemory.Owed("triple_apn"), CloudProfileMemory.Owed("double_apn"))) {
            assertEquals(memory, CloudProfileMemory.read(CloudProfileMemory.stored(memory)))
        }
        assertEquals(CloudProfileMemory.Clear, CloudProfileMemory.read("single_apn"))
    }

    /** A restore the car did not take stays owed, and is tried again after another hold. */
    @Test
    fun aFailedRestoreKeepsTheObligation() {
        val c = Car()
        c.pass(0) { core, car, m -> core.switchedOn(car, true, 0, m) }
        c.wifi = false
        c.pass(10_000)
        c.pass(10_000 + NETWORK_LOSS_GRACE_MS)
        c.takesProfile = false
        val at = 10_000 + PROFILE_HOLD_MS
        assertThrows(IllegalStateException::class.java) { c.pass(at) }
        assertEquals(CloudProfileMemory.Owed("triple_apn"), c.request.remembered)
        // Not once a pass: after another hold.
        assertEquals(emptyList<CloudStep>(), c.pass(at + 30_000))
        c.takesProfile = true
        assertEquals(listOf(WaitDisconnected, RestoreProfile("triple_apn")), c.pass(at + PROFILE_HOLD_MS))
        assertEquals(CloudProfileMemory.Clear, c.request.remembered)
        // And off has nothing left to write.
        assertEquals(emptyList<CloudStep>(), c.core.switchedOff(c.car, c.request.remembered))
    }

    /** The car's own APN up under the adapter's profile: its path is left alone until off. */
    @Test
    fun noRestoreUnderTheCarsOwnLiveApn() {
        val core = CloudLinkCore()
        val ownPath = adapted.copy(cellular = true, apn3State = "connect")
        core.reconcile(ownPath, false, 0, CloudProfileMemory.Owed("triple_apn"))
        assertEquals(
            emptyList<CloudStep>(),
            core.reconcile(ownPath, false, PROFILE_HOLD_MS * 3, CloudProfileMemory.Owed("triple_apn")),
        )
    }
}
