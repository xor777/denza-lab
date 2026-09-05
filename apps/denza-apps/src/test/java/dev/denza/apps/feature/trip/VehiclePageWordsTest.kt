package dev.denza.apps.feature.trip

import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the car's page says in each of the states this car has actually been read in.
 *
 * The page's whole argument is that a sentence carries the direction and a figure carries the
 * amount, so these are the tests that matter more than any measurement on it: they are the ones
 * that fail if it goes back to being a table of numbers with signs in front of them.
 */
class VehiclePageWordsTest {

    @Test
    fun aPackGivingEnergyToTheWheelsSaysSo() {
        val words = VehiclePageWords.headline(telemetry(powerKw = 34.0))
        assertEquals("the sentence", VehiclePageWords.TITLE_FROM_PACK, words?.text)
        assertEquals("and no mark: nothing is going in", false, words?.mark)
    }

    /**
     * The engine generating and the road giving back are two different sentences.
     *
     * Both are energy arriving at the pack and both are drawn in `RETURN`, and the driver can see
     * one of them out of the windscreen. The one worth a word is the other.
     */
    @Test
    fun theEngineGeneratingIsNotTheSameSentenceAsRecovery() {
        val generating = VehiclePageWords.headline(
            telemetry(powerKw = -8.0, generationKw = 8.0, generationState = 1.0),
        )
        assertEquals(VehiclePageWords.TITLE_FROM_ENGINE, generating?.text)
        assertEquals("into the pack", true, generating?.mark)

        val coasting = VehiclePageWords.headline(telemetry(powerKw = -14.0))
        assertEquals(VehiclePageWords.TITLE_TO_PACK, coasting?.text)
        assertEquals("into the pack", true, coasting?.mark)
    }

    @Test
    fun aGunInTheCarOutranksEverythingElse() {
        val words = VehiclePageWords.headline(
            telemetry(powerKw = -2.4, gun = 2.0),
        )
        assertEquals(VehiclePageWords.TITLE_FROM_CHARGER, words?.text)
        assertEquals("into the pack", true, words?.mark)
    }

    /**
     * A sentence with no reading behind it is the one thing this page must not print.
     *
     * «ИЗ БАТАРЕИ» over a dash would be a claim about a car that has not answered.
     */
    @Test
    fun aPackThatHasNotAnsweredGetsNoSentence() {
        assertNull(VehiclePageWords.headline(telemetry()))
    }

    @Test
    fun theEngineCellIsRevolutionsWhileItTurns() {
        val cell = VehiclePageWords.engineCell(
            telemetry(rpm = 1321.0, running = 1.0),
        )
        assertEquals(VehiclePageWords.TITLE_RPM to "1321", cell)
    }

    @Test
    fun andHowLongItRanOnceItStops() {
        val cell = VehiclePageWords.engineCell(
            telemetry(running = 0.0, trip = TripEnergy(engineSeconds = 14 * 60.0)),
        )
        assertEquals(VehiclePageWords.TITLE_ENGINE_MINUTES to "14", cell)
    }

    /**
     * And nothing at all when it has not run: a zero is never drawn.
     *
     * Including the half-minute case, which would print «0 мин за поездку» - a zero with a unit on
     * it is still a zero, and this is the exact shape of the sentence the Contour's sixth pass was
     * called for: *«что означает 0,0 от ДВС, когда ДВС заглушен?»*
     */
    @Test
    fun andNothingAtAllWhenItHasNotRun() {
        assertNull("never started", VehiclePageWords.engineCell(telemetry(running = 0.0)))
        assertNull(
            "ran for forty seconds",
            VehiclePageWords.engineCell(telemetry(running = 0.0, trip = TripEnergy(engineSeconds = 40.0))),
        )
        assertNull(
            "running, but the revolutions did not answer",
            VehiclePageWords.engineCell(telemetry(running = 1.0)),
        )
    }

    @Test
    fun theWindowNamesWhatTheBoxActuallyHas() {
        assertEquals(
            "a full box",
            VehiclePageWords.TITLE_WINDOW,
            VehiclePageWords.window(VehiclePageWords.WINDOW_SECONDS, narrow = false),
        )
        assertEquals(
            "and in a pane",
            VehiclePageWords.TITLE_WINDOW_SHORT,
            VehiclePageWords.window(VehiclePageWords.WINDOW_SECONDS, narrow = true),
        )
        assertEquals(
            "one still filling",
            "ПОСЛЕДНИЕ 0:45",
            VehiclePageWords.window(45, narrow = false),
        )
    }

    @Test
    fun consumptionNamesTheRoadItWasSpentOn() {
        val telemetry = telemetry().copy(consumption = List(30) { 19.4 })
        val spend = VehiclePageWords.spend(telemetry)
        assertTrue("the figure", spend!!.startsWith("19,4 кВт·ч/100"))
        assertTrue("and its window", spend.endsWith("ЗА 3 КМ"))
    }

    @Test
    fun andIsAbsentWhileTheCarStands() {
        assertNull("nothing spent yet", VehiclePageWords.spend(telemetry()))
        assertNull(
            "and a window that only gave energy back",
            VehiclePageWords.spend(telemetry().copy(consumption = List(30) { -1.0 })),
        )
    }

    private fun telemetry(
        powerKw: Double? = null,
        generationKw: Double? = null,
        generationState: Double? = null,
        gun: Double? = null,
        rpm: Double? = null,
        running: Double? = null,
        trip: TripEnergy = TripEnergy(),
    ): VehicleTelemetry {
        val values = LinkedHashMap<VehicleSignal, Double>()
        powerKw?.let { values[VehicleSignal.POWER_KW] = it }
        generationKw?.let { values[VehicleSignal.GENERATION_KW] = it }
        generationState?.let { values[VehicleSignal.GENERATION_STATE] = it }
        gun?.let { values[VehicleSignal.CHARGE_GUN] = it }
        rpm?.let { values[VehicleSignal.ENGINE_RPM] = it }
        running?.let { values[VehicleSignal.ENGINE_RUNNING] = it }
        return VehicleTelemetry(values = values, trip = trip)
    }
}
