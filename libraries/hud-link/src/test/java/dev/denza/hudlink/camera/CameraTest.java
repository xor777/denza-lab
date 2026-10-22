package dev.denza.hudlink.camera;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Independent samples produced by original ARM64 GBL functions, plus boundary regressions. */
public final class CameraTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        StockCameraQueue queue = null;
        String previous = "";
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.startsWith("#") || line.startsWith("case,") || line.isEmpty()) continue;
            String[] row = line.split(",");
            float from = Float.parseFloat(row[1]), target = Float.parseFloat(row[2]);
            if (!row[0].equals(previous)) {
                previous = row[0];
                queue = new StockCameraQueue();
                queue.target(from, target, Integer.parseInt(row[3]), Float.parseFloat(row[4]), 0);
            }
            Float actual = queue.pop(Long.parseLong(row[5]));
            boolean returned = row[6].equals("1");
            check((actual != null) == returned, row[0] + " native return at " + row[5]);
            if (returned) {
                // Frida records wall time after the native monotonic-ms evaluation. Allow 2 ms.
                double tolerance = Math.abs(target - from) * .002 + 1e-5;
                check(Math.abs(actual - Float.parseFloat(row[7])) <= tolerance,
                        row[0] + " native value at " + row[5] + ": " + actual + " vs " + row[7]);
            }
        }
        queue = new StockCameraQueue();
        queue.target(16, 17, 10, .7f, 0);
        check(queue.pop(0) == null, "immediate sample keeps the previous pose");
        queue.target(16, 14, 0, .7f, 10);
        check(Math.abs(queue.pop(100) - 16.07f) < 1e-5, "invalid count cannot replace the active transition");
        queue.target(16.07f, -9999, 10, .7f, 100);
        check(Math.abs(queue.pop(200) - 16.14f) < 1e-5, "sentinel cannot replace the active transition");
        queue.target(16.14f, 16.14f, 10, .7f, 200);
        check(Math.abs(queue.pop(300) - 16.21f) < 1e-5, "equal target preserves the active native trajectory");
        check(StockCameraQueue.nearestCourse(359, 1) == 361, "north crossing takes the short branch");
        check(StockCameraQueue.nearestCourse(1, 359) == -1, "reverse north crossing takes the short branch");
        check(StockCameraQueue.course(361) == 1 && StockCameraQueue.course(-1) == 359, "course normalized");
        double maxError = 0;
        for (String line : Files.readAllLines(Paths.get(args[1]))) {
            if (line.startsWith("#") || line.startsWith("zoom,") || line.isEmpty()) continue;
            String[] values = line.split(",");
            double[] row = new double[values.length];
            for (int i = 0; i < values.length; i++) row[i] = Double.parseDouble(values[i]);
            double[] point = StockCameraProjection.project(row[5], row[6], row[3], row[4], row[0], row[1], row[2]);
            double error = point == null ? Double.POSITIVE_INFINITY : Math.hypot(point[0] - row[7], point[1] - row[8]);
            maxError = Math.max(maxError, error);
            // Native quantizes its world coordinate to an integer. 0.15 raw px = 0.075 HUD px.
            check(error < .15, "native fa3 projection z=" + row[0] + " pitch=" + row[1] + " error=" + error);
        }
        check(Math.abs(StockCameraProjection.pixelToWorld(14.5) - 39.837406158447266) < 3e-5,
                "native fractional zoom interpolates pixel-to-world, not a power of two");
        check(Math.abs(StockCameraProjection.maxPitch(16.5) - 42.14478302001953) < 1e-5,
                "native profile pitch ceiling interpolates between levels");
        for (String line : Files.readAllLines(Paths.get(args[2]))) {
            if (line.startsWith("#") || line.startsWith("table,") || line.isEmpty()) continue;
            String[] row = line.split(",");
            StockCameraRules.Input input = new StockCameraRules.Input(Float.parseFloat(row[1]), Float.parseFloat(row[2]),
                    Float.parseFloat(row[3]), Float.parseFloat(row[4]), Float.parseFloat(row[5]));
            StockCameraRules.Rule rule = StockCameraRules.select(StockCameraRules.Table.valueOf(row[0]), input);
            check((rule == null ? -1 : rule.index) == Integer.parseInt(row[6]), "native first matching " + line);
            if (rule != null) {
                check(same(rule.target, Float.parseFloat(row[7])), "native target " + line);
                check(same(rule.lower, Float.parseFloat(row[8])), "native lower bound " + line);
                check(same(rule.upper, Float.parseFloat(row[9])), "native upper bound " + line);
            }
        }
        check(StockCameraRules.select(StockCameraRules.Table.BASE_ZOOM,
                new StockCameraRules.Input(Float.NaN, Float.NaN, 0, 16, 2)) == null,
                "missing road class cannot silently select the freeway rule");
        StockCameraRules.Input input = new StockCameraRules.Input(5, 0, 0, 16, 2);
        StockCameraRules.Rule base = StockCameraRules.select(StockCameraRules.Table.BASE_ZOOM, input);
        StockCameraRules.Rule keypoint = StockCameraRules.select(StockCameraRules.Table.KEYPOINT_PITCH, input);
        StockCameraRules.Target result = new StockCameraRules.Target(-9999, -777);
        check(!StockCameraRules.simpleZoom(null, result) && result.value == -9999 && result.count == -777,
                "original disabled SimpleZoom leaves outputs untouched");
        check(StockCameraRules.simpleZoom(base, result) && result.value == 17 && result.count == 10,
                "original enabled SimpleZoom result");
        result = new StockCameraRules.Target(-9999, -777);
        check(StockCameraRules.simplePitch(null, result) && result.value == -9999 && result.count == 10,
                "original disabled SimplePitch returns true with sentinel");
        check(StockCameraRules.simplePitch(keypoint, result) && result.value == 30 && result.count == 10,
                "original enabled SimplePitch result");
        for (String line : Files.readAllLines(Paths.get(args[3]))) {
            if (line.startsWith("#") || line.startsWith("case,") || line.isEmpty()) continue;
            String[] row = line.split(",");
            String[] coordinates = row[4].isEmpty() ? new String[0] : row[4].split(";");
            double[][] points = new double[coordinates.length][2];
            for (int i = 0; i < coordinates.length; i++) {
                String[] xy = coordinates[i].split(":");
                points[i][0] = Double.parseDouble(xy[0]); points[i][1] = Double.parseDouble(xy[1]);
            }
            StockCameraCurvature.Value value = StockCameraCurvature.calculate(Integer.parseInt(row[1]), Integer.parseInt(row[2]),
                    Long.parseLong(row[3]), points);
            check(value.method == Integer.parseInt(row[5]), "native curvature method " + row[0]);
            float expected = Float.parseFloat(row[6]);
            check(same(value.curvature, expected), "native curvature value " + row[0] + ": " + value.curvature + " vs " + expected);
        }
        check(StockCameraCurvature.segment(400, new long[]{100, 300}, new int[]{5, 4}) == 7.5f,
                "segment denominator includes links below the five-point threshold");
        check(StockCameraCurvature.segment(0, new long[0], new int[0]) == 0,
                "empty segment keeps the original zero accumulator");
        System.out.println("Native projection max error: " + maxError + " raw px");
        System.out.println("CameraTest: " + checks + " assertions passed");
    }
    private static boolean same(float a, float b) { return Float.floatToIntBits(a) == Float.floatToIntBits(b); }
}
