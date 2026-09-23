package dev.denza.apps.feature.split

import java.io.File
import kotlin.math.floor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SplitCrewScene] against `tools/design-canvas/split-crew/split-crew.html`, the page the owner
 * approved, constant by constant.
 *
 * The page draws from its own `const`s and the app from the scene's, so this is the join between
 * them, as `LuminoforSpecContractTest` is for Luminofor: a number changed on one side fails here
 * until the other side has moved in the same change. The page is read as text - its declarations,
 * its colour stops, the few numbers `drawShield` writes inline - not run.
 */
class SplitCrewBoardContractTest {

    private val page: String by lazy { repoFile("tools/design-canvas/split-crew/split-crew.html") }

    /**
     * A file of the repository, found the way the Luminofor tests find `spec.json` (`SpecJson`):
     * by walking up from the test's working directory until the path appears.
     */
    private fun repoFile(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("$path not found above ${System.getProperty("user.dir")}")
    }

    /** The page's `const NAME = <number>` or `, NAME = <number> [* U]`, evaluated. */
    private fun js(name: String): Double {
        val m = Regex("""(?:const\s+|,\s*)$name\s*=\s*(-?[0-9.]+)(\s*\*\s*U)?\s*[,;]""").find(page)
            ?: error("split-crew.html declares no $name")
        val v = m.groupValues[1].toDouble()
        return if (m.groupValues[2].isNotEmpty()) v * js("U") else v
    }

    private fun same(name: String, actual: Double) = assertEquals(name, js(name), actual, 0.0)

    private fun rgbArray(name: String): IntArray {
        val m = Regex("""$name = \[(\d+), (\d+), (\d+)]""").find(page) ?: error("no $name")
        return IntArray(3) { m.groupValues[it + 1].toInt() }
    }

    private fun jsRound(v: Double) = floor(v + 0.5).toInt()

    @Test
    fun theScreenAndTheSite() {
        same("W", SplitCrewScene.W)
        same("H", SplitCrewScene.H)
        same("FLOOR", SplitCrewScene.FLOOR)
        same("RAIL_T", SplitCrewScene.RAIL_T)
        same("RAIL_B", SplitCrewScene.RAIL_B)
        same("CX", SplitCrewScene.CX)
        same("CY", SplitCrewScene.CY)
        same("NARROW_LEFT", SplitCrewScene.NARROW_LEFT)
        same("NARROW_RIGHT", SplitCrewScene.NARROW_RIGHT)
    }

    @Test
    fun theDividersClock() {
        same("T_MOVE", SplitCrewScene.T_MOVE)
        same("CYCLE", SplitCrewScene.CYCLE)
        assertTrue(page.contains("if (u < 900) return leg(CX, NARROW_LEFT, 0, 900);"))
        assertTrue(page.contains("if (u < 2900) return leg(NARROW_LEFT, NARROW_RIGHT, 1100, 1800);"))
        assertTrue(page.contains("return leg(NARROW_RIGHT, CX, 3100, 900);"))
    }

    @Test
    fun thePeople() {
        same("U", SplitCrewScene.U)
        same("THIGH", SplitCrewScene.THIGH)
        same("SHIN", SplitCrewScene.SHIN)
        same("TORSO", SplitCrewScene.TORSO)
        same("NECK", SplitCrewScene.NECK)
        same("HEAD_R", SplitCrewScene.HEAD_R)
        same("UPPER", SplitCrewScene.UPPER)
        same("FORE", SplitCrewScene.FORE)
        assertTrue(page.contains("REACH = UPPER + FORE"))
    }

    @Test
    fun theCraneAndItsSignalman() {
        same("PILE_X", SplitCrewScene.PILE_X)
        same("STACK_X", SplitCrewScene.STACK_X)
        same("BW", SplitCrewScene.BW)
        same("BH", SplitCrewScene.BH)
        same("SLING", SplitCrewScene.SLING)
        same("HOOK_TOP", SplitCrewScene.HOOK_TOP)
        same("JIB_T", SplitCrewScene.JIB_T)
        same("JIB_B", SplitCrewScene.JIB_B)
        same("MAST", SplitCrewScene.MAST)
        same("CRANE_LEAD", SplitCrewScene.CRANE_LEAD)
        same("CRANE_CYCLE", SplitCrewScene.CRANE_CYCLE)
        assertTrue(page.contains("const P = pose({ x: ${SplitCrewScene.SIGNALMAN_X.toInt()}, f, lean: 0, nod: -0.12 });"))
    }

    @Test
    fun theFrameTheLadderAndTheHammer() {
        same("LADDER_X", SplitCrewScene.LADDER_X)
        same("LADDER_TOP", SplitCrewScene.LADDER_TOP)
        same("RUNG", SplitCrewScene.RUNG)
        same("LADDER_HALF", SplitCrewScene.LADDER_HALF)
        same("T_HAM", SplitCrewScene.T_HAM)
        same("HAM_PERIOD", SplitCrewScene.HAM_PERIOD)
        same("B_HIT", SplitCrewScene.B_HIT)
        same("B_UP", SplitCrewScene.B_UP)
        same("HAND_R", SplitCrewScene.HAND_R)
        same("HANDLE", SplitCrewScene.HANDLE)
        val rungs = Regex("""for \(const y of \[([^\]]+)]\)""").find(page)!!.groupValues[1]
            .split(",").map { it.trim().let { v -> if (v == "RUNG") js("RUNG") else v.toDouble() } }
        assertArrayEquals(rungs.toDoubleArray(), SplitCrewScene.RUNGS, 0.0)
        assertTrue(page.contains("fw = ${SplitCrewScene.FRAME_W.toInt()},"))
        assertTrue(page.contains("ft = fb - ${SplitCrewScene.FRAME_H.toInt()},"))
    }

