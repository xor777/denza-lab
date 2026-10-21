package dev.denza.apps.feature.diagnostics.ui

import kotlin.math.roundToInt

/**
 * The scanner's numbers: `tools/design-canvas/luminofor/scanner.json`, which `scanner.js` draws the
 * `scanner-*` boards from. Window dp. `ScannerSpecContractTest` holds every value here to the
 * JSON, so a board and a screen move in one change or neither moves.
 *
 * Colours are ARGB ints (the JSON's `rgba()` alpha rounded to 8 bits, as the canvas stores it); a
 * column list is dp, with a negative number standing for the JSON's `"Nfr"` share.
 */
internal object ScannerSpec {

    const val W = 1280f
    const val H = 680f

    /** The JSON's `"1fr"`: [columns] shares what the fixed columns leave. */
    const val FR = -1f

    object Fonts {
        const val ROBOTO_ASCENT = 0.9277f
        const val ROBOTO_DESCENT = 0.2441f
        const val MONO_ASCENT = 1.0479f
        const val MONO_DESCENT = 0.271f
        const val MONO_ADVANCE = 0.6001f

        /** «→» drawn as a line: neither face has U+2192. Fractions of the type size. */
        object Arrow {
            const val MONO_CELLS = 1f
            const val ROBOTO = 0.8f
            const val Y = 0.3f
            const val HEAD = 0.2f
            const val SPREAD = 0.13f
            const val STROKE = 0.075f
        }
    }

    private fun hex(rgb: Int): Int = (0xFF shl 24) or rgb
    private fun rgba(rgb: Int, a: Float): Int = ((a * 255f).roundToInt() shl 24) or rgb

    object Ink {
        val GROUND = hex(0x000000)
        val BAR = hex(0x0A0C0F)
        val RULE = hex(0x1A1E24)
        val HAIR = rgba(0xDAE1EB, 0.06f)
        val TEXT = hex(0xDAE1EB)
        val WHITE = hex(0xFFFFFF)
        val INK2 = hex(0xE6EBF1)
        val INK3 = hex(0xC9D0D8)
        val INK4 = hex(0xD3D9E0)
        val MUTE = hex(0x7D8692)
        val DIM = hex(0xA2AAB5)
        val DIM2 = hex(0x8C95A1)
        val FAINT = hex(0x6E7682)
        val FAINT2 = hex(0x5B636E)
        val FAINT3 = hex(0x4F5761)
        val SEP = hex(0x4A515B)
        val BLUE = hex(0x439FFC)
        val BLUE_SOFT = hex(0x8FC6FF)
        val BLUE_INK = hex(0xCFE6FF)
        val BLUE_FILL = rgba(0x439FFC, 0.14f)
        val BLUE_TINT = rgba(0x439FFC, 0.18f)
        val BLUE_SHARE = rgba(0x439FFC, 0.09f)
        val BLUE_EDGE = rgba(0x439FFC, 0.6f)
        val ORANGE = hex(0xFF9F19)
        val ORANGE_INK = hex(0xFFC882)
        val ORANGE_FILL = rgba(0xFF9F19, 0.16f)
        val ORANGE_ROW = rgba(0xFF9F19, 0.06f)
        val QUIET = hex(0x15191F)
        val QUIET_LINE = hex(0x2A3038)
        val LINE3 = hex(0x343B45)
        val LINE4 = hex(0x3A414B)
        val DIVIDER = hex(0x1F242C)
        val PLATE = hex(0x0B0E12)
        val HIST_FILL = hex(0x222831)
        val BIT_OFF = hex(0x1C2128)
        val PID_FILL = hex(0x12161C)
        val TILE_OK = hex(0x0B1118)
        val TILE_HIST = hex(0x12161C)
        val TILE_UNREAD = hex(0x05070A)
        val WIRE = rgba(0xDAE1EB, 0.75f)
        val WIRE2 = rgba(0xDAE1EB, 0.6f)
        val WIRE3 = rgba(0xDAE1EB, 0.45f)
        val WIRE4 = rgba(0xDAE1EB, 0.55f)
        val WIRE5 = rgba(0xDAE1EB, 0.85f)
        val BUS = rgba(0xDAE1EB, 0.28f)
        val BUS_LIVE = rgba(0x439FFC, 0.55f)

