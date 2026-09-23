package dev.denza.apps.feature.trip

/**
 * Roboto's advance, over-estimated, for a JVM test that has no text measurement.
 *
 * The strip's collisions depend on how wide its Roboto strings come out, and a unit test has no
 * `Paint`. So this is a table: every glyph the strip prints, its advance in ems as headless Chrome
 * measured it off the Google Fonts Roboto the boards are rendered with (`canvas.measureText` at
 * 1000 px, 2026-09-23), the wider of weights 400 and 500, rounded up to the hundredth. A string's
 * width is the sum of its glyphs' advances times [MARGIN], and a glyph the table does not know is
 * a full em - wider than anything in it.
 *
 * **Measured with the Cyrillic subset loaded.** Google Fonts serves a face in per-script subsets and
 * `document.fonts.load` fetches only those its sample text touches, so a first measurement with the
 * default sample read Cyrillic off the system's fallback sans - whose lower case is a tenth to a
 * fifth *narrower* than Roboto's («а» 0.45 em against Roboto's 0.55). That table under-estimated
 * every Russian caption; this one was taken with a sample naming both scripts, and Roboto's
 * Cyrillic «а», «е», «о» come out at their Latin twins' widths, as they should.
 *
 * **Why it is conservative.** Rounding up and taking the wider weight each make the estimate wider
 * than the board's own measurement. [MARGIN] covers what the table cannot see: kerning, which moves
 * a pair by a few hundredths of an em either way, and the head unit's system Roboto, an older cut
 * than the Google Fonts one whose advances differ by a few per cent. A layout that clears with this
 * estimate clears on the car.
 */
internal object RobotoEstimate : StripGeometry.Measure {

    /** Eight per cent on top of the rounded-up table. */
    const val MARGIN = 1.08f

    private const val TABLE =
        "0 57 1 57 2 57 3 57 4 57 5 57 6 57 7 57 8 57 9 57 А 67 Б 63 В 64 Г 56 Д 76 Е 57 Ё 57 " +
            "Ж 96 З 61 И 72 Й 72 К 65 Л 71 М 88 Н 72 О 69 П 72 Р 64 С 66 Т 61 У 63 Ф 80 Х 64 Ц 75 " +
            "Ч 69 Ш 95 Щ 100 Ъ 77 Ы 87 Ь 63 Э 68 Ю 93 Я 64 а 55 б 57 в 58 г 42 д 62 е 54 ё 54 ж 82 " +
            "з 52 и 58 й 58 к 56 л 58 м 76 н 58 о 57 п 58 р 57 с 53 т 50 у 49 ф 73 х 51 ц 61 ч 55 " +
            "ш 82 щ 87 ъ 65 ы 81 ь 56 э 54 ю 82 я 56 A 67 B 64 C 66 D 66 E 57 F 56 G 69 H 72 I 29 " +
            "J 56 K 64 L 54 M 88 N 72 O 69 P 64 Q 69 R 63 S 61 T 61 U 66 V 65 W 89 X 64 Y 62 Z 61 " +
            "a 55 b 57 c 53 d 57 e 54 f 36 g 57 h 56 i 26 j 26 k 53 l 26 m 88 n 56 o 57 p 57 q 57 " +
            "r 36 s 52 t 34 u 56 v 50 w 76 x 51 y 49 z 51 . 28 , 23 : 27 ; 24 · 29 / 42 - 34 – 66 " +
            "— 79 + 57 ° 39 % 74 ( 35 ) 35 ! 27 ? 49 ' 18 \" 32 & 65 → 100 … 100"

    private val advance: Map<Char, Float> = TABLE.split(' ').chunked(2).associate { (glyph, ems) ->
        glyph.single() to ems.toFloat() / 100f
    } + (' ' to 0.25f)

    fun em(glyph: Char): Float = advance[glyph] ?: 1f

    override fun sans(text: String, size: Float, strong: Boolean): Float =
        text.fold(0f) { width, glyph -> width + em(glyph) } * size * MARGIN
}
