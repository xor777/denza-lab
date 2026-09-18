package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one closed bucket is worth, and what it refuses to claim.
 *
 * Every expected number here is worked out from the raw samples in the test - energy is `Σ P·dt`
 * and road is the odometer's own difference - rather than through the production helpers. That is
 * `docs/energy-display-contract.md` §7's «independent arithmetic», and it is what makes a sign flip
 * or a dropped guard fail rather than agree with itself.
 */
class ConsumptionLogTest {

    /** 0.1 km every 6 s is 60 km/h, the cadence the hub samples at. */
    private fun ConsumptionLog.drive(steps: Int, powerKw: Double, fromKm: Double = 100.0) {
        sample(fromKm, powerKw, 0.0)
        repeat(steps) { index ->
            sample(fromKm + (index + 1) * 0.1, powerKw, 6.0)
        }
    }

    /** `Σ P·dt` in kilowatt-hours, which is what a bucket's energy is and nothing else. */
    private fun energy(powerKw: Double, seconds: Double) = powerKw * seconds / 3600.0

    @Test
    fun firstSampleOnlyAnchorsTheOdometer() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 6.0)
        assertTrue(log.buckets.isEmpty())
    }

    @Test
    fun aBucketClosesEveryHundredMetresAndCarriesItsOwnRoad() {
        val log = ConsumptionLog()
        log.drive(steps = 2, powerKw = 20.0)
        assertEquals(2, log.buckets.size)
        val first = log.buckets.first()
        assertEquals("where it closed", 100.1, first.odometerKm, 1e-9)
        assertEquals("20 kW for 6 s", energy(20.0, 6.0), first.kwh, 1e-12)
        assertEquals("one odometer tick", 0.1, first.km, 1e-9)
        assertEquals("all of it known", 0.1, first.knownKm, 1e-9)
        assertEquals(energy(20.0, 6.0) / 0.1 * 100.0, first.value, 1e-9)
        assertEquals(33.333, first.value, 0.001)
    }

    @Test
    fun regenerationMakesABucketNegative() {
        val log = ConsumptionLog()
        log.drive(steps = 2, powerKw = -12.0)
        assertEquals(energy(-12.0, 6.0) / 0.1 * 100.0, log.buckets.first().value, 1e-9)
        assertEquals(-20.0, log.buckets.first().value, 0.001)
    }

    /**
     * Energy while the car stands is the trip's, not the road's.
     *
     * `docs/energy-display-contract.md` §2.2. Two minutes of the engine charging on P put 0.33 kWh
     * into the pack on 2026-09-18, and a log that files standing energy into the next hundred metres
     * of road draws that as a blue shelf on the cut for the kilometre after it. The road accounting
     * is untouched by the rule - a standing sample carries no road anyway - so the bucket still
     * closes on its own hundred metres and still knows the energy over all of it.
     */
    @Test
    fun energySpentStandingStillIsNotTheRoadsAndTheBucketSaysNothingAboutIt() {
        val log = ConsumptionLog()
        log.sample(100.0, 0.0, 0.0, 40.0)
        log.sample(100.1, 0.0, 6.0, 40.0)
        // Thirty seconds at a light, drawing 6 kW, then the car moves off and the bucket closes.
        repeat(5) { log.sample(100.1, 6.0, 6.0, 0.0) }
        log.sample(100.2, 0.0, 6.0, 40.0)
        assertEquals(2, log.buckets.size)
        val standing = log.buckets.last()
        assertEquals("not one joule of the light is in it", 0.0, standing.kwh, 1e-12)
        assertEquals("standing still is no road", 0.1, standing.km, 1e-9)
        assertEquals("and the road it does have is all known", 0.1, standing.knownKm, 1e-9)
        assertTrue("so it is a reading and a point like any other", standing.known)
        assertEquals(0.0, standing.value, 1e-9)
    }

    /** The same thirty seconds while the car is rolling are the road's, to the joule. */
    @Test
    fun energySpentMovingIsTheRoadsToTheJoule() {
        val log = ConsumptionLog()
        log.sample(100.0, 0.0, 0.0, 40.0)
        log.sample(100.1, 0.0, 6.0, 40.0)
        repeat(5) { log.sample(100.1, 6.0, 6.0, 40.0) }
        log.sample(100.2, 0.0, 6.0, 40.0)
        val rolling = log.buckets.last()
        assertEquals("five intervals of 6 kW", energy(6.0, 30.0), rolling.kwh, 1e-12)
        assertEquals(energy(6.0, 30.0) / 0.1 * 100.0, rolling.value, 1e-9)
        assertEquals(50.0, rolling.value, 0.01)
    }

    /**
     * And the threshold is half a kilometre an hour, not zero.
     *
     * The id is a float off the bus and a car held on the brake reports a hair of creep, so the
     * question the log asks is «is this car moving», not «is this number exactly zero».
     */
    @Test
    fun aHairOfCreepIsStillStandingAndAWalkingPaceIsNot() {
        fun bucketAt(speedKmh: Double?): ConsumptionSample {
            val log = ConsumptionLog()
            log.sample(100.0, 0.0, 0.0, speedKmh)
            repeat(5) { log.sample(100.0, 6.0, 6.0, speedKmh) }
            log.sample(100.1, 0.0, 6.0, speedKmh)
            return log.buckets.single()
        }
        assertEquals(0.5, ConsumptionLog.STANDING_KMH, 1e-12)
        assertEquals("dead still", 0.0, bucketAt(0.0).kwh, 1e-12)
        assertEquals("creeping on the brake", 0.0, bucketAt(0.4).kwh, 1e-12)
        assertEquals("and on the threshold itself", 0.0, bucketAt(0.5).kwh, 1e-12)
        assertEquals("a walking pace is moving", energy(6.0, 30.0), bucketAt(0.6).kwh, 1e-12)
    }

    /** A speed the car did not answer counts as moving: a missing read is not a stop. */
    @Test
    fun aMissingSpeedReadingCountsAsMoving() {
        val log = ConsumptionLog()
        log.sample(100.0, 0.0, 0.0, null)
        repeat(5) { log.sample(100.0, 6.0, 6.0, null) }
        log.sample(100.1, 0.0, 6.0, null)
        assertEquals(
            "the road keeps the energy rather than losing it to a dropped read",
            energy(6.0, 30.0),
            log.buckets.single().kwh,
            1e-12,
        )
    }

    /**
     * And the trip keeps every joule, standing or not: it is the one figure about time as well.
     *
     * The ledger is fed by the same sweep and integrates the whole of it, which is why «ЗА ПОЕЗДКУ»
     * and the ten-kilometre figure are two different quantities and say so in two different words.
     */
    @Test
    fun theTripKeepsTheJoulesTheRoadRefuses() {
        val log = ConsumptionLog()
        val ledger = TripEnergyLedger()
        fun sweep(odometerKm: Double, powerKw: Double, dt: Double, speedKmh: Double) {
            log.sample(odometerKm, powerKw, dt, speedKmh)
            ledger.sample(
                odometerKm = odometerKm,
                powerKw = powerKw,
                generationKw = 0.0,
                engineRunning = false,
                parked = false,
                dtSeconds = dt,
            )
        }
        // A hundred metres first, because a trip begins where the car moves.
        sweep(100.0, 0.0, 0.0, 40.0)
        sweep(100.1, 0.0, 6.0, 40.0)
        // Then thirty seconds at a light drawing 6 kW, and off again.
        repeat(5) { sweep(100.1, 6.0, 6.0, 0.0) }
        sweep(100.2, 0.0, 6.0, 40.0)
        assertEquals(2, log.buckets.size)
        assertEquals("the road's bucket is empty", 0.0, log.buckets.last().kwh, 1e-12)
        assertEquals("and the trip has all of it", energy(6.0, 30.0), ledger.trip.netKwh, 1e-9)
    }

    @Test
    fun unknownEnergyOverAGapBecomesAHoleRatherThanAZero() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        // The dashboard was away for four minutes; the odometer moved, and the energy for that
        // road was never sampled. Zero would be a reading; unknown is the truth.
        log.sample(100.4, 30.0, 240.0)
        val bucket = log.buckets.single()
        assertEquals("the road is still the road", 0.4, bucket.km, 1e-9)
        assertEquals("and none of it is known", 0.0, bucket.knownKm, 1e-9)
        assertEquals(0.0, bucket.kwh, 1e-12)
        assertFalse(bucket.known)
        assertTrue("a hole, not a zero", bucket.value.isNaN())
    }

    @Test
    fun aMissingPowerReadingLeavesItsOwnRoadUnknown() {
        val log = ConsumptionLog()
        log.sample(100.0, 20.0, 0.0)
        // Half the bucket answered, half did not, and the bucket says so.
        log.sample(100.05, 20.0, 3.0)
        log.sample(100.1, null, 3.0)
        val bucket = log.buckets.single()
        assertEquals(0.1, bucket.km, 1e-9)
        assertEquals(0.05, bucket.knownKm, 1e-9)
        assertEquals(energy(20.0, 3.0), bucket.kwh, 1e-12)
        // Exactly half is still a reading; the figure is over the road it is known for.
        assertTrue(bucket.known)
        assertEquals(energy(20.0, 3.0) / 0.05 * 100.0, bucket.value, 1e-9)
    }

    @Test
    fun aBucketMostlyUnknownIsAHole() {
        val log = ConsumptionLog()
        log.sample(100.0, 20.0, 0.0)
        log.sample(100.02, 20.0, 1.2)
        log.sample(100.1, null, 4.8)
        val bucket = log.buckets.single()
        assertEquals(0.02, bucket.knownKm, 1e-9)
        assertFalse("a fifth of the road is not half of it", bucket.known)
        assertTrue(bucket.value.isNaN())
    }

    /**
     * And the boundary is a half, which is where it is decided rather than near it.
     *
     * A third known is a hole and a half is a reading; a rule loose enough to accept a third would
     * print a figure over road most of which nobody watched, which is the defect the known road
     * exists to prevent, one step milder.
     */
    @Test
    fun theHalfIsTheBoundaryAndAThirdIsAlreadyAHole() {
        fun bucket(knownSeconds: Double): ConsumptionSample {
            val log = ConsumptionLog()
            log.sample(100.0, 20.0, 0.0)
            log.sample(100.0 + knownSeconds / 60.0, 20.0, knownSeconds)
            log.sample(100.1, null, 6.0 - knownSeconds)
            return log.buckets.single()
        }
        // 0.1 km at one kilometre a minute: three seconds is half the bucket, two is a third.
        assertEquals(0.05, bucket(3.0).knownKm, 1e-9)
        assertTrue("exactly half is a reading", bucket(3.0).known)
        assertEquals(0.0333, bucket(2.0).knownKm, 1e-3)
        assertFalse("a third is not", bucket(2.0).known)
        assertTrue(bucket(2.0).value.isNaN())
    }

    @Test
    fun anOdometerStepLongerThanOneBucketClosesOneBucketOfThatWholeRoad() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        // Three hundred metres inside one sample interval: one bucket, and it says 0.3 km.
        log.sample(100.3, 30.0, 6.0)
        val bucket = log.buckets.single()
        assertEquals("one record", 1, log.buckets.size)
        assertEquals("carrying the whole step", 0.3, bucket.km, 1e-9)
        assertEquals(0.3, bucket.knownKm, 1e-9)
        assertEquals(energy(30.0, 6.0), bucket.kwh, 1e-12)
        // The axis is the road, so the figure is over 0.3 km rather than over a nominal tick.
        assertEquals(energy(30.0, 6.0) / 0.3 * 100.0, bucket.value, 1e-9)
    }

    @Test
    fun anOdometerJumpDropsTheOpenWorkInsteadOfInventingConsumption() {
        val log = ConsumptionLog()
        log.sample(100.0, 30.0, 0.0)
        log.sample(100.1, 30.0, 6.0)
        // Driven with the dashboard closed: the odometer is 40 km further on.
        log.sample(140.0, 30.0, 6.0)
        // Forty kilometres of road nobody watched must not become a bucket. The one that had
        // honestly closed before the jump stays.
        assertEquals(1, log.buckets.size)
        log.drive(steps = 2, powerKw = 20.0, fromKm = 140.0)
        assertEquals(3, log.buckets.size)
    }

    @Test
    fun aMissingOdometerReadPausesTheBucketWithoutBreakingIt() {
        val log = ConsumptionLog()
        log.sample(100.0, 20.0, 0.0)
        log.sample(null, 20.0, 6.0)
        log.sample(100.1, 20.0, 6.0)
        val bucket = log.buckets.single()
        assertEquals(energy(20.0, 6.0) / 0.1 * 100.0, bucket.value, 1e-9)
    }

    @Test
    fun theRetentionKeepsOnlyTheMostRecentBuckets() {
        val log = ConsumptionLog(capacity = 3)
        var odometer = 100.0
        repeat(5) { index ->
            log.sample(odometer, (index + 1) * 10.0, 0.0)
            repeat(2) {
                odometer += 0.1
                log.sample(odometer, (index + 1) * 10.0, 6.0)
            }
        }
        assertEquals(3, log.buckets.size)
        assertEquals(energy(50.0, 6.0) / 0.1 * 100.0, log.buckets.last().value, 1e-9)
    }

    @Test
    fun everyClosedBucketIsOfferedToWhoeverIsKeepingThem() {
        val seen = mutableListOf<ConsumptionSample>()
        val log = ConsumptionLog(onBucketClosed = seen::add)
        log.drive(steps = 3, powerKw = 20.0)
        assertEquals(3, seen.size)
        // The odometer is carried with the record, which is what anchors it to a bin and what lets
        // a journal decide later whether it is still part of the retained road.
        assertEquals(100.1, seen.first().odometerKm, 1e-6)
        assertEquals(log.buckets.last(), seen.last())
    }

    @Test
    fun theWindowIsTenKilometresOfRoadEvenWhenBucketsAreLonger() {
        val log = ConsumptionLog()
        var odometer = 100.0
        // Three hundred metres a bucket: thirty-four of them are 10.2 km, and the window is the
        // tail that reaches ten - which is thirty-four records, not a hundred.
        log.sample(odometer, 20.0, 0.0)
        repeat(40) {
            odometer += 0.3
            log.sample(odometer, 20.0, 6.0)
        }
        assertEquals(40, log.buckets.size)
        val window = log.window
        val road = window.sumOf { it.km }
        assertTrue("the window holds ten kilometres: $road", road >= ConsumptionWindow.KM)
        assertTrue("and not much more: $road", road < ConsumptionWindow.KM + 0.3 + 1e-9)
        assertEquals(34, window.size)
    }

    @Test
    fun aJournalIsSeededOnlyWithRoadTheCarHasJustCovered() {
        val log = ConsumptionLog()
        val restored = log.restore(
            samples = listOf(
                ConsumptionSample(900.0, 0.011, 0.1, 0.1),   // forty kilometres ago
                ConsumptionSample(939.5, 0.022, 0.1, 0.1),
                ConsumptionSample(939.9, 0.033, 0.1, 0.1),
            ),
            odometerKm = 940.0,
            windowKm = 30.0,
        )
        assertTrue(restored)
        assertEquals(listOf(939.5, 939.9), log.buckets.map { it.odometerKm })
    }

    /**
     * And what it seeds is history: yesterday's readings are readings until they are pushed out.
     *
     * The journal keeps thirty kilometres so a restart mid-drive keeps its history, and twenty of
     * those can be yesterday - the car was driven with the app closed, or shut down at the end of
     * one drive and started at the beginning of the next. The second review bounded the window at
     * ten kilometres behind the odometer for that reason; the axis is recorded road now, so the
     * bound is gone and a restart shows the road it recorded rather than an empty box
     * (contract §2.6).
     */
    @Test
    fun aJournalFromYesterdaysRoadIsShownUntilTodaysPushesItOut() {
        val log = ConsumptionLog()
        val yesterday = List(100) { ConsumptionSample(900.0 + (it + 1) * 0.1, 0.02, 0.1, 0.1) }
        assertTrue(log.restore(yesterday, odometerKm = 930.0, windowKm = 30.0))
        assertEquals("all of it is still retained", 100, log.buckets.size)
        assertEquals("and all of it is the window", 100, log.window.size)
        assertEquals(10.0, ConsumptionWindow.coveredKm(log.window), 1e-9)
        assertEquals(20.0, ConsumptionWindow.mean(log.window)!!, 1e-9)
    }

    /** A re-anchor is a seam in the record and nothing else: the road either side of it is road. */
    @Test
    fun theBucketsFromBeforeAReanchorStayInTheWindowUntilTheyArePushedOut() {
        val log = ConsumptionLog()
        log.drive(steps = 20, powerKw = 20.0)
        assertEquals(20, log.buckets.size)
        assertEquals("two kilometres of window", 20, log.window.size)

        // Driven forty kilometres with the dashboard closed. The forty kilometres are not recorded
        // and never become a bucket; the two kilometres before them are still two kilometres.
        log.sample(142.0, 20.0, 6.0)
        assertEquals("the record the car drove is still the record", 20, log.window.size)

        log.drive(steps = 3, powerKw = 20.0, fromKm = 142.0)
        assertEquals(
            "and the new road joins it across the seam",
            listOf(101.9, 102.0, 142.1, 142.2, 142.3),
            log.window.map { it.odometerKm }.takeLast(5),
        )
        assertEquals("while the retention keeps the lot", 23, log.buckets.size)
        assertEquals(2.3, ConsumptionWindow.coveredKm(log.window), 1e-9)
    }

    @Test
    fun aJournalFromAheadOfTheCarIsRefusedRatherThanTrusted() {
        val log = ConsumptionLog()
        // An odometer that went backwards means this journal is not this car's.
        val restored = log.restore(
            samples = listOf(ConsumptionSample(5000.0, 0.018, 0.1, 0.1)),
            odometerKm = 940.0,
            windowKm = 30.0,
        )
        assertFalse(restored)
    }

    @Test
    fun resetForgetsEverything() {
        val log = ConsumptionLog()
        log.drive(steps = 2, powerKw = 20.0)
        log.reset()
        assertTrue(log.buckets.isEmpty())
    }
}