        /** Inks `scanner.js` writes inline rather than in the JSON. */
        val PRIMARY_FILL = rgba(0x439FFC, 0.10f)
        val PRIMARY_EDGE = rgba(0x439FFC, 0.55f)
        val COUNTER_GLOW = rgba(0xFF9F19, 0.7f)
        val BIT_GLOW = rgba(0x439FFC, 0.8f)
        val TRACE_GLOW = rgba(0x439FFC, 0.6f)
        val RANGE_GLOW = rgba(0x439FFC, 0.7f)

        /** The JSON's names, for the contract test. */
        val byName: Map<String, Int> = mapOf(
            "ground" to GROUND, "bar" to BAR, "rule" to RULE, "hair" to HAIR, "text" to TEXT, "white" to WHITE,
            "ink2" to INK2, "ink3" to INK3, "ink4" to INK4, "mute" to MUTE, "dim" to DIM, "dim2" to DIM2,
            "faint" to FAINT, "faint2" to FAINT2, "faint3" to FAINT3, "sep" to SEP, "blue" to BLUE,
            "blueSoft" to BLUE_SOFT, "blueInk" to BLUE_INK, "blueFill" to BLUE_FILL, "blueTint" to BLUE_TINT,
            "blueShare" to BLUE_SHARE, "blueEdge" to BLUE_EDGE, "orange" to ORANGE, "orangeInk" to ORANGE_INK,
            "orangeFill" to ORANGE_FILL, "orangeRow" to ORANGE_ROW, "quiet" to QUIET, "quietLine" to QUIET_LINE,
            "line3" to LINE3, "line4" to LINE4, "divider" to DIVIDER, "plate" to PLATE, "histFill" to HIST_FILL,
            "bitOff" to BIT_OFF, "pidFill" to PID_FILL, "tileOk" to TILE_OK, "tileHist" to TILE_HIST,
            "tileUnread" to TILE_UNREAD, "wire" to WIRE, "wire2" to WIRE2, "wire3" to WIRE3, "wire4" to WIRE4,
            "wire5" to WIRE5, "bus" to BUS, "busLive" to BUS_LIVE,
        )
    }

    /**
     * A text style: a size, a weight and a line height. A word's baseline is
     * `top + (lh - (ascent + descent) * size) / 2 + ascent * size` - a Compose `lineHeight` with
     * `LineHeightStyle(Center, Trim.None)`.
     */
    data class Style(val size: Float, val lh: Float, val weight: Int = 400, val mono: Boolean = false, val track: Float = 0f) {
        val ascent: Float get() = if (mono) Fonts.MONO_ASCENT else Fonts.ROBOTO_ASCENT
        val descent: Float get() = if (mono) Fonts.MONO_DESCENT else Fonts.ROBOTO_DESCENT

        /** The baseline below the top of the line box. */
        val baseline: Float get() = (lh - (ascent + descent) * size) / 2f + ascent * size

        /** «→» in this style, the board's `arrowW`. */
        val arrowWidth: Float get() = (if (mono) Fonts.Arrow.MONO_CELLS * Fonts.MONO_ADVANCE else Fonts.Arrow.ROBOTO) * size + track * size
    }

