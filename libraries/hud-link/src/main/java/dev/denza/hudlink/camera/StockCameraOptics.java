package dev.denza.hudlink.camera;

/**
 * The MapKit camera that draws what the stock fa3 HUD camera draws. The stock view is 800×800 with
 * the car at (400,560), cropped to (100,290,600,360) and halved to 300×180. A MapKit offscreen
 * window of 600×540 with its focus at the centre, cropped to its top 600×360 and halved, gives the
 * same output frame with the car at (150,135) - {@link #WINDOW_WIDTH}, {@link #CROP_HEIGHT}.
 *
 * <p>For seven stock poses (zoom 14..17 at the profile's pitch ceiling) MapKit's zoom, tilt and
 * vertical field of view were fitted on the pinned Navigator until a ground grid's scale,
 * foreshortening and perspective matched the original projection exactly
 * (research/yandex-hud-renderer/stock-camera-mapkit-calibration.csv). Between them the three
 * corrections are interpolated by stock zoom; the fitted field of view follows the stock fa3 FOV
 * table converted to the 540-pixel window's height (60/36/42° become 43/25/29°). A pitch below the
 * ceiling keeps its zoom's tilt correction: the fit was made at the ceiling only.
 */
public final class StockCameraOptics {
    public static final int WINDOW_WIDTH = 600, WINDOW_HEIGHT = 540;
    public static final float FOCUS_X = 300, FOCUS_Y = 270;
    /** The part of the window the HUD shows, from its top left, and the scale to the 300×180 frame. */
    public static final int CROP_WIDTH = 600, CROP_HEIGHT = 360;
    public static final float SCALE = .5f;

    private static final double[] STOCK_ZOOM = {14, 14.5, 15, 15.5, 16, 16.5, 17};
    private static final double[] STOCK_PITCH = {29, 29, 29, 33.91740798950195, 38.834815979003906, 42.14478302001953, 45.454750061035156};
    private static final double[] MAPKIT_ZOOM = {14.002759332219116, 14.364106251225582, 14.870273573923166, 15.258014025944835,
            15.830282089187559, 16.270871976213936, 16.90832931626604};
    private static final double[] MAPKIT_TILT = {28.585527141458723, 28.58742432972985, 28.586726920337114, 33.57719909211359,
            38.550195100826215, 41.890795940413604, 45.227656451482645};
    private static final double[] MAPKIT_FOV = {43.26202399518817, 34.01566379011234, 25.151501268142187, 27.222455737072547,
            29.33901430832413, 29.301276207686023, 29.273148081852366};

    /** MapKit's zoom, tilt and vertical field of view in degrees for the stock pose. */
    public static final class Pose {
        public final float zoom, tilt, fov;
        Pose(float zoom, float tilt, float fov) { this.zoom = zoom; this.tilt = tilt; this.fov = fov; }
    }

    private StockCameraOptics() { }

    public static Pose mapkit(float stockZoom, float stockPitch) {
        double zoom = Math.max(STOCK_ZOOM[0], Math.min(STOCK_ZOOM[STOCK_ZOOM.length - 1], stockZoom));
        int i = 0;
        while (i < STOCK_ZOOM.length - 2 && zoom > STOCK_ZOOM[i + 1]) i++;
        double t = (zoom - STOCK_ZOOM[i]) / (STOCK_ZOOM[i + 1] - STOCK_ZOOM[i]);
        double zoomOffset = lerp(STOCK_ZOOM[i] - MAPKIT_ZOOM[i], STOCK_ZOOM[i + 1] - MAPKIT_ZOOM[i + 1], t);
        double tiltOffset = lerp(STOCK_PITCH[i] - MAPKIT_TILT[i], STOCK_PITCH[i + 1] - MAPKIT_TILT[i + 1], t);
        double fov = lerp(MAPKIT_FOV[i], MAPKIT_FOV[i + 1], t);
        return new Pose((float) (stockZoom - zoomOffset), (float) Math.max(0, stockPitch - tiltOffset), (float) fov);
    }

    /** A window pixel on the 300×180 frame. */
    public static float frame(float window) { return window * SCALE; }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
