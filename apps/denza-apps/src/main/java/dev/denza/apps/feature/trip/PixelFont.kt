package dev.denza.apps.feature.trip

/**
 * A 5x7 dot-matrix font, Cyrillic and Latin, in the shape of the LED and
 * vacuum-fluorescent tickers of eighties consumer electronics.
 *
 * Written out as binary literals so each glyph is legible as a picture in the
 * source: a `1` is a lit dot. Capitals only — the displays this imitates had no
 * lowercase, and at this dot count a lowercase set would be mush. Callers
 * uppercase their text first.
 *
 * Cyrillic letters whose capital form is identical to a Latin one (А В Е К М Н
 * О Р С Т Х) share that glyph rather than repeating it.
 */
object PixelFont {

    const val COLUMNS = 5
    const val ROWS = 7

    /** Blank columns between characters. */
    const val TRACKING = 1

    private val A = intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
    private val B = intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110)
    private val C = intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110)
    private val D = intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110)
    private val E = intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111)
    private val F = intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000)
    private val G = intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111)
    private val H = intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
    private val I = intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111)
    private val J = intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100)
    private val K = intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001)
    private val L = intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111)
    private val M = intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001)
    private val N = intArrayOf(0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001)
    private val O = intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110)
    private val P = intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000)
    private val Q = intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101)
    private val R = intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001)
    private val S = intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110)
    private val T = intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100)
    private val U = intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110)
    private val V = intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100)
    private val W = intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001)
    private val X = intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001)
    private val Y = intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100)
    private val Z = intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111)

    private val BE = intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b11110)
    private val GHE = intArrayOf(0b11111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000)
    private val DE = intArrayOf(0b00110, 0b01010, 0b01010, 0b01010, 0b01010, 0b11111, 0b10001)
    private val ZHE = intArrayOf(0b10101, 0b10101, 0b10101, 0b11111, 0b10101, 0b10101, 0b10101)
    private val ZE = intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00001, 0b10001, 0b01110)
    private val II = intArrayOf(0b10001, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b10001)
    private val IISHORT = intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b10001)
    private val EL = intArrayOf(0b00111, 0b01001, 0b01001, 0b01001, 0b01001, 0b01001, 0b10001)
    private val PE = intArrayOf(0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001)
    private val UU = intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b01000, 0b10000)
    private val EF = intArrayOf(0b00100, 0b01110, 0b10101, 0b10101, 0b10101, 0b01110, 0b00100)
    private val TSE = intArrayOf(0b10010, 0b10010, 0b10010, 0b10010, 0b10010, 0b11111, 0b00001)
    private val CHE = intArrayOf(0b10001, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b00001)
    private val SHA = intArrayOf(0b10101, 0b10101, 0b10101, 0b10101, 0b10101, 0b10101, 0b11111)
    private val SHCHA = intArrayOf(0b10101, 0b10101, 0b10101, 0b10101, 0b10101, 0b11111, 0b00001)
    private val HARD = intArrayOf(0b11000, 0b01000, 0b01000, 0b01110, 0b01001, 0b01001, 0b01110)
    private val YERU = intArrayOf(0b10001, 0b10001, 0b10001, 0b11101, 0b10011, 0b10011, 0b11101)
    private val SOFT = intArrayOf(0b10000, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b11110)
    private val EREV = intArrayOf(0b01110, 0b10001, 0b00001, 0b00111, 0b00001, 0b10001, 0b01110)
    private val YU = intArrayOf(0b10010, 0b10101, 0b10101, 0b11101, 0b10101, 0b10101, 0b10010)
    private val YA = intArrayOf(0b01111, 0b10001, 0b10001, 0b01111, 0b00101, 0b01001, 0b10001)
    private val YO = intArrayOf(0b01010, 0b11111, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111)

    private val D0 = intArrayOf(0b01110, 0b10011, 0b10011, 0b10101, 0b11001, 0b11001, 0b01110)
    private val D1 = intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
    private val D2 = intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111)
    private val D3 = intArrayOf(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110)
    private val D4 = intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010)
    private val D5 = intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110)
    private val D6 = intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110)
    private val D7 = intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000)
    private val D8 = intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110)
    private val D9 = intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100)

    private val SPACE = intArrayOf(0, 0, 0, 0, 0, 0, 0)
    private val DOT = intArrayOf(0, 0, 0, 0, 0, 0b01100, 0b01100)
    private val COMMA = intArrayOf(0, 0, 0, 0, 0b00110, 0b00110, 0b01100)
    private val MIDDOT = intArrayOf(0, 0, 0b00110, 0b00110, 0, 0, 0)
    private val DASH = intArrayOf(0, 0, 0, 0b11111, 0, 0, 0)
    private val APOS = intArrayOf(0b00100, 0b00100, 0, 0, 0, 0, 0)
    private val QUOTE = intArrayOf(0b01010, 0b01010, 0, 0, 0, 0, 0)
    private val BANG = intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0, 0b00100)
    private val QUERY = intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0, 0b00100)
    private val LPAREN = intArrayOf(0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010)
    private val RPAREN = intArrayOf(0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000)
    private val COLON = intArrayOf(0, 0b01100, 0b01100, 0, 0b01100, 0b01100, 0)
    private val SLASH = intArrayOf(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000)
    private val AMP = intArrayOf(0b01100, 0b10010, 0b10100, 0b01000, 0b10101, 0b10010, 0b01101)
    private val PLUS = intArrayOf(0, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0)

    private val GLYPHS: Map<Char, IntArray> = buildMap {
        put('A', A); put('B', B); put('C', C); put('D', D); put('E', E); put('F', F)
        put('G', G); put('H', H); put('I', I); put('J', J); put('K', K); put('L', L)
        put('M', M); put('N', N); put('O', O); put('P', P); put('Q', Q); put('R', R)
        put('S', S); put('T', T); put('U', U); put('V', V); put('W', W); put('X', X)
        put('Y', Y); put('Z', Z)

        // Cyrillic capitals that are drawn identically to a Latin letter.
        put('А', A); put('В', B); put('Е', E); put('К', K); put('М', M); put('Н', H)
        put('О', O); put('Р', P); put('С', C); put('Т', T); put('Х', X)

        put('Б', BE); put('Г', GHE); put('Д', DE); put('Ж', ZHE); put('З', ZE)
        put('И', II); put('Й', IISHORT); put('Л', EL); put('П', PE); put('У', UU)
        put('Ф', EF); put('Ц', TSE); put('Ч', CHE); put('Ш', SHA); put('Щ', SHCHA)
        put('Ъ', HARD); put('Ы', YERU); put('Ь', SOFT); put('Э', EREV); put('Ю', YU)
        put('Я', YA); put('Ё', YO)

        put('0', D0); put('1', D1); put('2', D2); put('3', D3); put('4', D4)
        put('5', D5); put('6', D6); put('7', D7); put('8', D8); put('9', D9)

        put(' ', SPACE); put('.', DOT); put(',', COMMA); put('·', MIDDOT)
        put('-', DASH); put('–', DASH); put('—', DASH); put('\'', APOS); put('’', APOS)
        put('"', QUOTE); put('«', QUOTE); put('»', QUOTE); put('!', BANG); put('?', QUERY)
        put('(', LPAREN); put(')', RPAREN); put(':', COLON); put('/', SLASH)
        put('&', AMP); put('+', PLUS)
    }

    /** The glyph for [character], or a blank for anything not in the set. */
    fun glyph(character: Char): IntArray = GLYPHS[character] ?: SPACE

    /** Width of [text] in dot columns, including the gap after each character. */
    fun widthInDots(text: String): Int =
        if (text.isEmpty()) 0 else text.length * (COLUMNS + TRACKING) - TRACKING

    /** Normalises text to what the font can actually draw. */
    fun prepare(text: String): String = text.uppercase()
}