    object Type {
        val CRUMB = Style(15f, 18f)
        val CRUMB_NOW = Style(15f, 18f, 500)
        val META = Style(13f, 15f)
        val META_MONO = Style(13f, 18f, mono = true)
        val COUNTER_LABEL = Style(11f, 14f, track = 0.07f)
        val COUNTER_BIG = Style(24f, 26f, mono = true)
        val COUNTER = Style(22f, 24f, mono = true)
        val TITLE = Style(20f, 24f, 500)
        val TITLE_ASIDE = Style(14f, 16f)
        val LINE = Style(13f, 17f, mono = true)
        val TAB = Style(15f, 18f)
        val COL_HEAD = Style(11f, 13f, track = 0.07f)
        val COL_HEAD_TIGHT = Style(11f, 13f, track = 0.06f)
        val BIT_HEAD = Style(10f, 12f, track = 0.02f)
        val GROUP_TITLE = Style(11f, 14f, 500, track = 0.08f)
        val GROUP_SUMMARY = Style(12f, 14f, mono = true)
        val CELL = Style(14.5f, 18f)
        val CELL14 = Style(14f, 18f)
        val CELL_SMALL = Style(13.5f, 18f)
        val CELL_MONO = Style(13.5f, 18f, mono = true)
        val CELL_NUM = Style(14.5f, 18f, mono = true)
        val CELL_ABBR = Style(12.5f, 18f, mono = true)
        val CELL_ASIDE = Style(12.5f, 18f)
        val UNIT = Style(13f, 18f)
        val VALUE = Style(16f, 20f, 500, mono = true)
        val VALUE_BMS = Style(15f, 18f, 500, mono = true)
        val SIG = Style(12f, 16f, mono = true)
        val NORM = Style(12.5f, 16f)
        val NORM_MONO = Style(13f, 18f, mono = true)
        val BADGE = Style(11.5f, 14f, 500, track = 0.04f)
        val BADGE_SMALL = Style(11f, 14f, 500, track = 0.04f)
        val BADGE_BIG = Style(12f, 14f, 500, track = 0.04f)
        val BUTTON = Style(14f, 16f)
        val NOTE = Style(12.5f, 15f)
        val NOTE_MONO = Style(12.5f, 16f, mono = true)
        val CODE = Style(16f, 20f, 500, mono = true)
        val CODE_FAULT = Style(17f, 20f, 500, mono = true)
        val LEAD = Style(15f, 22f)
        val BODY = Style(14f, 21f)
        val BODY_STRONG = Style(14.5f, 21f)
        val SMALL = Style(13f, 19f)
        val LEGEND_KEY = Style(13f, 18f, mono = true)
        val LEGEND = Style(13f, 18f)
        val PLATE_LINE = Style(14.5f, 18f)
        val PLATE_MONO = Style(12.5f, 16f, mono = true)
        val LIST_COUNT = Style(20f, 22f, mono = true)
        val LIST_SERVICE = Style(12f, 15f, mono = true)
        val FAULT_TITLE = Style(15f, 20f)
        val FAULT_NOTE = Style(12.5f, 16f)
        val KEY = Style(13.5f, 18f)
        val KEY_VALUE = Style(14.5f, 18f, mono = true)
        val PID_HEAD = Style(12f, 14f, mono = true)
        val PID = Style(12.5f, 14f, mono = true)
        val BIT = Style(11f, 14f, mono = true)
        val UNIT_NAME = Style(13.5f, 17f)
        val UNIT_TAG = Style(11.5f, 15f, mono = true)
        val ROW_SIG = Style(11f, 14f, mono = true)
        val ABBR = Style(14f, 17f, 500, mono = true)
        val ABBR_NAME = Style(11.5f, 15f)
        val TILE_ABBR = Style(13f, 16f, 500, mono = true)
        val TILE_ADDR = Style(11f, 16f, mono = true)
        val TILE_NAME = Style(11f, 14f)
        val TILE_STATE = Style(10.5f, 15f, 500, track = 0.03f)
        /** The systems down the left: a system's name and its codes' mark. */
        val RAIL_ITEM = Style(15f, 20f)
        val RAIL_MARK = Style(11f, 16f, mono = true)
        /** A parameter on its own page: the reading and its unit. */
        val PARAM_VALUE = Style(72f, 80f, 500, mono = true)
        val PARAM_UNIT = Style(18f, 22f)
        val TOPO_LABEL = Style(11f, 16f, track = 0.07f)
        val HEAD_UNIT = Style(13f, 16f, 500)
        val HEAD_UNIT_HOPS = Style(11f, 16f, mono = true)
        val HV_ABBR = Style(13.5f, 16f, 500, mono = true)
        val HV_NAME = Style(12f, 14f)
        val HV_STATE = Style(11f, 13f, 500, track = 0.03f)
        val HV_LABEL = Style(11.5f, 15f)
        val HV_VOLTS = Style(13f, 18f, mono = true)
        val PACK_TITLE = Style(17f, 20f, 500)
        val PACK_TAG = Style(12f, 16f, mono = true)
        val PACK_STATE = Style(11f, 13f, 500, track = 0.04f)
        val PACK_KEY = Style(12.5f, 19f)
        val PACK_VALUE = Style(14f, 19f, mono = true)
        val GLYPH = Style(15f, 18f)
        val DRIVE_CELL = Style(15f, 18f, mono = true)
        val DRIVE_SMALL = Style(12f, 18f, mono = true)
        val COUNT16 = Style(16f, 18f, mono = true)
        val ADAS11 = Style(11f, 18f, mono = true)
        val ADAS12_5 = Style(12.5f, 18f, mono = true)
        val ADAS13 = Style(13f, 18f, mono = true)
        val ADAS14 = Style(14f, 18f, mono = true)
        val ADAS16 = Style(16f, 18f, mono = true)

