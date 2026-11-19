package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.design.luminofor.ThermalGlyphs
import dev.denza.apps.design.luminofor.ThermalGlyphs.Cell
import dev.denza.apps.feature.trip.StripModel
import dev.denza.apps.feature.trip.StripReadings
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each temperature stays under its own glyph on both screens, whatever order the row stands in.
 *
 * Since 2026-11-19 the row is not the data's order: the inverter stands third, beside the front
 * motor it feeds, while the readings keep pack, front, rear left, rear right, inverter. A renderer
 * that walked the row and the readings with one index would print the rear-left motor's figure
 * under the inverter's glyph and nothing would look wrong. Five different temperatures, so a figure
 * that moved cannot agree by accident.
 */
class ThermalRowBindingTest {

    private val signals = linkedMapOf(
        Cell.PACK to (VehicleSignal.PACK_TEMP_AVG to 36.0),
        Cell.FRONT to (VehicleSignal.MOTOR_FRONT_C to 88.0),
        Cell.REAR_LEFT to (VehicleSignal.MOTOR_REAR_LEFT_C to 61.0),
        Cell.REAR_RIGHT to (VehicleSignal.MOTOR_REAR_RIGHT_C to 63.0),
        Cell.INVERTER to (VehicleSignal.INVERTER_C to 74.0),
    )

    private val telemetry = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = signals.values.toMap() + mapOf(VehicleSignal.POWER_KW to 30.0, VehicleSignal.PACK_VOLT to 549.0),
    )

    @Test
    fun theRowIsTheBoards() {
        assertEquals(listOf(Cell.PACK, Cell.FRONT, Cell.INVERTER, Cell.REAR_LEFT, Cell.REAR_RIGHT), ThermalGlyphs.ROW)
        assertEquals(
            LuminoforSpec.Cluster.Glyph.ROW,
            ThermalGlyphs.ROW.map { cell ->
                cell.name.lowercase().split('_').let { it.first() + it.drop(1).joinToString("") { w -> w.replaceFirstChar(Char::uppercase) } }
            },
        )
    }

    @Test
    fun eachSlotPrintsItsOwnGlyphsReadingOnBothScreens() {
        val frame = ContourPanel().run(telemetry, 1f)
        val model = StripModel().also { StripReadings().car(it, telemetry, NOW) }
        ThermalGlyphs.ROW.forEachIndexed { slot, cell ->
            val expected = signals.getValue(cell).second.toInt().toString()
            // what the two renderers draw in this slot: ROW[slot] over temps[ROW[slot].ordinal]
            assertEquals("cluster, slot $slot ($cell)", expected, frame.temps[cell.ordinal].value)
            assertEquals("car page, slot $slot ($cell)", expected + ContourReadout.DEGREE, model.temps[cell.ordinal].figure)
        }
    }

    /** And both renderers do draw it that way: the slot's glyph, and the reading at its glyph's place. */
    @Test
    fun bothRenderersTakeTheReadingFromTheGlyph() {
        val main = File("src/main/java/dev/denza/apps/feature")
        for (file in listOf(File(main, "cluster/dashboard/ClusterDashboardRenderer.kt"), File(main, "trip/VehiclePageRenderer.kt"))) {
            val source = file.readText()
            assertTrue(file.name, source.contains("val glyph = ThermalGlyphs.ROW[slot]"))
            assertTrue(file.name, source.contains("temps[glyph.ordinal]"))
            assertEquals(file.name, 1, Regex("""ThermalGlyphs\.draw\(pen, glyph,""").findAll(source).count())
            assertEquals(file.name, 1, Regex("""ThermalGlyphs\.draw\(""").findAll(source).count())
        }
    }
}

/** A frame's clock for snapshots that carry no stamp of their own: nothing in them ages. */
private const val NOW = 0L
