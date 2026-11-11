package dev.denza.apps.feature.trip

import com.sun.management.ThreadMXBean
import dev.denza.apps.core.StringsXmlSource
import dev.denza.apps.feature.cluster.dashboard.ContourPanel
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.ConsumptionSample
import dev.denza.apps.feature.vehicle.EngineTrace
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the drawn words cost a frame: the cluster's frame is built thirty times a second and the car
 * page's once per drawn frame, and their words are read through `AppText` each time.
 *
 * Past the first frame a steady car reaches `Resources` for nothing and formats nothing. The
 * allocation is measured because a `String.format` on the frame path would show only there: the
 * template it fills comes out of the memo.
 */
class DrawnWordsCostTest {

    private val bytes = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun steady(): VehicleTelemetry {
        val road = List(100) { ConsumptionSample(110.0 - (99 - it) * 0.1, 0.017, 0.1, 0.1) }
        val engine = EngineTrace()
        repeat(30) { engine.sample(it * 1_000L, engineRunning = it < 5, generationKw = 0.0) }
        return VehicleTelemetry(
            access = VehicleAccess.READY,
            values = linkedMapOf(
                VehicleSignal.POWER_KW to 26.0,
                VehicleSignal.PACK_VOLT to 549.0,
                VehicleSignal.PACK_TEMP_AVG to 28.0,
                VehicleSignal.MOTOR_FRONT_C to 31.0,
                VehicleSignal.MOTOR_REAR_LEFT_C to 29.0,
                VehicleSignal.MOTOR_REAR_RIGHT_C to 31.0,
                VehicleSignal.INVERTER_C to 32.0,
                VehicleSignal.GEARBOX_PARK to 0.0,
            ),
            consumption = road,
            chart = ConsumptionChart.of(road),
            engineTrace = engine.snapshot(),
            trip = TripEnergy(netKwh = 9.3, kilometres = 42.0, engineSeconds = 360.0, engineKwh = 1.1),
        )
    }

    /** [frame] [FRAMES] times past a warmed-up start: what they read and what each one allocated. */
    private fun measure(what: String, frame: () -> Unit) {
        repeat(WARM) { frame() }
        val read = StringsXmlSource.lookups.get()
        val start = bytes.currentThreadAllocatedBytes
        repeat(FRAMES) { frame() }
        val perFrame = (bytes.currentThreadAllocatedBytes - start) / FRAMES
        assertEquals("$what reached Resources on a steady frame", read, StringsXmlSource.lookups.get())
        assertTrue("$what allocated $perFrame bytes a frame", perFrame <= BYTES_A_FRAME)
    }

    @Test
    fun aSteadyClusterFrameReadsNothingAndFormatsNothing() {
        val panel = ContourPanel()
        val car = steady()
        panel.run(car, 1f)
        measure("the cluster") { panel.builder.build(panel.frame, car, panel.motion, panel.scene, panel.clock) }
    }

    @Test
    fun aSteadyCarPageFrameReadsNothingAndFormatsNothing() {
        val readings = StripReadings()
        val model = StripModel()
        val car = steady()
        measure("the car page") { readings.car(model, car) }
    }

    @Test
    fun aClosedCarIsTheSameOnBothScreens() {
        val car = VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет")
        val panel = ContourPanel()
        panel.run(car, 1f)
        measure("the closed cluster") { panel.builder.build(panel.frame, car, panel.motion, panel.scene, panel.clock) }
        val readings = StripReadings()
        val model = StripModel()
        measure("the closed car page") { readings.car(model, car) }
    }

    private companion object {
        const val WARM = 2_000
        const val FRAMES = 2_000

        /** Measured: 16 on the cluster, 0 on the car page. A `String.format` alone is several hundred. */
        const val BYTES_A_FRAME = 64L
    }
}
