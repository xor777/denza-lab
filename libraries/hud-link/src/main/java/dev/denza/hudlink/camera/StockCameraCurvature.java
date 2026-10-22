package dev.denza.hudlink.camera;

/** Original GBL's three different curvature measures; point density is not a turn angle.
 * CalculateCurvature ELF0x12b7a68; segment aggregation is in BuildPathCache ELF0x12a1a30.
 * Coordinates are native XY doubles (Z ignored), lengths unsigned metres, arithmetic float32. */
public final class StockCameraCurvature {
    private StockCameraCurvature() { }

    public static final class Value {
        public final int method;
        public final float curvature;
        Value(int method, float curvature) { this.method = method; this.curvature = curvature; }
    }

    public static Value calculate(int roadClass, int formway, long lengthMetres, double[][] points) {
        if (formway == 3 || formway == 6 || formway == 8) {
            float sum = 0;
            for (int i = 1; i + 1 < points.length; i++) {
                double ax = points[i - 1][0] - points[i][0], ay = points[i - 1][1] - points[i][1];
                double bx = points[i + 1][0] - points[i][0], by = points[i + 1][1] - points[i][1];
                double cosine = (ax * bx + ay * by) / (Math.sqrt(ax * ax + ay * ay) * Math.sqrt(bx * bx + by * by));
                float angle = 0;
                // ARM FCMP/B.LE takes the first branch for unordered NaN as well. Ghidra's
                // C-like comparison loses this; original repeated-point probes contribute zero.
                if (Double.isNaN(cosine) || cosine <= -1) angle = 180;
                else if (cosine < 1) angle = (float) (Math.acos(cosine) * 57.295779513082195);
                sum += Math.abs(180f - angle);
            }
            return new Value(1, sum);
        }
        if (roadClass == 0 || roadClass == 6 || roadClass == 7) {
            float density = points.length >= 5 && lengthMetres != 0 ? (float) (points.length - 2) / ((float) lengthMetres / 1000f) : 0;
            return new Value(2, density);
        }
        return new Value(4, 0); // Assigned its segment aggregate when the path cache is built.
    }

    /** The native denominator includes every segment link, even those excluded from the numerator.
     * Caller supplies the original segment length and valid-link metadata, rather than summing
     * only qualifying links or using a geometry window that changes its meaning. */
    public static float segment(long totalLengthMetres, long[] linkLengths, int[] pointCounts) {
        if (linkLengths.length != pointCounts.length) throw new IllegalArgumentException("link metadata length");
        float sum = 0;
        for (int i = 0; i < linkLengths.length; i++) {
            if (pointCounts[i] < 5 || linkLengths[i] == 0) continue;
            float weight = (float) linkLengths[i] / (float) totalLengthMetres;
            float density = (float) (pointCounts[i] - 2) / ((float) linkLengths[i] / 1000f);
            sum += weight * density;
        }
        return sum;
    }
}
