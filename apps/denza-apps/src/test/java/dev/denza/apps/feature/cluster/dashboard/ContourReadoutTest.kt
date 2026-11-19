package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.core.AppDuration
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every number and every word the Contour prints, decided away from the canvas.
 *
 * The strings matter as much as the formats here: the Luminofor board prints these words, and
 * `ContourFixturesContractTest` holds them against its fixtures.
 */
class ContourReadoutTest {

    @Test
    fun thePetalIsAWholeNumberOnTheMoveAndATenthOnPark() {
        // At 100 km/h a tenth changes three times a second, and a figure that flickers is a figure
        // nobody reads (m5). Standing still it is worth the resolution.
        assertEquals("17", ContourReadout.consumption(16.8, parked = false))
        assertEquals("16,8", ContourReadout.consumption(16.8, parked = true))
    }

    @Test
    fun numbersAreWrittenWithACommaTheWayEveryOtherPanelInThisAppWritesOne() {
        assertEquals("9,3", ContourReadout.tenth(9.3))
        assertEquals("12,4", ContourReadout.tenth(12.35))
        assertEquals("0,0", ContourReadout.tenth(0.0))
    }

    @Test
    fun aWholeNumberIsAWholeNumberAndNeverADash() {
        // The panel this replaces drew «—» for a reading that had not arrived. The Contour draws
        // nothing at all and keeps the caption, so there is no dash to format.
        assertEquals("552", ContourReadout.whole(552.4))
        assertEquals("1780", ContourReadout.whole(1780.0))
        assertEquals("42", ContourReadout.whole(42.0))
    }

    @Test
    fun aChargeIsADurationLikeEveryOther() {
        // AppDuration's figure-unit pairs, never a clock: «2:15» beside the sunset's «19:44» read
        // as a time of day.
        assertEquals("2 ч 15 мин", ContourReadout.chargeLeft(135).toString())
        assertEquals("45 мин", ContourReadout.chargeLeft(45).toString())
        assertEquals("1 ч 05 мин", ContourReadout.chargeLeft(65).toString())
        assertEquals("12 ч 30 мин", ContourReadout.chargeLeft(12 * 60 + 30).toString())
        for (minutes in listOf(0, 45, 59, 60, 135, 599, 600, 5_999)) {
            assertEquals(AppDuration.of(minutes * 60L).toString(), ContourReadout.chargeLeft(minutes).toString())
        }
    }

    @Test
    fun anEstimatePastTheGateIsABadReadAndIsClamped() {
        // Both ids are gated to 0..99, so 99 ч 59 мин is as far as an estimate can legally read.
        assertEquals("99 ч 59 мин", ContourReadout.chargeLeft(9_999).toString())
        assertEquals(ContourReadout.chargeLeft(0).toString(), ContourReadout.chargeLeft(-3).toString())
        val widest = ContourReadout.chargeLeft(ContourReadout.MAX_CHARGE_MINUTES)
        assertTrue("every figure is one the wide figures draw", (widest.first + widest.second).all { it.isDigit() })
    }

    @Test
    fun aTemperatureIsAnExceptionOnlyWhenItHasLeftItsBand() {
        assertEquals(
            ContourReadout.Level.NORMAL,
            ContourReadout.thermalState(33.0, ContourReadout.PACK_BAND_HIGH_C),
        )
        assertEquals(
            ContourReadout.Level.WATCH,
            ContourReadout.thermalState(48.0, ContourReadout.PACK_BAND_HIGH_C),
        )
        assertEquals(
            ContourReadout.Level.ALERT,
            ContourReadout.thermalState(60.0, ContourReadout.PACK_BAND_HIGH_C),
        )
    }

    @Test
    fun aColdPackIsNotAnException() {
        // It used to be, at 15 °C, which is most winter mornings here - and a shelf whose rule is
        // "colour means look at this" cannot spend orange on the ordinary.
        assertEquals(
            ContourReadout.Level.NORMAL,
            ContourReadout.thermalState(-4.0, ContourReadout.PACK_BAND_HIGH_C),
        )
    }

