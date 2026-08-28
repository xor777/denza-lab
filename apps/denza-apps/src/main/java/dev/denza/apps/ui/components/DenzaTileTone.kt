package dev.denza.apps.ui.components

/**
 * How a tile reads at a glance, before any word on it is read.
 *
 * The dashboard is looked at from a driver's seat and mostly not read at all - what it has to
 * answer first is "is anything wrong", and the answer has to arrive as colour and weight, not as a
 * sentence. Five tones is what that needs and no more:
 *
 * The design boards fix the two ends. A live tile is the raised surface with a champagne edge and a
 * faint bloom off it; an idle tile is the quiet surface with a hairline. The middle three are the
 * states the old screen could only say in words, and they say them in the vehicle's own two alarm
 * colours: amber when the car is waiting for a decision from the driver, coral when something is
 * actually broken.
 *
 * This is a plain enum on purpose. The mapping from a feature's state to its tone is policy worth
 * testing, and policy that needs a Compose runtime to test is policy that stops being tested.
 */
enum class DenzaTileTone {

    /** Doing its job right now. */
    LIVE,

    /** Off, and nothing is wrong with that. */
    IDLE,

    /** Starting or recovering: the tile is busy and the driver need do nothing. */
    WORKING,

    /** Waiting for a decision only the driver can make. Amber. */
    ATTENTION,

    /** Broken, or not available on this car at all. Coral. */
    BROKEN,
    ;

    companion object {

        /**
         * What a feature reads as when it cannot be touched.
         *
         * The tile and the chip are one object and had two different answers to this. The tile
         * greyed the name and kept the accent glyph; the chip greyed the glyph and kept the dot lit.
         * So the same feature behind the same ADB gate looked switched off on the full screen and
         * running in a pane - and the one place both are drawn at once is a screenshot nobody takes.
         *
         * One rule: a feature nothing can be done to keeps its words and gives up its colour. It is
         * [IDLE] as far as the drawing is concerned - quiet surface, hairline edge, muted glyph,
         * muted dot, no ring turning - and the name stays in full ink, because being unreachable is
         * not a reason to stop saying which tile this is.
         */
        fun shown(tone: DenzaTileTone, enabled: Boolean): DenzaTileTone =
            if (enabled) tone else IDLE
    }
}