    @Test
    fun theThreeBlues() {
        assertArrayEquals(rgbArray("HALO"), SplitCrewScene.HALO)
        assertArrayEquals(rgbArray("CORE"), SplitCrewScene.CORE)
        assertArrayEquals(rgbArray("CROWN"), SplitCrewScene.CROWN)
    }

    /** The field's stops, each `rgb(<colour>, k)` worked out as the page's `rgb()` and `mix()` do. */
    @Test
    fun theField() {
        val colours = mapOf("HALO" to rgbArray("HALO"), "CORE" to rgbArray("CORE"))
        val stops = Regex("""field\.addColorStop\(([0-9.]+), rgb\((.+?), ([0-9.]+)\)\);""").findAll(page).toList()
        assertEquals(SplitCrewScene.FIELD_STOPS.size, stops.size)
        stops.forEachIndexed { i, m ->
            assertEquals("field stop $i", m.groupValues[1].toDouble(), SplitCrewScene.FIELD_STOPS[i], 0.0)
            val expr = m.groupValues[2]
            val k = m.groupValues[3].toDouble()
            val c: DoubleArray = Regex("""mix\((\w+), (\w+), ([0-9.]+)\)""").matchEntire(expr)?.let { mix ->
                val a = colours.getValue(mix.groupValues[1])
                val b = colours.getValue(mix.groupValues[2])
                val t = mix.groupValues[3].toDouble()
                DoubleArray(3) { a[it] + (b[it] - a[it]) * t }
            } ?: colours.getValue(expr).let { rgb -> DoubleArray(3) { rgb[it].toDouble() } }
            val expected = (0xFF shl 24) or (jsRound(c[0] * k) shl 16) or (jsRound(c[1] * k) shl 8) or jsRound(c[2] * k)
            assertEquals("field colour $i", expected, SplitCrewScene.FIELD_COLORS[i])
        }
        assertTrue(page.contains("createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, HALF_DIAG)"))
        assertTrue(page.contains("const HALF_DIAG = Math.hypot(W / 2, H / 2);"))
    }

    @Test
    fun theHaze() {
        val stops = Regex("""haze\.addColorStop\(([0-9.]+), rgba\(HALO, ([0-9.]+)\)\);""").findAll(page).toList()
        assertEquals(SplitCrewScene.HAZE_STOPS.size, stops.size)
        stops.forEachIndexed { i, m ->
            assertEquals("haze stop $i", m.groupValues[1].toDouble(), SplitCrewScene.HAZE_STOPS[i], 0.0)
            assertEquals("haze alpha $i", m.groupValues[2].toDouble(), SplitCrewScene.HAZE_ALPHAS[i], 0.0)
        }
        val r = SplitCrewScene.HAZE_RADIUS.toInt()
        assertTrue(page.contains("haze = ctx.createRadialGradient(0, 0, 0, 0, 0, $r);"))
        assertTrue(page.contains("ctx.scale(1, ${SplitCrewScene.HAZE_SQUASH});"))
        assertTrue(page.contains("ctx.fillRect(-$r, -$r, ${2 * r}, ${2 * r});"))
        assertTrue(page.contains("ctx.globalAlpha = prog(t, 0, ${SplitCrewScene.HAZE_IN_MS.toInt()}, decelerate);"))
    }

    @Test
    fun theRevealAndTheCaption() {
        val open = "const open = prog(t, 0, ${SplitCrewScene.REVEAL_MS.toInt()}, decelerate), " +
            "rr = ${SplitCrewScene.REVEAL_FROM.toInt()} + ${SplitCrewScene.REVEAL_GROWTH.toInt()} * open;"
        assertTrue(page.contains(open))
        assertTrue(page.contains("ctx.fillText(CAPTION, W / 2, ${SplitCrewScene.CAPTION_Y.toInt()});"))
        assertTrue(page.contains("ctx.font = '400 ${SplitCrewScene.CAPTION_SIZE.toInt()}px Roboto"))
        assertTrue(page.contains("ctx.fillStyle = \"rgba(255,255,255,${SplitCrewScene.CAPTION_WHITE})\";"))
        assertTrue(page.contains("ctx.globalAlpha = prog(t, 0, ${SplitCrewScene.CAPTION_IN_MS.toInt()});"))
        assertTrue(page.contains("ctx.textBaseline = \"middle\";"))
    }

    /** The page's words are the app's string: the caption on the page is the one the shield shows. */
    @Test
    fun theCaptionIsTheAppsString() {
        val caption = Regex("""const CAPTION = "([^"]+)";""").find(page)!!.groupValues[1]
        val strings = repoFile("apps/denza-apps/src/main/res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"split_launch_overlay_text\">$caption</string>"))
    }

    @Test
    fun theEasing() {
        assertTrue(page.contains("const standard = cubicBezier(0.4, 0, 0.2, 1);"))
        assertTrue(page.contains("const decelerate = cubicBezier(0.05, 0.7, 0.1, 1);"))
        assertTrue(page.contains("for (let i = 0; i < ${SplitCrewScene.BISECTIONS}; i++)"))
    }
}
