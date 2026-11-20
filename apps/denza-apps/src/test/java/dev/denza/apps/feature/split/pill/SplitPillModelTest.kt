package dev.denza.apps.feature.split.pill

import dev.denza.apps.feature.split.SceneVisibility
import dev.denza.apps.feature.split.SplitLiveScene
import dev.denza.apps.feature.split.SplitMemory
import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import dev.denza.apps.feature.split.SplitPickerLivePane
import dev.denza.apps.feature.split.SplitScene
import dev.denza.apps.feature.split.SplitSlot
import dev.denza.apps.feature.split.SplitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pill shows for a state (contract 1.15, «К 1.15», ред. 2026-11-19): one rule of the rows
 * per test, the oracle written out as the row the user sees.
 */
class SplitPillModelTest {

    @Test
    fun nothingWithoutAProductTwoPaneSceneOnScreen() {
        val full = state(NAV, MUSIC)
        val live = live(NAV, MUSIC)
        assertNotNull(SplitPillModel.describe(full, live, NARROW_LEFT, CATALOGUE))
        assertNull("toggle off", SplitPillModel.describe(full.copy(enabled = false), live, NARROW_LEFT, CATALOGUE))
        assertNull("no scene", SplitPillModel.describe(full.copy(scene = null), live, NARROW_LEFT, CATALOGUE))
        assertNull("one pane", SplitPillModel.describe(full.copy(scene = SplitScene.Full(SplitPane.PRIMARY)), live, NARROW_LEFT, CATALOGUE))
        assertNull("covered", SplitPillModel.describe(full.copy(visibility = SceneVisibility.COVERED), live, NARROW_LEFT, CATALOGUE))
        assertNull("projected", SplitPillModel.describe(full.copy(projectedPane = SplitPane.PRIMARY), live, NARROW_LEFT, CATALOGUE))
        assertNull("no divider", SplitPillModel.describe(full, live, null, CATALOGUE))
    }

    /**
     * «Пилюля» off (1.15.14): every scene that has a pill with the switch on has none - the handle
     * over a full pair (which the dock button's open request would open), the repair row of an
     * emptied pane, both panes empty, the survivor of a collapse - at either detent.
     */
    @Test
    fun theSwitchOffDescribesNothingWhereOnWouldShowSomething() {
        val memory = SplitMemory(last = SplitPair(NAV, MUSIC), pinned = listOf(SplitPair(WAZE, RADIO)))
        val empty = mapOf(SplitPane.PRIMARY to SplitSlot.Picker, SplitPane.SECONDARY to SplitSlot.Picker)
        val scenes = mapOf(
            "handle over a full pair" to (state(NAV, MUSIC, memory) to live(NAV, MUSIC)),
            "an emptied pane" to (state(NAV, MUSIC, memory) to live(null, MUSIC)),
            "a choice owed" to (state(WAZE, RADIO, memory).copy(owed = setOf(SplitPane.PRIMARY)) to live(null, RADIO)),
            "both panes empty" to (state(null, null, memory).copy(slots = empty) to live(null, null)),
            "after a collapse" to (state(null, MUSIC, memory) to live(null, MUSIC)),
        )
        scenes.forEach { (name, scene) ->
            val (s, l) = scene
            listOf(NARROW_LEFT, WIDE_LEFT).forEach { divider ->
                assertNotNull("$name has a pill with the switch on", SplitPillModel.describe(s, l, divider, CATALOGUE, pillEnabled = true))
                assertNull("$name has none with it off", SplitPillModel.describe(s, l, divider, CATALOGUE, pillEnabled = false))
            }
        }
    }

    @Test
    fun thePairOnScreenIsTheOnlyChipOfAnEmptyMemory() {
        val d = describe(state(NAV, MUSIC), live(NAV, MUSIC))
        assertEquals(
            listOf(chip(NAV, MUSIC, live = true, badge = PillBadge.OUTLINE), PillItem.Separator, PillItem.Plus),
            d.home,
        )
        assertFalse(d.vacant)
        assertEquals(SplitPair(NAV, MUSIC), d.live)
    }

    @Test
    fun pinsComeFirstAndTheLivePairTakesTheThirdPlace() {
        val memory = SplitMemory(
            last = SplitPair(RADIO, MAPS),
            recent = listOf(SplitPair(WAZE, MUSIC), SplitPair(MAPS, RADIO)),
            pinned = listOf(SplitPair(MAPS, MUSIC)),
        )
        val d = describe(state(NAV, MUSIC, memory), live(NAV, MUSIC))
        assertEquals(
            listOf(
                chip(MAPS, MUSIC),
                chip(RADIO, MAPS),
                chip(NAV, MUSIC, live = true, badge = PillBadge.OUTLINE),
                PillItem.Separator,
                PillItem.Plus,
            ),
            d.home,
        )
    }

