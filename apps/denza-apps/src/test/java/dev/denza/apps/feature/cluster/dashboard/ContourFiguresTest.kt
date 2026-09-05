package dev.denza.apps.feature.cluster.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The one memo on the panel, and the reason it needs slots.
 *
 * The Contour redraws at sixty frames a second because its followers have to be integrated, and
 * about twenty numbers are printed in each of those frames. Almost none of them change between two
 * frames: a temperature moves once every ten seconds, an odometer once a kilometre. Formatting them
 * again is a `String.format` and a string per number per frame - twelve hundred a second of garbage
 * for a panel that is drawn over somebody else's instruments.
 */
class ContourFiguresTest {

    @Test
    fun aNumberThatHasNotChangedIsNotPrintedAgain() {
        val figures = ContourFigures()
        val first = figures.whole(ContourFigures.Slot.VOLTS, 549.0)
        assertEquals("549", first)
        assertSame("the same reading is the same string", first, figures.whole(ContourFigures.Slot.VOLTS, 549.0))
    }

    @Test
    fun aNumberThatChangedIsPrintedAgain() {
        val figures = ContourFigures()
        val first = figures.whole(ContourFigures.Slot.VOLTS, 549.0)
        val second = figures.whole(ContourFigures.Slot.VOLTS, 551.0)
        assertEquals("551", second)
        assertNotSame(first, second)
        // And the memo follows the value rather than latching it: going back reprints.
        assertEquals("549", figures.whole(ContourFigures.Slot.VOLTS, 549.0))
    }

    @Test
    fun twoDigitsThatRoundTheSameWayShareAString() {
        // Which is the whole point of keying on what is *printed* being decided by what is read:
        // 549.4 and 549.0 are one figure on the glass, and the second one costs nothing.
        val figures = ContourFigures()
        assertEquals("549", figures.whole(ContourFigures.Slot.VOLTS, 549.0))
        assertEquals("549", figures.whole(ContourFigures.Slot.VOLTS, 549.4))
    }

    @Test
    fun everyCellOfTheTemperatureRowKeepsItsOwnAnswer() {
        // Five cells drawn through one function with one format. A single memo would be missed by
        // every one of them, every frame, because the cell next door holds a different number.
        val figures = ContourFigures()
        val printed = (0 until ContourFigures.CELLS).map { figures.cell(it, 30.0 + it) }
        assertEquals(listOf("30", "31", "32", "33", "34"), printed)
        (0 until ContourFigures.CELLS).forEach { index ->
            assertSame("cell $index keeps its own", printed[index], figures.cell(index, 30.0 + index))
        }
    }

    @Test
    fun everySeatOfTheTripKeepsItsOwn() {
        val figures = ContourFigures()
        val net = figures.seat(0, 9.3)
        val regen = figures.seat(1, 3.1)
        val engine = figures.seat(2, 1.1)
        assertEquals(listOf("9,3", "3,1", "1,1"), listOf(net, regen, engine))
        assertSame(net, figures.seat(0, 9.3))
        assertSame(regen, figures.seat(1, 3.1))
        assertSame(engine, figures.seat(2, 1.1))
    }

    @Test
    fun theSameConsumptionReadsDifferentlyStandingStillAndTheMemoKnowsIt() {
        // The petal prints an integer on the move and a tenth on P, so the gear is part of the
        // answer and has to be part of the key. A memo on the number alone would hold «17» across
        // the moment the car stops.
        val figures = ContourFigures()
        assertEquals("17", figures.consumption(16.8, parked = false))
        assertEquals("16,8", figures.consumption(16.8, parked = true))
        assertEquals("17", figures.consumption(16.8, parked = false))
    }

    @Test
    fun theEngineWindowIsKeyedOnItsSecondsAndOnTheFaceItIsSetIn() {
        val figures = ContourFigures()
        val long = figures.intoPack(82, short = false)
        assertEquals("В БАТАРЕЮ · ПОСЛЕДНИЕ 1:22", long)
        assertSame(long, figures.intoPack(82, short = false))
        assertEquals("В БАТАРЕЮ · 1:22", figures.intoPack(82, short = true))
        assertEquals("В БАТАРЕЮ · ПОСЛЕДНИЕ 1:23", figures.intoPack(83, short = false))
    }

    @Test
    fun thePetalsUnitAndItsCountdownAreRememberedToo() {
        val figures = ContourFigures()
        val unit = figures.perHundredKm(1.2)
        assertEquals("кВт·ч/100 км · за 1,2 км", unit)
        assertSame(unit, figures.perHundredKm(1.2))
        val left = figures.chargeLeft(135)
        assertEquals("2:15", left)
        assertSame(left, figures.chargeLeft(135))
        assertEquals("2:16", figures.chargeLeft(136))
    }

    @Test
    fun everySlotIsUsedByExactlyOneFormat() {
        // The memo keys on a number and a flag and nothing else, so a slot asked for a whole one
        // frame and a tenth the next would answer the wrong string. That is a rule about the call
        // sites, and this is it written down: every slot below appears once in the renderer.
        val figures = ContourFigures()
        assertEquals("42", figures.whole(ContourFigures.Slot.ODOMETER, 42.0))
        assertEquals("9,3", figures.seat(0, 9.3))
        assertEquals("1780", figures.whole(ContourFigures.Slot.RPM, 1780.0))
        assertEquals("14", figures.whole(ContourFigures.Slot.GENERATION, 14.0))
        assertEquals("6", figures.whole(ContourFigures.Slot.ENGINE_MINUTES, 6.0))
        assertEquals("44", figures.whole(ContourFigures.Slot.SPREAD, 44.0))
    }
}
