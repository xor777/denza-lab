package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationRejectionGateTest {
    private var now = 1_000_000L
    private val store = MemoryStore()
    private val gate = RegistrationRejectionGate(store, clock = { now })

    @Test
    fun `one and two rejections do not drop the registration`() {
        assertEquals(RejectionVerdict.Unconfirmed(1, 3), gate.recordRejection())
        now += MINUTE
        assertEquals(RejectionVerdict.Unconfirmed(2, 3), gate.recordRejection())
        now += 30 * MINUTE
        // A third rejection is still required, however long the first two are apart.
        assertEquals(RejectionEvidence(1_000_000L, 2), store.value)
    }

    @Test
    fun `three rejections inside fifteen minutes do not drop the registration`() {
        gate.recordRejection()
        now += 5 * MINUTE
        gate.recordRejection()
        now += 9 * MINUTE
        assertEquals(RejectionVerdict.Unconfirmed(3, 3), gate.recordRejection())
        now += MINUTE - 1
        assertEquals(RejectionVerdict.Unconfirmed(3, 3), gate.recordRejection())
    }

    @Test
    fun `three rejections spanning fifteen minutes drop the registration`() {
        gate.recordRejection()
        now += 7 * MINUTE
        gate.recordRejection()
        now += 8 * MINUTE
        assertEquals(RejectionVerdict.Confirmed, gate.recordRejection())
    }

    @Test
    fun `a success in between resets the count`() {
        gate.recordRejection()
        now += 7 * MINUTE
        gate.recordRejection()
        now += MINUTE
        gate.recordSuccess()
        assertNull(store.value)
        now += 7 * MINUTE
        assertEquals(RejectionVerdict.Unconfirmed(1, 3), gate.recordRejection())
        now += 15 * MINUTE
        assertEquals(RejectionVerdict.Unconfirmed(2, 3), gate.recordRejection())
        assertEquals(RejectionVerdict.Confirmed, gate.recordRejection())
    }

    @Test
    fun `the evidence survives a restart`() {
        gate.recordRejection()
        now += 10 * MINUTE
        gate.recordRejection()
        now += 5 * MINUTE
        val restarted = RegistrationRejectionGate(store, clock = { now })
        assertEquals(RejectionVerdict.Confirmed, restarted.recordRejection())
    }

    @Test
    fun `a clock that moved back restarts the window`() {
        gate.recordRejection()
        gate.recordRejection()
        now -= 60 * MINUTE
        assertEquals(RejectionVerdict.Unconfirmed(1, 3), gate.recordRejection())
    }

    private class MemoryStore : RejectionEvidenceStore {
        var value: RejectionEvidence? = null
        override fun rejectionEvidence(): RejectionEvidence? = value
        override fun saveRejectionEvidence(value: RejectionEvidence?): Boolean { this.value = value; return true }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
