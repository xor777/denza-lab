package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Where each road-event icon stands, mirroring placeEvents() in research/yandex-hud-renderer/hud-scene.js.
 * Plain Java on purpose: the host tests run it without Android or the theme parser.
 */
final class HudEvents {
    /** An event on screen: its icon kind, its point on the route, metres to it along the route, the limit for a camera. */
    static final class Mark {
        final String key, kind; final float x, y; final int limit; final double distance;
        Mark(String key, String kind, float x, float y, int limit, double distance) {
            this.key = key; this.kind = kind; this.x = x; this.y = y; this.limit = limit; this.distance = distance;
        }
    }
    /** A placed icon: the side it took and its box, in frame pixels. */
    static final class Placed {
        final Mark mark; final String kind, side; final float[] box;
        Placed(Mark mark, String kind, String side, float[] box) { this.mark = mark; this.kind = kind; this.side = side; this.box = box; }
    }

    /** Which event of a place stands for it, most important first; mirrors RANK in hud-scene.js. */
    static final List<String> RANK = Arrays.asList("camera-limit", "camera", "lane-camera", "crossroad-camera", "no-stopping-camera",
            "mobile-camera", "closed", "accident", "railway", "bump", "works", "school", "crossroad", "overtaking", "drawbridge", "police", "danger");

    final int max, width, height;
    /** Route samples (3 px apart) a corner may touch when no side is clear; more and the icon is not drawn. */
    final int graze;
    final float edge, cover, gap, merge, span;
    final float[] cursor;
    final String[] sides;
    /** Icon box sizes by kind, [w, h]. */
    final Map<String, float[]> sizes;

    HudEvents(int max, int width, int height, float edge, float cover, float gap, float merge, float span, int graze,
              float[] cursor, String[] sides, Map<String, float[]> sizes) {
        this.max = max; this.width = width; this.height = height; this.edge = edge; this.cover = cover; this.gap = gap; this.graze = graze;
        this.merge = merge; this.span = span; this.cursor = cursor; this.sides = sides; this.sizes = sizes;
    }

    /**
     * One icon a place: events in route order, each within {@link #merge} metres of the one before and
     * the place no longer than {@link #span}, are one place, shown by its most important event (RANK).
     * Clusters of icons were what annoyed on the glass (owner, 2026-10-18).
     */
    List<Mark> places(List<Mark> marks) {
        List<Mark> order = new ArrayList<>();
        for (Mark mark : marks) if (sizes.containsKey(kind(mark)) && Float.isFinite(mark.x) && Float.isFinite(mark.y)) order.add(mark);
        Collections.sort(order, (a, b) -> Double.compare(a.distance, b.distance));
        List<Mark> result = new ArrayList<>();
        double start = 0, end = 0;
        for (Mark mark : order) {
            int last = result.size() - 1;
            if (last >= 0 && mark.distance - end <= merge && mark.distance - start <= span) {
                end = mark.distance;
                if (RANK.indexOf(kind(mark)) < RANK.indexOf(kind(result.get(last)))) result.set(last, mark);
                continue;
            }
            result.add(mark);
            start = end = mark.distance;
        }
        return result;
    }