    @Test
    fun aPinnedLivePairWearsTheSolidStarInItsOwnPlace() {
        val memory = SplitMemory(last = SplitPair(NAV, MUSIC), pinned = listOf(SplitPair(NAV, MUSIC)), recent = listOf(SplitPair(WAZE, RADIO)))
        val d = describe(state(NAV, MUSIC, memory), live(NAV, MUSIC))
        assertEquals(
            listOf(chip(NAV, MUSIC, live = true, badge = PillBadge.SOLID), chip(WAZE, RADIO), PillItem.Separator, PillItem.Plus),
            d.home,
        )
    }

    @Test
    fun theScreensLeftDecidesTheChipsSides() {
        // At 434 the narrow primary pane is on the left; at 846 it is on the right.
        val narrow = describe(state(NAV, MUSIC), live(NAV, MUSIC), NARROW_LEFT)
        val wide = describe(state(NAV, MUSIC), live(NAV, MUSIC), WIDE_LEFT)
        assertEquals(SplitPane.PRIMARY, narrow.leftPane)
        assertEquals(SplitPane.SECONDARY, wide.leftPane)
        assertEquals(NAV, (narrow.home[0] as PillItem.Chip).left)
        assertEquals(MUSIC, (wide.home[0] as PillItem.Chip).left)
    }

    @Test
    fun theAppsRowHasEachPanesAppsOnItsSideNeverOneOnScreen() {
        val memory = SplitMemory(last = SplitPair(NAV, MUSIC), recent = listOf(SplitPair(WAZE, RADIO)))
        val d = describe(state(NAV, MUSIC, memory), live(NAV, MUSIC), NARROW_LEFT)
        // Left (primary): its own remembered app first, then the catalogue, outward from the mark.
        // Right (secondary): the same, never NAV or MUSIC.
        assertEquals(
            listOf(
                PillItem.Single(SplitPane.PRIMARY, RADIO),
                PillItem.Single(SplitPane.PRIMARY, MAPS),
                PillItem.Single(SplitPane.PRIMARY, WAZE),
                PillItem.DividerMark,
                PillItem.Single(SplitPane.SECONDARY, RADIO),
                PillItem.Single(SplitPane.SECONDARY, MAPS),
                PillItem.Single(SplitPane.SECONDARY, CHROME),
            ),
            d.apps,
        )
    }

    @Test
    fun anEmptiedPaneOpensTheRepairRowOnItsSide() {
        // The primary pane (left at 434) left empty with its slot still owing NAV.
        val s = state(NAV, MUSIC, SplitMemory(last = SplitPair(NAV, MUSIC)))
        val d = describe(s, live(null, MUSIC), NARROW_LEFT)
        assertTrue(d.vacant)
        assertEquals(
            listOf(
                PillItem.Single(SplitPane.PRIMARY, CHROME),
                PillItem.Single(SplitPane.PRIMARY, RADIO),
                PillItem.Single(SplitPane.PRIMARY, MAPS),
                PillItem.Separator,
                chip(NAV, MUSIC, missing = Side.LEFT),
            ),
            d.home,
        )
        assertFalse("no «+» in the repair row", PillItem.Plus in d.home)
    }

    @Test
    fun theRepairRowStandsOnTheRightForTheRightPane() {
        val s = state(NAV, MUSIC, SplitMemory(last = SplitPair(NAV, MUSIC)))
        val d = describe(s, live(NAV, null), NARROW_LEFT)
        assertEquals(chip(NAV, MUSIC, missing = Side.RIGHT), d.home[0])
        assertEquals(PillItem.Separator, d.home[1])
        assertEquals(PillItem.Single(SplitPane.SECONDARY, MAPS), d.home[2])
    }

    @Test
    fun aPaneWithNothingToRestoreGetsOnlyApps() {
        val s = state(null, MUSIC).copy(slots = mapOf(SplitPane.PRIMARY to SplitSlot.Picker, SplitPane.SECONDARY to SplitSlot.App(MUSIC)))
        val d = describe(s, live(null, MUSIC))
        assertEquals(
            listOf(PillItem.Single(SplitPane.PRIMARY, CHROME), PillItem.Single(SplitPane.PRIMARY, RADIO), PillItem.Single(SplitPane.PRIMARY, MAPS)),
            d.home,
        )
    }

    @Test
    fun bothPanesEmptyShowThePairsWithoutAnyLiveMarkOrTheApps() {
        val empty = mapOf(SplitPane.PRIMARY to SplitSlot.Picker, SplitPane.SECONDARY to SplitSlot.Picker)
        val remembered = describe(
            state(null, null, SplitMemory(last = SplitPair(NAV, MUSIC))).copy(slots = empty),
            live(null, null),
        )
        assertEquals(listOf(chip(NAV, MUSIC), PillItem.Separator, PillItem.Plus), remembered.home)
        assertTrue(remembered.vacant)
        val nothing = describe(state(null, null).copy(slots = empty), live(null, null))
        assertEquals(nothing.apps, nothing.home)
    }

