package dev.denza.apps.ui.components

/**
 * How a tile reads at a glance, before any word on it is read.
 *
 * The dashboard is looked at from a driver's seat and mostly not read at all - what it has to
 * answer first is "is anything wrong", and the answer has to arrive as colour and weight, not as a
 * sentence. Five tones is what that needs and no more:
 *
 * Luminofor fixes the two ends. A live tile is the lit plate with its glyph in the dock's blue and a
 * faint halo off it; an idle tile is the dark plate with a grey glyph and no halo (see [TileFace]).
 * The middle three are the states the old screen could only say in words: working keeps the lit
 * face and turns a ring beside the glyph, and the other two light the glyph and the status in the
 * vehicle's own two alarm colours - orange when the car is waiting for a decision from the driver,
 * red when something is actually broken.
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
         * One rule: a feature nothing can be done to keeps its words and gives up its light. It is
         * [IDLE] as far as the drawing is concerned - the dark plate, the grey glyph, no ring
         * turning, the words at the dark face's intensities - because being unreachable reads the
         * same as being off from a driver's seat, and a lit plate nothing answers on is a promise.
         */
        fun shown(tone: DenzaTileTone, enabled: Boolean): DenzaTileTone =
            if (enabled) tone else IDLE
    }
}
