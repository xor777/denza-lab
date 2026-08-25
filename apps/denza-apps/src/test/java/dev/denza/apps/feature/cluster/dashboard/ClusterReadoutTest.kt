package dev.denza.apps.feature.cluster.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterReadoutTest {

    @Test
    fun packVoltageIsReadAgainstItsWorkingWindowNotAgainstZero() {
        // 552 V, the reading captured at 72 % on 2026-08-23, sits just past the middle.
        assertEquals(0.52f, ClusterReadout.voltFraction(552.0)!!, 1e-4f)
        assertEquals(0f, ClusterReadout.voltFraction(500.0)!!, 1e-4f)
        assertEquals(1f, ClusterReadout.voltFraction(600.0)!!, 1e-4f)
    }

    @Test
    fun aVoltageOutsideTheWindowClampsRatherThanLeavingTheGauge() {
        assertEquals(0f, ClusterReadout.voltFraction(410.0)!!, 1e-4f)
        assertEquals(1f, ClusterReadout.voltFraction(720.0)!!, 1e-4f)
    }

    @Test
    fun aMissingReadingIsAbsentRatherThanZero() {
        assertNull(ClusterReadout.voltFraction(null))
        assertNull(ClusterReadout.rpmFraction(null))
        assertNull(ClusterReadout.generationFraction(null))
        assertEquals(ClusterReadout.DASH, ClusterReadout.whole(null))
    }

    @Test
    fun aGeneratingEngineSitsLowOnItsOwnSpan() {
        // 1321 rpm was the held generation set-point; the span is the engine's, not that reading's.
        val fraction = ClusterReadout.rpmFraction(1321.0)!!
        assertTrue(fraction > 0.2f)
        assertTrue(fraction < 0.25f)
    }

    @Test
    fun idleGenerationIsVisibleBecauseItsDialIsSquareRoot() {
        // 10 kW against a 100 kW ceiling is a tenth linearly and a third on this dial.
        val fraction = ClusterReadout.generationFraction(10.0)!!
        assertEquals(0.3162f, fraction, 1e-3f)
    }

    @Test
    fun anEngineThatIsNotGeneratingShowsNothingRatherThanAFloor() {
        assertNull(ClusterReadout.generationFraction(0.0))
        assertNull(ClusterReadout.generationFraction(-3.0))
    }

    @Test
    fun theAverageIsOverSpendingOnlySoItCannotReadBelowEveryBar() {
        val bars = listOf(20.0, 30.0, -10.0, 25.0)
        assertEquals(25.0, ClusterReadout.averageConsumption(bars)!!, 1e-9)
    }

    @Test
    fun aRunWithNothingButRecoveryHasNoAverageToShow() {
        assertNull(ClusterReadout.averageConsumption(listOf(-4.0, -9.0)))
        assertNull(ClusterReadout.averageConsumption(emptyList()))
    }

    @Test
    fun theChartSaysHowFarBackItReaches() {
        // Twenty-four closed buckets of 200 m is the 4.8 km the vehicle page already draws.
        assertEquals(4.8, ClusterReadout.chartDistanceKm(List(24) { 18.0 }, 0.2), 1e-9)
    }

    @Test
    fun theLampLineNamesAFaultAndOtherwiseStaysQuiet() {
        assertEquals("все в норме", ClusterReadout.lampLine(emptyList(), 8, 8))
        assertEquals("перегрев ОЖ", ClusterReadout.lampLine(listOf("перегрев ОЖ"), 8, 8))
        assertEquals(
            "перегрев ОЖ · давление масла",
            ClusterReadout.lampLine(listOf("перегрев ОЖ", "давление масла"), 8, 8),
        )
    }

    @Test
    fun lampsThatNeverAnsweredAreNotReportedAsHealthy() {
        assertEquals("жидкости не ответили", ClusterReadout.lampLine(emptyList(), 0, 8))
        assertEquals("в норме 5 из 8", ClusterReadout.lampLine(emptyList(), 5, 8))
    }

    @Test
    fun theLampLineCountsTheCatalogRatherThanASpelledOutNumber() {
        // A ninth lamp must not leave the healthy line still saying eight.
        assertEquals("в норме 8 из 9", ClusterReadout.lampLine(emptyList(), 8, 9))
        assertEquals("все в норме", ClusterReadout.lampLine(emptyList(), 9, 9))
    }

    @Test
    fun aSpreadIsOrdinaryUntilItIsNot() {
        // 4 mV was the live reading; the watch line is 25 and the alert line 40.
        assertEquals(ClusterReadout.Level.NORMAL, ClusterReadout.spreadState(4.0))
        assertEquals(ClusterReadout.Level.WATCH, ClusterReadout.spreadState(30.0))
        assertEquals(ClusterReadout.Level.ALERT, ClusterReadout.spreadState(41.0))
        assertEquals(ClusterReadout.Level.UNKNOWN, ClusterReadout.spreadState(null))
    }

    @Test
    fun aTemperatureIsWarmBeforeItIsHot() {
        val high = ClusterReadout.PACK_BAND_HIGH_C
        assertEquals(ClusterReadout.Level.NORMAL, ClusterReadout.thermalState(28.0, high))
        assertEquals(ClusterReadout.Level.WATCH, ClusterReadout.thermalState(45.0, high))
        assertEquals(ClusterReadout.Level.ALERT, ClusterReadout.thermalState(60.0, high))
        assertEquals(ClusterReadout.Level.UNKNOWN, ClusterReadout.thermalState(null, high))
    }

    @Test
    fun theCarsOwnLowFuelAlarmOutranksOurPercentage() {
        // 53 % was the live reading. A tank the car calls low is low even with no level to show.
        assertEquals(ClusterReadout.Level.NORMAL, ClusterReadout.fuelState(53.0, low = false))
        assertEquals(ClusterReadout.Level.WATCH, ClusterReadout.fuelState(12.0, low = false))
        assertEquals(ClusterReadout.Level.ALERT, ClusterReadout.fuelState(12.0, low = true))
        assertEquals(ClusterReadout.Level.ALERT, ClusterReadout.fuelState(null, low = true))
        assertEquals(ClusterReadout.Level.UNKNOWN, ClusterReadout.fuelState(null, low = false))
    }

    @Test
    fun aTankIsReadStraightThroughBecauseAFuelGaugeIsWhereLinearIsHonest() {
        assertEquals(0.53f, ClusterReadout.fuelFraction(53.0)!!, 1e-4f)
        assertNull(ClusterReadout.fuelFraction(null))
    }

    @Test
    fun aStoppedEngineSaysWhatItIsWorthRatherThanJustThatItIsStopped() {
        assertEquals(
            "заглушен · 491 км на бензине",
            ClusterReadout.engineLine(generating = false, generationKw = null, running = false, fuelRangeKm = 491.0),
        )
        assertEquals(
            "заглушен",
            ClusterReadout.engineLine(generating = false, generationKw = null, running = false, fuelRangeKm = null),
        )
        assertEquals(
            "заряжает 12 кВт",
            ClusterReadout.engineLine(generating = true, generationKw = 12.0, running = true, fuelRangeKm = 491.0),
        )
        assertEquals(
            "работает",
            ClusterReadout.engineLine(generating = false, generationKw = null, running = true, fuelRangeKm = 491.0),
        )
        assertEquals(
            "двигатель не ответил",
            ClusterReadout.engineLine(generating = false, generationKw = null, running = null, fuelRangeKm = null),
        )
    }

    @Test
    fun aChargeReportsTheWaitAndLeavesTheRateToTheDialThatAlreadyShowsIt() {
        assertEquals("заряжается · осталось 2 ч 15 мин", ClusterReadout.chargeLine(135))
        assertEquals("заряжается", ClusterReadout.chargeLine(null))
        // A car that has stopped estimating is still charging.
        assertEquals("заряжается", ClusterReadout.chargeLine(0))
    }

    @Test
    fun theNarrowLayoutKeepsTheWaitAndDropsTheVerbItHasNoRoomFor() {
        assertEquals("осталось 2 ч 15 мин", ClusterReadout.chargeLine(135, brief = true))
        assertEquals("заряжается", ClusterReadout.chargeLine(null, brief = true))
    }

    @Test
    fun aNarrowLineNamesTheFirstFaultAndCountsTheRestRatherThanHidingThem() {
        val faults = listOf("давление масла", "перегрев ОЖ", "уровень ОЖ")
        assertEquals("давление масла +2", ClusterReadout.lampLine(faults, 8, 8, brief = true))
        // One fault fits either way, so brief changes nothing.
        assertEquals("давление масла", ClusterReadout.lampLine(faults.take(1), 8, 8, brief = true))
        assertEquals(
            "давление масла · перегрев ОЖ · уровень ОЖ",
            ClusterReadout.lampLine(faults, 8, 8),
        )
    }

    @Test
    fun durationDropsTheHalfThatWouldReadAsZero() {
        assertEquals("2 ч", ClusterReadout.duration(120))
        assertEquals("45 мин", ClusterReadout.duration(45))
        assertEquals("1 ч 5 мин", ClusterReadout.duration(65))
    }

    @Test
    fun anEmptyChartSaysWhyItIsEmptyRatherThanRepeatingAFullOnesWords() {
        assertEquals("стоим", ClusterReadout.chartCaption(null, 0.0, stationary = true))
        assertEquals("считаю расход", ClusterReadout.chartCaption(null, 0.0, stationary = false))
        assertEquals(
            "18,4 средний за 4,8 км",
            ClusterReadout.chartCaption(18.42, 4.8, stationary = false),
        )
    }

    @Test
    fun numbersAreWrittenWithACommaLikeEveryOtherPanel() {
        assertEquals("18,4", ClusterReadout.fmt(18.42, 1))
        assertEquals("13,1", ClusterReadout.fmt(13.051, 1))
        assertEquals("552", ClusterReadout.whole(552.0))
    }
}