        /** The JSON's names, for the contract test. */
        val byName: Map<String, Style> = mapOf(
            "crumb" to CRUMB, "crumbNow" to CRUMB_NOW, "meta" to META, "metaMono" to META_MONO,
            "counterLabel" to COUNTER_LABEL, "counterBig" to COUNTER_BIG, "counter" to COUNTER, "title" to TITLE,
            "titleAside" to TITLE_ASIDE, "line" to LINE, "tab" to TAB,
            "colHead" to COL_HEAD, "colHeadTight" to COL_HEAD_TIGHT, "bitHead" to BIT_HEAD, "groupTitle" to GROUP_TITLE,
            "groupSummary" to GROUP_SUMMARY, "cell" to CELL, "cell14" to CELL14, "cellSmall" to CELL_SMALL,
            "cellMono" to CELL_MONO, "cellNum" to CELL_NUM, "cellAbbr" to CELL_ABBR, "cellAside" to CELL_ASIDE,
            "unit" to UNIT, "value" to VALUE, "valueBms" to VALUE_BMS, "sig" to SIG, "norm" to NORM,
            "normMono" to NORM_MONO, "badge" to BADGE, "badgeSmall" to BADGE_SMALL, "badgeBig" to BADGE_BIG,
            "button" to BUTTON, "note" to NOTE, "noteMono" to NOTE_MONO, "code" to CODE, "codeFault" to CODE_FAULT,
            "lead" to LEAD, "body" to BODY, "bodyStrong" to BODY_STRONG, "small" to SMALL, "legendKey" to LEGEND_KEY,
            "legend" to LEGEND, "plateLine" to PLATE_LINE, "plateMono" to PLATE_MONO, "listCount" to LIST_COUNT,
            "listService" to LIST_SERVICE, "faultTitle" to FAULT_TITLE, "faultNote" to FAULT_NOTE, "key" to KEY,
            "keyValue" to KEY_VALUE, "pidHead" to PID_HEAD, "pid" to PID, "bit" to BIT, "unitName" to UNIT_NAME,
            "unitTag" to UNIT_TAG, "rowSig" to ROW_SIG, "abbr" to ABBR,
            "abbrName" to ABBR_NAME, "tileAbbr" to TILE_ABBR, "tileAddr" to TILE_ADDR, "tileName" to TILE_NAME,
            "tileState" to TILE_STATE, "railItem" to RAIL_ITEM, "railMark" to RAIL_MARK, "paramValue" to PARAM_VALUE, "paramUnit" to PARAM_UNIT, "topoLabel" to TOPO_LABEL, "headUnit" to HEAD_UNIT,
            "headUnitHops" to HEAD_UNIT_HOPS, "hvAbbr" to HV_ABBR, "hvName" to HV_NAME, "hvState" to HV_STATE,
            "hvLabel" to HV_LABEL, "hvVolts" to HV_VOLTS, "packTitle" to PACK_TITLE, "packTag" to PACK_TAG,
            "packState" to PACK_STATE, "packKey" to PACK_KEY, "packValue" to PACK_VALUE,
            "glyph" to GLYPH, "driveCell" to DRIVE_CELL, "driveSmall" to DRIVE_SMALL,
            "count16" to COUNT16, "adas11" to ADAS11, "adas12_5" to ADAS12_5, "adas13" to ADAS13,
            "adas14" to ADAS14, "adas16" to ADAS16,
        )
    }

    /** A status chip's look: the JSON's `tones` (ink, and a fill or a line). */
    data class Tone(val ink: Int, val fill: Int? = null, val line: Int? = null)

