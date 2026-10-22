package dev.denza.hudlink.camera;

/** Measured fa3 HUD optics, kept in GBL's full 800px coordinate space before its crop.
 * The original projection-matrix/grid oracle is pinned in stock-camera-projection-oracle.csv.
 * These are profile measurements, not a generic conversion between SDK zoom numbers. */
public final class StockCameraProjection {
    private StockCameraProjection() { }

    private static final double WORLD = 268435456d;
    private static final double[] PIXEL_TO_WORLD = {53.11653137207031, 26.55825424194336, 13.456192970275879, 6.373991012573242};
    private static final double[] FOV = {60, 36, 42, 42};
    private static final double[] MAX_PITCH = {29, 29, 38.834815979003906, 45.454750061035156};

    public static double pixelToWorld(double zoom) { return interpolate(PIXEL_TO_WORLD, zoom); }
    public static double fov(double zoom) { return interpolate(FOV, zoom); }
    public static double maxPitch(double zoom) { return interpolate(MAX_PITCH, zoom); }

    private static double interpolate(double[] table, double zoom) {
        double bounded = Math.max(14, Math.min(17, zoom));
        int index = Math.min(2, (int) bounded - 14);
        return table[index] + (table[index + 1] - table[index]) * (bounded - 14 - index);
    }

    /** The zoom whose world units per pixel are {@code perPixel}: the table's inverse, held to 14..17. */
    public static double zoomFor(double perPixel) {
        if (!(perPixel < PIXEL_TO_WORLD[0])) return 14;
        for (int i = 0; i < PIXEL_TO_WORLD.length - 1; i++) {
            if (perPixel >= PIXEL_TO_WORLD[i + 1]) return 14 + i + (PIXEL_TO_WORLD[i] - perPixel) / (PIXEL_TO_WORLD[i] - PIXEL_TO_WORLD[i + 1]);
        }
        return 17;
    }

    /** World units east and north of the centre, as the projection measures them. */
    public static double[] world(double latitude, double longitude, double centerLatitude, double centerLongitude) {
        return new double[]{((longitude - centerLongitude + 540) % 360 - 180) / 360 * WORLD,
                (mercator(latitude) - mercator(centerLatitude)) / (2 * Math.PI) * WORLD};
    }

    /** Full native viewport pixels. Null means the point is behind the perspective horizon. */
    public static double[] project(double latitude, double longitude, double centerLatitude, double centerLongitude,
                            double zoom, double pitch, double course) {
        double dx = ((longitude - centerLongitude + 540) % 360 - 180) / 360 * WORLD;
        double dy = (mercator(latitude) - mercator(centerLatitude)) / (2 * Math.PI) * WORLD;
        double angle = Math.toRadians(course), tilt = Math.toRadians(pitch);
        double right = (dx * Math.cos(angle) - dy * Math.sin(angle)) / pixelToWorld(zoom);
        double forward = (dx * Math.sin(angle) + dy * Math.cos(angle)) / pixelToWorld(zoom);
        double focal = 400 / Math.tan(Math.toRadians(fov(zoom) / 2));
        double denominator = 1 + forward * Math.sin(tilt) / focal;
        if (denominator <= 0) return null;
        return new double[]{400 + right / denominator, 560 - forward * Math.cos(tilt) / denominator};
    }

    private static double mercator(double latitude) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(Math.max(-85, Math.min(85, latitude))) / 2));
    }

    public static double pixelsPerMetre(double latitude, double zoom) {
        return WORLD / (40075016.68557849 * Math.cos(Math.toRadians(latitude)) * pixelToWorld(zoom));
    }
}
