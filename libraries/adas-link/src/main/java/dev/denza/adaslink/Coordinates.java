package dev.denza.adaslink;

/**
 * WGS-84 to the frame the stock map sends (its SDK frame), then onto the stock's
 * {@code Coord2DInt32} grid of 1/3 600 000 degree. The GCJ candidate is the port of
 * amap-injector research/stock-map-native/routing_coordinates.py (AutoDice inner routine, Moscow envelope only,
 * native 1/3 686 400 quantization); {@link Frame#WGS84} is the identity for inspection and for
 * routes outside Moscow.
 */
public final class Coordinates {
    public enum Frame { WGS84, NATIVE_GCJ_UNRESTRICTED_CANDIDATE }

    static final int NATIVE_SCALE = 3686400;
    static final double DEG = Math.PI / 180.0;

    /** {lon, lat} in the target frame, unquantized to the stock grid. */
    public static double[] toSdk(double lon, double lat, Frame frame) {
        if (!(Double.isFinite(lon) && Double.isFinite(lat))) throw new IllegalArgumentException("non-finite coordinate");
        if (frame == Frame.WGS84) return new double[]{lon, lat};
        if (!(lon >= 35.0 && lon <= 40.0 && lat >= 54.0 && lat <= 58.0))
            throw new IllegalArgumentException("Candidate port supports only the Moscow research envelope");
        double qlon = (long) (lon * NATIVE_SCALE) / (double) NATIVE_SCALE, qlat = (long) (lat * NATIVE_SCALE) / (double) NATIVE_SCALE;
        double[] r = forward(qlon, qlat);
        return new double[]{(long) (r[0] * NATIVE_SCALE) / (double) NATIVE_SCALE, (long) (r[1] * NATIVE_SCALE) / (double) NATIVE_SCALE};
    }

    /** Coord2DInt32 units of a target-frame degree value. */
    public static int units(double degrees) { return (int) Math.round(degrees * StockPath.SCALE); }

    /** The stock double for a target-frame degree value: the grid point, as {@code int / 3600000.0}. */
    public static double grid(double degrees) { return units(degrees) / 3600000.0d; }

    static double[] forward(double lon, double lat) {
        double x = lon - 105.0, y = lat - 35.0;
        double root = Math.sqrt(Math.abs(x));
        double common = (Math.sin(x * 18.84955592153876) + Math.sin(x * 6.283185307179586)) * 13.33333333333333;
        double dlat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * root
                + common
                + (Math.sin(y * Math.PI) + 2.0 * Math.sin(y * 1.047197551196598)) * 13.33333333333333
                + (Math.sin(y * 0.2617993877991494) + 2.0 * Math.sin(y * 0.1047197551196598)) * 106.6666666666667;
        double dlon = x + 300.0 + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * root
                + common
                + (Math.sin(x * Math.PI) + 2.0 * Math.sin(x * 1.047197551196598)) * 13.33333333333333
                + (Math.sin(x * 0.2617993877991494) + 2.0 * Math.sin(x * 0.1047197551196598)) * 100.0;
        double radians = 0.0174532925199433;
        double sinlat = Math.sin(lat * radians), coslat = Math.cos(lat * radians);
        double magic = 1.0 - 0.00669342 * sinlat * sinlat;
        double rootmagic = Math.sqrt(magic);
        return new double[]{lon + rootmagic * dlon / (6378245.0 * coslat) / radians,
                lat + magic * rootmagic * dlat / 6335552.7273521 / radians};
    }

    /** Haversine on the WGS-84 source, the prototype's metric (R = 6 371 008.8 m). */
    public static double distance(double lon1, double lat1, double lon2, double lat2) {
        // x * (pi / 180) exactly as Python's math.radians, so host oracle and module agree to the bit.
        double p = lat1 * DEG, q = lat2 * DEG;
        double s = Math.sin((p - q) / 2), t = Math.sin((lon1 - lon2) * DEG / 2);
        double d = s * s + Math.cos(p) * Math.cos(q) * t * t;
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(d)));
    }

    private Coordinates() {}
}
