package dev.denza.apps.design.luminofor

/**
 * The Luminofor numbers, as the app reads them.
 *
 * `tools/design-canvas/luminofor/spec.json` is the design, approved by the owner as a live page on
 * 2026-09-23 and frozen there as a board: `luminofor.js` draws every board from that file, and
 * `LuminoforSpecContractTest` holds every value below to it. Change one side and the test fails
 * until the other has moved too - the same rule the older boards and their contract tests kept.
 *
 * Units: the head unit is laid out in dp on its 1280 x 680 window (panes 828 and 416 wide), the
 * cluster in its own units on 1507.5556 x 424 laid over the 2560 x 720 display. A [Light] is a
 * pair: the halo tints glow, the core is what a stroke or a fill actually is.
 */
object LuminoforSpec {

    class Light(val halo: Int, val core: Int)

    /** Everything on both screens is added, not painted over: `lighter` on the board, PLUS here. */
    const val COMPOSITING_ADDITIVE: Boolean = true

    const val BACKGROUND: Int = 0xFF000000.toInt()

    object ClusterInk {
        val INK = Light(0xFFDAE1EB.toInt(), 0xFFECF1F7.toInt())
        val GREY = Light(0xFF86909B.toInt(), 0xFF86909B.toInt())
        val BLUE = Light(0xFF2D82D7.toInt(), 0xFF78B2EC.toInt())
        val ORANGE = Light(0xFFFF9F19.toInt(), 0xFFFFC882.toInt())
        val RED = Light(0xFFFF4046.toInt(), 0xFFFFA0A0.toInt())
    }

    object HeadInk {
        val WHITE = Light(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())

        /** The dock's own active icon, sampled off the car: `#439FFC`. */
        val BLUE = Light(0xFF439FFC.toInt(), 0xFF80BEFF.toInt())
        const val CARD_ON: Int = 0xFF2C2B33.toInt()
        const val CARD_OFF: Int = 0xFF17161B.toInt()
        const val CROWN: Int = 0xF2C8E4FF.toInt()
        const val HANDLE: Int = 0x59FFFFFF
        const val DOT_ON: Int = 0xFFFFFFFF.toInt()
        const val DOT_OFF: Int = 0x47FFFFFF
    }

    object Type {
        const val CLUSTER_WEIGHT: Int = 500
        const val HEAD_WEIGHT: Int = 400
        const val HEAD_STRONG: Int = 500
    }

    object Digits {
        const val CAP_RATIO: Float = 0.71f
        const val CAP: Float = 100f
        const val TRACK: Float = 18f
        const val STROKE_PER_SIZE: Float = 0.075f
        const val STROKE_MIN: Float = 2.2f
        const val HERO_STROKE: Float = 4.2f

        /** Advance (without tracking) and the stroked path, on a 100-unit cap. */
        val GLYPHS: Map<Char, Pair<Float, String>> = linkedMapOf(
            '0' to (70f to "M22 0H48A22 22 0 0 1 70 22V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78V22A22 22 0 0 1 22 0Z"),
            '1' to (70f to "M20 20L44 0V100"),
            '2' to (70f to "M0 22A22 22 0 0 1 22 0H48A22 22 0 0 1 70 22V34Q70 48 56 56L0 100H70"),
            '3' to (70f to "M4 0H66L32 42H48A22 22 0 0 1 70 64V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78"),
            '4' to (70f to "M52 100V0L0 68H70"),
            '5' to (70f to "M66 0H4L2 44H48A22 22 0 0 1 70 66V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78"),
            '6' to (70f to "M62 4Q52 0 36 0H22A22 22 0 0 0 0 22V78A22 22 0 0 0 22 100H48A22 22 0 0 0 70 78V66A22 22 0 0 0 48 44H0"),
            '7' to (70f to "M0 0H70L24 100"),
            '8' to (70f to "M22 0H48A18 18 0 0 1 66 18V28A18 18 0 0 1 48 46H22A18 18 0 0 1 4 28V18A18 18 0 0 1 22 0ZM22 46H48A22 22 0 0 1 70 68V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78V68A22 22 0 0 1 22 46Z"),
            '9' to (70f to "M8 96Q18 100 34 100H48A22 22 0 0 0 70 78V22A22 22 0 0 0 48 0H22A22 22 0 0 0 0 22V34A22 22 0 0 0 22 56H70"),
            ',' to (14f to "M9 94L4 112"),
            '.' to (14f to "M7 99.6L7 100"),
            ':' to (16f to "M8 32.6L8 33M8 84.6L8 85"),
            '°' to (34f to "M4 14A12 12 0 1 1 28 14A12 12 0 1 1 4 14Z"),
            '+' to (56f to "M28 34V78M6 56H50"),
            '-' to (50f to "M5 56H45"),
            ' ' to (30f to ""),
        )
    }

