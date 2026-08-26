package dev.denza.apps.feature.trip

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The analyser on `Main.dc.html`, read at test time, against the constants it is drawn from.
 *
 * The same join as `MainBoardContractTest` makes for the tile, for the same reason. The strip went
 * out looking nothing like the board while every number that mattered was written down on both
 * sides - the bars were the board's, the ramp was the board's, and the ramp was anchored to the
 * wrong thing, so two thirds of the columns drew in the dark end and the panel read as four loud
 * bars and a shadow. Numbers a test can compare are the ones that stop drifting.
 *
 * **What it cannot check.** How a bar moves. Attack, release, peak hold and the automatic gain are
 * not on the board and cannot be: the board is a still. Those are `SpectrumDynamicsTest`'s, and in
 * the end a car with music playing.
 */
class SpectrumBoardContractTest {

    @Test
    fun theBoardAndTheCodeCountTheSameColumns() {
        assertEquals(
            "columns",
            number("""hint-placeholder-count="(\d+)"""").toInt(),
            SpectrumSource.BAND_COUNT,
        )
    }

    @Test
    fun aColumnIsAsWideAsItsShareOfTheRow() {
        // The board writes a width and a gap; the code writes the ratio between them.
        val width = number("""<div style="width:([\d.]+)px; display:flex""")
        val gap = number("""align-items:flex-end; gap:([\d.]+)px; height:198px""")
        assertEquals(
            "bar width fraction",
            width / (width + gap),
            SpectrumRenderer.BAR_WIDTH_FRACTION.toDouble(),
            1e-3,
        )
    }

    @Test
    fun theCrownAndItsCornerAreTheBoards() {
        assertEquals(
            "peak marker height",
            number("""<div style="height:([\d.]+)px; background:#FFF8DA"""),
            SpectrumRenderer.PEAK_UNITS.toDouble(),
            1e-4,
        )
        assertEquals(
            "bar corner",
            number("""border-radius:([\d.]+)px 2px 0 0"""),
            SpectrumRenderer.BAR_RADIUS_UNITS.toDouble(),
            1e-4,
        )
    }

    @Test
    fun theRampReachesFullChampagneWhereTheBoardSaysItDoes() {
        // The board writes the champagne stop as a percentage from the foot; the code builds the
        // ramp from the crown down, so the two are one minus the other.
        val fromTheFoot = number("""#4A4222, #FEEFAB ([\d.]+)%""") / 100.0
        assertEquals("champagne stop", 1.0 - fromTheFoot, ACCENT_STOP.toDouble(), 1e-3)
    }

    @Test
    fun theReflectionIsCroppedAndFlatAsDrawn() {
        assertEquals(
            "reflection crop",
            number("""height:([\d.]+)px; overflow:hidden; opacity"""),
            SpectrumRenderer.REFLECT_UNITS.toDouble(),
            1e-4,
        )
        assertEquals(
            "reflection opacity",
            number("""overflow:hidden; opacity:([\d.]+);"""),
            SpectrumRenderer.REFLECT_ALPHA / 255.0,
            0.01,
        )
    }

    @Test
    fun theSegmentGridHasTheBoardsPitch() {
        val clear = number("""rgba\(7,8,10,0\) ([\d.]+)px, #07080A""")
        val pitch = number("""#07080A [\d.]+px, #07080A ([\d.]+)px""")
        assertEquals("scan pitch", pitch, SpectrumRenderer.SCAN_PITCH_UNITS.toDouble(), 1e-4)
        assertEquals(
            "dark fraction",
            (pitch - clear) / pitch,
            SpectrumRenderer.SCAN_DARK_FRACTION.toDouble(),
            1e-3,
        )
    }

    /**
     * The ticker is a typeface seen through a dot screen, and both numbers come from the board.
     *
     * There used to be a hand-written 5x7 matrix font here instead - nine kilobytes of binary
     * literals with its own Cyrillic set - which no test could have caught, because the board and
     * the code were not describing the same thing at all. They are now, so this can hold them.
     */
    @Test
    fun theTickerIsTheBoardsTypeAndTheBoardsDotScreen() {
        val size = number("""\.led \{[^}]*font-size:([\d.]+)px""")
        val tracking = number("""\.led \{[^}]*letter-spacing:([\d.]+)px""")
        val cell = number("""\.led \{[^}]*background-size:([\d.]+)px""")

        assertEquals("size", size, SpectrumRenderer.LED_SIZE_UNITS.toDouble(), 1e-4)
        assertEquals(
            "tracking",
            tracking / size,
            SpectrumRenderer.LED_TRACKING_EM.toDouble(),
            1e-4,
        )
        assertEquals("dot cell", cell, SpectrumRenderer.LED_DOT_CELL_UNITS.toDouble(), 1e-4)
    }

    /**
     * The play mark is gone from both, or from neither.
     *
     * It was the board's own `M7 4l12 8-12 8z`, and the code matched it to the unit after a live
     * comparison caught it four dp adrift. The owner then looked at the finished thing and said it
     * was superfluous, which it was: a title that scrolls does not need a glyph to say it is a
     * title. This fails if it comes back to one side only.
     */
    @Test
    fun theBoardDrawsNoPlayMarkBesideTheTitle() {
        assertEquals(
            "play mark on the board",
            false,
            BOARD.readText().contains("M7 4l12 8-12 8z"),
        )
    }

    private fun number(pattern: String): Double =
        Regex(pattern).find(BOARD.readText())?.groupValues?.get(1)?.toDouble()
            ?: error("the board has nothing matching $pattern")

    private companion object {
        /** Where the code's ramp puts full champagne, measured from the crown. */
        const val ACCENT_STOP = 0.38f

        val BOARD: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .map { File(it, "tools/design-canvas/Main.dc.html") }
            .firstOrNull { it.isFile }
            ?: error("Main.dc.html not found above ${System.getProperty("user.dir")}")
    }
}
