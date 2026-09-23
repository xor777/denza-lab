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
        "0 57 1 57 2 57 3 57 4 57 5 57 6 57 7 57 8 57 9 57 " +
            "А 73 Б 67 В 67 Г 56 Д 68 Е 62 Ё 67 Ж 97 З 62 И 75 Й 75 К 73 Л 68 М 89 Н 73 О 73 П 73 " +
            "Р 56 С 67 Т 62 У 75 Ф 75 Х 73 Ц 73 Ч 69 Ш 92 Щ 93 Ъ 75 Ы 93 Ь 62 Э 67 Ю 94 Я 69 " +
            "а 45 б 50 в 46 г 40 д 45 е 45 ё 45 ж 75 з 40 и 52 й 52 к 50 л 50 м 61 н 50 о 50 п 50 " +
            "р 50 с 45 т 46 у 50 ф 73 х 50 ц 50 ч 50 ш 76 щ 76 ъ 56 ы 68 ь 46 э 45 ю 75 я 47 " +
            "A 67 B 64 C 66 D 66 E 57 F 56 G 69 H 72 I 29 J 56 K 64 L 54 M 88 N 72 O 69 P 64 Q 69 " +
            "R 63 S 61 T 61 U 66 V 65 W 89 X 64 Y 62 Z 61 a 55 b 57 c 53 d 57 e 54 f 36 g 57 h 56 " +
            "i 26 j 26 k 53 l 26 m 88 n 56 o 57 p 57 q 57 r 36 s 52 t 34 u 56 v 50 w 76 x 51 y 49 " +
            "z 51 . 28 , 23 : 27 ; 24 · 29 / 42 - 34 – 66 — 79 + 57 ° 39 % 74 ( 35 ) 35 ! 27 ? 49 " +
            "' 18 \" 32 & 65 → 100 … 100"

    private val advance: Map<Char, Float> = TABLE.split(' ').chunked(2).associate { (glyph, ems) ->
        glyph.single() to ems.toFloat() / 100f
    } + (' ' to 0.25f)

    fun em(glyph: Char): Float = advance[glyph] ?: 1f

    override fun sans(text: String, size: Float, strong: Boolean): Float =
        text.fold(0f) { width, glyph -> width + em(glyph) } * size * MARGIN
}
