package dev.denza.apps.feature.simulcast

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulcastStopPolicyTest {
    /** A share started from the stock dialog, then «Поддержка трансляции» switched off. */
    @Test
    fun `switching support off leaves a share the stock app started`() {
        assertEquals(
            SimulcastStop.NOTHING,
            SimulcastStopPolicy.decide(SimulcastStopRequest.SWITCH_OFF, ownsBridge = false),
        )
    }

    @Test
    fun `switching support off ends the share we started`() {
        assertEquals(
            SimulcastStop.OWNED_BRIDGE,
            SimulcastStopPolicy.decide(SimulcastStopRequest.SWITCH_OFF, ownsBridge = true),
        )
    }

    @Test
    fun `the exit control ends the share on screen whoever holds it`() {
        assertEquals(
            SimulcastStop.OWNED_BRIDGE,
            SimulcastStopPolicy.decide(SimulcastStopRequest.EXIT_CONTROL, ownsBridge = true),
        )
        assertEquals(
            SimulcastStop.DISHARE_CURRENT,
            SimulcastStopPolicy.decide(SimulcastStopRequest.EXIT_CONTROL, ownsBridge = false),
        )
    }
}
