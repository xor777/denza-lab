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
        val ORANGE = hex(0xFF9F19)
        val ORANGE_INK = hex(0xFFC882)
        val ORANGE_FILL = rgba(0xFF9F19, 0.16f)
        val QUIET = hex(0x15191F)
        val QUIET_LINE = hex(0x2A3038)
        val LINE3 = hex(0x343B45)
        val LINE4 = hex(0x3A414B)
        val DIVIDER = hex(0x1F242C)
        val PLATE = hex(0x0B0E12)
        val HIST_FILL = hex(0x222831)
        val PID_FILL = hex(0x12161C)
        val WIRE = rgba(0xDAE1EB, 0.75f)
        val WIRE2 = rgba(0xDAE1EB, 0.6f)
        val WIRE3 = rgba(0xDAE1EB, 0.45f)
        val WIRE4 = rgba(0xDAE1EB, 0.55f)
        val WIRE5 = rgba(0xDAE1EB, 0.85f)
        /** v4 (2026-10-23): red is now - an active record, MIL on; a band is a norm; pick marks a record in a raw answer. */
        val RED = hex(0xFF4046)
        val RED_INK = hex(0xFF8A8D)
        val RED_FILL = rgba(0xFF4046, 0.16f)
        val RED_ROW = rgba(0xFF4046, 0.07f)
        val BAND = rgba(0xDAE1EB, 0.05f)
        val PICK = rgba(0xDAE1EB, 0.1f)
        /** The report's first page: a sheet of paper and its inks. */
        val PAPER = hex(0xF4F6F8)
        val PAPER_INK = hex(0x1B1F24)
        val PAPER_GREY = hex(0x5F6773)
        val PAPER_RULE = hex(0xD5DAE0)

        /** Inks `scanner.js` writes inline rather than in the JSON. */
        val PRIMARY_FILL = rgba(0x439FFC, 0.10f)
        val PRIMARY_EDGE = rgba(0x439FFC, 0.55f)
        /** A counter that says now - MIL on, a record failing - glows red (`blockHead()`). */
        val RED_GLOW = rgba(0xFF4046, 0.7f)
        val TRACE_GLOW = rgba(0x439FFC, 0.6f)
        /** A parameter's page (`sysParam()`): the reading's glow, in norm and out of it; the trace's and its last point's. */
        val PARAM_GLOW = rgba(0x439FFC, 0.45f)
        val PARAM_GLOW_OUT = rgba(0xFF9F19, 0.55f)
        val PARAM_TRACE_GLOW = rgba(0x439FFC, 0.7f)
        val PARAM_DOT_GLOW = rgba(0x439FFC, 0.9f)

        /** The JSON's names, for the contract test. */
        val byName: Map<String, Int> = mapOf(
            "ground" to GROUND, "bar" to BAR, "rule" to RULE, "hair" to HAIR, "text" to TEXT, "white" to WHITE,
            "ink2" to INK2, "ink3" to INK3, "ink4" to INK4, "mute" to MUTE, "dim" to DIM, "dim2" to DIM2,
            "faint" to FAINT, "faint2" to FAINT2, "faint3" to FAINT3, "sep" to SEP, "blue" to BLUE,
            "blueSoft" to BLUE_SOFT, "blueInk" to BLUE_INK, "blueFill" to BLUE_FILL, "orange" to ORANGE, "orangeInk" to ORANGE_INK,
            "orangeFill" to ORANGE_FILL, "quiet" to QUIET, "quietLine" to QUIET_LINE,
            "line3" to LINE3, "line4" to LINE4, "divider" to DIVIDER, "plate" to PLATE, "histFill" to HIST_FILL,
            "pidFill" to PID_FILL, "wire" to WIRE, "wire2" to WIRE2, "wire3" to WIRE3, "wire4" to WIRE4,
            "wire5" to WIRE5, "red" to RED, "redInk" to RED_INK, "redFill" to RED_FILL,
            "redRow" to RED_ROW, "band" to BAND, "pick" to PICK, "paper" to PAPER, "paperInk" to PAPER_INK,
            "paperGrey" to PAPER_GREY, "paperRule" to PAPER_RULE,
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
        val CELL = Style(14.5f, 18f)
        val CELL_SMALL = Style(13.5f, 18f)
        val CELL_MONO = Style(13.5f, 18f, mono = true)
        val CELL_NUM = Style(14.5f, 18f, mono = true)
        val CELL_ABBR = Style(12.5f, 18f, mono = true)
        val CELL_ASIDE = Style(12.5f, 18f)
        val UNIT = Style(13f, 18f)
        val VALUE = Style(16f, 20f, 500, mono = true)
        val VALUE_BMS = Style(15f, 18f, 500, mono = true)
        val SIG = Style(12f, 16f, mono = true)
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
        val BODY_STRONG = Style(14.5f, 21f)
        val SMALL = Style(13f, 19f)
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
        /** The systems down the left: a system's name and its codes' mark. */
        val RAIL_ITEM = Style(15f, 20f)
        val RAIL_MARK = Style(12.5f, 16f, mono = true)
        /** A lane's number in its square of «Графики». */
        val PICK_NUM = Style(11.5f, 14f, 500, mono = true)
        /** A parameter on its own page: the reading and its unit. */
        val PARAM_VALUE = Style(72f, 80f, 500, mono = true)
        val PARAM_UNIT = Style(18f, 22f)
        val HV_ABBR = Style(13.5f, 16f, 500, mono = true)
        val HV_NAME = Style(12f, 14f)
        val HV_LABEL = Style(11.5f, 15f)
        val HV_VOLTS = Style(13f, 18f, mono = true)
        val PACK_TITLE = Style(17f, 20f, 500)
        val PACK_TAG = Style(12f, 16f, mono = true)
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
        /** v4 (2026-10-23): a code in a row, a code as a title, a chip's letters, a code's meaning. */
        val CODE_SMALL = Style(13.5f, 18f, 500, mono = true)
        val CODE_TITLE = Style(22f, 24f, 500, mono = true)
        val TAG = Style(10f, 14f, track = 0.06f)
        val MEANING = Style(19f, 24f, 500)
        val SMALL12 = Style(12f, 16f)
        /** A raw ask and answer, a chart's tick and a lane's scale, the view switch, a checklist's item. */
        val RAW = Style(12.5f, 22f, mono = true)
        val TICK = Style(10f, 12f, mono = true)
        val SCALE = Style(10.5f, 13f, mono = true)
        val SEGMENT = Style(13f, 16f)
        val ITEM = Style(15f, 18f)
        /** The report's first page in its own small type. */
        val PAPER_TITLE = Style(9.5f, 12f, 500, track = 0.06f)
        val PAPER = Style(7.5f, 10.5f)
        val PAPER_SMALL = Style(6.5f, 9.5f)
        val PAPER_MONO = Style(6.5f, 9.5f, mono = true)
        val PAPER_CODE = Style(6.5f, 9.5f, 500, mono = true)
        val PAPER_HEAD = Style(6.5f, 9f, 500, track = 0.08f)
        val PAPER_FOOT = Style(6f, 9f)

        /** The JSON's names, for the contract test. */
        val byName: Map<String, Style> = mapOf(
            "crumb" to CRUMB, "crumbNow" to CRUMB_NOW, "meta" to META, "metaMono" to META_MONO,
            "counterLabel" to COUNTER_LABEL, "counterBig" to COUNTER_BIG, "counter" to COUNTER, "title" to TITLE,
            "titleAside" to TITLE_ASIDE, "line" to LINE, "tab" to TAB,
            "colHead" to COL_HEAD, "colHeadTight" to COL_HEAD_TIGHT, "bitHead" to BIT_HEAD, "groupTitle" to GROUP_TITLE,
            "cell" to CELL, "cellSmall" to CELL_SMALL,
            "cellMono" to CELL_MONO, "cellNum" to CELL_NUM, "cellAbbr" to CELL_ABBR, "cellAside" to CELL_ASIDE,
            "unit" to UNIT, "value" to VALUE, "valueBms" to VALUE_BMS, "sig" to SIG,
            "normMono" to NORM_MONO, "badge" to BADGE, "badgeSmall" to BADGE_SMALL, "badgeBig" to BADGE_BIG,
            "button" to BUTTON, "note" to NOTE, "noteMono" to NOTE_MONO, "code" to CODE, "codeFault" to CODE_FAULT,
            "lead" to LEAD, "bodyStrong" to BODY_STRONG, "small" to SMALL, "plateLine" to PLATE_LINE, "plateMono" to PLATE_MONO, "listCount" to LIST_COUNT,
            "listService" to LIST_SERVICE, "faultTitle" to FAULT_TITLE, "faultNote" to FAULT_NOTE, "key" to KEY,
            "keyValue" to KEY_VALUE, "pidHead" to PID_HEAD, "pid" to PID, "bit" to BIT, "unitName" to UNIT_NAME,
            "unitTag" to UNIT_TAG, "rowSig" to ROW_SIG, "abbr" to ABBR,
            "abbrName" to ABBR_NAME, "tileAbbr" to TILE_ABBR, "tileAddr" to TILE_ADDR, "tileName" to TILE_NAME,
            "railItem" to RAIL_ITEM, "railMark" to RAIL_MARK, "pickNum" to PICK_NUM, "paramValue" to PARAM_VALUE, "paramUnit" to PARAM_UNIT,
            "hvAbbr" to HV_ABBR, "hvName" to HV_NAME, "hvLabel" to HV_LABEL, "hvVolts" to HV_VOLTS, "packTitle" to PACK_TITLE, "packTag" to PACK_TAG,
            "packKey" to PACK_KEY, "packValue" to PACK_VALUE,
            "glyph" to GLYPH, "driveCell" to DRIVE_CELL, "driveSmall" to DRIVE_SMALL,
            "count16" to COUNT16, "adas11" to ADAS11, "adas12_5" to ADAS12_5, "adas13" to ADAS13,
            "adas14" to ADAS14, "adas16" to ADAS16, "codeSmall" to CODE_SMALL, "codeTitle" to CODE_TITLE, "tag" to TAG,
            "meaning" to MEANING, "small12" to SMALL12, "raw" to RAW, "tick" to TICK, "scale" to SCALE, "segment" to SEGMENT,
            "item" to ITEM, "paperTitle" to PAPER_TITLE, "paper" to PAPER, "paperSmall" to PAPER_SMALL, "paperMono" to PAPER_MONO,
            "paperCode" to PAPER_CODE, "paperHead" to PAPER_HEAD, "paperFoot" to PAPER_FOOT,
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
        val ON = Tone(Ink.RED_INK, fill = Ink.RED_FILL)

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
        /** The car's figures right of the crumbs: a «·» with this room either side, a label this far from its figure. */
        const val META_GAP = 9f
        const val VOLTS_GAP = 5f
    }

    /** A record's sign (`sign()`): ● active, ◌ pending (dashed), ○ in history, a dashed square for no answer. */
    object Sign {
        const val R = 4f
        const val STROKE = 1.5f
        val DASH = floatArrayOf(3f, 2f)
        const val SQUARE = 9f
    }

    /** A row's chevron, its box's side. */
    const val CHEVRON = 14f

    /** The view switch at the tabs' right end («Таблица / Графики»). */
    object Segmented {
        const val H = 26f
        const val PAD = 12f
        const val R = 5f
        const val GAP = 2f
    }

    /**
     * A signal's square of «Графики» (`pickBox()`, `closeMark()`): a live table's column of [COL] before its first, the
     * square [INSET] into it; on the graphs' legend the square, the name [LEGEND_GAP] after it and «×» at the legend's end.
     */
    object Pick {
        const val COL = 40f
        const val INSET = 12f
        const val SIZE = 18f
        const val R = 4f
        const val STROKE = 1.5f
        const val LEGEND_GAP = 8f
        const val CLOSE_SIZE = 9f
        const val CLOSE_STROKE = 1.5f
        const val CLOSE_GAP = 12f
    }

    /** A status byte as one strip from bit 7 to 0 (`bitStrip()`). */
    object BitStrip {
        const val CELL = 44f
        const val H = 34f
        const val GAP = 4f
        const val R = 4f
        const val CELL_Y = 16f
        const val NAME_Y = 56f
        const val NAME_OUT = 4f
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

    /**
     * «Блоки» (`blocksTable()`): the table's columns - the sign, the block, its name, the route, the
     * protocol, the records, F194, the median answer, the chevron - its head and its rows.
     */
    object Modules {
        val COLS = listOf(18f, 104f, 268f, 140f, 108f, 76f, 196f, 70f, 36f)
        const val NUM_PAD = 14f
        const val GROUP_PAD = 18f
        const val TOP = 168f
        const val HEAD = 30f
        const val LIST_TOP = 199f
        const val LIST_BOTTOM = 620f
        const val GROUP = 28f
        const val ROW = 30f
        const val RULE = 1f
    }

    /** A block on a scheme (`unitTile()`): abbreviation and address over its short name. */
    object Tile {
        const val W = 124f
        const val H = 36f
        const val R = 5f
        const val PAD_X = 7f
        const val PITCH = 40f
        const val ROW_GAP = 2f
    }

    /**
     * «Сводка» (`summary()`): the band of record kinds under the head, then the systems in two columns -
     * a block's head and a row a record - and the quiet blocks' line under the left one.
     */
    object Summary {
        const val BAND_TOP = 124f
        const val BAND_H = 36f
        const val BAND_GAP = 10f
        const val BAND_ITEM_GAP = 24f
        const val TOP = 172f
        const val COL_W = 496f
        const val COL_GAP = 24f
        const val LABEL = 18f
        const val HEAD = 32f
        const val HEAD_GAP = 10f
        const val ROW = 24f
        const val ROW_PAD = 18f
        val ROW_COLS = listOf(92f, FR, 40f, 14f)
        const val ROW_GAP = 12f
        const val BLOCK_GAP = 6f
        const val SYSTEM_GAP = 12f
        const val QUIET_GAP = 6f
        const val QUIET_H = 34f
        const val QUIET_CHEVRON_GAP = 8f
        const val TAG_H = 16f
        const val TAG_PAD = 5f
        const val TAG_R = 3f
    }

    /**
     * A code's screen (`codeScreen()`), over the whole window: three columns under a rule - the
     * meaning, the status byte and the reads; the addressee and the same failure type; the chronology,
     * the snapshot and the counters.
     */
    object Code {
        const val RULE = 131f
        val X = listOf(24f, 448f, 864f)
        val W = listOf(384f, 392f, 392f)
        const val LABEL = 148f
        const val MEANING = 168f
        const val MEANING_NOTE = 196f
        const val STATUS_LABEL = 236f
        const val STRIP = 256f
        const val WORDS = 334f
        const val WORDS_NOTE = 358f
        const val READ_LABEL = 404f
        const val READS = 424f
        /** The record's plate in a raw answer: out by [PICK_X], [PICK_Y], its corners [PICK_R]. */
        const val PICK_X = 3f
        const val PICK_Y = 1f
        const val PICK_R = 3f
        const val CARD_TOP = 168f
        const val CARD_H = 78f
        const val CARD_PAD_X = 14f
        const val CARD_PAD_Y = 12f
        const val CARD_R = 6f
        const val CARD_GAP = 8f
        const val SAME_LABEL = 276f
        const val SAME = 296f
        const val SAME_ROW = 28f
        val SAME_COLS = listOf(104f, FR, 70f, 14f)
        const val SAME_GAP = 10f
        const val SAME_NOTE_GAP = 12f
        const val TIME_TOP = 176f
        const val TIME_DOT = 8f
        const val TIME_BAR = 2f
        const val TIME_BAR_ALPHA = 0.55f
        const val TIME_AXIS = 30f
        const val TIME_TICK = 10f
        const val TIME_LABEL_GAP = 4f
        const val TIME_CAPTION = 50f
        const val SNAP_LABEL = 260f
        const val SNAP = 280f
        const val SNAP_ROW = 28f
        const val SNAP_KEY = 180f
        const val COUNT_GAP = 13f
        const val COUNT_ROW = 20f
    }

    /**
     * «Тяговая батарея · Здоровье» (`packHealth()`): the table of now, the reference and 30 days, its
     * mini charts, and beside it the cells from their mean and the probes.
     */
    object Health {
        const val TOP = 168f
        const val W = 600f
        const val HEAD = 30f
        const val ROW = 44f
        val COLS = listOf(236f, 112f, 132f, 120f)
        const val VALUE_PAD = 20f
        const val SUB_GAP = 2f
        const val UNIT_GAP = 5f
        const val SPARK_W = 104f
        const val SPARK_H = 22f
        const val SPARK_STROKE = 1.5f
        const val SPARK_DOT = 2f
        const val CELLS_X = 648f
        const val CELLS_LABEL_Y = 168f
        const val CELLS_TOP = 196f
        const val CELLS_W = 392f
        const val CELLS_H = 150f
        /** Dots a millivolt. */
        const val CELLS_MV = 15f
        val CELLS_GRID = listOf(-4, -2, 2, 4)
        const val CELLS_SCALE_W = 26f
        const val CELLS_GAP = 0.7f
        const val CELLS_STATS_Y = 358f
        const val PROBES_LABEL_Y = 392f
        const val PROBES_TOP = 414f
        const val PROBES_COLS = 7
        const val PROBE_W = 52f
        const val PROBE_H = 42f
        const val PROBE_STEP_X = 56f
        const val PROBE_STEP_Y = 48f
        const val PROBE_PAD_X = 7f
        const val PROBE_PAD_Y = 4f
        const val PROBE_R = 5f
        /** The bars' room on the right of the chart, for the sweep and the extremes' words. */
        const val CELLS_RIGHT = 30f
        /** A bar not at an extreme is drawn at this much of its light. */
        const val BAR_ALPHA = 0.75f
        const val BAR_MIN_W = 1.2f
    }

    /** «Графики» (`sysGraphs()`): up to four lanes on one axis, the legend at their left. */
    object Graphs {
        const val LEGEND_W = 248f
        const val PLOT_X = 288f
        val LANES = listOf(172f, 280f, 388f, 496f)
        const val LANE = 94f
        const val NAME_GAP = 3f
        const val VALUE_GAP = 6f
        const val CURSOR_TOP = 168f
        const val CURSOR_BOTTOM = 598f
        const val CURSOR_DOT = 3.5f
        const val AXIS_Y = 604f
        const val TAG_Y = 600f
        const val TAG_W = 68f
        const val TAG_H = 20f
        const val TAG_R = 4f
        const val SCALE_INSET = 4f
        /** The trace's width; a scale's word stands this far inside its lane. */
        const val TRACE = 1.5f
        const val SCALE_PAD = 2f
    }

    /** «Журнал» (`journal()`): the day axis and its lanes at the left, the comparison's panel at the right. */
    object Journal {
        const val PLOT_X = 188f
        const val PLOT_W = 536f
        const val AXIS_Y = 176f
        const val GRID_TOP = 190f
        const val GRID_H = 418f
        const val LANE_W = 156f
        val LANES = listOf(200f, 232f, 264f)
        const val LANE_H = 28f
        val RULES = listOf(300f, 494f)
        const val CODES_LABEL = 312f
        const val CODES_TOP = 332f
        const val CODE_STEP = 22f
        const val CODE_H = 22f
        const val VALUES_LABEL = 506f
        val VALUES = listOf(526f, 564f)
        const val VALUE_H = 30f
        const val DOT = 6f
        const val POINT = 2.2f
        const val CELL_LABEL = 2f
        const val PANEL_X = 748f
        const val PANEL_TOP = 176f
        const val PANEL_H = 432f
        const val PANEL_PAD_X = 16f
        const val PANEL_PAD_Y = 14f
        const val PANEL_R = 6f
        const val PANEL_ROW = 26f
        const val PANEL_HEAD = 30f
        const val PANEL_GAP = 10f
        /** «Сравнение» across the page (`journalCompare()`): its caption and moments, then three plates. */
        const val COMPARE_TOP = 176f
        const val COMPARE_HEAD = 30f
        const val COMPARE_COLS = 3
        const val COMPARE_GAP = 16f
        /** «Коды» (`journalCodes()`): a row a code - sign, code, block, first and last read, reads, now - and a chevron. */
        val TABLE_COLS = listOf(18f, 128f, 150f, 150f, 160f, 120f, FR, 36f)
        const val TABLE_TOP = 168f
        const val TABLE_HEAD = 30f
        const val TABLE_LIST_TOP = 199f
        const val TABLE_LIST_BOTTOM = 620f
        const val TABLE_ROW = 21f
        const val TABLE_RULE = 1f
    }

    /** «Отчёт» (`report()`): the first page on the left, what goes in and where on the right. */
    object Report {
        const val PAPER_X = 24f
        const val PAPER_Y = 64f
        const val PAPER_W = 390f
        const val PAPER_H = 552f
        const val PAPER_R = 3f
        const val X = 452f
        const val TITLE_Y = 64f
        const val LINE_Y = 94f
        const val LABEL_Y = 128f
        const val LIST_TOP = 146f
        const val ROW = 38f
        const val BOX = 18f
        const val BOX_R = 4f
        const val BOX_GAP = 12f
        const val DEST_LABEL = 400f
        const val DEST = 420f
        const val DEST_NOTE = 446f
        const val DEST_SIZE = 470f
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
        /** The block's own side, wider than the DTC screen's: an update's «был <версия> · <код>» fits whole. */
        const val LEFT_X = 24f
        const val LEFT_W = 624f
        const val RIGHT_X = 680f
        const val RIGHT_W = 576f
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

    /** «Тесты», Mode 06 (`ecuTests()`): the line of what was asked, then the monitors in two columns. */
    object EcuTests {
        const val LINE = 172f
        const val TOP = 196f
        val X = listOf(24f, 652f)
        const val W = 604f
        /** A monitor's title line; its words stand [TITLE_Y] into it. */
        const val TITLE = 20f
        const val TITLE_Y = 6f
        const val ROW = 20f
        /** A line of the exchange; its words [RAW_Y] into it. */
        const val RAW = 20f
        const val RAW_Y = 2f
        /** TID, the value, its unit, the minimum, the scale, the maximum, the word. */
        val ROW_COLS = listOf(60f, 84f, 60f, 76f, FR, 76f, 80f)
        const val VALUE_PAD = 8f
        const val LIMIT_PAD = 10f
        const val SCALE_INSET = 8f
        const val SCALE_TICK = 8f
        const val MARK_W = 2f
        const val MARK_H = 12f
        const val OUT_W = 4f
        const val OUT_H = 6f
        const val OUT_GAP = 2f
    }

    object BodyDtc {
        const val TOP = 172f
        const val HEAD = 30f
        /** The code, the failure type, the description, the status in words. */
        val TABLE = listOf(130f, 260f, FR, 440f)
        const val ROW_MIN = 52f
        const val BELOW = 328f
        /** The selected code's status byte this far under the last code when the codes reach past [BELOW]. */
        const val BELOW_GAP = 19f
        const val EXPLAIN_GAP = 8f
        /** «КАК ПРОЧИТАН» beside the status byte. */
        const val READ_X = 672f
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
    }

    /** The systems down the left (`rail()`), and the screens at the width it leaves. */
    object Rail {
        const val W = 216f
        const val PAD_X = 20f
        const val PAD_TOP = 8f
        const val ITEM = 38f
        const val SEP_GAP = 8f
        const val BAR = 3f
        /** A mark's sign this far before its count. */
        const val MARK_GAP = 7f
        val LIVE_COLS = listOf(50f, 300f, 100f, 64f, 76f, 76f, 100f, FR)
        /** The pack's readings as a live table: the device first, the FID beside the name. */
        val PACK_LIVE_COLS = listOf(58f, 286f, 132f, 50f, 76f, 76f, 100f, FR)
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

    /**
     * One parameter on its own page (`sysParam()`): the reading at the left, its chart across the
     * rest; the area's coordinates. The JSON's `param`, then the numbers `scanner.js` writes inline.
     */
    object Param {
        const val CHART_X = 380f
        const val CHART_TOP = 196f
        const val CHART_BOTTOM = 584f
        const val LABEL_Y = 196f
        const val VALUE_Y = 220f
        const val STATS_Y = 368f
        const val STATS_W = 300f
        const val STATS_STEP = 38f

        const val VALUE_GLOW = 18f
        const val UNIT_GAP = 4f
        /** A side row's value stands this much over its label's line box; its rule this far under the label's top. */
        const val STATS_LIFT = 3f
        const val STATS_RULE = 22f
        const val GRID_LABEL_GAP = 10f
        const val TICK_LABEL_GAP = 10f
        const val LIMIT_LABEL_X = 8f
        const val LIMIT_LABEL_GAP = 4f
        const val DASH = 4f
        const val TRACE = 1.8f
        const val TRACE_GLOW = 8f
        const val DOT_R = 4.5f
        const val DOT_GLOW = 10f

        /** The windows, seconds, in the tabs' order; the page opens on the second. */
        val WINDOWS = listOf(10, 60, 300)
        const val FIRST_WINDOW = 1
    }

    /**
     * «Тяговая батарея · Ячейки» (`sysPackCells()`): the cells' chart and the probes' tiles; the
     * area's coordinates. The JSON's `packCells`, then the numbers `scanner.js` writes inline.
     */
    object PackCells {
        const val CHART_X = 76f
        const val CHART_TOP = 222f
        const val CHART_BOTTOM = 430f
        const val CELLS_HEAD_Y = 182f
        const val BAR_GAP = 1.2f
        /** The sweep under way: a line this thick under the chart's foot, and its dot's radius. */
        const val SWEEP = 2f
        const val SWEEP_DOT = 3f
        const val PROBES_HEAD_Y = 474f
        const val PROBES_TOP = 500f
        const val PROBE_H = 74f
        const val PROBE_GAP = 8f
        const val PROBE_PAD_X = 10f
        const val PROBE_PAD_Y = 10f
        const val PROBE_VALUE_Y = 18f
        const val PROBE_UNIT_Y = 24f

        /** A head's figure stands this much over its caption; the gaps after a figure and after a caption. */
        const val STAT_LIFT = 3f
        const val STAT_GAP = 6f
        const val STAT_STEP = 22f
        const val GRID_LABEL_GAP = 8f
        /** A bar is at least this tall, so a cell at the reference still shows. */
        const val BAR_MIN = 1.5f
        /** The extremes' leaders: from this far off the bar's end to this far, the word 2 dp further. */
        const val LEADER_FROM = 4f
        const val LEADER_TO = 12f
        const val LEADER_WORD = 14f
        const val TICK_GAP = 6f
        /** Numbers on the axis: the first cell, then every [TICK_STEP]. */
        const val TICK_STEP = 20
        const val PROBE_R = 6f
        /** How long the bars take to move from one sweep's offsets to the next's, ms; not on a board. */
        const val MOVE_MS = 300
    }

    /**
     * «Флаги GB/T 32960» (`sysGbFlags()`): the JSON's `gbFlags` - a tile a flag, [COLS] to a row [GAP]
     * apart across the area less 24 on each side, the first group on rows 0 and 1 of [ROW_Y], every
     * later one on row 2, each group's caption at its [GROUP_Y]; the head's figures stand as
     * [PackCells]' do. Then the numbers `scanner.js` writes inline.
     */
    object GbFlags {
        const val HEAD_Y = 182f
        const val COLS = 5
        const val GAP = 12f
        val GROUP_Y = listOf(222f, 446f)
        val ROW_Y = listOf(244f, 342f, 468f)
        const val TILE_H = 86f
        const val TILE_R = 6f
        const val PAD_X = 12f
        const val PAD_Y = 10f
        const val NAME_Y = 22f
        const val STATE_Y = 60f

        /** The row of every group after the first. */
        const val LATER_ROW = 2
        /** The name's line box stands this much over `NAME_Y + PAD_Y`; the code this much under the word's. */
        const val NAME_LIFT = 4f
        const val CODE_DROP = 2f
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
    }

    /**
     * Кузов (`sysBodyState()`): the plates and the edge tiles at [X] from either side; the door
     * radars' centres and the rear ones' point on the door, the domains' top and the tailgate's
     * line - all from the car's top.
     */
    object BodyScheme {
        const val X = 24f
        const val RADAR_FRONT = 134f
        const val RADAR_REAR = 294f
        const val RADAR_REAR_AT = 274f
        const val DOMAIN_TOP = 310f
        const val TAIL_Y = 390f

        /**
         * The lamps on the left corners' arcs, degrees clockwise on the screen (mirrored to the right):
         * the headlamp on the nose's, the reversing and the stop lamps on the tail's; [LAMP_W] wide, a
         * lit one over a [LAMP_HALO] halo of its ink.
         */
        val LAMP_HEAD = 200f to 248f
        val LAMP_REVERSE = 102f to 118f
        val LAMP_STOP = 126f to 164f
        const val LAMP_W = 3f
        const val LAMP_HALO = 9f
        /** An indicator: a mark [TURN_GAP] off the car's side, [TURN_LEN] long, [TURN_HALF] each way of [TURN_Y] from the end; its halo. */
        const val TURN_GAP = 8f
        const val TURN_LEN = 8f
        const val TURN_Y = 40f
        const val TURN_HALF = 5f
        const val TURN_HALO = 9f
        /** The hood's line takes this many degrees of each nose arc beside the nose's flat. */
        const val HOOD = 12f
        /** The hood's and the tailgate's words' tops from the car's top. */
        val WORDS = 66f to 318f
        /** «СВЕТ»'s middle from the car's top; «ЗАДНИЕ ФОНАРИ»'s middle and width. */
        const val LIGHTS = 70f
        const val TAIL_PLATE_Y = 381f
        const val TAIL_PLATE_W = 340f

        /** Where the headlamps' tiles' leaders, and «СВЕТ»'s, end: from the car's top-left (top-right). */
        val LAMP_AT = 26f to 14f
        /** A lit lamp's halo, as the cabin's reading lights. */
        const val HALO_ALPHA = 0.25f
    }

    /**
     * Шасси: the rear-wheel steering's tiles this far under the rear axle, their leaders' point on the
     * wheel; the parking brake's calipers inside the rear wheels, its word under the rear axle.
     */
    object ChassisScheme {
        const val STEER_DROP = 74f
        const val STEER_AT = 20f
        const val CALIPER_IN = 9f
        const val CALIPER_W = 5f
        const val CALIPER_H = 20f
        const val CALIPER_R = 1.5f
        const val EPB_Y = 8f
    }

    /**
     * Безопасность: the belts' tiles this far from the car's sides; the seats in their column, their
     * glyph's edge at the tile's, the front row's and the second row's tops from the car's top, the
     * second row's centre a [STEP] lower, the words [PAD] outside the glyph.
     */
    object SafetyScheme {
        const val GAP = 48f
        val ROWS = 146f to 244f
        const val STEP = 47f
        const val PAD = 12f
        const val SEAT_W = 26f
        const val SEAT_H = 30f
        const val SEAT_R = 6f
        const val STRAP_IN = 6f
        const val STRAP_TOP = 5f
        const val STRAP_FOOT = 9f
    }

    /**
     * Салон (`sysCabin()`): the plates [X] from either side, [PLATE_W] wide, stacked from [TOP] to
     * [BOTTOM] [GAP] apart, their leaders bending [BEND_IN] off the car's edge and [BEND_STEP] further
     * out each; the wheel, the seats, the console with its pads and fridges, the pips and the lamps
     * from the car's top-left. The JSON's `cabinScheme`, then the numbers `scanner.js` writes inline.
     */
    object CabinScheme {
        const val X = 24f
        const val PLATE_W = 300f
        const val TOP = 176f
        const val BOTTOM = 618f
        const val GAP = 10f
        const val BEND_IN = 14f
        const val BEND_STEP = 18f
        /** The wheel: its centre and radius. */
        val WHEEL = Triple(46f, 134f, 12f)
        val FRONT_X = listOf(20f, 104f)
        const val FRONT_Y = 150f
        const val FRONT_W = 52f
        const val FRONT_H = 52f
        const val FRONT_R = 10f
        /** The second row: left, centre, right. */
        val BENCH_X = listOf(16f, 65f, 114f)
        const val BENCH_Y = 234f
        const val BENCH_W = 46f
        const val BENCH_H = 52f
        const val BENCH_R = 9f
        const val CONSOLE_Y = 144f
        const val CONSOLE_H = 82f
        const val CONSOLE_W = 22f
        const val CONSOLE_R = 4f
        const val PAD_Y = 150f
        const val PAD_W = 8f
        const val PAD_H = 13f
        const val FRIDGE_Y = 190f
        const val FRIDGE_H = 18f
        const val REAR_FRIDGE_Y = 214f
        const val REAR_FRIDGE_H = 9f
        /** A pip's side, the gap between pips, its inset from the seat's foot and side. */
        const val PIP = 5f
        const val PIP_GAP = 2f
        const val PIP_PAD = 6f
        const val LAMP_R = 2.6f
        const val LAMP_GLOW = 6f
        const val LAMP_IN = 7f
        const val LAMP_DOWN = 7f
        /** The centre seat's leader ends this far under the car's top, between the rear seats. */
        const val CENTRE_LEADER = 292f

        /** The strap: from [STRAP_IN] in from the right and [STRAP_TOP] down to [STRAP_IN] in from the left, [STRAP_FOOT] over the foot. */
        const val STRAP_IN = 8f
        const val STRAP_TOP = 6f
        const val STRAP_FOOT = 14f
        const val STRAP = 2f
        val STRAP_DASH = floatArrayOf(4f, 3f)
        const val PIP_R = 1f
        const val WHEEL_STROKE = 2f
        const val WHEEL_DOT = 2.5f
        /** The pads either side of the console's middle, this far from it. */
        const val PAD_GAP = 1f
        const val PAD_R = 2f
        /** A fridge's box inside the console. */
        const val BOX_INSET = 2f
        const val BOX_R = 2f
        const val LAMP_GLOW_ALPHA = 0.25f
        /** Where leaders end: over the wheel to the cluster, over the pads, over a fridge's foot. */
        const val CLUSTER_LIFT = 18f
        const val PADS_LIFT = 4f
        const val FRIDGE_LIFT = 3f
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
        /** PDC-VCU's tile this far left of the car, level with the VCU. */
        const val DOMAIN_GAP = 24f
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
        /** The blade across the pins: the BMS says the contactor is closed. */
        const val CONTACTOR_CLOSED = "M504 360H536"
        val CONTACTOR_PINS = listOf(504f to 360f, 536f to 360f)
        const val CONTACTOR_PIN = 2.6f
        val CONTACTOR_LABEL = Triple(502f, 368f, 70f)
        /** The charge port's label under the charger, as the contactor's: its top-left and width. */
        val CHARGE_LABEL = Triple(110f, 416f, 100f)
        const val TRUNK = "M576 210V540"
        const val TAPS = "M576 210H628M576 320H628M576 440H628M576 540H628"
        const val SHAFTS = "M796 210H822M796 320H822M796 440H822M796 540H822"
        const val COUPLING = "M870 317H888M870 323H888"
        const val FEEDS = "M174 260H216M174 378H216M99 406V500"

        data class Machine(val x: Float, val y: Float, val glyph: String, val live: Boolean)

        val MACHINES = listOf(
            Machine(846f, 210f, "M", true), Machine(846f, 320f, "G", true),
            Machine(846f, 440f, "M", true), Machine(846f, 540f, "M", true),
        )
        const val MACHINE_R = 24f
        const val BUS_LABEL_RIGHT = 568f
        const val BUS_LABEL_Y = 203.5f
        /** A unit's place, and its width where it is not [UNIT_W]: the inverters on the bus, wide enough for «Инвертор задний правый». */
        data class Place(val x: Float, val y: Float, val w: Float? = null)

        val UNITS: Map<String, Place> = linkedMapOf(
            "FMCU" to Place(628f, 182f, 168f), "ISG" to Place(628f, 292f, 168f), "SMCU" to Place(628f, 412f, 168f),
            "SMCU3" to Place(628f, 512f, 168f), "ECM" to Place(888f, 292f), "PTC" to Place(24f, 232f),
            "DCOBC" to Place(24f, 350f), "LBMS" to Place(24f, 500f),
        )
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
        /** The converter's mode this far under its tile, its word a line under the label. */
        const val MODE_Y = 14f
        const val MODE_LINE = 18f
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