    @Test
    fun theRepairRowAfterACollapseBringsBackTheAppThatWentNotASecondCopy() {
        // Scenario 47 at 434: MUSIC stood in the narrow (primary) pane, NAV in the wide one. NAV is
        // collapsed; the firmware leaves MUSIC in the wide pane. The button reopens the split with a
        // fresh picker in the narrow pane, and memory still says MUSIC | NAV.
        val s = state(null, MUSIC, SplitMemory(last = SplitPair(MUSIC, NAV)))
        val d = describe(s, live(null, MUSIC), NARROW_LEFT)
        assertEquals(setOf(SplitPane.PRIMARY), d.empty)
        // The chip is the pair as it will stand once repaired: NAV back, in the empty narrow pane.
        assertEquals(chip(NAV, MUSIC, missing = Side.LEFT), d.home.last())
        assertFalse("never a second MUSIC", d.home.any { it is PillItem.Chip && it.pair == SplitPair(MUSIC, MUSIC) })
        assertFalse("the apps offered are neither of the pair", d.home.any { it is PillItem.Single && it.packageName in setOf(NAV, MUSIC) })
        // The survivor where it stood before - the same pair, the same way round.
        val same = describe(state(null, NAV, SplitMemory(last = SplitPair(MUSIC, NAV))), live(null, NAV), NARROW_LEFT)
        assertEquals(chip(MUSIC, NAV, missing = Side.LEFT), same.home.last())
    }

    @Test
    fun theRepairRowFindsTheSurvivorsPairInThePinsWhenTheLastPairIsAnother() {
        val memory = SplitMemory(last = SplitPair(WAZE, RADIO), pinned = listOf(SplitPair(NAV, MUSIC)))
        val d = describe(state(null, MUSIC, memory), live(null, MUSIC), NARROW_LEFT)
        assertEquals(chip(NAV, MUSIC, missing = Side.LEFT), d.home.last())
        // A survivor no remembered pair holds: no chip, only apps for the empty pane.
        val stranger = describe(state(null, CHROME, memory), live(null, CHROME), NARROW_LEFT)
        assertTrue(stranger.home.isNotEmpty())
        assertTrue(stranger.home.all { it is PillItem.Single && it.pane == SplitPane.PRIMARY })
    }

    @Test
    fun aChoiceThatDidNotLandIsTheRepairRowsChip() {
        // 1.3.11, the review's policy: WAZE | RADIO chosen, RADIO stood and WAZE did not. The primary
        // pane is owed WAZE and shows its picker; one tap on the chip finishes the pair.
        val s = state(WAZE, RADIO, SplitMemory(last = SplitPair(NAV, MUSIC))).copy(owed = setOf(SplitPane.PRIMARY))
        listOf(live(null, RADIO), emptyMap()).forEach { scene ->
            val d = describe(s, scene, NARROW_LEFT)
            assertTrue(d.vacant)
            assertEquals(setOf(SplitPane.PRIMARY), d.empty)
            assertEquals(chip(WAZE, RADIO, missing = Side.LEFT), d.home.last())
            assertEquals(PillItem.Separator, d.home[d.home.size - 2])
            assertFalse(PillItem.Plus in d.home)
        }
    }

    @Test
    fun fillingTheFirstOfTwoEmptyPanesLeavesTheRepairRowForTheOther() {
        val memory = SplitMemory(last = SplitPair(NAV, MUSIC))
        val both = describe(state(null, null, memory), live(null, null))
        assertEquals(setOf(SplitPane.PRIMARY, SplitPane.SECONDARY), both.empty)
        // RADIO lands on the left; the right is still empty and RADIO is in no remembered pair.
        val one = describe(state(RADIO, null, memory), live(RADIO, null))
        assertEquals(setOf(SplitPane.SECONDARY), one.empty)
        assertTrue(one.vacant)
        assertTrue("apps for the right pane only", one.home.all { it is PillItem.Single && it.pane == SplitPane.SECONDARY })
        assertFalse(one.home.any { it is PillItem.Single && it.packageName == RADIO })
        // NAV lands on the left instead: the remembered pair's other half is the chip.
        val pair = describe(state(NAV, null, memory), live(NAV, null))
        assertEquals(chip(NAV, MUSIC, missing = Side.RIGHT), pair.home.first())
    }