    @Test
    fun theInverterHasItsOwnBandBecauseItRunsHotterThanThePack() {
        assertEquals(
            ContourReadout.Level.NORMAL,
            ContourReadout.thermalState(51.0, ContourReadout.INVERTER_WATCH_C),
        )
        assertEquals(
            ContourReadout.Level.ALERT,
            ContourReadout.thermalState(92.0, ContourReadout.INVERTER_WATCH_C),
        )
    }

    @Test
    fun theSpreadOnlyEarnsACellWhenItIsWorthLookingAt() {
        assertFalse(ContourReadout.spreadIsWorthACell(ContourReadout.spreadState(12.0)))
        assertTrue(ContourReadout.spreadIsWorthACell(ContourReadout.spreadState(30.0)))
        assertTrue(ContourReadout.spreadIsWorthACell(ContourReadout.spreadState(44.0)))
        assertEquals(ContourReadout.Level.ALERT, ContourReadout.spreadState(44.0))
    }

    @Test
    fun theEngineBlockNamesWhatThePackTookAndForHowLong() {
        // One wording for the block over its chart and for the trip's detail after it has gone:
        // what the pack took while the engine ran.
        assertEquals("ЗАРЯД ОТ ДВС", ContourReadout.CAPTION_ENGINE_CHARGE)
        assertTrue(
            "and no mark leads it: the chart's blue is the mark",
            !ContourReadout.CAPTION_ENGINE_CHARGE.contains("●"),
        )
        // The run's own time under its figure: five seconds after a start it says five seconds.
        assertEquals("ЗА 0:05", ContourReadout.engineRun(5))
        assertEquals("ЗА 2:52", ContourReadout.engineRun(172))
        // A tenth, and whole from ten: the column after the chart holds «9,9» or «99».
        assertEquals("0,4", ContourReadout.engineKwh(0.4247))
        assertEquals("9,9", ContourReadout.engineKwh(9.94))
        assertEquals("10", ContourReadout.engineKwh(9.95))
        assertEquals("12", ContourReadout.engineKwh(12.4))
    }

    @Test
    fun aWindowIsFourGlyphsAndAMarkWhateverItIsWorth() {
        // Which is what lets a caption be a coordinate and a reading at once: the phrase is laid
        // out right to left off a fixed edge, the figures are tabular, so no anchor in front of the
        // duration moves as it counts up.
        val lengths = (0..ContourReadout.MAX_WINDOW_SECONDS).map { ContourReadout.clock(it).length }
        assertEquals("every «м:сс» is one length", setOf(4), lengths.toSet())
        // Past the clamp the string would gain a glyph and every anchor would move. The trace it
        // names is two minutes long, so this is a guard rather than a case.
        assertEquals("9:59", ContourReadout.clock(ContourReadout.MAX_WINDOW_SECONDS))
        assertEquals("9:59", ContourReadout.clock(100_000))
        assertEquals("0:00", ContourReadout.clock(-1))
    }

    @Test
    fun thePetalsUnitNamesTheRoadTheFigureIsTheMeanOf() {
        // The same defect one level down: «за 10 км» is what the log holds when it *has* ten
        // kilometres. A fresh install, a reset journal or the first minutes of a drive is a few
        // hundred metres, and a figure read against a road it is not the mean of is the thing the
        // seventh pass added this window to stop.
        assertEquals("кВт·ч/100 км · за 0,5 км", ContourReadout.perHundredKm(0.5, 10.0))
        assertEquals("кВт·ч/100 км · за 3,7 км", ContourReadout.perHundredKm(3.7, 10.0))
        assertEquals("кВт·ч/100 км · за 10 км", ContourReadout.perHundredKm(10.0, 10.0))
        assertEquals(
            "the full form is the constant, so the two records cannot drift apart",
            ContourReadout.UNIT_PER_100KM,
            ContourReadout.perHundredKm(ConsumptionWindow.KM, ConsumptionWindow.KM),
        )
        // Buckets of a hundred metres do not always add up to the window in binary - thirty of
        // them made 3.0000000000000004 - and a window one rounding short of full must not print
        // «за 10,0 км» beside a figure that is the whole one.
        assertEquals(
            "кВт·ч/100 км · за 10 км",
            ContourReadout.perHundredKm(10.0 - 1e-12, 10.0),
        )
        assertEquals("кВт·ч/100 км · за 9,9 км", ContourReadout.perHundredKm(9.9, 10.0))
        // The formatter takes the window as an argument rather than carrying one of its own.
        assertTrue(ContourReadout.perHundredKm(3.0, 3.0).endsWith("за 3 км"))
        // And the unit in front of the window is the petal's own, joined the way both screens join
        // a unit to the road it is true over.
        assertTrue(
            ContourReadout.perHundredKm(3.7, 10.0)
                .startsWith(ContourReadout.UNIT_PER_100KM_UNIT + ContourReadout.SEPARATOR),
        )
    }

