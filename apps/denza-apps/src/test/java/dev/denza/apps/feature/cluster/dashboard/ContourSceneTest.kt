package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.EngineTrace
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which scene the panel is in, and which of its values are still true.
 *
 * The scene is the point of these: the Contour has no error text and no dimming, so everything that
 * used to be a message or an alpha is a different arrangement of the same panel. What is tested here
 * is that each arrangement is reached for one reason and left for one reason.
 */
class ContourSceneTest {

    private val frame = 1f / 30f

    private fun ready(
        values: Map<VehicleSignal, Double> = mapOf(VehicleSignal.POWER_KW to 34.0),
        consumption: List<Double> = listOf(17.0),
        trip: TripEnergy = TripEnergy(netKwh = 9.3, kilometres = 42.0),
        trace: EngineTrace? = null,
    ) = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = values,
        consumption = consumption,
        trip = trip,
        engineTrace = trace?.snapshot() ?: VehicleTelemetry().engineTrace,
    )

    /** Feeds [seconds] of frames, with a snapshot arriving three times a second. */
    private fun run(scene: ContourScene, t: VehicleTelemetry, seconds: Float) {
        var elapsed = 0f
        var nextPacket = 0f
        while (elapsed < seconds) {
            val arrived = elapsed >= nextPacket
            if (arrived) nextPacket += 1f / 3f
            scene.frame(t, arrived, frame)
            elapsed += frame
        }
    }

    /** Feeds [seconds] of frames with nothing arriving at all. */
    private fun silence(scene: ContourScene, t: VehicleTelemetry, seconds: Float) {
        var elapsed = 0f
        while (elapsed < seconds) {
            scene.frame(t, false, frame)
            elapsed += frame
        }
    }

    @Test
    fun theFirstSecondsAreTheSkeletonAndNothingElse() {
        val scene = ContourScene()
        scene.frame(VehicleTelemetry(), false, frame)

        // A heading arrives with its first value; before that the panel has no words on it (m4).
        // There is no "starting" arrangement to name: the skeleton is what a panel with nothing
        // known on it draws, and that is exactly this.
        ContourValue.entries.forEach { assertFalse("$it", scene.known(it)) }
        assertFalse(scene.stage.unavailable)
        assertFalse(scene.stage.charging)
    }

    @Test
    fun aClosedShellIsAnInstructionRatherThanAnError() {
        val scene = ContourScene()
        scene.frame(
            VehicleTelemetry(
                access = VehicleAccess.UNAVAILABLE,
                message = "ADB-ключ не подтверждён · Помощь → Диагностика",
            ),
            true,
            frame,
        )

        assertTrue(scene.stage.unavailable)
        assertEquals("ADB-ключ не подтверждён · Помощь → Диагностика", scene.stage.message)
    }

    @Test
    fun theOrdinaryStateIsEverythingBeingTrue() {
        // Which is the panel's whole shape: driving is not an arrangement, it is what is left when
        // the shell is open, the values are fresh, no gun is in and the car is moving.
        val scene = ContourScene()
        run(scene, ready(), 1f)

        assertFalse(scene.stage.unavailable)
        assertFalse(scene.stage.charging)
        assertFalse(scene.stage.parked)
        assertTrue(scene.fresh(ContourValue.POWER))
        assertTrue(scene.fresh(ContourValue.PETAL))
    }

    @Test
    fun everyValueLeavesTwoSecondsAfterTheBusGoesQuietAndEveryCaptionStays() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_VOLT to 552.0)), 1f)
        assertTrue(scene.fresh(ContourValue.VOLTS))

        silence(scene, ready(), 1f)
        assertTrue("a second of quiet is not a loss", scene.fresh(ContourValue.VOLTS))

        silence(scene, ready(), 1f)
        assertFalse("the value went", scene.fresh(ContourValue.VOLTS))
        assertTrue("the caption stayed", scene.known(ContourValue.VOLTS))
    }

    @Test
    fun aCaptionOfSomethingThatDidNotHappenThisTripLeavesWithTheTrip() {
        val scene = ContourScene()
        run(
            scene,
            ready(trip = TripEnergy(netKwh = 5.9, engineKwh = 0.6, engineSeconds = 180.0, kilometres = 27.0)),
            1f,
        )
        assertTrue(scene.known(ContourValue.TRIP_ENGINE))
        assertTrue(scene.known(ContourValue.ENGINE_MINUTES))

        // The car moved off P: the ledger cleared, and the next packet carries a trip with no
        // engine in it. The owner's photograph of 2026-09-05 had «ДАЛ ДВС» standing over nothing
        // for the rest of the drive, because "known" was "seen once" for the trip's cells as it
        // is for a sampled value - and a trip quantity that a fresh packet does not carry is not a
        // read that failed, it is a thing that did not happen.
        run(scene, ready(trip = TripEnergy(netKwh = 0.1, kilometres = 0.3)), 1f)
        assertFalse("«ДАЛ ДВС» went with the trip it was about", scene.known(ContourValue.TRIP_ENGINE))
        assertFalse("and so did the corner's minutes", scene.known(ContourValue.ENGINE_MINUTES))
        assertTrue("the trip's own phrase is still there", scene.known(ContourValue.TRIP_NET))
        assertTrue("a sampled value keeps its caption through the same packets", scene.known(ContourValue.POWER))
    }

    @Test
    fun aSingleNullIsTheSameRuleAppliedToOneValue() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_VOLT to 552.0)), 1f)

        // The pack voltage stops answering; everything else keeps arriving.
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 3f)

        assertTrue("the panel is not in a state about it", scene.fresh(ContourValue.POWER))
        assertFalse("the figure went", scene.fresh(ContourValue.VOLTS))
        assertTrue("«БАТАРЕЯ · В» stayed", scene.known(ContourValue.VOLTS))
    }

    @Test
    fun theFollowedValuesAreHeldAcrossASweepThatDidNotCarryThem() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 0.5f)
        assertEquals(34f, scene.held(ContourValue.POWER)!!, 1e-4f)

        // One sweep drops POWER_KW - a sentinel word, a failed range gate - while the rest of the
        // batch publishes. The panel's rule is two seconds, so the band keeps the reading it has:
        // dropping it here teleported the follower and reseeded the peak and the hero's figure.
        run(scene, ready(mapOf(VehicleSignal.PACK_VOLT to 552.0)), 0.5f)
        assertTrue("still inside its own horizon", scene.fresh(ContourValue.POWER))
        assertEquals(34f, scene.held(ContourValue.POWER)!!, 1e-4f)

        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 41.0)), 0.5f)
        assertEquals(41f, scene.held(ContourValue.POWER)!!, 1e-4f)
    }

    @Test
    fun aHeldValueGoesWithItsOwnFreshness() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 0.5f)
        run(scene, ready(mapOf(VehicleSignal.PACK_VOLT to 552.0)), 3f)

        assertFalse(scene.fresh(ContourValue.POWER))
        assertNull("and there is nothing left to follow", scene.held(ContourValue.POWER))
    }

    @Test
    fun aChargeIsHeldAsEnergyArrivingRatherThanAsALoad() {
        val scene = ContourScene()
        run(
            scene,
            ready(
                mapOf(
                    VehicleSignal.CHARGE_GUN to 2.0,
                    VehicleSignal.CHARGE_KW to 7.0,
                    VehicleSignal.POWER_KW to -6.8,
                ),
            ),
            0.5f,
        )
        assertEquals(-7f, scene.held(ContourValue.POWER)!!, 1e-4f)
    }

    @Test
    fun aColdValueIsGivenTheCadenceThatFillsIt() {
        val scene = ContourScene()
        val warm = ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_TEMP_AVG to 31.0))
        run(scene, warm, 1f)
        assertTrue(scene.fresh(ContourValue.PACK_TEMP))

        // The cold sweep runs every ten seconds and rebuilds its map from what answered, so one
        // flaky cold read removes the key from every snapshot until the next one. At a two-second
        // horizon that blanked a standing temperature for eight seconds.
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 12f)
        assertTrue("one missed cold sweep is not a lost temperature", scene.fresh(ContourValue.PACK_TEMP))
        assertTrue("while the hot values keep their own two seconds", scene.fresh(ContourValue.POWER))

        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 15f)
        assertFalse("a second missed sweep is a reading that stopped", scene.fresh(ContourValue.PACK_TEMP))
        assertTrue("and the caption stays", scene.known(ContourValue.PACK_TEMP))
    }

    @Test
    fun aGunHasToStayInForTwoSecondsBeforeThePanelBelievesIt() {
        val scene = ContourScene()
        val charging = ready(
            values = mapOf(
                VehicleSignal.POWER_KW to -7.0,
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_HOURS to 2.0,
                VehicleSignal.CHARGE_MINUTES to 15.0,
            ),
        )

        run(scene, charging, 1.5f)
        assertFalse("not yet", scene.stage.charging)
        run(scene, charging, 1f)
        assertTrue(scene.stage.charging)
        assertTrue(scene.fresh(ContourValue.CHARGE_LEFT))

        // And it goes the moment the gun does: pulling a cable is not ambiguous.
        run(scene, ready(), 0.2f)
        assertFalse(scene.stage.charging)
    }

    @Test
    fun theCountdownCannotOutliveTheLinkThatFillsIt() {
        // The scene name used to carry this in the order of its branches: link loss outranked
        // charging, so a bus that went quiet took the countdown off the petal with everything else.
        // With the name gone that order is a guard on the flag, and this is it.
        val scene = ContourScene()
        val charging = ready(
            values = mapOf(
                VehicleSignal.POWER_KW to -7.0,
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_HOURS to 2.0,
                VehicleSignal.CHARGE_MINUTES to 15.0,
            ),
        )
        run(scene, charging, 3f)
        assertTrue(scene.stage.charging)

        silence(scene, charging, 3f)
        assertFalse("the bus is quiet, so there is no countdown to draw", scene.stage.charging)

        // And a closed shell outranks it too, whatever the last snapshot said.
        run(scene, charging, 3f)
        assertTrue(scene.stage.charging)
        scene.frame(VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет"), true, frame)
        assertTrue(scene.stage.unavailable)
        assertFalse(scene.stage.charging)
    }

    @Test
    fun theEngineBoxOwnsTheRightShelfForTheWholeTwoMinutes() {
        val trace = EngineTrace()
        var clock = 0L
        repeat(20) { trace.sample(clock + it * 1_000L, engineRunning = true, generationKw = 14.0) }
        clock += 20_000L

        val scene = ContourScene()
        run(scene, ready(trace = trace), 1f)
        assertTrue(scene.stage.engineBox)

        // A hundred and nineteen dead seconds, and the box is still there.
        repeat(119) { trace.sample(clock + it * 1_000L, engineRunning = false, generationKw = 0.0) }
        run(scene, ready(trace = trace), 1f)
        assertTrue("the box holds", scene.stage.engineBox)

        // One more, and the last live slot has walked off the left edge.
        trace.sample(clock + 119_000L, engineRunning = false, generationKw = 0.0)
        run(scene, ready(trace = trace), 1f)
        assertFalse("and then it goes, with no timer anywhere", scene.stage.engineBox)
    }

    @Test
    fun aRunningEngineKeepsTheShelfEvenOnPark() {
        val trace = EngineTrace()
        repeat(20) { trace.sample(it * 1_000L, engineRunning = true, generationKw = 14.0) }

        val scene = ContourScene()
        run(
            scene,
            ready(
                values = mapOf(
                    VehicleSignal.POWER_KW to -12.0,
                    VehicleSignal.GEARBOX_PARK to 1.0,
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 1780.0,
                    VehicleSignal.GENERATION_KW to 14.0,
                ),
                trace = trace,
            ),
            1f,
        )

        // The owner sat on P with the generator charging the pack and the shelf showed him three
        // frozen trip figures instead of the one live thing on the panel. While the engine turns,
        // the box is what the shelf is for; the trip's cells wait for the engine to stop.
        assertTrue("the car is standing", scene.stage.parked)
        assertTrue("and the engine is running", scene.stage.engineRunning)
        assertTrue("so the box keeps the shelf", scene.stage.engineBox)
    }

    @Test
    fun standingStillOutranksAWarmEngineTraceOnTheRightShelf() {
        val trace = EngineTrace()
        repeat(20) { trace.sample(it * 1_000L, engineRunning = true, generationKw = 14.0) }

        val scene = ContourScene()
        run(
            scene,
            ready(
                values = mapOf(VehicleSignal.POWER_KW to 1.4, VehicleSignal.GEARBOX_PARK to 1.0),
                trace = trace,
            ),
            1f,
        )

        // Both facts are true and one shelf has to hold them. A car that has stopped is the one
        // moment its driver can read three numbers instead of glancing at one, and the engine's
        // box is a shape about the last two minutes of a drive that has ended. The trip wins: the
        // three cells are what P is for, and the box comes back the moment the car moves.
        assertTrue("the car is standing", scene.stage.parked)
        assertFalse("so the box does not take the shelf", scene.stage.engineBox)

        // And it is the standing that does it, not the trace going quiet: the same trace under a
        // car that is rolling still owns the shelf.
        run(scene, ready(trace = trace), 1f)
        assertTrue("the box is back the moment the car moves", scene.stage.engineBox)
        assertFalse(scene.stage.parked)
    }

    @Test
    fun aSceneThatHasNotChangedIsTheSameSceneRatherThanANewOne() {
        // This is asked once per frame at sixty frames a second, and it answers the same five
        // things for minutes at a time. A fresh object per frame is garbage on a view drawn over
        // the vehicle's own instruments, and it is also a lie: nothing about the panel changed.
        val scene = ContourScene()
        val driving = ready(mapOf(VehicleSignal.POWER_KW to 34.0))
        run(scene, driving, 1f)
        val first = scene.stage

        run(scene, driving, 0.02f)
        assertSame("nothing moved, so the stage did not", first, scene.stage)
        silence(scene, driving, 0.02f)
        assertSame("and a frame with no sweep in it certainly did not", first, scene.stage)

        // And it is not latched: a gear change is a new stage.
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 1.4, VehicleSignal.GEARBOX_PARK to 1.0)), 0.02f)
        assertNotSame(first, scene.stage)
        assertTrue(scene.stage.parked)
    }

    @Test
    fun parkIsAFlagAndNotAnArrangement() {
        // It changes a seat count and a decimal place wherever else the panel happens to be, which
        // is why it was never one of the scene's seven words in the first place.
        val scene = ContourScene()
        run(
            scene,
            ready(mapOf(VehicleSignal.POWER_KW to 1.4, VehicleSignal.GEARBOX_PARK to 1.0)),
            1f,
        )
        assertTrue(scene.stage.parked)
    }

    @Test
    fun aQuantityThatDidNotHappenThisTripHasNoCell() {
        val scene = ContourScene()
        run(scene, ready(trip = TripEnergy(netKwh = 9.3, kilometres = 42.0)), 1f)

        assertTrue(scene.known(ContourValue.TRIP_NET))
        assertTrue(scene.known(ContourValue.TRIP_KM))
        // «0,0 ОТ ДВС» is the cell the owner's question deleted. A zero is never drawn.
        assertFalse(scene.known(ContourValue.TRIP_ENGINE))
        assertFalse(scene.known(ContourValue.TRIP_REGEN))
        assertFalse(scene.known(ContourValue.ENGINE_MINUTES))
    }

    @Test
    fun anEngineThatRanEarnsItsCornerAndItsCell() {
        val scene = ContourScene()
        run(
            scene,
            ready(trip = TripEnergy(netKwh = 5.9, engineKwh = 0.6, engineSeconds = 180.0, kilometres = 27.0)),
            1f,
        )

        assertTrue(scene.known(ContourValue.ENGINE_MINUTES))
        assertTrue(scene.known(ContourValue.TRIP_ENGINE))
        assertFalse("and the revolutions are not a reading while it sleeps", scene.known(ContourValue.RPM))
    }

    @Test
    fun revolutionsAreOnlyAReadingWhileTheEngineIsRunning() {
        val scene = ContourScene()
        // The rpm id answers a resting zero on this car; that is not a reading of the engine.
        run(
            scene,
            ready(mapOf(VehicleSignal.ENGINE_RPM to 0.0, VehicleSignal.ENGINE_RUNNING to 0.0)),
            1f,
        )
        assertFalse(scene.known(ContourValue.RPM))

        run(
            scene,
            ready(mapOf(VehicleSignal.ENGINE_RPM to 1780.0, VehicleSignal.ENGINE_RUNNING to 3.0)),
            1f,
        )
        assertTrue(scene.known(ContourValue.RPM))
        assertTrue(scene.stage.engineRunning)
    }

    @Test
    fun theSpreadIsOnlyKnownOnceBothEndsOfThePackHaveAnswered() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.CELL_MIN_MV to 3_800.0)), 1f)
        assertFalse(scene.known(ContourValue.SPREAD))

        run(
            scene,
            ready(mapOf(VehicleSignal.CELL_MIN_MV to 3_800.0, VehicleSignal.CELL_MAX_MV to 3_844.0)),
            1f,
        )
        assertTrue(scene.known(ContourValue.SPREAD))
    }

    @Test
    fun linkLossDoesNotEraseTheSceneItInterrupted() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_VOLT to 552.0)), 1f)
        silence(scene, ready(), 3f)

        // Everything is removed and every caption is still on the panel: link loss is the stale
        // rule applied to every value at once, not a different panel - which is why it never had a
        // picture of its own and does not need a name.
        assertTrue(scene.known(ContourValue.POWER))
        assertTrue(scene.known(ContourValue.PETAL))
        assertFalse(scene.fresh(ContourValue.POWER))
        assertFalse(scene.fresh(ContourValue.PETAL))

        // And it comes back the moment a packet does.
        run(scene, ready(), 0.5f)
        assertTrue(scene.fresh(ContourValue.POWER))
    }
}
