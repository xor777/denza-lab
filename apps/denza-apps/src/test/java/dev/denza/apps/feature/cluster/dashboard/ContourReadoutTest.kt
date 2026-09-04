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
    }

    @Test
    fun theAverageIsOverTheSpendingBucketsOnly() {
        // Averaging a descent in would answer a question nobody asks and read lower than any part
        // of the history beside it.
        assertEquals(20.0, ContourReadout.averageConsumption(listOf(10.0, 30.0, -8.0))!!, 1e-9)
        assertNull(ContourReadout.averageConsumption(emptyList()))
        assertNull(ContourReadout.averageConsumption(listOf(-4.0, -2.0)))
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
        assertTrue(ContourReadout.LEGEND_INTO_PACK.endsWith("2 МИН"))
        assertTrue(ContourReadout.LEGEND_INTO_PACK_SHORT.startsWith("В БАТАРЕЮ"))
        assertTrue(
            "only the window may be dropped",
            ContourReadout.LEGEND_INTO_PACK_SHORT.length < ContourReadout.LEGEND_INTO_PACK.length,
        )
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
