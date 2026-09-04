package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.EngineTrace
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals(ContourMode.STARTING, scene.stage.mode)
        // A heading arrives with its first value; before that the panel has no words on it (m4).
        ContourValue.entries.forEach { assertFalse("$it", scene.known(it)) }
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

        assertEquals(ContourMode.UNAVAILABLE, scene.stage.mode)
        assertEquals("ADB-ключ не подтверждён · Помощь → Диагностика", scene.stage.message)
    }

    @Test
    fun theOrdinaryStateIsDriving() {
        val scene = ContourScene()
        run(scene, ready(), 1f)

        assertEquals(ContourMode.DRIVING, scene.stage.mode)
        assertTrue(scene.fresh(ContourValue.POWER))
        assertTrue(scene.fresh(ContourValue.PETAL))
    }

    @Test
    fun everyValueLeavesTwoSecondsAfterTheBusGoesQuietAndEveryCaptionStays() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_VOLT to 552.0)), 1f)
        assertTrue(scene.fresh(ContourValue.VOLTS))

        silence(scene, ready(), 1f)
        assertEquals("a second of quiet is not a loss", ContourMode.DRIVING, scene.stage.mode)
        assertTrue(scene.fresh(ContourValue.VOLTS))

        silence(scene, ready(), 1f)
        assertEquals(ContourMode.LINK_LOST, scene.stage.mode)
        assertFalse("the value went", scene.fresh(ContourValue.VOLTS))
        assertTrue("the caption stayed", scene.known(ContourValue.VOLTS))
    }

    @Test
    fun aSingleNullIsTheSameRuleAppliedToOneValue() {
        val scene = ContourScene()
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0, VehicleSignal.PACK_VOLT to 552.0)), 1f)

        // The pack voltage stops answering; everything else keeps arriving.
        run(scene, ready(mapOf(VehicleSignal.POWER_KW to 34.0)), 3f)

        assertEquals("the panel is not in a state about it", ContourMode.DRIVING, scene.stage.mode)
        assertTrue(scene.fresh(ContourValue.POWER))
        assertFalse("the figure went", scene.fresh(ContourValue.VOLTS))
        assertTrue("«БАТАРЕЯ · В» stayed", scene.known(ContourValue.VOLTS))
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
        assertEquals("not yet", ContourMode.DRIVING, scene.stage.mode)
        run(scene, charging, 1f)
        assertEquals(ContourMode.CHARGING, scene.stage.mode)
        assertTrue(scene.fresh(ContourValue.CHARGE_LEFT))

        // And it goes the moment the gun does: pulling a cable is not ambiguous.
        run(scene, ready(), 0.2f)
        assertEquals(ContourMode.DRIVING, scene.stage.mode)
    }

    @Test
    fun theEngineBoxOwnsTheRightShelfForTheWholeTwoMinutes() {
        val trace = EngineTrace()
        var clock = 0L
        repeat(20) { trace.sample(clock + it * 1_000L, rpm = 1780.0, generationKw = 14.0) }
        clock += 20_000L

        val scene = ContourScene()
        run(scene, ready(trace = trace), 1f)
        assertEquals(ContourMode.ENGINE, scene.stage.mode)
        assertTrue(scene.stage.engineBox)

        // A hundred and nineteen dead seconds, and the box is still there.
        repeat(119) { trace.sample(clock + it * 1_000L, rpm = 0.0, generationKw = 0.0) }
        run(scene, ready(trace = trace), 1f)
        assertTrue("the box holds", scene.stage.engineBox)

        // One more, and the last live slot has walked off the left edge.
        trace.sample(clock + 119_000L, rpm = 0.0, generationKw = 0.0)
        run(scene, ready(trace = trace), 1f)
        assertFalse("and then it goes, with no timer anywhere", scene.stage.engineBox)
        assertEquals(ContourMode.DRIVING, scene.stage.mode)
    }

    @Test
    fun aCarInParkWithTheBoxUpIsBothThingsAtOnce() {
        val trace = EngineTrace()
        repeat(20) { trace.sample(it * 1_000L, rpm = 1780.0, generationKw = 14.0) }

        val scene = ContourScene()
        run(
            scene,
            ready(
                values = mapOf(VehicleSignal.POWER_KW to 1.4, VehicleSignal.GEARBOX_PARK to 1.0),
                trace = trace,
            ),
            1f,
        )

        // The box owns the shelf and the petal still grows its tenth. Folding one of these into
        // the other would make the panel depend on which of two true things was checked first.
        assertEquals(ContourMode.ENGINE, scene.stage.mode)
        assertTrue(scene.stage.engineBox)
        assertTrue(scene.stage.parked)
    }

    @Test
    fun parkIsAScene() {
        val scene = ContourScene()
        run(
            scene,
            ready(mapOf(VehicleSignal.POWER_KW to 1.4, VehicleSignal.GEARBOX_PARK to 1.0)),
            1f,
        )
        assertEquals(ContourMode.PARKED, scene.stage.mode)
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
        // rule applied to every value at once, not a different panel.
        assertEquals(ContourMode.LINK_LOST, scene.stage.mode)
        assertTrue(scene.known(ContourValue.POWER))
        assertTrue(scene.known(ContourValue.PETAL))
        assertFalse(scene.fresh(ContourValue.POWER))
        assertFalse(scene.fresh(ContourValue.PETAL))

        // And it comes back the moment a packet does.
        run(scene, ready(), 0.5f)
        assertEquals(ContourMode.DRIVING, scene.stage.mode)
        assertTrue(scene.fresh(ContourValue.POWER))
    }
}
