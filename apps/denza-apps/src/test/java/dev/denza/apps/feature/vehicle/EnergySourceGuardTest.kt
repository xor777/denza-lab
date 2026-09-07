package dev.denza.apps.feature.vehicle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neither renderer formats an energy number, read off the two files rather than off their output.
 *
 * `docs/energy-display-contract.md` §1 and §7: **the two renderers own geometry and nothing else.**
 * `EnergyReadoutsTest` proves the two screens agree about what `EnergyReadouts` says; nothing there
 * can see a renderer that stopped asking it and went back to `String.format`. That is exactly how
 * the divergence this contract was written for happened - the car page grew its own sign, its own
 * window and its own engine cell one line at a time, each of them locally reasonable.
 *
 * So this is a source-level guard, in the style of the board contract tests: it reads the two files
 * and refuses the calls that would put a second formatter in one of them.
 *
 * **What it cannot check.** Whether the strings it does draw came from the right field. A renderer
 * that printed `readouts.windowCaps` where it means `readouts.window` passes this and fails a
 * screenshot; the point of a guard is the class of defect it makes impossible, not the class it
 * makes unlikely.
 */
class EnergySourceGuardTest {

    @Test
    fun neitherRendererFormatsANumberOfItsOwn() {
        listOf(CLUSTER, STRIP).forEach { file ->
            val source = file.readText()
            BANNED.forEach { call ->
                assertEquals(
                    "${file.name} calls $call - the two renderers own geometry and nothing else",
                    0,
                    source.split(call).size - 1,
                )
            }
        }
    }

    /**
     * And the words that are left in them are the ones only that screen says.
     *
     * `ContourReadout` is the cluster's own vocabulary and the strip reads what it needs of it, so
     * a bare `VehiclePageWords.` in the strip is legitimate - for the two readings this page names
     * and the one unit the cluster has no room for. What is not legitimate is an *energy* string
     * there, which is what the three that left it were.
     */
    @Test
    fun theCarPageOnlyKeepsTheWordsThatAreItsOwn() {
        val source = STRIP.readText()
        val words = Regex("""VehiclePageWords\.(\w+)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("TITLE_VOLTS", "TITLE_SPEND", "UNIT_MV", "UNIT_V"), words)
        // The engine's cell, the direction of the pack's flow, the consumption and its window all
        // left that file for `EnergyReadouts`; the words file cannot quietly regrow them.
        val vocabulary = File(STRIP.parentFile, "VehiclePageWords.kt").readText()
        listOf("engineCell", "fun volts", "БАТАРЕ", "РАСХОД 0").forEach {
            assertTrue("«$it» is back in VehiclePageWords", !vocabulary.contains(it))
        }
    }

    private companion object {
        private val MAIN: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "apps/denza-apps/src/main/java/dev/denza/apps") }
            .firstOrNull { it.isDirectory }
            ?: error("the app's sources are not above ${System.getProperty("user.dir")}")

        val CLUSTER = File(MAIN, "feature/cluster/dashboard/ClusterDashboardRenderer.kt")
        val STRIP = File(MAIN, "feature/trip/VehiclePageRenderer.kt")

        /**
         * The three ways a renderer could print a number of its own.
         *
         * `ContourReadout`'s two formatters and Java's, which is what every hand-rolled figure in
         * this app has been. `ContourReadout.UNIT_KW`, `ContourReadout.whole` inside a `companion`
         * that names an *axis* rather than a reading (`AXIS_CEILING`) and the glyph constants are
         * not numbers a snapshot decides, so the ban is on the call rather than on the class.
         */
        val BANNED = listOf(
            "ContourReadout.whole(",
            "ContourReadout.tenth(",
            "ContourReadout.consumption(",
            "ContourReadout.perHundredKm(",
            "ContourReadout.windowCaps(",
            "ContourReadout.windowFoot(",
            "String.format(",
        )
    }
}
