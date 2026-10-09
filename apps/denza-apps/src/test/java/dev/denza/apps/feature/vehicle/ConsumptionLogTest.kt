package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one closed bucket is worth, and what it refuses to claim.
 *
 * Every expected number here is worked out from the raw samples in the test - energy is `Σ P·dt`,
 * road is the odometer's own difference, and known road is that road times the seconds that
 * answered over the seconds the car moved, counted here - rather than through the production
 * helpers. That is `docs/energy-display-contract.md` §7's «independent arithmetic», and it is what
 * makes a sign flip or a dropped guard fail rather than agree with itself.
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

    /**
     * The odometer the way the car reports it: whole tenths of a kilometre, decoded from an integer
     * count, so the hundred metres of a bucket arrive in the one poll that closes it and the polls
     * before it carry none. Every test of what a bucket *knows* is written on this input.
     *
     * The tests this replaced (until 2026-10-09) stood the half-known rule on fractional odometers
     * - 100.02, 100.05 - that this car never produces, and on the one input it does produce the rule
     * was decided by the closing poll alone (contract §2.6).
     */
    private class Car(val log: ConsumptionLog = ConsumptionLog()) {
        private var tenths = 1000

        init {
            log.sample(tenths / 10.0, 0.0, 0.0, CRUISE_KMH)
        }

        /** One poll [dt] seconds after the last, the odometer [ticks] tenths further on. */
        fun poll(powerKw: Double?, dt: Double = 1.0, ticks: Int = 0, speedKmh: Double? = CRUISE_KMH) {
            tenths += ticks
            log.sample(tenths / 10.0, powerKw, dt, speedKmh)
        }

        /**
         * One bucket: a poll a second for every entry of [powers], the tick on the last of them.
         * A null is a power read that did not answer.
         */
        fun bucket(vararg powers: Double?): ConsumptionSample {
            powers.forEachIndexed { index, power -> poll(power, ticks = if (index == powers.lastIndex) 1 else 0) }
            return log.buckets.last()
        }
    }

    /**
     * The road arrives on the closing poll, and the energy is every moving second before it.
     *
     * Six polls a second apart is 60 km/h; the odometer stands through five of them and steps on the
     * sixth. Nothing about this bucket is fractional, which is what every bucket on this car is like.
     */
    @Test
    fun aBucketsRoadArrivesInThePollThatClosesIt() {
        val car = Car()
        val bucket = car.bucket(20.0, 20.0, 20.0, 20.0, 20.0, 20.0)
        assertEquals(1, car.log.buckets.size)
        assertEquals("one tick", 0.1, bucket.km, 1e-9)
        assertEquals("all of it known", 0.1, bucket.knownKm, 1e-12)
        assertEquals("six seconds at 20 kW", energy(20.0, 6.0), bucket.kwh, 1e-12)
        assertEquals(33.333, bucket.value, 0.001)
    }

    /**
     * A power read that drops inside a bucket is a second nobody measured, not a second that cost
     * nothing.
     *
     * Until 2026-10-09 the bucket's known road was the road of the polls that had power, and on this
     * odometer that is the closing poll's: the dropped second's energy went missing and the bucket
     * stayed a full reading of 0.1 km, five sixths of what the road cost. Known road is the share of
     * the moving time that had power now, so the bucket is a reading over five sixths of its road and
     * prints what the road cost.
     */
    @Test
    fun aDroppedPowerReadInsideABucketIsUnknownTimeRatherThanLostEnergy() {
        val bucket = Car().bucket(20.0, 20.0, null, 20.0, 20.0, 20.0)
        assertEquals(0.1, bucket.km, 1e-9)
        assertEquals("five of six moving seconds known", 0.1 * 5.0 / 6.0, bucket.knownKm, 1e-12)
        assertEquals("the energy of the seconds that answered", energy(20.0, 5.0), bucket.kwh, 1e-12)
        assertTrue(bucket.known)
        assertEquals("what the road cost, not five sixths of it", energy(20.0, 6.0) / 0.1 * 100.0, bucket.value, 1e-9)
    }

    /**
     * And one on the closing poll no longer throws the bucket away.
     *
     * It used to: the closing poll was the only one carrying road, so a missing power read there made
     * all of the bucket's road unknown, whatever its other five seconds had measured.
     */
    @Test
    fun aClosingPollWithoutPowerNoLongerThrowsTheBucketAway() {
        val bucket = Car().bucket(20.0, 20.0, 20.0, 20.0, 20.0, null)
        assertEquals(0.1 * 5.0 / 6.0, bucket.knownKm, 1e-12)
        assertTrue("a reading", bucket.known)
        assertEquals(energy(20.0, 6.0) / 0.1 * 100.0, bucket.value, 1e-9)
    }

    /**
     * The half is the boundary, on the car's own ticks: three seconds of six is a reading, two is not.
     *
     * A rule loose enough to accept a third would print a figure over road most of which nobody
     * watched, which is the defect the known road exists to prevent, one step milder.
     */
    @Test
    fun theHalfIsTheBoundaryAndAThirdIsAlreadyAHole() {
        val half = Car().bucket(20.0, null, 20.0, null, 20.0, null)
        assertEquals(0.1 * 3.0 / 6.0, half.knownKm, 1e-12)
        assertTrue("exactly half is a reading", half.known)
        assertEquals(energy(20.0, 3.0) / (0.1 * 3.0 / 6.0) * 100.0, half.value, 1e-9)

        val third = Car().bucket(null, 20.0, null, null, 20.0, null)
        assertEquals(0.1 * 2.0 / 6.0, third.knownKm, 1e-12)
        assertFalse("a third is not", third.known)
        assertTrue(third.value.isNaN())
    }

    /**
     * A gap longer than the cadence is unknown time, whatever the power read at its end said.
     *
     * Four minutes with the dashboard away in slow traffic, one tick: the bucket watched four of its
     * two hundred and forty-four moving seconds, and that is not a reading - a hole, not a zero.
     */
    @Test
    fun aGapLongerThanTheCadenceIsUnknownTime() {
        val car = Car()
        repeat(4) { car.poll(30.0) }
        car.poll(30.0, dt = 240.0, ticks = 1)
        val away = car.log.buckets.single()
        assertEquals("the road is still the road", 0.1, away.km, 1e-9)
        assertEquals("four seconds of 244", 0.1 * 4.0 / 244.0, away.knownKm, 1e-12)
        assertEquals("and only their energy", energy(30.0, 4.0), away.kwh, 1e-12)
        assertFalse(away.known)
        assertTrue("a hole, not a zero", away.value.isNaN())

        // Ten seconds of a forty-second bucket is a quarter of it unknown: still a reading, over the
        // three quarters that were measured.
        val slow = Car()
        repeat(15) { slow.poll(12.0) }
        slow.poll(12.0, dt = 10.0)
        repeat(15) { slow.poll(12.0) }
        slow.poll(12.0, ticks = 1)
        val bucket = slow.log.buckets.single()
        assertEquals(0.1 * 31.0 / 41.0, bucket.knownKm, 1e-12)
        assertTrue(bucket.known)
        assertEquals(energy(12.0, 31.0) / (0.1 * 31.0 / 41.0) * 100.0, bucket.value, 1e-9)
    }

    /**
     * A stop is in neither side of the share: the light's minute without a power answer costs the
     * bucket nothing, because standing energy is the trip's and not the road's (contract §2.2).
     */
    @Test
    fun aStopWithoutAPowerAnswerCostsTheBucketNothing() {
        val car = Car()
        repeat(3) { car.poll(15.0) }
        repeat(60) { car.poll(null, speedKmh = 0.0) }
        repeat(2) { car.poll(15.0) }
        car.poll(15.0, ticks = 1)
        val bucket = car.log.buckets.single()
        assertEquals("every moving second answered", 0.1, bucket.knownKm, 1e-12)
        assertEquals(energy(15.0, 6.0), bucket.kwh, 1e-12)
    }

    /**
     * A bucket whose speed never read above standing has no moving time to measure a share over, and
     * no energy filed against its road: it knows none of that road.
     *
     * It used to be a reading of nothing - zero energy over a hundred metres - which is an invented
     * zero, the thing the known road exists to refuse.
     */
    @Test
    fun aBucketThatNeverMovedByItsSpeedKnowsNoneOfItsRoad() {
        val car = Car()
        repeat(5) { car.poll(6.0, speedKmh = 0.0) }
        car.poll(6.0, ticks = 1, speedKmh = 0.0)
        val bucket = car.log.buckets.single()
        assertEquals(0.0, bucket.kwh, 1e-12)
        assertEquals(0.0, bucket.knownKm, 1e-12)
        assertFalse(bucket.known)
    }

    /**
     * An odometer step of more than one tick is not a reading (contract §2.6).
     *
     * A slow sweep on the highway can see 0.2 or 0.3 km at once. That closes one bucket of the whole
     * step with none of it known, and the next bucket is an ordinary reading again. Until 2026-10-09
     * the step was a reading of its whole road on one point of a chart whose points are a hundred
     * metres each, which is what this test pinned then.
     */
    @Test
    fun anOdometerStepOfSeveralTicksIsNotAReading() {
        val car = Car()
        car.poll(30.0, dt = 3.0)
        car.poll(30.0, dt = 6.0, ticks = 3)
        val step = car.log.buckets.single()
        assertEquals("one record", 1, car.log.buckets.size)
        assertEquals("carrying the whole step", 0.3, step.km, 1e-9)
        assertEquals("none of it known", 0.0, step.knownKm, 1e-12)
        assertFalse(step.known)
        assertTrue(step.value.isNaN())

        val next = car.bucket(30.0, 30.0, 30.0)
        assertEquals("and the next tick is a reading again", 0.1, next.knownKm, 1e-12)
        assertTrue(next.known)
    }

    /**
     * §7, «the caption is the chart», on the car's own ticks: a dropped read in every bucket and a
     * two-tick step every tenth leave the unit naming the chart's own width, and the figure what the
     * road cost.
     *
     * Before 2026-10-09 the first printed five sixths of what the road cost, and the second drew a
     * two-tick step as one point and two hundred metres of the unit's road, so the chart and the
     * unit no longer counted the same readings.
     */
    @Test
    fun droppedReadsAndLongStepsLeaveTheUnitTheChartsWidth() {
        val car = Car()
        repeat(120) { index ->
            if (index % 10 == 9) {
                car.poll(25.0, dt = 6.0, ticks = 2)
            } else {
                car.bucket(25.0, 25.0, null, 25.0, 25.0, 25.0)
            }
        }
        val window = car.log.window
        val chart = ConsumptionChart.of(car.log.buckets)
        assertEquals("a hundred readings", 100, window.count { it.known })
        assertEquals("ten kilometres of them", 10.0, ConsumptionWindow.coveredKm(window), 1e-9)
        assertEquals("which is the chart's width", chart.span * ConsumptionChart.PITCH_KM, ConsumptionWindow.coveredKm(window), 1e-9)
        assertEquals("and the figure is what the road cost", energy(25.0, 6.0) / 0.1 * 100.0, ConsumptionWindow.mean(window)!!, 1e-9)
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

    /**
     * The screens are handed the tail, and the journal keeps the rest.
     *
     * How far back the tail reaches - ten kilometres of readings, however long a bucket is - is
     * `ConsumptionWindowTest`'s. What is this class's own is that [ConsumptionLog.window] is that
     * tail of what it retains rather than all of it, which is what the snapshot carries.
     */
    @Test
    fun theWindowIsTheTailOfTheRetainedRoad() {
        val log = ConsumptionLog()
        log.drive(steps = 150, powerKw = 20.0)
        assertEquals("fifteen kilometres retained", 150, log.buckets.size)
        assertEquals("ten of them in the window", 100, log.window.size)
        assertEquals("the newest ten", 105.1, log.window.first().odometerKm, 1e-6)
        assertEquals(115.0, log.window.last().odometerKm, 1e-6)
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

    private companion object {
        /** Sixty kilometres an hour: a hundred metres in six one-second polls. */
        const val CRUISE_KMH = 60.0
    }
}