    object Cluster {
        const val DISPLAY_W: Int = 2560
        const val DISPLAY_H: Int = 720
        const val W: Float = 1507.5556f
        const val H: Float = 424f
        const val AXIS_X: Float = W / 2f
        const val MARGIN: Float = 48f

        const val STOCK_TOP: Float = 160.1778f
        const val STOCK_BOTTOM: Float = 335.6667f
        const val LEFT_APERTURE_RX: Float = 361.5778f
        const val RIGHT_APERTURE_RX: Float = 301.5111f
        const val PETAL_RX: Float = 353.3333f
        const val PETAL_RY: Float = 194.3333f
        const val PETAL_CY: Float = 353.3333f

        object Grid {
            const val CAPTION: Float = 197f
            const val GLYPH_BASE: Float = 207f
            const val BASELINE: Float = 246.6578f
            const val AXIS: Float = 296f
            const val SIDE: Float = 200f
            const val TEMP_PITCH: Float = 72f
            const val HERO_SIZE: Float = 88f
            const val HERO_UNIT_GAP: Float = 32f
            const val HERO_UNIT_SIZE: Float = 34f
            const val FIGURE_SIZE: Float = 52f
            const val TEMP_SIZE: Float = 34f
            const val CAPTION_SIZE: Float = 17f
            const val CAPTION_TRACK: Float = 0.12f
            const val CELL_CAPTION_TRACK: Float = 0.08f
            const val UNIT_SIZE: Float = 17f
            const val UNIT_GAP: Float = 8f
            const val DETAIL_SIZE: Float = 15f
            const val DETAIL_TRACK: Float = 0.06f
            const val DETAIL_DROP: Float = 30f
        }

        object Band {
            const val OUT_KW: Float = 300f
            const val IN_KW: Float = 100f
            const val FILAMENT_HALO_WIDTH: Float = 5f
            const val FILAMENT_HALO_ALPHA: Float = 0.06f
            const val FILAMENT_CORE_WIDTH: Float = 1f
            const val FILAMENT_CORE_ALPHA: Float = 0.5f
            const val ZERO_TICK_HALF: Float = 7f
            const val ZERO_TICK_STROKE: Float = 1.6f
            const val ZERO_TICK_INTENSITY: Float = 0.45f
            const val BEAM_STROKE: Float = 3.2f
            const val BEAM_INTENSITY: Float = 0.85f
            const val HEAD_RADIUS: Float = 3.4f
            const val HEAD_BLUR: Float = 14f
            const val PEAK_TICK_HALF: Float = 5f
            const val PEAK_TICK_STROKE: Float = 1.4f
            const val THREADS_BASE: Float = 30f
            const val THREADS_PER_UNIT: Float = 0.45f
            const val THREADS_AMP_BASE: Float = 5f
            const val THREADS_AMP_RANGE: Float = 13f
            const val THREADS_AMP_KW: Float = 200f
            const val THREADS_WIDTH: Float = 0.7f
            const val GLOW_MAX: Float = 0.13f
            const val GLOW_FULL_KW: Float = 120f
            const val GLOW_RADIUS: Float = 340f
            const val GLOW_REACH_BELOW: Float = 40f
        }

        object Trace {
            const val ZERO: Float = 364f
            const val TOP: Float = 327.08f
            const val DROP: Float = 377f
            const val UP_TO: Float = 60f
            const val DOWN_TO: Float = 20f
            const val WIDTH: Float = 232f

            /** One pitch for the hundred points; a window still filling grows from the right edge. */
            const val POINTS: Int = 100
            const val GAP_FROM_AXIS: Float = 8f
            const val RUNS: Int = 10