    /**
     * A minus in front of nothing but zeros is not a direction, so it is not printed.
     *
     * The consumption is the one signed figure on either screen and it is signed because it is an
     * exception - a road that gave back more than it cost. A hundred metres of coasting is −0.4,
     * which `%.0f` prints as «-0» and the panel drew in the return's blue: a minus the reader can
     * see over a magnitude they cannot.
     */
    @Test
    fun aFigureThatRoundedToZeroDoesNotKeepItsMinus() {
        assertEquals("0", ContourReadout.whole(-0.4))
        assertEquals("0", ContourReadout.whole(-0.0))
        assertEquals("0,0", ContourReadout.tenth(-0.04))
        assertEquals("0", ContourReadout.consumption(-0.4, parked = false))
        assertEquals("0,0", ContourReadout.consumption(-0.04, parked = true))
        // And a figure that still has a magnitude keeps it.
        assertEquals("-15", ContourReadout.whole(-15.0))
        assertEquals("-0,4", ContourReadout.tenth(-0.4))
        assertEquals("-1", ContourReadout.whole(-0.6))
    }

    @Test
    fun everyCaptionOnThePanelNamesTheWindowItIsTrueOver() {
        // The seventh pass, in four strings. A number integrated over an interval that does not say
        // which interval is read against the interval the reader brought.
        assertTrue(ContourReadout.UNIT_PER_100KM.endsWith("за 10 км"))
        assertTrue(ContourReadout.TITLE_ENGINE_MINUTES.endsWith("ЗА ПОЕЗДКУ"))
        assertTrue(ContourReadout.CAPTION_TRIP.endsWith("ЗА ПОЕЗДКУ"))
    }

    @Test
    fun aCaptionIsANameOnly() {
        // 2026-11-19: a caption names what the figure is, in one case and one tracking; the unit
        // stands beside the figure, so no caption carries a separator or a unit.
        val captions = listOf(
            ContourReadout.TITLE_VOLTS, ContourReadout.TITLE_ENGINE_RPM, ContourReadout.TITLE_ENGINE_MINUTES,
            ContourReadout.CAPTION_TRIP, ContourReadout.CAPTION_REGEN, ContourReadout.CAPTION_ENGINE_CHARGE,
            ContourReadout.CAPTION_SPREAD,
        )
        for (caption in captions) {
            assertEquals(caption, caption.uppercase(), caption)
            assertFalse(caption, caption.contains("·"))
        }
        for (unit in listOf(ContourReadout.UNIT_VOLT, ContourReadout.UNIT_KM, ContourReadout.UNIT_KWH)) {
            assertFalse(unit, captions.any { it.split(' ').contains(unit) })
        }
    }

    @Test
    fun unitsAreCaseSensitiveAndHeadingsAreNot() {
        // «В», «об/мин», «кВт·ч», «км»: a tracked capital is a heading, a unit is
        // not one, and a tracked heading does not get to rewrite ГОСТ 8.417.
        assertEquals("кВт", ContourReadout.UNIT_KW)
        assertEquals("кВт·ч", ContourReadout.UNIT_KWH)
        assertEquals("км", ContourReadout.UNIT_KM)
        assertEquals("мВ", ContourReadout.UNIT_MILLIVOLT)
        assertEquals("В", ContourReadout.UNIT_VOLT)
    }
}