    object Tones {
        val OK = Tone(Ink.BLUE_SOFT, fill = Ink.BLUE_FILL)
        val HIST = Tone(Ink.INK4, fill = Ink.HIST_FILL)
        val UNREAD = Tone(Ink.DIM, line = Ink.QUIET_LINE)
        val NONE = Tone(Ink.FAINT, line = Ink.LINE3)
        val READY = Tone(Ink.BLUE_SOFT, fill = Ink.BLUE_FILL)
        val WAITING = Tone(Ink.INK3, line = Ink.LINE4)
        val ABSENT = Tone(Ink.FAINT2)
        val ON = Tone(Ink.ORANGE_INK, fill = Ink.ORANGE_FILL)

        val byName: Map<String, Tone> = mapOf(
            "ok" to OK, "hist" to HIST, "unread" to UNREAD, "none" to NONE,
            "ready" to READY, "waiting" to WAITING, "absent" to ABSENT, "on" to ON,
        )
    }

    object Header {
        const val HEIGHT = 48f
        const val PAD_LEFT = 24f
        const val PAD_RIGHT = 12f
        const val GAP = 18f
        const val CLOSE_W = 40f
        const val CLOSE_H = 36f
        const val CLOSE_R = 6f
        const val CLOSE_ICON = 14f
        const val CLOSE_STROKE = 1.4f
        const val DOT = 7f
        const val DOT_GAP = 7f
        const val DOT_GLOW = 6f
    }

    object Block {
        const val TOP = 60f
        const val HEIGHT = 56f
        const val TITLE_GAP = 5f
        const val COUNTER_W = 112f
        /** A counter wider than [COUNTER_W] takes its widest value and [COUNTER_PAD] rounded up to this. */
        const val COUNTER_STEP = 8f
        const val COUNTER_PAD = 16f
        const val COUNTER_GAP = 4f
        const val BADGE_GAP = 24f
    }

    object Tabs {
        const val TOP = 124f
        const val HEIGHT = 36f
        const val GAP = 28f
        const val UNDERLINE = 2f
        const val DOT_GAP = 8f
    }

    object Footer {
        const val TOP = 628f
        const val HEIGHT = 52f
        const val GAP = 10f
        const val BUTTON_H = 36f
        const val BUTTON_PAD = 16f
        const val BUTTON_R = 6f
        const val DIVIDER_W = 1f
        const val DIVIDER_H = 20f
        const val LEGEND_W = 16f
        const val LEGEND_H = 10f
        const val LEGEND_R = 2f
        const val LEGEND_GAP = 7f
    }

    object Badge {
        const val H = 20f
        const val PAD = 8f
        const val R = 4f
        const val SMALL_H = 18f
        const val SMALL_PAD = 7f
        const val BIG_H = 24f
        const val BIG_PAD = 10f
    }

    object Scan {
        const val TABLE_TOP = 168f
        const val TABLE_HEAD = 30f
        const val PAD = 12f
        const val NUM_PAD = 18f
        const val LIST_TOP = 199f
        const val LIST_BOTTOM = 620f
        const val GROUP = 28f
        const val ROW = 30f
        const val RULE = 1f
        const val GROUP_GAP = 14f
        const val CHEVRON = 14f
    }

    object Topology {
        const val COLUMN = 137f
        const val LEFT = 24f
        const val TILE_W = 124f
        const val TILE_H = 36f
        const val TILE_R = 5f
        const val TILE_PAD_X = 7f
        const val TILE_INSET = 10f
        const val TILE_PITCH = 40f
        const val TILE_ROW_GAP = 2f
        const val HEAD_X = 24f
        const val HEAD_Y = 170f
        const val HEAD_W = 168f
        const val HEAD_H = 28f
        const val HEAD_R = 5f
        const val HEAD_PAD = 10f
        const val FIRST_LABEL = 206f
        const val LABEL_H = 22f
        const val GROUP_GAP = 8f
        const val TRUNK_Y = 184f
        const val TRUNK_FIRST_Y = 198f
        const val TAIL_Y = 224f
        const val BUS_INSET = 4f

        /** One group's slice in a column: rows [from] until [to] (null: to the end); [continued] has no label. */
        data class Part(val id: String, val from: Int = 0, val to: Int? = null, val continued: Boolean = false)

        val COLUMNS: List<List<Part>> = listOf(
            listOf(Part("power")),
            listOf(Part("chassis"), Part("climate")),
            listOf(Part("assist")),
            listOf(Part("body", 0, 9)),
            listOf(Part("body", 9, 11, continued = true), Part("lv")),
            listOf(Part("salon")),
            listOf(Part("misc", 0, 9)),
            listOf(Part("misc", 9, 17, continued = true)),
            listOf(Part("misc", 17, 25, continued = true)),
        )
    }