    /**
     * The nearest {@link #max} places. Each tries the sides in order and takes the first that is on
     * screen, off the cursor, off the route and not under the icon placed before it by more than
     * {@link #cover} of its area; failing that, the side with the least route under it if no more than
     * {@link #graze} samples: at the cursor's nose the only side left lay on the route ahead (emulator, 2026-10-18).
     * route: x0, y0, x1, y1, ...
     */
    List<Placed> place(List<Mark> marks, float[] route) {
        List<Placed> placed = new ArrayList<>();
        for (Mark mark : places(marks)) {
            if (placed.size() >= max) break;
            String kind = kind(mark);
            float[] size = sizes.get(kind);
            if (size == null || !Float.isFinite(mark.x) || !Float.isFinite(mark.y)) continue;
            if (mark.x < edge || mark.x > width - edge || mark.y < edge || mark.y > height - edge) continue;
            float[] best = null; String bestSide = null; int bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < sides.length; i++) {
                float[] b = box(mark.x, mark.y, size, sides[i]);
                if (b[0] < 0 || b[1] < 0 || b[0] + b[2] > width || b[1] + b[3] > height) continue;
                if (overlaps(b, cursor, 2)) continue;
                boolean under = false;
                for (Placed p : placed) if (covered(b, p.box)) { under = true; break; }
                if (under) continue;
                int score = routeIn(route, b) * 10 + i;
                if (score < bestScore) { bestScore = score; best = b; bestSide = sides[i]; }
                if (score == i) break;
            }
            if (best != null && bestScore / 10 <= graze) placed.add(new Placed(mark, kind, bestSide, best));
        }
        return placed;
    }

    static String kind(Mark mark) { return mark.kind.equals("camera") && mark.limit > 0 ? "camera-limit" : mark.kind; }

    /**
     * Beside the point the icon rises above it; '-high' lifts it wholly above, clear of the cursor.
     * Near the top edge a side icon slides down into the frame rather than giving up its side.
     */
    float[] box(float x, float y, float[] size, String side) {
        float w = size[0], h = size[1];
        switch (side) {
            case "right": return new float[]{x + gap, Math.max(0, y - h + 8), w, h};
            case "right-high": return new float[]{x + gap, Math.max(0, y - h - 2), w, h};
            case "left": return new float[]{x - gap - w, Math.max(0, y - h + 8), w, h};
            case "left-high": return new float[]{x - gap - w, Math.max(0, y - h - 2), w, h};
            case "above": return new float[]{x - w / 2, y - gap - h, w, h};
            default: return new float[]{x - w / 2, y + gap, w, h};
        }
    }

    /** Where the lead line meets the icon box. */
    static float[] leadEnd(Placed p) {
        float bx = p.box[0], by = p.box[1], bw = p.box[2], bh = p.box[3], cx = bx + bw / 2;
        boolean right = p.side.startsWith("right"), left = p.side.startsWith("left");
        float ex = right ? bx : left ? bx + bw : cx;
        float ey = p.side.equals("above") ? by + bh : p.side.equals("below") ? by : Math.min(Math.max(p.mark.y, by + 4), by + bh - 4);
        return new float[]{ex, ey};
    }

    static boolean overlaps(float[] a, float[] b, float pad) {
        return a[0] < b[0] + b[2] + pad && b[0] < a[0] + a[2] + pad && a[1] < b[1] + b[3] + pad && b[1] < a[1] + a[3] + pad;
    }

    private boolean covered(float[] a, float[] b) {
        float w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
        float h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
        return w > 0 && h > 0 && w * h > cover * Math.min(a[2] * a[3], b[2] * b[3]);
    }

    private static int routeIn(float[] route, float[] r) {
        if (route == null) return 0;
        int n = 0;
        for (int i = 0; i + 1 < route.length; i += 2) {
            float x = route[i], y = route[i + 1];
            if (x >= r[0] - 2 && x <= r[0] + r[2] + 2 && y >= r[1] - 2 && y <= r[1] + r[3] + 2) n++;
        }
        return n;
    }

    /** The route ahead as screen samples every few pixels along the projected polyline. */
    static float[] samples(List<float[]> polyline, float step) {
        List<Float> out = new ArrayList<>();
        for (int i = 0; i + 1 < polyline.size(); i++) {
            float[] a = polyline.get(i), b = polyline.get(i + 1);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.ceil(length / step));
            for (int k = i == 0 ? 0 : 1; k <= steps; k++) {
                out.add(a[0] + (b[0] - a[0]) * k / steps);
                out.add(a[1] + (b[1] - a[1]) * k / steps);
            }
        }
        float[] result = new float[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }
}
