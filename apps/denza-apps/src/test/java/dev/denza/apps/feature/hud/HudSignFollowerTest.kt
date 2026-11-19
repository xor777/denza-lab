package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's sign behind «Ограничение скорости»: off is off at once, and stays off. */
class HudSignFollowerTest {
    private var signOn = true
    private val writes = mutableListOf<String>()
    private var duringRead: (() -> Unit)? = null
    private var now = 0L

    /** A car whose reads see another sign, so every pass ends in a write unless it is voided. */
    private fun shell(command: String): String {
        if (command.contains("autoservice 6 ")) {
            writes += command
            return "@@0\nResult: Parcel(00000001)\n@@1\nResult: Parcel(00000001)\n@@2\nResult: Parcel(00000001)\n"
        }
        duringRead?.invoke()
        duringRead = null
        return "Result: Parcel(00000000 00000009   '........')"
    }

    private val pump = HudNativeSpeedLimitPump(::shell, { now })
    private val follower = HudSignFollower({ signOn }, { HudSignTarget { pump.request(it) } })

    /**
     * Review round 2, finding 1: the switch goes off while the worker reads the car. The host
     * heard it only on the main thread, after the read, and then passed its cached 60 again.
     */
    @Test
    fun theSignSwitchedOffDuringAReadWritesNothingThenOrAfter() {
        follower.limitChanged(60) // main thread: the composition's limit
        pump.apply()
        pump.step() // first read: the car's sign differs, the confirming read waits
        now += HudNativeSpeedLimitEngine.BASE_DELAY_MS
        duringRead = {
            signOn = false
            follower.switchMoved() // the repository's thread, as the switch moves
        }

        pump.step()
        follower.follow() // the main thread's onSettingsChanged, the composition not yet pumped
        pump.apply()
        now += HudNativeSpeedLimitEngine.BASE_DELAY_MS
        pump.step()

        assertTrue(writes.isEmpty())
        assertNull(pump.nextStepDelayMs())
    }

    @Test
    fun withTheSignOnTheLimitIsWritten() {
        follower.limitChanged(60)
        pump.apply()
        pump.step()
        now += HudNativeSpeedLimitEngine.BASE_DELAY_MS
        pump.step()

        assertEquals(listOf(HudNativeSpeedLimitProtocol.writeCommand(60)), writes)
    }

    @Test
    fun theSignsThreadStartsOnlyWithALimitTheSwitchLetsThrough() {
        var started = 0
        signOn = false
        val follower = HudSignFollower({ signOn }, { started++; HudSignTarget { } })

        follower.limitChanged(60)
        follower.switchMoved()
        assertEquals(0, started)

        signOn = true
        follower.follow()
        assertEquals(1, started)
    }
}
