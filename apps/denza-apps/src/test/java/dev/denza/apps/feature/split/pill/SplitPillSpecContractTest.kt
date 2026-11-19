package dev.denza.apps.feature.split.pill

import dev.denza.apps.design.luminofor.SpecJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pill's numbers and the design's `split-pill/spec.json` are one (contract «К 1.15»): every
 * value [SplitPillSpec] carries is the value at its path in the spec, and the spec's own derived
 * values hold among the numbers the code reads. A board that moves without the code, or the code
 * without the board, fails here.
 */
class SplitPillSpecContractTest {

    private val spec: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        SpecJson.parse(SpecJson.read("split-pill/spec.json")) as Map<String, Any?>
    }

    private fun at(path: String): Any? {
        var v: Any? = spec
        for (key in path.split('.')) {
            v = when (v) {
                is Map<*, *> -> if (v.containsKey(key)) v[key] else fail("$path: no $key in spec.json")
                is List<*> -> v.getOrNull(key.toInt()) ?: fail("$path: no element $key in spec.json")
                else -> fail("$path: not a container at $key")
            }
        }
        return v
    }

    private fun num(path: String): Double = (at(path) as? Number)?.toDouble() ?: error("$path is not a number")

    @Test
    fun everyValueTheCodeReadsIsTheSpecs() {
        val wrong = mutableListOf<String>()
        val read = SplitPillSpec.KEYS.map { it.first }.toSet()
        SplitPillSpec.KEYS.forEach { (path, value) ->
            val expected = at(path)
            val same = when (value) {
                is Double -> expected is Number && kotlin.math.abs(expected.toDouble() - value) < 1e-9
                is Int -> expected is String && rgba(expected) == value
                is String -> expected == value
                // A colour named by another key: the name is the spec's and the ARGB is that colour's.
                is PillColorRef -> expected == value.name && rgba(at("color.${value.name}") as String) == value.argb
                // A spring is exactly [ms, z]: a third number would be a value the code drops.
                is PillSpringSpec -> expected is List<*> && expected.size == 2 &&
                    (expected[0] as Number).toDouble() == value.ms && (expected[1] as Number).toDouble() == value.z
                // A tween's every field is read: the four the type carries, any other as a key of its own.
                is PillTween -> expected is Map<*, *> &&
                    (expected["ms"] as Number).toDouble() == value.ms &&
                    expected["ease"] == value.ease &&
                    ((expected["delay"] as? Number)?.toDouble() ?: 0.0) == value.delay &&
                    ((expected["stagger"] as? Number)?.toDouble() ?: 0.0) == value.stagger &&
                    expected.keys.all { field -> field in TWEEN_FIELDS || "$path.$field" in read }
                else -> false
            }
            if (!same) wrong += "$path: code $value, spec $expected"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun theShadowsAreDrawnInTheColoursTheSpecNames() {
        // `pill.lift[i][3]` names a colour; the code's shadow colour is the one it names, not a guess.
        assertEquals(SplitPillSpec.Color.LIFT, SplitPillSpec.Pill.LIFT_COLOR)
        assertEquals(SplitPillSpec.Color.LIFT_TIGHT, SplitPillSpec.Pill.LIFT_TIGHT_COLOR)
        assertEquals(4, (at("pill.lift.0") as List<*>).size)
        assertEquals(4, (at("pill.lift.1") as List<*>).size)
        assertEquals("two shadows, no third", 2, (at("pill.lift") as List<*>).size)
    }

    @Test
    fun theKeysAreDistinctAndNoneIsMissed() {
        val paths = SplitPillSpec.KEYS.map { it.first }
        assertEquals(paths.distinct(), paths)
        // Every section of spec.json is read whole: a key added anywhere must be read, or listed in
        // [UNREAD] with its reason.
        val leaves = mutableListOf<String>()
        fun walk(prefix: String, v: Any?) {
            if (v is Map<*, *>) v.forEach { (k, x) -> walk("$prefix.$k", x) } else leaves += prefix
        }
        spec.forEach { (section, v) -> if (section != "about") walk(section, v) }
        val read = paths.toSet()
        fun covered(leaf: String, set: Set<String>) =
            leaf in set || set.any { leaf.startsWith("$it.") } || set.any { it.startsWith("$leaf.") }
        val missed = leaves.filter { leaf -> !covered(leaf, read) && !covered(leaf, UNREAD.keys) }
        assertEquals("keys of spec.json the pill does not read", emptyList<String>(), missed)
        val stale = UNREAD.keys.filter { key -> covered(key, read) || leaves.none { it == key || it.startsWith("$key.") } }
        assertEquals("keys listed as unread that are read, or are not in spec.json", emptyList<String>(), stale)
    }

    @Test
    fun theSpecsDerivedNumbersHold() {
        // The disc lies `rise` above the panes' bottom line, centred at `cy`.
        assertEquals(num("stage.paneTop") + num("stage.paneH") - num("handle.rise"), SplitPillSpec.Handle.BOTTOM, 1e-9)
        assertEquals(SplitPillSpec.Handle.BOTTOM - SplitPillSpec.Handle.H / 2, num("handle.cy"), 1e-9)
        // The handle is the pill at rest: a capsule as tall as it is wide.
        assertEquals(SplitPillSpec.Handle.W, SplitPillSpec.Handle.H, 0.0)
        // The finger's travel is the top edge's way from the disc to the open pill.
        assertEquals(
            (SplitPillSpec.Handle.BOTTOM - SplitPillSpec.Handle.H) -
                (SplitPillSpec.Pill.OPEN_BOTTOM - SplitPillSpec.Pill.OPEN_H),
            SplitPillSpec.Pull.TRAVEL,
            1e-9,
        )
        assertEquals("linear", at("pull.width"))
        assertEquals(listOf(SplitPillSpec.Stage.DETENT_NARROW_LEFT, SplitPillSpec.Stage.DETENT_WIDE_LEFT), (at("stage.detents") as List<*>).map { (it as Number).toDouble() })
    }

    @Test
    fun theRememberedPairsAreAsManyAsTheBoardKeeps() {
        // `row.recents` and the memory's own bound are one number (1.14): the memory keeps what the
        // board's row may show among the recent pairs.
        assertEquals(SplitPillSpec.Row.RECENTS.toInt(), dev.denza.apps.feature.split.SplitMemory.RECENT_LIMIT)
    }

    @Test
    fun theGlyphsAreThePages() {
        val page = SpecJson.read("split-pill/split-pill.html")
        assertTrue("the plus is the page's", page.contains(PillGlyphs.PLUS))
        assertTrue("the star is the page's", page.contains(PillGlyphs.STAR))
    }

    @Test
    fun everyEaseTheCodeNamesExists() {
        val names = SplitPillSpec.KEYS.mapNotNull { (_, v) -> (v as? PillTween)?.ease }.toSet()
        names.forEach { name -> PillEase.named(name).at(0.5) }
    }

    private companion object {
        val TWEEN_FIELDS = setOf("ms", "ease", "delay", "stagger")

        /**
         * The keys of spec.json the app does not read, each with its reason. "Slice 1 should" marks a
         * key the shipped behaviour would read if it drew that detail as the board does; the rest
         * belong to parts of the board that are not the shipped pill.
         */
        val UNREAD: Map<String, String> = mapOf(
            // The stand around the pill: the board's screen, panes and divider, not the pill.
            "stage.paneTop" to "derived: held by theSpecsDerivedNumbersHold",
            "stage.paneH" to "derived: held by theSpecsDerivedNumbersHold",
            "stage.edge" to "the board's stand", "stage.gap" to "the board's stand",
            "stage.paneRadius" to "the board's stand", "stage.grip" to "the board's stand",
            "stage.retire" to "the board's stand", "stage.motion" to "the board's stand",
            // Derived numbers, held by theSpecsDerivedNumbersHold.
            "handle.rise" to "derived", "handle.cy" to "derived", "pull.width" to "\"linear\", held below",
            // Eases written as formulas; SplitPillMotionTest.theEasesAreThePages holds PillEase to them.
            "ease.out" to "formula", "ease.outQ" to "formula", "ease.inOut" to "formula",
            // The board's alternative glass (window blur), not the product's.
            "pill.blur" to "alternative glass", "color.glass-blur" to "alternative glass",
            // Edit mode (1.15.8), the flights and the launch placeholders: not in slice 1.
            "pill.edit" to "edit", "row.editPad" to "edit", "ring" to "edit", "badge.minus" to "edit",
            "drag" to "edit", "press.lift" to "edit", "spring.edit" to "edit", "spring.lift" to "edit",
            "color.red" to "edit", "color.drag-drop" to "edit",
            "motion.unpin" to "edit", "motion.unpinBadge" to "edit", "motion.lift" to "edit",
            "motion.ring" to "edit", "motion.ringOut" to "edit",
            "timer.edit" to "edit", "timer.liftAt" to "edit", "timer.longPress" to "edit",
            "timer.unpinEnter" to "edit",
            "fly" to "flights", "color.fly-drop" to "flights", "motion.flight" to "flights",
            "motion.oldDim" to "flights", "motion.ringFade" to "flights", "motion.pulse" to "flights",
            "motion.appIn" to "flights", "motion.dissolve" to "flights", "motion.flyOut" to "flights",
            "timer.launchHold" to "launch placeholder",
            // Slice 1 should: details of the shipped pill the app draws its own way or not at all.
            "chip.maskBox" to "slice 1 should: the chip layer pads its mask by 1 dp instead",
            "ghost.tail" to "slice 1 should: the contour's dash is not phased to end in a 20 dp tail",
            "ghost.glowBox" to "slice 1 should: the contour's glow is drawn in its own bounds",
            "ghost.glyphFrom" to "slice 1 should: the contour's glyph (the icon tinted blue) is not faded in from 0.35",
            "apps.mark.glow" to "slice 1 should: the apps row's mark is drawn without a glow (the spec's is 0)",
            "press.tapGrow" to "slice 1 should: a tapped item does not grow to 1.015 on release",
            "press.tapGrowAt" to "slice 1 should: as press.tapGrow",
            "motion.tapPress" to "slice 1 should: as press.tapGrow",
            "motion.tapRelease" to "slice 1 should: as press.tapGrow",
            "enter.badgePeak" to "slice 1 should: the badge enters on badgeIn without the 1.22 keyframe",
            "enter.badgePeakAt" to "slice 1 should: as enter.badgePeak",
            "enter.badgeOrigin" to "slice 1 should: as enter.badgePeak",
        )
    }

    private fun rgba(text: String): Int {
        val t = text.trim()
        if (t.startsWith("#")) {
            var h = t.substring(1)
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            return (0xFF000000L or h.toLong(16)).toInt()
        }
        val p = t.substringAfter('(').substringBefore(')').split(',').map { it.trim().toDouble() }
        val a = Math.round((if (p.size > 3) p[3] else 1.0) * 255)
        return ((a shl 24) or (p[0].toLong() shl 16) or (p[1].toLong() shl 8) or p[2].toLong()).toInt()
    }
}
