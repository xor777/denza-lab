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
    fun theOwnersChoiceIsOnDiskBeforeAvcIsAskedToChangeIt() {
        listOf(PIP_LEFT_ON_METER, FULL_SCREEN, OFF).forEach { owner ->
            val events = mutableListOf<String>()
            var stored: AvcTurnCameraChoice? = null
            MirrorStockChoicePolicy.applyEnable(
                current = owner,
                remembered = null,
                store = { stored = it; events += "store $it"; true },
                write = { events += "write $it"; it },
            )
            assertEquals("from $owner", listOf("store $owner", "write $PIP_ON_HEAD_UNIT"), events)
            assertEquals(owner, stored)
        }
    }

    @Test
    fun aLostReplyKeepsTheOwnersChoiceForTheNextStartAndTheSwitchOff() {
        // AVC applies `1` but its reply times out (or the process dies inside the write).
        var stored: AvcTurnCameraChoice? = null
        val result = MirrorStockChoicePolicy.applyEnable(
            current = OFF,
            remembered = null,
            store = { stored = it; true },
            write = { null },
        )
        assertEquals(MirrorStockChoicePolicy.EnableResult.NoAnswer, result)
        assertEquals(OFF, stored)

        // The next monitor reads `1`: already right, and the memory stays.
        assertEquals(
            MirrorStockChoicePolicy.EnableResult.Unchanged,
            MirrorStockChoicePolicy.applyEnable(
                current = PIP_ON_HEAD_UNIT,
                remembered = stored,
                store = { stored = it; true },
                write = { error("already right: AVC is not asked") },
            ),
        )
        assertEquals(OFF, stored)
        // Turning Mirrors off gives the owner's `OFF` back.
        assertEquals(
            Step(write = OFF, forget = true),
            MirrorStockChoicePolicy.onDisable(PIP_ON_HEAD_UNIT, remembered = stored),
        )
    }

    @Test
    fun aProcessDyingInsideTheWriteStillLeavesTheOwnersChoiceStored() {
        var stored: AvcTurnCameraChoice? = null
        runCatching {
            MirrorStockChoicePolicy.applyEnable(
                current = PIP_LEFT_ON_METER,
                remembered = null,
                store = { stored = it; true },
                write = { throw IllegalStateException("process killed") },
            )
        }
        assertEquals(PIP_LEFT_ON_METER, stored)
    }

    @Test
    fun aWriteAvcDidNotTakeLeavesNothingToGiveBack() {
        var stored: AvcTurnCameraChoice? = null
        val result = MirrorStockChoicePolicy.applyEnable(
            current = OFF,
            remembered = null,
            store = { stored = it; true },
            write = { OFF },
        )
        assertEquals(MirrorStockChoicePolicy.EnableResult.Answered(PIP_ON_HEAD_UNIT, OFF), result)
        assertEquals("read-back proved the write did not land", null, stored)
    }

    @Test
    fun aChoiceThatCannotBeStoredIsNeverWrittenOver() {
        val result = MirrorStockChoicePolicy.applyEnable(
            current = OFF,
            remembered = null,
            store = { false },
            write = { error("AVC must not be asked without the owner's choice on disk") },
        )
        assertEquals(MirrorStockChoicePolicy.EnableResult.Unchanged, result)
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

    /** The restore worker's world: AVC's answers in order, the switch, the stored choice. */
    private class GiveBackWorld(
        var remembered: AvcTurnCameraChoice? = PIP_LEFT_ON_METER,
        reads: List<AvcTurnCameraChoice?>,
        writes: List<AvcTurnCameraChoice?> = emptyList(),
    ) {
        val reads = ArrayDeque(reads)
        val writes = ArrayDeque(writes)
        var mirrorsOn = false
        val events = mutableListOf<String>()
        var onPause: () -> Unit = {}

        fun run(): MirrorStockChoicePolicy.GiveBack = MirrorStockChoicePolicy.giveBack(
            remembered = { remembered },
            mirrorsOn = { mirrorsOn },
            read = { reads.removeFirstOrNull().also { events += "read $it" } },
            write = { choice ->
                events += "write $choice"
                if (writes.isEmpty()) choice else writes.removeFirst()
            },
            forget = { remembered = null; events += "forget" },
            pause = { ms -> events += "pause $ms"; onPause() },
        )
    }

    /**
     * Round-2 finding 6: the owner had the meter card, Mirrors set `1`, and the switch-off's first
     * choice read gets no answer (AVC restarting). Before 2026-11-19 the worker gave up there and
     * nothing tried again while Mirrors stayed off, so the car kept `1`.
     */
    @Test
    fun aSwitchOffWhoseFirstReadGetsNoAnswerStillGivesTheOwnersChoiceBack() {
        val world = GiveBackWorld(reads = listOf(null, null, PIP_ON_HEAD_UNIT))

        assertEquals(MirrorStockChoicePolicy.GiveBack.GIVEN_BACK, world.run())
        assertEquals(
            listOf("read null", "pause 500", "read null", "pause 1000", "read $PIP_ON_HEAD_UNIT",
                "write $PIP_LEFT_ON_METER", "forget"),
            world.events,
        )
        assertEquals(null, world.remembered)
    }

    @Test
    fun aWriteAvcDoesNotConfirmIsTriedAgainAndTheChoiceIsKeptUntilItIs() {
        val world = GiveBackWorld(
            reads = listOf(PIP_ON_HEAD_UNIT, PIP_ON_HEAD_UNIT),
            writes = listOf(null),
        )

        assertEquals(MirrorStockChoicePolicy.GiveBack.GIVEN_BACK, world.run())
        assertEquals(
            listOf("read $PIP_ON_HEAD_UNIT", "write $PIP_LEFT_ON_METER", "pause 500",
                "read $PIP_ON_HEAD_UNIT", "write $PIP_LEFT_ON_METER", "forget"),
            world.events,
        )
    }

    @Test
    fun anAvcThatNeverAnswersIsLeftAfterTheLastPauseWithTheChoiceStillStored() {
        val world = GiveBackWorld(reads = List(10) { null })

        assertEquals(MirrorStockChoicePolicy.GiveBack.NO_ANSWER, world.run())
        assertEquals(
            "one attempt, then one after each pause",
            MirrorStockChoicePolicy.GIVE_BACK_BACKOFF_MS.size + 1,
            world.events.count { it == "read null" },
        )
        assertEquals("the next switch-off tries again", PIP_LEFT_ON_METER, world.remembered)
    }

    @Test
    fun mirrorsTurnedBackOnDuringAPauseEndTheGiveBackWithNothingWritten() {
        val world = GiveBackWorld(reads = listOf(null, PIP_ON_HEAD_UNIT))
        world.onPause = { world.mirrorsOn = true }

        assertEquals(MirrorStockChoicePolicy.GiveBack.MIRRORS_ON, world.run())
        assertEquals(listOf("read null", "pause 500"), world.events)
        assertEquals("the new monitor owns the choice", PIP_LEFT_ON_METER, world.remembered)
    }
}
