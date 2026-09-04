package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.design.instrument.InstrumentPen

/**
 * How wide a string comes out, in the panel's own units.
 *
 * [ContourPlan] needs this and nothing else about type, which is what keeps the whole geometry
 * testable on a JVM with no `Paint` in it. There are two implementations and they are two records of
 * one measurement:
 *
 *  - [BOARD] is what headless Chrome measured on 2026-09-04 for `gen_contour.py`, checked at 18, 180
 *    and 360 px and agreeing to 0.01, so the numbers are the face's own advances rather than a
 *    rounding of one size. `ContourBoardContractTest` builds the plan from it and holds every
 *    coordinate against the drawn board;
 *  - [of] is the car's own `Paint`, measured once when the view is sized.
 *
 * **Why both.** A cell on either shelf is exactly as wide as the wider of its caption and its
 * payload, so a caption is a coordinate here. The board is the record of that decision; the car is
 * where it has to be true. If the framework resolves a face whose «РАЗБРОС ЯЧЕЕК» is two units
 * wider, the cell should be two units wider - that is the rule working, not failing. What must not
 * drift is the *arithmetic*, and that is what the contract test pins.
 */
internal fun interface ContourType {

    /** The advance of [text] set in [face], in virtual panel units. */
    fun width(text: String, face: InstrumentFace): Float

    companion object {

        /** A Roboto Regular digit, as a share of its type size. */
        const val DIGIT_EM = 0.5620f

        /** And Light, which is narrower, and which only the hero is set in. */
        const val DIGIT_LIGHT_EM = 0.5547f

        /** A comma is a fifth of a digit here. A monospaced face gave it a whole cell. */
        const val COMMA_EM = 0.1969f

        /** And a colon a quarter, which is what «2:15» needed. */
        const val COLON_EM = 0.2422f

        /**
         * Every word the panel draws, at the face it is drawn in, as Chrome measured it.
         *
         * Only the strings a coordinate depends on are here - a unit hanging off a field, a caption
         * deciding how wide its cell must be. A number is not in this table: it lives in a reserve
         * field sized by its digit count, which is the whole point.
         *
         * Measured and deliberately absent: «ВЕРНУЛА РЕКУПЕРАЦИЯ» is 253.5469 against
         * «РЕКУПЕРАЦИЯ»'s 150.6719, which puts the three seats of a car standing on P at 629.7 and
         * their left edge 41 units *inside* the hero's own «кВт». That is why the middle caption
         * keeps its dot instead of taking the verb the other two got.
         *
         * The engine's two sentences came from a run of the same method on the day the panel first
         * ran on a bench, and that run put every Cyrillic *word* between 0.9 and 2.4 units under the
         * seventh pass's table while every digit, the comma, the colon and «°» reproduced exactly -
         * Google's Roboto moving under one name rather than a second method. It is inside the two
         * per cent this table is allowed to differ from the car's own face by, and the only thing it
         * decides is whether that one phrase keeps its long window.
         */
        val WORDS: Map<Pair<InstrumentFace, String>, Float> = mapOf(
            (InstrumentFace.READING to ContourReadout.UNIT_KW) to 55.9219f,
            (InstrumentFace.READING to ContourReadout.DEGREE) to 12.7031f,
            (InstrumentFace.READING to ContourReadout.UNIT_MILLIVOLT) to 46.4063f,
            (InstrumentFace.UNIT to ContourReadout.UNIT_KW) to 29.6094f,
            (InstrumentFace.UNIT to ContourReadout.UNIT_KWH) to 44.1094f,
            (InstrumentFace.UNIT to ContourReadout.UNIT_KM) to 23.0938f,
            (InstrumentFace.UNIT to ContourReadout.UNIT_PER_100KM) to 184.1094f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_PACK) to 91.9219f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_MOTORS) to 90.6250f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_INVERTER) to 110.0000f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_SPREAD) to 167.7344f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_REGEN) to 150.6719f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_ENGINE_GAVE) to 94.3750f,
            (InstrumentFace.CAPTION to ContourReadout.CAPTION_TRIP) to 144.5469f,
            (InstrumentFace.CAPTION to ContourReadout.LEGEND_INTO_PACK) to 341.7813f,
            (InstrumentFace.CAPTION to ContourReadout.LEGEND_INTO_PACK_SHORT) to 204.7188f,
            (InstrumentFace.HEADING to ContourReadout.TITLE_PACK) to 126.0781f,
            (InstrumentFace.HEADING to ContourReadout.TITLE_ENGINE_RPM) to 137.9844f,
            (InstrumentFace.HEADING to ContourReadout.TITLE_ENGINE_MINUTES) to 224.9375f,
        )

        /** The board's own measurement, which is the record the code is held against. */
        val BOARD: ContourType = ContourType { text, face ->
            when {
                text.length == 1 && text[0].isDigit() ->
                    face.size * if (face == InstrumentFace.HERO) DIGIT_LIGHT_EM else DIGIT_EM

                text == "," -> face.size * COMMA_EM
                text == ":" -> face.size * COLON_EM
                else -> WORDS[face to text]
                    ?: error("«$text» at $face was never measured on the board")
            }
        }

        /** The car's own face, measured through the pen the panel is drawn with. */
        fun of(pen: InstrumentPen): ContourType = ContourType(pen::widthOf)
    }
}