            /** A run cut at a ceiling wears one tick this long, just outside the box. */
            const val TICK: Float = 3f
            const val STROKE: Float = 1.5f
            const val FIGURE_SIZE: Float = 52f
            const val UNIT_GAP: Float = 10f
            const val UNIT_SIZE: Float = 17f
        }

        object EngineBox {
            const val WIDTH: Float = 230f
            const val UP_TO: Float = 30f
            const val STROKE: Float = 1.8f
            const val BASE_STROKE: Float = 0.8f
        }

        object Glyph {
            const val OUTLINE: Float = 1.4f
            const val WHEEL: Float = 1.1f
            const val HEIGHT: Float = 24f
        }
    }

    object Head {
        /** A strip box in window dp: left, top, right, bottom. */
        class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

        object Full {
            const val WIDTH: Float = 1280f
            const val HEIGHT: Float = 680f
            const val MARGIN: Float = 48f
            val STRIP_BOX = Box(48f, 372f, 1232f, 668f)

            object Tiles {
                const val TOP: Float = 20f
                const val GAP: Float = 12f
                const val HEIGHT: Float = 164f
                const val RADIUS: Float = 22f
                const val ICON: Float = 30f
                const val ICON_INSET_X: Float = 20f
                const val ICON_INSET_Y: Float = 22f
                const val NAME_BASELINE: Float = 116f
                const val STATUS_BASELINE: Float = 142f
                const val TEXT_INSET: Float = 20f
                const val NAME_SIZE: Float = 19f
                const val STATUS_SIZE: Float = 15f
            }

            object Strip {
                const val CAPTION: Float = 404f
                const val VALUE: Float = 454f
                const val VALUE_SIZE: Float = 46f
                const val LABEL_SIZE: Float = 17f
                const val TITLE_SIZE: Float = 34f
                const val READING_GAP: Float = 56f
                const val SPECTRUM_TOP: Float = 488f
                const val FLOOR: Float = 646f
                const val BARS: Int = 36
                const val DOTS_Y: Float = 664f
            }

            object Car {
                const val HERO_SIZE: Float = 56f
                const val SIDE: Float = 155f
                const val TEMP_PITCH: Float = 64f
                const val TEMP_SIZE: Float = 30f
                const val CHART_CAPTION: Float = 502f
                const val CHART_CAPTION_SIZE: Float = 15f
                const val CHART_TOP: Float = 514f
                const val CHART_HEIGHT: Float = 126f
            }
        }

        object Two {
            const val WIDTH: Float = 828f
            const val HEIGHT: Float = 680f
            const val MARGIN: Float = 20f
            const val CAPTION_BAR: Float = 24f
            val STRIP_BOX = Box(20f, 124.7f, 808f, 668f)

            object Chips {
                const val TOP: Float = 40f
                const val SIZE: Float = 60.7f
                const val RADIUS: Float = 16f
                const val ICON: Float = 26f
                const val PER_ROW: Int = 11
            }

            object Sound {
                const val TRACK_CAPTION: Float = 160f
                const val TRACK_VALUE: Float = 200f
                const val TITLE_SIZE: Float = 32f
                const val LABEL_SIZE: Float = 16f
                const val CAPTION: Float = 246f
                const val VALUE: Float = 294f
                const val VALUE_SIZE: Float = 46f
                const val GAP: Float = 48f
                const val SPECTRUM_TOP: Float = 324f
                const val FLOOR: Float = 634f
                const val BARS: Int = 24
            }

            object Car {
                const val CAPTION: Float = 160f
                const val VALUE: Float = 212f
                const val HERO_SIZE: Float = 56f
                const val VALUE_SIZE: Float = 46f
                const val GAP: Float = 56f
                const val ROW2_CAPTION: Float = 262f
                const val ROW2_VALUE: Float = 304f
                const val VOLT_SIZE: Float = 34f
                const val TEMP_PITCH: Float = 68f
                const val TEMP_SIZE: Float = 30f
                const val CHART_CAPTION: Float = 350f
                const val CHART_TOP: Float = 364f
                const val CHART_HEIGHT: Float = 200f
            }

            const val DOTS_Y: Float = 656f
        }

        object One {
            const val WIDTH: Float = 416f
            const val HEIGHT: Float = 680f
            const val MARGIN: Float = 12f
            const val CAPTION_BAR: Float = 24f
            val STRIP_BOX = Box(12f, 182.6f, 404f, 668f)

