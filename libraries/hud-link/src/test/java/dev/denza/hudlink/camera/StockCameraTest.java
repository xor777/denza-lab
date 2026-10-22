package dev.denza.hudlink.camera;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The stock camera on a synthetic route: which record and targets it chooses, how the queues carry
 * them, the HUD's bounds, and the MapKit optics at the calibrated poses. Plain main, as CameraTest,
 * so the YandexHUD build runs it without JUnit; StockCameraJUnitTest runs both under Gradle.
 */
public final class StockCameraTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    /** A straight route north from Moscow's latitude, a vertex every {@code step} metres. */
    private static StockCamera.Route straight(double length, double step, boolean highway) {
        int n = (int) Math.round(length / step) + 1;
        double[] lat = new double[n], lon = new double[n], metres = new double[n];
        boolean[] high = new boolean[n - 1];
        for (int i = 0; i < n; i++) {
            metres[i] = i * step;
            lat[i] = 55.75 + Math.toDegrees(metres[i] / 6371008.8);
            lon[i] = 37.6;
        }
        Arrays.fill(high, highway);
        return new StockCamera.Route(lat, lon, metres, high);
    }

    /** Fixes every second from {@code from} to {@code to} metres at 10 m/s, frames at 5 fps; the last pose. */
    private static StockCamera.Pose drive(StockCamera camera, StockCamera.Route route, List<StockCamera.Keypoint> keypoints,
                                          double from, double to, double course, int limit, long[] clock, float[] maxStep) {
        StockCamera.Pose pose = camera.frame(clock[0]);
        for (double at = from; at <= to; at += 10) {
            camera.locate(route, keypoints, at, course, limit, clock[0]);
            for (int frame = 0; frame < 5; frame++) {
                clock[0] += 200;
                StockCamera.Pose next = camera.frame(clock[0]);
                if (maxStep != null) {
                    maxStep[0] = Math.max(maxStep[0], Math.abs(next.zoom - pose.zoom));
                    maxStep[1] = Math.max(maxStep[1], Math.abs(next.pitch - pose.pitch));
                }
                pose = next;
            }
        }
        return pose;
    }

    public static void main(String[] args) throws Exception {
        StockCamera.Policy policy = new StockCamera.Policy();
        StockCamera camera = new StockCamera(policy);
        StockCamera.Pose start = camera.frame(0);
        check(start.zoom == 16 && Math.abs(start.pitch - 33.741604f) < 1e-5 && start.strategy == StockCamera.BASE,
                "the stock HUD's untouched pose before any target");

        // A city main road with a sparse polyline: base zoom 16, pitch asks 60 and stops at the ceiling.
        StockCamera.Route city = straight(4000, 100, false);
        List<StockCamera.Keypoint> keypoints = Arrays.asList(new StockCamera.Keypoint(StockCamera.ACTION, 2500),
                new StockCamera.Keypoint(StockCamera.END, 4000));
        long[] clock = {0};
        float[] step = {0, 0};
        StockCamera.Pose base = drive(camera, city, keypoints, 0, 300, 0, 60, clock, step);
        check(base.roadClass == 7 && base.strategy == StockCamera.BASE, "60 km/h off a highway is a main road on its base record");
        check(Math.abs(base.zoom - 16) < 1e-4, "main road under curvature 35: base zoom 16, got " + base.zoom);
        check(Math.abs(base.pitch - StockCameraProjection.maxPitch(16)) < 1e-4, "base pitch 60 held under the ceiling: " + base.pitch);
        check(step[1] <= 15f / 10 + 1e-4, "pitch moves at most maxDelta/count a frame: " + step[1]);

        // 1100 m before the turn its Action record starts: the far manoeuvre fits below 14, the rule's lower bound 15 holds.
        step[0] = step[1] = 0;
        StockCamera.Pose far = drive(camera, city, keypoints, 1400, 1500, 0, 60, clock, step);
        check(far.strategy == StockCamera.ACTION, "within the main road's 1100 m the Action record is live");
        check(Math.abs(far.zoom - 15) < .05, "a far manoeuvre zooms out to the rule's lower bound: " + far.zoom);
        check(Math.abs(far.pitch - Math.min(30, StockCameraProjection.maxPitch(far.zoom))) < 1e-3,
                "a keypoint record asks pitch 30, under the zoom's ceiling: " + far.pitch);
        check(step[0] <= .7f / 10 + 1e-5, "zoom moves at most 0.07 a frame: " + step[0]);

        // Closing in, the turn is kept at the rectangle's top: the zoom climbs to the HUD's 17.
        float previous = far.zoom;
        boolean climbing = true;
        StockCamera.Pose near = far;
        for (double at = 1600; at <= 2450; at += 50) {
            near = drive(camera, city, keypoints, at, at + 40, 0, 60, clock, null);
            climbing &= near.zoom >= previous - 1e-4;
            previous = near.zoom;
        }
        check(climbing, "approaching a manoeuvre never zooms out");
        check(Math.abs(near.zoom - 17) < 1e-4, "50 m before the turn the HUD's maximum zoom: " + near.zoom);

        // After the turn the base record is back.
        StockCamera.Pose after = drive(camera, city, keypoints, 2510, 2700, 0, 60, clock, null);
        check(after.strategy == StockCamera.BASE, "a passed manoeuvre gives back the base record");

        // The finish: EndPoint never zooms out within its record.
        previous = Float.NEGATIVE_INFINITY;
        boolean growing = true;
        for (double at = 2950; at < 3990; at += 40) {
            StockCamera.Pose end = drive(camera, city, keypoints, at, at + 30, 0, 60, clock, null);
            check(end.strategy == StockCamera.END, "within 1100 m of the finish the EndPoint record");
            growing &= end.zoom >= previous - 1e-4 || previous == Float.NEGATIVE_INFINITY;
            previous = Math.max(previous, end.zoom);
        }
        check(growing, "EndPoint zoom only grows");

        // A manoeuvre behind the course: no zoom target, the zoom stays where it is.
        camera.reset();
        clock[0] = 0;
        StockCamera.Pose turned = drive(camera, city, keypoints, 1500, 1600, 180, 60, clock, null);
        check(turned.strategy == StockCamera.ACTION && Math.abs(turned.zoom - 16) < 1e-4,
                "the stock angle test drops a keypoint 90° or more off the course: " + turned.zoom);

        // A motorway: base zoom 14, pitch at the low ceiling.
        camera.reset();
        clock[0] = 0;
        StockCamera.Route motorway = straight(8000, 400, true);
        StockCamera.Pose fast = drive(camera, motorway, Collections.<StockCamera.Keypoint>emptyList(), 0, 600, 0, 110, clock, null);
        check(fast.roadClass == 0 && Math.abs(fast.zoom - 14) < 1e-4, "a highway at 110 is a freeway at zoom 14: " + fast.zoom);
        check(Math.abs(fast.pitch - StockCameraProjection.maxPitch(14)) < 1e-4, "freeway pitch under the zoom-14 ceiling: " + fast.pitch);
        check(camera.roadClass(true, 80) == 6 && camera.roadClass(false, 40) == 9 && camera.roadClass(true, 0) == 0,
                "highway and limit stand in for the road class");

        // A dense polyline is curvy by the stock density measure: main road zoom 17.
        camera.reset();
        clock[0] = 0;
        StockCamera.Pose curvy = drive(camera, straight(4000, 10, false), Collections.<StockCamera.Keypoint>emptyList(), 0, 200, 0, 60, clock, null);
        check(Math.abs(curvy.zoom - 17) < 1e-4, "50+ vertices a km is curvature over 35: zoom 17, got " + curvy.zoom);

        // MapKit optics: the calibration's poses come back exactly.
        String calibration = args.length > 0 ? args[0] : "research/yandex-hud-renderer/stock-camera-mapkit-calibration.csv";
        int rows = 0;
        for (String line : Files.readAllLines(Paths.get(calibration))) {
            if (line.startsWith("#") || line.startsWith("stockZoom") || line.isEmpty()) continue;
            String[] v = line.split(",");
            StockCameraOptics.Pose pose = StockCameraOptics.mapkit(Float.parseFloat(v[0]), Float.parseFloat(v[1]));
            check(Math.abs(pose.zoom - Double.parseDouble(v[2])) < 1e-4 && Math.abs(pose.tilt - Double.parseDouble(v[3])) < 1e-4
                    && Math.abs(pose.fov - Double.parseDouble(v[4])) < 1e-4, "calibrated MapKit pose " + line);
            rows++;
        }
        check(rows == 7, "seven calibrated poses");
        StockCameraOptics.Pose between = StockCameraOptics.mapkit(16.25f, 30);
        check(between.zoom > 15.83f && between.zoom < 16.28f && between.tilt < 30 && between.tilt > 29.5f && between.fov > 29.2f,
                "between poses the corrections interpolate; a pitch under the ceiling keeps its tilt offset");
        check(StockCameraOptics.frame(StockCameraOptics.FOCUS_Y) == 135 && StockCameraOptics.frame(StockCameraOptics.CROP_HEIGHT) == 180,
                "the window's focus is the frame's car point, its crop the frame");
        System.out.println("StockCameraTest: " + checks + " assertions passed");
    }
}
