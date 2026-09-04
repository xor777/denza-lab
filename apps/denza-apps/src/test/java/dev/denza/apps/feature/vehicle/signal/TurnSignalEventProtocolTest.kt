package dev.denza.apps.feature.vehicle.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalEventProtocolTest {
    @Test
    fun onlyLiveConfirmedLightValuesGetSemanticNames() {
        assertEquals(TurnIndicatorMode.OFF, TurnSignalDecoder.indicatorMode(1))
        assertEquals(TurnIndicatorMode.LEFT, TurnSignalDecoder.indicatorMode(2))
        assertEquals(TurnIndicatorMode.RIGHT, TurnSignalDecoder.indicatorMode(4))
        assertEquals(TurnIndicatorMode.HAZARD, TurnSignalDecoder.indicatorMode(6))
        assertEquals(
            TurnIndicatorMode.VendorDefined(3),
            TurnSignalDecoder.indicatorMode(3),
        )
        assertEquals(
            TurnIndicatorMode.VendorDefined(99),
            TurnSignalDecoder.indicatorMode(99),
        )
    }

    @Test
    fun switchValuesStayRawBecauseCancellationProducesOppositeBlips() {
        assertEquals(TurnSwitchPhase(1), TurnSignalDecoder.switchPhase(1))
        assertEquals(TurnSwitchPhase(2), TurnSignalDecoder.switchPhase(2))
        assertEquals(TurnSwitchPhase(5), TurnSignalDecoder.switchPhase(5))
    }

    @Test
    fun aBatchAcceptsCrLfAndPreservesEventOrder() {
        val decoder = TurnSignalBatchDecoder()
        val result = decoder.decode(
            "S 1 1000000 321912876 1\r\n" +
                "S 2 1100000 950009900 1\r\n" +
                "E 3 2000000 321912876 2\r\n" +
                "E 4 2063000 950009900 2\r\n",
            sourceEpoch = 9L,
            publishedAtElapsedMs = 3L,
        ) as TurnSignalBatchResult.Updates

        assertEquals(4, result.values.size)
        assertTrue(result.values[0] is VehicleSignalSourceUpdate.Sample<*>)
        assertTrue(result.values[1] is VehicleSignalSourceUpdate.Sample<*>)
        assertTrue(result.values[2] is VehicleSignalSourceUpdate.Event<*>)
        assertTrue(result.values[3] is VehicleSignalSourceUpdate.Event<*>)
        assertEquals(TurnSwitchPhase(1), sampleValue(result.values[0]))
        assertEquals(TurnIndicatorMode.OFF, sampleValue(result.values[1]))
        assertEquals(TurnSwitchPhase(2), sampleValue(result.values[2]))
        assertEquals(TurnIndicatorMode.LEFT, sampleValue(result.values[3]))
    }

    @Test
    fun sequenceGapAndOverflowRequireAReconnect() {
        val gapDecoder = TurnSignalBatchDecoder()
        gapDecoder.decode(
            "S 1 1000000 321912876 1\n",
            sourceEpoch = 1L,
            publishedAtElapsedMs = 1L,
        )

        assertTrue(
            gapDecoder.decode(
                "E 3 2000000 950009900 2\n",
                sourceEpoch = 1L,
                publishedAtElapsedMs = 2L,
            ) is TurnSignalBatchResult.Reconnect,
        )
        assertTrue(
            TurnSignalBatchDecoder().decode(
                "O 1 3000000\n",
                sourceEpoch = 1L,
                publishedAtElapsedMs = 3L,
            ) is TurnSignalBatchResult.Reconnect,
        )
    }

    @Test
    fun heartbeatOnlyProvesChannelLiveness() {
        val result = TurnSignalBatchDecoder().decode(
            "H 31000000\n",
            sourceEpoch = 5L,
            publishedAtElapsedMs = 32L,
        ) as TurnSignalBatchResult.Updates

        assertEquals(
            VehicleSignalSourceUpdate.Heartbeat(5L, 31L),
            result.values.single(),
        )
    }

    private fun sampleValue(update: VehicleSignalSourceUpdate): Any =
        when (update) {
            is VehicleSignalSourceUpdate.Sample<*> -> update.value
            is VehicleSignalSourceUpdate.Event<*> -> update.value
            else -> error("not a value update")
        }
}
