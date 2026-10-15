package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.List;

/** Pure route geometry for the stock Navigation Fusion protocol; never synthesizes a turn. */
final class HudAr {
    static final int MAX_POINTS = 256;
    static final double EXIT_METERS = 120;
    static final long MAX_AGE_MS = 2500;

    static final class Point {
        final double lon, lat;
        Point(double lon, double lat) { this.lon = lon; this.lat = lat; }
        boolean valid() { return Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90; }
        String tuple() { return decimal(lon) + "," + decimal(lat) + ",0"; }
    }

    static final class Position {
        final int segment;
        final double fraction;
        Position(int segment, double fraction) { this.segment = segment; this.fraction = fraction; }
    }

    static final class Pose {
        final Point point;
        final double heading, speed, altitude;
        final long expiresAt;
        private Pose(Point point, double heading, double speed, double altitude, long expiresAt) {
            this.point = point; this.heading = heading; this.speed = speed; this.altitude = altitude; this.expiresAt = expiresAt;
        }
        static Pose read(Point point, Double heading, Double speed, Double accuracy, Double altitude,
                         long timestamp, long wallNow, long elapsedNow) {
            long age = wallNow - timestamp;
            if (!point.valid() || heading == null || !Double.isFinite(heading) || heading < 0 || heading > 360
                    || speed == null || !Double.isFinite(speed) || speed < 0 || speed > 100
                    || accuracy == null || !Double.isFinite(accuracy) || accuracy < 0 || accuracy > 20
                    || timestamp <= 0 || age < -1000 || age > MAX_AGE_MS) return null;
            double height = altitude == null ? 0 : altitude;
            if (!Double.isFinite(height) || height < -1000 || height > 10000) return null;
            return new Pose(point, heading % 360, speed, height, elapsedNow + MAX_AGE_MS - Math.max(0, age));
        }
    }

    /** Immutable, contains no MapKit handles; safe to serialize on the output worker. */
    static final class Frame {
        final Pose pose;
        final Point target;
        final Point[] line;
        final double progress;
        Frame(Pose pose, Point target, Point[] line, double progress) {
            this.pose = pose; this.target = target; this.line = line; this.progress = progress;
        }
        String lineJson() {
            StringBuilder out = new StringBuilder(line.length * 32).append('[');
            for (Point point : line) {
                if (out.length() > 1) out.append(',');
                out.append('[').append(point.tuple()).append(']');
            }
            return out.append(']').toString();
        }
    }

    /** Cached once per native route id. Distances locate slices, never replace route vertices. */
    static final class Route {
        private final Point[] points;
        private final double[] distances;
        Route(Point[] points) {
            if (points.length < 2 || points.length > 200_000) throw new IllegalArgumentException("AR route size");
            this.points = points.clone();
            distances = new double[points.length];
            for (int i = 0; i < points.length; i++) {
                if (points[i] == null || !points[i].valid()) throw new IllegalArgumentException("AR coordinate");
                if (i > 0) distances[i] = distances[i - 1] + distance(points[i - 1], points[i]);
            }
            if (!(distances[points.length - 1] > 0)) throw new IllegalArgumentException("Empty AR route");
        }

        Frame frame(Position here, Position turn, Position following, Pose pose) {
            if (pose == null || !valid(here) || !valid(turn) || (following != null && !valid(following))) return null;
            double start = along(here), junction = along(turn), total = distances[points.length - 1];
            double end = Math.min(total, junction + EXIT_METERS);
            if (following != null) end = Math.min(end, along(following));
            // A passed junction or an ambiguous/missing exit must not retain a previous flying arrow.
            if (junction - start < .5 || end - junction < 2) return null;
            Point first = point(here), target = point(turn);
            if (distance(first, pose.point) > 30) return null;
            List<Point> line = new ArrayList<>();
            add(line, first);
            boolean targetAdded = false;
            for (int i = here.segment + 1; i < points.length && distances[i] < end; i++) {
                if (distances[i] <= start) continue;
                if (!targetAdded && distances[i] >= junction) { add(line, target); targetAdded = true; }
                add(line, points[i]);
                if (line.size() > MAX_POINTS) return null; // No arbitrary decimation of bends.
            }
            if (!targetAdded) add(line, target);
            add(line, at(end));
            if (line.size() < 3 || line.size() > MAX_POINTS) return null;
            return new Frame(pose, target, line.toArray(new Point[0]), start / total);
        }

        private boolean valid(Position p) {
            return p != null && p.segment >= 0 && p.segment < points.length - 1
                    && Double.isFinite(p.fraction) && p.fraction >= 0 && p.fraction <= 1;
        }
        private double along(Position p) { return distances[p.segment] + p.fraction * (distances[p.segment + 1] - distances[p.segment]); }
        private Point point(Position p) { return between(points[p.segment], points[p.segment + 1], p.fraction); }
        private Point at(double along) {
            int low = 0, high = points.length - 1;
            while (high - low > 1) { int mid = (low + high) >>> 1; if (distances[mid] < along) low = mid; else high = mid; }
            double span = distances[high] - distances[low];
            return between(points[low], points[high], span > 0 ? (along - distances[low]) / span : 0);
        }
    }

    static boolean supported(String action) {
        return action != null && !action.equals("BOARD_FERRY") && !action.equals("LEAVE_FERRY")
                && !action.equals("FINISH") && !action.equals("WAYPOINT") && NavigatorSource.manoeuvre(action) != null;
    }
    private static void add(List<Point> points, Point point) {
        if (points.isEmpty() || distance(points.get(points.size() - 1), point) > .01) points.add(point);
    }
    private static Point between(Point a, Point b, double fraction) {
        double lon = a.lon + wrap(b.lon - a.lon) * fraction;
        return new Point(wrap(lon), a.lat + (b.lat - a.lat) * fraction);
    }
    private static double wrap(double degrees) { return ((degrees + 540) % 360) - 180; }
    static double distance(Point a, Point b) {
        double lat = Math.toRadians(b.lat - a.lat), lon = Math.toRadians(wrap(b.lon - a.lon));
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.pow(Math.sin(lon / 2), 2);
        return 12_742_017.6 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
    private static String decimal(double value) { return Double.toString(Math.round(value * 1e7) / 1e7); }
}
