package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.mirrors.AvcTurnCameraChoice.FULL_SCREEN
import dev.denza.apps.feature.mirrors.AvcTurnCameraChoice.OFF
import dev.denza.apps.feature.mirrors.AvcTurnCameraChoice.PIP_LEFT_ON_METER
import dev.denza.apps.feature.mirrors.AvcTurnCameraChoice.PIP_ON_HEAD_UNIT
import dev.denza.apps.feature.mirrors.MirrorStockChoicePolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorStockChoicePolicyTest {
    @Test
    fun turningMirrorsOnPutsTheStockCardOnTheHeadUnitAndRemembersTheOwnersChoice() {
        listOf(PIP_LEFT_ON_METER, FULL_SCREEN, OFF).forEach { owner ->
            assertEquals(
                "from $owner",
                Step(write = PIP_ON_HEAD_UNIT, remember = owner),
                MirrorStockChoicePolicy.onEnable(owner, remembered = null),
            )
        }
    }

    @Test
    fun anAlreadyRightChoiceIsLeftAloneAndAnEarlierMemoryIsKept() {
        assertEquals(Step(), MirrorStockChoicePolicy.onEnable(PIP_ON_HEAD_UNIT, remembered = null))
        assertEquals(Step(), MirrorStockChoicePolicy.onEnable(PIP_ON_HEAD_UNIT, remembered = OFF))
        // Reset behind our back (factory reset, update): put it back, keep the first memory.
        assertEquals(
            Step(write = PIP_ON_HEAD_UNIT, remember = PIP_LEFT_ON_METER),
            MirrorStockChoicePolicy.onEnable(OFF, remembered = PIP_LEFT_ON_METER),
        )
    }

    @Test
    fun turningMirrorsOffGivesTheOwnersChoiceBackOnlyIfOursIsStillThere() {
        assertEquals(
            Step(write = PIP_LEFT_ON_METER, forget = true),
            MirrorStockChoicePolicy.onDisable(PIP_ON_HEAD_UNIT, remembered = PIP_LEFT_ON_METER),
        )
        assertEquals(
            "the owner chose something since: respect it",
            Step(forget = true),
            MirrorStockChoicePolicy.onDisable(FULL_SCREEN, remembered = PIP_LEFT_ON_METER),
        )
        assertEquals(
            "we never changed it",
            Step(),
            MirrorStockChoicePolicy.onDisable(PIP_ON_HEAD_UNIT, remembered = null),
        )
    }
}