    object EcuDtc {
        const val LEFT_X = 24f
        const val LEFT_W = 588f
        const val RIGHT_X = 644f
        const val RIGHT_W = 612f
        const val TOP = 172f
        const val HEAD = 30f
        const val LIST_ROW = 34f
        const val MON_ROW = 32f
        val LIST_COLS = listOf(FR, 130f, 110f)
        val MON_COLS = listOf(FR, 120f, 110f)
        const val PLATE_GAP = 18f
        const val PLATE_PAD_X = 16f
        const val PLATE_PAD_Y = 14f
        const val PLATE_GAP_IN = 6f
        const val PLATE_R = 6f
    }

    object EcuFault {
        val COLS = listOf(96f, FR, 70f)
        const val ROW_MIN = 64f
        const val ROW_PAD = 10f
        const val LIST_GAP = 16f
        const val LIST_PAD_Y = 10f
        const val LIST_PAD_X = 12f
        const val LIST_GAP_IN = 4f
    }

    object EcuLive {
        const val TOP = 168f
        const val HEAD = 30f
        const val ROW = 32f
        val COLS = listOf(56f, 360f, 116f, 70f, 82f, 82f, 110f, FR)
        const val SPARK_W = 300f
        const val SPARK_H = 22f
        const val SPARK_STROKE = 1.4f
        const val SPARK_GLOW = 3f
    }

    object EcuIdent {
        val OWN_COLS = listOf(240f, FR)
        val CACHE_COLS = listOf(200f, FR)
        const val ROW = 32f
        const val NOTE_GAP = 14f
        const val PID_GAP = 22f
        const val PID_HEAD_H = 18f
        const val PID_GRID_GAP = 8f
        const val PID_COLS = 12
        const val PID_H = 24f
        const val PID_CELL_GAP = 4f
        const val PID_R = 3f
    }

    object BodyDtc {
        const val TOP = 172f
        const val HEAD = 30f
        val COLS = listOf(130f, 260f, FR, 80f, 46f, 46f, 46f, 46f, 46f, 46f, 46f, 46f)
        const val ROW_MIN = 52f
        const val BIT = 12f
        const val BIT_R = 3f
        const val BELOW = 328f
        /** The explanation's distance under the last code when the codes reach past [BELOW]. */
        const val BELOW_GAP = 19f
        const val EXPLAIN_W = 600f
        const val EXPLAIN_GAP = 8f
        const val LEGEND_X = 672f
        const val LEGEND_W = 584f
        const val LEGEND_KEY = 70f
        const val LEGEND_GAP = 12f
        const val LEGEND_ROW_GAP = 6f
    }

    object Bms {
        const val TOP = 168f
        const val HEAD = 30f
        const val ROW = 27f
        val COLS = listOf(150f, 330f, 120f, 70f, 220f, FR)
        const val VALUE_PAD = 14f
    }

    object Drives {
        const val TOP = 168f
        const val HEAD = 44f
        const val ROW = 40f
        val COLS = listOf(320f, FR, FR, FR, FR, 70f)
        const val PAD = 20f
    }

    object Adas {
        const val TOP = 168f
        const val HEAD = 50f
        const val ROW = 33f
        val COLS = listOf(124f, FR, FR, FR, FR, FR, FR, FR)
        const val PAD = 8f
    }

    object Brakes {
        const val TOP = 172f
        const val HEAD = 30f
        const val ASK_ROW = 34f
        val ASK_COLS = listOf(FR, 110f, 140f)
        const val PLATE_GAP = 18f
        const val PLATE_PAD_X = 16f
        const val PLATE_PAD_Y = 14f
        const val PLATE_GAP_IN = 10f
        const val PLATE_R = 6f
        const val BITS_H = 30f
        const val BITS_GAP = 4f
        const val BITS_R = 4f
    }

