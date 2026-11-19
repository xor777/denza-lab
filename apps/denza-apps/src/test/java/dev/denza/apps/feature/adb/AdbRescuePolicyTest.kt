package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRescuePolicyTest {
    private val start = AdbRescueSnapshot()

    @Test
    fun `a refused key offers the control and says the switch`() {
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, checked.phase)
        assertTrue(checked.canAsk)
        assertEquals(AdbSystemSwitch.ENABLED, checked.systemSwitch)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL, checked.details)
    }

    /** A new process starts unknown: there is no latch, and nothing is pending from an earlier one. */
    @Test
    fun `a fresh snapshot is unknown and offers nothing`() {
        assertEquals(AdbRescuePhase.UNKNOWN, start.phase)
        assertFalse(start.canAsk)
    }

    @Test
    fun `a request on its way is the ring's phase and offers no second one`() {
        val refused = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)
        val asking = AdbRescuePolicy.asking(refused)
        val sent = AdbRescuePolicy.sent(asking, AdbSystemSwitch.UNKNOWN, AdbAskLog(listOf(10L, 20L), total = 7, lastAtMs = 20L))

        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, asking.phase)
        assertFalse(asking.canAsk)
        assertEquals(7, sent.attemptCount)
        assertEquals(20L, sent.lastAttemptAtMillis)
        assertEquals(2, sent.requestsToday)
        // The reading that let the request through, not the one from the last check.
        assertEquals(AdbSystemSwitch.UNKNOWN, sent.systemSwitch)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_UNREADABLE_DETAIL, sent.details)
    }

    /**
     * What the access row says about a car that does not answer or answered oddly: a state and the
     * switch's reading, never an exception's name.
     */
    @Test
    fun `a failed check says a state and the switch, and keeps the failure's name for the report`() {
        val silent = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.UNAVAILABLE, AdbSystemSwitch.UNKNOWN, "SocketTimeoutException")
        assertEquals("Нет связи с машиной", silent.message)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_UNREADABLE_DETAIL, silent.details)
        assertEquals("SocketTimeoutException", silent.lastFailure)

        val odd = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.ERROR, AdbSystemSwitch.ENABLED, AdbRescuePolicy.UNEXPECTED_REPLY)
        assertEquals("Доступ не подтверждён", odd.message)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL, odd.details)

        val shownStates = listOf(
            silent, odd, AdbRescuePolicy.checking(start), AdbRescuePolicy.systemSwitchOff(start),
            AdbRescuePolicy.refused(start), AdbRescuePolicy.asking(start),
        )
        for (snapshot in shownStates) {
            val shown = listOfNotNull(snapshot.message, snapshot.details).joinToString(" ")
            assertFalse(shown, Regex("[A-Za-z]{3,}").containsMatchIn(shown.replace("ADB", "").replace("Denza Apps", "")))
        }
    }

    @Test
    fun `trust is remembered and clears the box the last approval missed`() {
        val missed = AdbRescuePolicy.refused(start).copy(alwaysAllowMissed = true)
        val checked = AdbRescuePolicy.afterCheck(missed, AdbCheckOutcome.TRUSTED, AdbSystemSwitch.ENABLED)

        assertEquals(AdbRescuePhase.TRUSTED, checked.phase)
        assertTrue(checked.trustedBefore)
        assertFalse(checked.alwaysAllowMissed)
        assertFalse(checked.canAsk)
    }

    /** A refusal after an approval without the tick keeps the box on the gate. */
    @Test
    fun `a refusal keeps the missed box until trust`() {
        val missed = AdbRescuePolicy.refused(start).copy(alwaysAllowMissed = true)
        val again = AdbRescuePolicy.afterCheck(missed, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.ENABLED)

        assertTrue(again.alwaysAllowMissed)
    }

    @Test
    fun `an off system switch is the service state, not a confirmation request`() {
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.DISABLED)

        assertEquals(AdbRescuePhase.UNAVAILABLE, checked.phase)
        assertEquals(AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL, checked.details)
        assertFalse(checked.canAsk)
    }

    @Test
    fun `an unreadable system switch keeps the handshake classification`() {
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.AUTHORIZATION_REQUIRED, AdbSystemSwitch.UNKNOWN)

        assertEquals(AdbRescuePhase.AUTHORIZATION_REQUIRED, checked.phase)
        assertTrue(checked.canAsk)
        assertEquals(AdbSystemSwitch.UNKNOWN, checked.systemSwitch)
    }

    @Test
    fun `a trusted handshake outranks an off switch`() {
        val checked = AdbRescuePolicy.afterCheck(start, AdbCheckOutcome.TRUSTED, AdbSystemSwitch.DISABLED)

        assertEquals(AdbRescuePhase.TRUSTED, checked.phase)
    }

    @Test
    fun `a check keeps the gate it looks at, and the first check has none`() {
        assertNull(AdbRescuePolicy.checking(start).recheckOf)
        for (phase in listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.AUTHORIZATION_REQUIRED, AdbRescuePhase.ERROR)) {
            val checking = AdbRescuePolicy.checking(start.copy(phase = phase))
            assertEquals(phase, checking.recheckOf)
            // A second press during the check keeps what the first kept.
            assertEquals(phase, AdbRescuePolicy.checking(checking).recheckOf)
        }
        assertNull(AdbRescuePolicy.checking(start.copy(phase = AdbRescuePhase.TRUSTED)).recheckOf)
    }

    @Test
    fun `the day's requests are the last twenty-four hours, and a clock set back forgets them`() {
        val day = AdbRescuePolicy.DAY_MS
        val now = 10 * day
        assertEquals(
            listOf(now - day + 1, now - 1, now),
            AdbRescuePolicy.lastDay(listOf(now - day, now - day + 1, now - 1, now, now + 5), now),
        )
    }
}