            object Chips {
                const val TOP: Float = 36f
                const val SIZE: Float = 55.3f
                const val RADIUS: Float = 14f
                const val ICON: Float = 26f
                const val PER_ROW: Int = 6
                const val ROW_GAP: Float = 12f
            }

            object Sound {
                const val TRACK_CAPTION: Float = 204f
                const val TRACK_VALUE: Float = 236f
                const val TITLE_SIZE: Float = 26f
                const val LABEL_SIZE: Float = 14f
                const val ROWS_TOP: Float = 286f
                const val ROW_PITCH: Float = 42f
                const val VALUE_X: Float = 118f
                const val VALUE_SIZE: Float = 30f
                const val SPECTRUM_TOP: Float = 400f
                const val FLOOR: Float = 636f
                const val BARS: Int = 12
            }

            object Car {
                const val CAPTION: Float = 204f
                const val VALUE: Float = 252f
                const val HERO_SIZE: Float = 44f
                const val ROW2_CAPTION: Float = 292f
                const val ROW2_VALUE: Float = 326f
                const val ROW2_SIZE: Float = 28f
                const val ROW2_GAP: Float = 40f
                const val TEMPS_CAPTION: Float = 370f
                const val TEMPS_VALUE: Float = 404f
                const val TEMP_PITCH: Float = 76f
                const val TEMP_SIZE: Float = 26f
                const val CHART_CAPTION: Float = 446f
                const val CHART_CAPTION_SIZE: Float = 13f
                const val CHART_TOP: Float = 458f
                const val CHART_HEIGHT: Float = 150f
            }

            const val DOTS_Y: Float = 660f
        }

        object Handle {
            const val WIDTH: Float = 44f
            const val HEIGHT: Float = 4f
            const val TOP: Float = 9f
        }

        object Reading {
            const val UNIT_RATIO: Float = 0.42f
            const val UNIT_GAP_RATIO: Float = 0.2f
            const val RATE_RATIO: Float = 0.45f
            const val RATE_GAP_RATIO: Float = 0.3f
            const val UNIT_ALPHA: Float = 0.9f
        }

        object Icon {
            const val STROKE: Float = 1.5f
            const val ON_GLOW: Float = 0.35f
            const val OFF_ALPHA: Float = 0.42f

            /** The working ring: its box on a tile and on a chip, the chip's inset, and its arc. */
            object Ring {
                const val SIZE: Float = 18f
                const val CHIP_SIZE: Float = 10f
                const val CHIP_INSET: Float = 8f
                const val SWEEP: Float = 90f
            }
        }

        object Spectrum {
            const val BAR_WIDTH: Float = 22.97f
            const val LINE_WIDTH: Float = 2f
            const val LINE_PITCH: Float = 4.4f
            const val CROWN_HEIGHT: Float = 2f
            const val CROWN_LIFT: Float = 6f
            const val HEADROOM: Float = 10f
            const val GRADIENT_FOOT: Float = 0.34f
            const val GRADIENT_TOP: Float = 1.0f
            const val GLOW_ALPHA: Float = 0.09f
            const val GLOW_PAD: Float = 3f
            const val HAZE_RX: Float = 0.52f
            const val HAZE_RY: Float = 0.62f
            const val HAZE_CY: Float = 0.62f

            /** Offset along the haze's radius and the halo's alpha there. */
            val HAZE_STOPS: List<Pair<Float, Float>> = listOf(0f to 0.15f, 0.55f to 0.06f, 1f to 0f)
        }

        object Chart {
            /** The cluster's hundred points, a full window edge to edge. */
            const val POINTS: Int = 100
            const val TICK: Float = 3f
            const val ZERO_AT: Float = 0.75f
            const val UP_TO: Float = 60f
            const val DOWN_TO: Float = 20f
            const val FILL_UP: Float = 0.10f
            const val FILL_DOWN: Float = 0.45f
            const val STROKE: Float = 1.6f
            const val ZERO_STROKE: Float = 0.8f
            const val ZERO_ALPHA: Float = 0.25f
            const val DOT: Float = 3f
            const val CAPTION_ALPHA: Float = 0.8f
        }
    }
}
