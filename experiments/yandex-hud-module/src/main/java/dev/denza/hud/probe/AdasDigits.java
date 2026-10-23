package dev.denza.hud.probe;

/**
 * Luminofor's wide figures (tools/design-canvas/luminofor/spec.json, "digits"), the ones the ADAS
 * overlay prints: a stroked path on a 100-unit cap, advance = width + tracking. AdasOverlayTest
 * reads spec.json and holds this copy to it.
 */
final class AdasDigits {
    static final float CAP = 100, CAP_RATIO = 0.71f, TRACK = 18, STROKE_PER_SIZE = 0.075f, STROKE_MIN = 2.2f;
    static final String CHARS = "0123456789, ";
    static final float[] ADVANCE = {70, 70, 70, 70, 70, 70, 70, 70, 70, 70, 14, 30};
    static final String[] GLYPHS = {
            "M22 0H48A22 22 0 0 1 70 22V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78V22A22 22 0 0 1 22 0Z",
            "M20 20L44 0V100",
            "M0 22A22 22 0 0 1 22 0H48A22 22 0 0 1 70 22V34Q70 48 56 56L0 100H70",
            "M4 0H66L32 42H48A22 22 0 0 1 70 64V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78",
            "M52 100V0L0 68H70",
            "M66 0H4L2 44H48A22 22 0 0 1 70 66V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78",
            "M62 4Q52 0 36 0H22A22 22 0 0 0 0 22V78A22 22 0 0 0 22 100H48A22 22 0 0 0 70 78V66A22 22 0 0 0 48 44H0",
            "M0 0H70L24 100",
            "M22 0H48A18 18 0 0 1 66 18V28A18 18 0 0 1 48 46H22A18 18 0 0 1 4 28V18A18 18 0 0 1 22 0ZM22 46H48A22 22 0 0 1 70 68V78A22 22 0 0 1 48 100H22A22 22 0 0 1 0 78V68A22 22 0 0 1 22 46Z",
            "M8 96Q18 100 34 100H48A22 22 0 0 0 70 78V22A22 22 0 0 0 48 0H22A22 22 0 0 0 0 22V34A22 22 0 0 0 22 56H70",
            "M9 94L4 112",
            ""};

    static int glyph(char ch) { return CHARS.indexOf(ch); }

    /** Board pixels per glyph unit at a figure size. */
    static float k(float size) { return size * CAP_RATIO / CAP; }

    static float stroke(float size) { return Math.max(STROKE_MIN, size * STROKE_PER_SIZE); }

    static float width(String s, float size) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            int g = glyph(s.charAt(i));
            if (g < 0) continue;
            w += ADVANCE[g] + (i < s.length() - 1 ? TRACK : 0);
        }
        return w * k(size);
    }

    private AdasDigits() { }
}
