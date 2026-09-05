package dev.denza.apps.feature.cluster.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every number and every word the Contour prints, decided away from the canvas.
 *
 * The strings matter as much as the formats here: both shelves are laid out by measuring these
 * captions, so a caption is a coordinate. `ContourBoardContractTest` holds them against the board.
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
    fun aChargeIsAClockRatherThanAQuantity() {
        assertEquals("2:15", ContourReadout.chargeLeft(135))
        assertEquals("0:45", ContourReadout.chargeLeft(45))
        assertEquals("0:00", ContourReadout.chargeLeft(-3))
        assertEquals("the last one that fits the seat", "9:59", ContourReadout.chargeLeft(599))
    }

    @Test
    fun anEstimateTooLongForTheSeatIsHoursAlone() {
        // «12:30» is five glyphs against a field of three and a mark, and the history box hangs off
        // that field: widening it would put the box's left edge inside the vehicle's own graphics.
        // A wall socket overnight is the case, and the minutes in it are noise.
        assertEquals("10 ч", ContourReadout.chargeLeft(600))
        assertEquals("12 ч", ContourReadout.chargeLeft(12 * 60 + 30))
        assertEquals("28 ч", ContourReadout.chargeLeft(28 * 60))
        // Both ids are gated to 0..99, so 99:59 is as far as an estimate can legally read.
        assertEquals("99 ч", ContourReadout.chargeLeft(9_999))
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
    fun theEngineBoxScaleIsLinearToThirtyKilowattsAndClamped() {
        // The concept named a square root to 100 kW and the owner called the result flat twice. At
        // the 14 kW this car ordinarily returns, a root over 100 is 0.37 of the box; linear over 30
        // is a half, which is what «сплющен» was about once the height had nowhere left to go.
        assertEquals(0.0f, ContourReadout.generationFraction(0.0), 1e-6f)
        assertEquals(0.467f, ContourReadout.generationFraction(14.0), 1e-3f)
        assertEquals(1.0f, ContourReadout.generationFraction(30.0), 1e-6f)
        assertEquals("clamped: more than the span is the same fact as the span", 1.0f, ContourReadout.generationFraction(180.0), 1e-6f)
        assertEquals(30.0, ContourReadout.GENERATION_FULL_KW, 1e-9)
    }

    @Test
    fun theEngineBoxSaysWhatItIsInsteadOfNamingItsOwnLines() {
        // The eighth pass, in two strings. «ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт» was a key to a picture,
        // and a panel with no room for a key is not allowed to need one.
        assertTrue(ContourReadout.LEGEND_INTO_PACK.startsWith("В БАТАРЕЮ"))
        assertTrue(ContourReadout.LEGEND_INTO_PACK_SHORT.startsWith("В БАТАРЕЮ"))
        assertTrue(
            "only «ПОСЛЕДНИЕ» may be dropped",
            ContourReadout.LEGEND_INTO_PACK_SHORT.length < ContourReadout.LEGEND_INTO_PACK.length,
        )
        // And both templates end in a window, because the window is drawn and not asserted.
        assertTrue(ContourReadout.LEGEND_INTO_PACK.endsWith("0:00"))
        assertTrue(ContourReadout.LEGEND_INTO_PACK_SHORT.endsWith("0:00"))
    }

    @Test
    fun theEngineBoxNamesHowFarBackItActuallyReaches() {
        // The ninth pass's own defect. «ПОСЛЕДНИЕ 2 МИН» is the box's *capacity*: the trace grows
        // from the right and is never front-padded, so five seconds after an engine start the
        // shape was one step wide and the words under it claimed two minutes of it.
        assertEquals("В БАТАРЕЮ · ПОСЛЕДНИЕ 0:05", ContourReadout.intoPack(5, short = false))
        assertEquals("В БАТАРЕЮ · ПОСЛЕДНИЕ 1:22", ContourReadout.intoPack(82, short = false))
        assertEquals("В БАТАРЕЮ · ПОСЛЕДНИЕ 2:00", ContourReadout.intoPack(120, short = false))
        // The face that crowds the phrase drops the adverb and keeps the reading.
        assertEquals("В БАТАРЕЮ · 1:22", ContourReadout.intoPack(82, short = true))
    }

    @Test
    fun aWindowIsFourGlyphsAndAMarkWhateverItIsWorth() {
        // Which is what lets a caption be a coordinate and a reading at once: the phrase is laid
        // out right to left off a fixed edge, the figures are tabular, so no anchor in front of the
        // duration moves as it counts up.
        val lengths = (0..ContourReadout.MAX_WINDOW_SECONDS).map { ContourReadout.clock(it).length }
        assertEquals("every «м:сс» is one length", setOf(4), lengths.toSet())
        assertEquals("and the template is measured at one of them", 4, "0:00".length)
        // Past the clamp the string would gain a glyph and every anchor would move. The trace it
        // names is two minutes long, so this is a guard rather than a case.
        assertEquals("9:59", ContourReadout.clock(ContourReadout.MAX_WINDOW_SECONDS))
        assertEquals("9:59", ContourReadout.clock(100_000))
        assertEquals("0:00", ContourReadout.clock(-1))
    }

    @Test
    fun thePetalsUnitNamesTheRoadTheFigureIsTheMeanOf() {
        // The same defect one level down: «за 3 км» is what the log holds when it *has* three
        // kilometres. A fresh install, a reset journal or the first minutes of a drive is a few
        // hundred metres, and a figure read against a road it is not the mean of is the thing the
        // seventh pass added this window to stop.
        assertEquals("кВт·ч/100 км · за 0,5 км", ContourReadout.perHundredKm(0.5, 3.0))
        assertEquals("кВт·ч/100 км · за 1,2 км", ContourReadout.perHundredKm(1.2, 3.0))
        assertEquals("кВт·ч/100 км · за 3 км", ContourReadout.perHundredKm(3.0, 3.0))
        assertEquals(
            "the full form is the constant, so the two records cannot drift apart",
            ContourReadout.UNIT_PER_100KM,
            ContourReadout.perHundredKm(3.0, 3.0),
        )
        // Thirty buckets of a hundred metres do not add up to 3.0 in binary, and a window one
        // rounding short of full must not print «за 3,0 км» beside a figure that is the whole one.
        assertEquals(
            "кВт·ч/100 км · за 3 км",
            ContourReadout.perHundredKm(30 * 0.1, 3.0),
        )
        assertEquals("кВт·ч/100 км · за 2,9 км", ContourReadout.perHundredKm(2.9, 3.0))
    }

    @Test
    fun everyCaptionOnThePanelNamesTheWindowItIsTrueOver() {
        // The seventh pass, in four strings. A number integrated over an interval that does not say
        // which interval is read against the interval the reader brought.
        assertTrue(ContourReadout.UNIT_PER_100KM.endsWith("за 3 км"))
        assertTrue(ContourReadout.TITLE_ENGINE_MINUTES.endsWith("за поездку"))
        assertTrue(ContourReadout.CAPTION_TRIP.endsWith("ЗА ПОЕЗДКУ"))
        // And the kilometres lead the phrase, so the odometer's reserve is a margin rather than a
        // gap after a separator.
        assertTrue(ContourReadout.CAPTION_TRIP.startsWith("·"))
    }

    @Test
    fun unitsAreCaseSensitiveAndHeadingsAreNot() {
        // «БАТАРЕЯ · В», «ДВС · об/мин», «кВт·ч», «км»: a tracked capital is a heading, a unit is
        // not one, and a tracked heading does not get to rewrite ГОСТ 8.417.
        assertEquals("кВт", ContourReadout.UNIT_KW)
        assertEquals("кВт·ч", ContourReadout.UNIT_KWH)
        assertEquals("км", ContourReadout.UNIT_KM)
        assertEquals("мВ", ContourReadout.UNIT_MILLIVOLT)
        assertTrue(ContourReadout.TITLE_PACK.endsWith(" В"))
        assertTrue(ContourReadout.TITLE_ENGINE_RPM.endsWith("об/мин"))
    }
}