    /** The systems down the left (`rail()`), and the screens at the width it leaves. */
    object Rail {
        const val W = 216f
        const val PAD_X = 20f
        const val PAD_TOP = 10f
        const val ITEM = 40f
        const val SEP_GAP = 8f
        const val BAR = 3f
        val SCAN_COLS = listOf(290f, 120f, 150f, 100f, 80f, 80f, 150f, FR)
        val LIVE_COLS = listOf(50f, 300f, 100f, 64f, 76f, 76f, 100f, FR)
        const val TOPO_COLUMN = 112f
        const val TOPO_TILE_W = 100f
        const val TOPO_TILE_PAD_X = 5f
        const val TOPO_TILE_INSET = 8f
        val DTC_COLS = listOf(150f, 120f, 250f, FR, 190f, 40f)
        const val DTC_ROW = 34f
        const val ECU_LEFT_X = 24f
        const val ECU_LEFT_W = 420f
        const val ECU_RIGHT_X = 468f
        const val ECU_RIGHT_W = 572f
        val ECU_LIST_COLS = listOf(FR, 100f, 90f)
        val ECU_MON_COLS = listOf(FR, 100f, 110f)
        const val PENDING_X = 36f
        const val PENDING_Y = 184f
    }

    /** Where a scheme may stand: under the tabs, over the footer. */
    object Content {
        const val TOP = 172f
        const val BOTTOM = 620f
    }

    /** The car from above, front up (`car()`). */
    object Car {
        const val TOP = 196f
        const val H = 400f
        const val W = 176f
        const val NOSE_R = 58f
        const val TAIL_R = 44f
        const val AXLE = 92f
        const val WHEEL_W = 14f
        const val WHEEL_H = 58f
        const val MIRROR_W = 12f
        const val MIRROR_H = 10f
        const val SHIELD = 112f
        const val REAR = 96f
        const val GLASS_INSET = 12f
        const val ROOF_INSET = 22f
        val DOORS_FRONT = 124f to 212f
        val DOORS_REAR = 216f to 300f
        val ANCHORS = 22f to 86f
        const val DOMAIN_Y = 300f
    }

    /** A plate of lines beside a scheme with its leader (`callout()`), and a machine's circle. */
    object Callout {
        const val LEFT_X = 96f
        const val W = 220f
        const val PAD_X = 12f
        const val PAD_Y = 9f
        const val GAP = 3f
        const val R = 6f
        const val DOT = 2.6f
        const val MACHINE_R = 20f
    }

    object AdasCar {
        const val TOP = 236f
        const val H = 330f
        val CORNER = 18f to 26f
        const val FRONT_SPREAD = 40f
        const val FRONT_LEN = 60f
        const val CORNERS_SPREAD = 60f
        const val CORNERS_LEN = 56f
        const val MRR_Y = 176f
        const val FR_DROP = 0f
        const val ADC_LIFT = 0f
        const val IPA_LIFT = 0f
    }

    object DrivesCar {
        const val PAIR = 40f
        const val BOX_W = 280f
        const val ENGINE_Y = 20f
        const val ENGINE_W = 92f
        const val ENGINE_H = 30f
        const val REAR_BLOCKS_DY = 34f
    }

    /** The HV schema at the rail's width (`hvBody()` on `hvRail`). */
    object Hv {
        const val PACK_X = 216f
        const val PACK_Y = 230f
        const val PACK_W = 260f
        const val PACK_H = 260f
        const val PACK_R = 8f
        const val PACK_PAD_X = 16f
        const val PACK_PAD_Y = 14f
        const val PACK_GAP = 9f
        const val UNIT_W = 150f
        const val UNIT_H = 56f
        const val UNIT_R = 6f
        const val UNIT_PAD_X = 10f
        const val UNIT_ROW_GAP = 2f
        const val CONTACTOR_WIRE = "M476 360H504M536 360H576"
        const val CONTACTOR_BLADE = "M504 360L532 348"
        val CONTACTOR_PINS = listOf(504f to 360f, 536f to 360f)
        const val CONTACTOR_PIN = 2.6f
        val CONTACTOR_LABEL = Triple(502f, 368f, 70f)
        const val TRUNK = "M576 210V540"
        const val TAPS = "M576 210H628M576 320H628M576 440H628M576 540H628"
        const val SHAFTS = "M778 210H822M778 320H822M778 440H822M778 540H822"
        const val COUPLING = "M870 317H888M870 323H888"
        const val FEEDS = "M174 260H216M174 378H216M99 406V500"