    @Test
    fun onlyWhatCanBeLaunchedIsOfferedButThePairOnScreenAlwaysIs() {
        // WAZE is not in the launch catalogue (uninstalled, or no launcher entry).
        val catalogue = listOf(MAPS, RADIO, CHROME, NAV, MUSIC)
        val memory = SplitMemory(
            last = SplitPair(NAV, MUSIC),
            recent = listOf(SplitPair(MAPS, RADIO)),
            pinned = listOf(SplitPair(WAZE, RADIO)),
        )
        val d = SplitPillModel.describe(state(NAV, MUSIC, memory), live(NAV, MUSIC), NARROW_LEFT, catalogue)!!
        assertEquals(
            listOf(chip(NAV, MUSIC, live = true, badge = PillBadge.OUTLINE), chip(MAPS, RADIO), PillItem.Separator, PillItem.Plus),
            d.home,
        )
        assertFalse(d.apps.any { it is PillItem.Single && it.packageName == WAZE })
        // The pair on screen keeps its chip even when one of its apps is not in the catalogue.
        val onScreen = SplitPillModel.describe(state(WAZE, RADIO, memory), live(WAZE, RADIO), NARROW_LEFT, catalogue)!!
        assertEquals(chip(WAZE, RADIO, live = true, badge = PillBadge.SOLID), onScreen.home.first())
        // A missing app that cannot be launched is no repair chip.
        val repair = SplitPillModel.describe(
            state(null, RADIO, SplitMemory(last = SplitPair(WAZE, RADIO))),
            live(null, RADIO),
            NARROW_LEFT,
            catalogue,
        )!!
        assertFalse(repair.home.any { it is PillItem.Chip })
        // Both panes empty and no launchable pair: the apps row.
        val none = SplitPillModel.describe(
            state(null, null, SplitMemory(last = SplitPair(WAZE, RADIO))),
            live(null, null),
            NARROW_LEFT,
            catalogue,
        )!!
        assertEquals(none.apps, none.home)
        // A catalogue not read yet filters nothing.
        val unread = SplitPillModel.describe(state(NAV, MUSIC, memory), live(NAV, MUSIC), NARROW_LEFT, emptyList())!!
        assertTrue(unread.home.contains(chip(WAZE, RADIO)))
    }

    @Test
    fun anAdoptedSceneWithoutAReadShowsItsSlots() {
        val d = describe(state(NAV, MUSIC), emptyMap())
        assertEquals(SplitPair(NAV, MUSIC), d.live)
    }

    @Test
    fun keysFollowThePairAndThePane() {
        assertEquals(chip(NAV, MUSIC).key, chip(NAV, MUSIC, live = true, badge = PillBadge.SOLID).key)
        assertFalse(chip(NAV, MUSIC).key == chip(MUSIC, NAV).key)
        assertFalse(PillItem.Single(SplitPane.PRIMARY, NAV).key == PillItem.Single(SplitPane.SECONDARY, NAV).key)
    }

    private fun describe(s: SplitState, l: SplitLiveScene, divider: Double = NARROW_LEFT): SplitPillDescription =
        SplitPillModel.describe(s, l, divider, CATALOGUE) ?: error("no pill")

    private fun state(primary: String?, secondary: String?, memory: SplitMemory = SplitMemory()) = SplitState(
        enabled = true,
        slots = mapOf(
            SplitPane.PRIMARY to (primary?.let { SplitSlot.App(it) } ?: SplitSlot.Picker),
            SplitPane.SECONDARY to (secondary?.let { SplitSlot.App(it) } ?: SplitSlot.Picker),
        ),
        scene = SplitScene.Split,
        visibility = SceneVisibility.VISIBLE,
        memory = memory,
    )

    private fun live(primary: String?, secondary: String?): SplitLiveScene = mapOf(
        SplitPane.PRIMARY to SplitPickerLivePane(SplitPane.PRIMARY, 60, primary?.let { 70 }, primary),
        SplitPane.SECONDARY to SplitPickerLivePane(SplitPane.SECONDARY, 61, secondary?.let { 71 }, secondary),
    )

    /** A chip as drawn at the 434 detent: the primary app on the left. */
    private fun chip(
        primary: String,
        secondary: String,
        live: Boolean = false,
        badge: PillBadge? = null,
        missing: Side? = null,
    ) = PillItem.Chip(SplitPair(primary, secondary), primary, secondary, missing, live, badge)

    private companion object {
        const val NARROW_LEFT = 434.0
        const val WIDE_LEFT = 846.0
        const val NAV = "nav"
        const val MUSIC = "music"
        const val RADIO = "radio"
        const val WAZE = "waze"
        const val MAPS = "maps"
        const val CHROME = "chrome"
        val CATALOGUE = listOf(MAPS, RADIO, CHROME, NAV, MUSIC, WAZE)
    }
}