        data class Machine(val x: Float, val y: Float, val glyph: String, val live: Boolean)

        val MACHINES = listOf(
            Machine(846f, 210f, "M", true), Machine(846f, 320f, "G", true),
            Machine(846f, 440f, "M", false), Machine(846f, 540f, "M", false),
        )
        const val MACHINE_R = 24f
        const val BUS_LABEL_RIGHT = 568f
        const val BUS_LABEL_Y = 203.5f
        val VOLTS = listOf(586f to 236f, 586f to 466f)
        val UNITS: Map<String, Pair<Float, Float>> = linkedMapOf(
            "FMCU" to (628f to 182f), "ISG" to (628f to 292f), "SMCU" to (628f to 412f), "SMCU3" to (628f to 512f),
            "ECM" to (888f to 292f), "PTC" to (24f to 232f), "DCOBC" to (24f to 350f), "LBMS" to (24f to 500f),
        )
    }

    /** A range on a scale (`rangeBar()`). */
    object RangeBar {
        const val H = 8f
        const val HEAD_GAP = 24f
        const val TICK_GAP = 8f
        const val MIN_SPAN = 3f
    }

    object PackScheme {
        const val X = 60f
        const val Y = 196f
        const val W = 230f
        const val H = 380f
        const val R = 12f
        const val PAD = 10f
        val POLES = 262f to 506f
        const val BUS_X = 610f
        const val ISO_X = 380f
        const val ISO_W = 220f
        const val ISO_H = 124f
        const val RANGES_X = 720f
        const val RANGES_Y = 224f
        const val RANGES_W = 300f
        const val RANGES_STEP = 120f
    }

    object EngineScheme {
        const val X0 = 60f
        const val X1 = 1000f
        const val PIPE_Y = 400f
        const val PIPE = 26f
        const val THROTTLE = 210f
        const val BLOCK_X = 400f
        const val BLOCK_Y = 340f
        const val BLOCK_W = 200f
        const val BLOCK_H = 120f
        const val CYL = 18f
        const val CAT_X = 720f
        const val CAT_W = 90f
        const val CAT_H = 44f
        const val O2 = 880f
        const val UP_Y = 322f
        const val DOWN_Y = 482f

        /** A reading's place on the air's way: above or below the pipe, its x, its plate's width, where its leader ends. */
        data class Point(val up: Boolean, val x: Float, val w: Float, val at: Float?)

        /** By the JSON's names; [EnginePlace][dev.denza.apps.feature.diagnostics.scan.EnginePlace] in camel case. */
        val POINTS: Map<String, Point> = linkedMapOf(
            "inlet" to Point(true, 110f, 170f, null), "throttle" to Point(false, 210f, 170f, null),
            "manifold" to Point(true, 300f, 150f, null), "blockTop" to Point(true, 500f, 240f, 340f),
            "blockBottom" to Point(false, 500f, 240f, 460f), "catalyst" to Point(true, 765f, 180f, 378f),
            "oxygen" to Point(false, 880f, 180f, 437f),
        )
    }

    object ClimateScheme {
        const val LEFT_X = 60f
        const val ZONE_W = 240f
        const val ZONE_H = 200f
        const val ZONE_Y = 300f
        const val STEM = 40f
    }

    object LvScheme {
        const val X0 = 60f
        const val X1 = 1030f
        const val BUS_Y = 300f
        const val DCDC_X = 190f
        const val BATT_X = 360f
        const val BATT_DROP = 90f
        const val LOAD_X = 520f
        const val LOAD_STEP = 130f
        const val LOAD_DROP = 90f
    }

    /**
     * The board's `columns()`: [spec] in dp, [FR]-shares splitting what the fixed ones and the gaps
     * leave of [w]; returns each column's x and width.
     */
    fun columns(spec: List<Float>, x: Float, w: Float, gap: Float = 0f): List<Pair<Float, Float>> {
        val fixed = spec.filter { it >= 0f }.sum() + gap * (spec.size - 1)
        val frs = spec.filter { it < 0f }.sumOf { -it.toDouble() }.toFloat()
        val unit = if (frs > 0f) (w - fixed) / frs else 0f
        var cx = x
        return spec.map { v ->
            val cw = if (v >= 0f) v else -v * unit
            (cx to cw).also { cx += cw + gap }
        }
    }
}
