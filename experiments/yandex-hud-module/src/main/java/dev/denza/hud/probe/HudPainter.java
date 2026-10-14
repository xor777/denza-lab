package dev.denza.hud.probe;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two owned buffers. UI thread composes; one worker encodes before the next frame may use them.
 * Draws the theme's templates op for op as research/yandex-hud-renderer/hud-scene.js paints them;
 * only placement (lane side, which road events fit) is computed here, mirroring build().
 */
final class HudPainter implements AutoCloseable {
    private final Context context;
    private final JSONObject theme, palette, lanes, icons, events;
    private final Bitmap output = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Bitmap overlay = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(output), overlayCanvas = new Canvas(overlay);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final Map<JSONArray, Path> paths = new IdentityHashMap<>();
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream(32768);
    private final float[] cursor;
    private String key, laneKey, side;
    private float[] badge;
    private byte[] staticPng;
    int overlayBuilds, placedMarks;

    HudPainter(Context context, JSONObject theme) throws JSONException {
        this.context = context;
        this.theme = theme;
        palette = theme.getJSONObject("palette");
        lanes = theme.getJSONObject("lanes");
        icons = theme.getJSONObject("icons");
        events = theme.getJSONObject("events");
        cursor = rect(events.getJSONArray("cursor"));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void compose(HudMap.Shot shot, NavigatorSource.Frame frame) throws Exception {
        boolean lanesShown = frame.drawMap() && validLanes(frame.lanes);
        if (lanesShown) laneSide(frame.lanes, shot == null ? null : shot.route); else laneKey = null;
        String next = frame.mode + frame.lanes + (lanesShown ? side : "");
        if (!next.equals(key)) {
            overlay.eraseColor(Color.TRANSPARENT);
            badge = null;
            if (frame.drawMap()) {
                if (lanesShown) badge = laneBadge(frame.lanes);
                draw(overlayCanvas, theme.getJSONArray("cursor"), 1, null);
            } else if (!frame.mode.equals("off")) {
                JSONObject text = new JSONObject().put("op", "text").put("x", 10).put("y", 24).put("size", 16)
                        .put("color", palette.getString("hint")).put("align", "left")
                        .put("value", frame.mode.equals("rerouting") ? "Маршрут…" : "Нет позиции");
                draw(overlayCanvas, new JSONArray().put(text), 1, null);
            }
            key = next;
            staticPng = null;
            overlayBuilds++;
        }
        output.eraseColor(Color.BLACK);
        if (shot != null && shot.bitmap != null) {
            canvas.drawBitmap(shot.bitmap, 0, 0, null);
            if (shot.finish != null) group(canvas, Math.round(shot.finish[0]), Math.round(shot.finish[1]), theme.getJSONObject("finish").getJSONArray("ops"), null);
            List<Object[]> placed = placeEvents(shot.marks);
            placedMarks = placed.size();
            for (int i = placed.size() - 1; i >= 0; i--) {
                Object[] p = placed.get(i);
                group(canvas, (Integer) p[1], (Integer) p[2], icons.getJSONObject((String) p[0]).getJSONArray("ops"), (String) p[3]);
            }
        }
        canvas.drawBitmap(overlay, 0, 0, null);
    }

    /* ---------- placement, mirrors hud-scene.js ---------- */

    private boolean validLanes(List<NavigatorSource.Lane> value) throws JSONException {
        if (value.isEmpty() || value.size() > lanes.getInt("max")) return false;
        JSONObject glyphs = lanes.getJSONObject("glyphs");
        for (NavigatorSource.Lane lane : value) {
            if (!lane.plain) continue;
            if (lane.directions.isEmpty()) return false;
            for (String direction : lane.directions) if (!glyphs.has(direction)) return false;
            if (lane.highlight != null && !lane.directions.contains(lane.highlight)) return false;
        }
        return true;
    }

    /**
     * The corner the manoeuvre does not turn into, unless the route drawn ahead reaches it and the other
     * corner is freer (hud-scene.js laneSide). Within one lane sign the corner only moves when the other
     * corner has at most half the route samples, so the badge does not hop from frame to frame.
     */
    private void laneSide(List<NavigatorSource.Lane> value, float[] route) throws JSONException {
        JSONArray rightward = lanes.getJSONArray("rightward");
        boolean any = false, allRight = true;
        for (NavigatorSource.Lane lane : value) {
            if (!lane.plain || lane.highlight == null) continue;
            any = true;
            boolean right = false;
            for (int i = 0; i < rightward.length(); i++) if (rightward.getString(i).equals(lane.highlight)) right = true;
            allRight &= right;
        }
        String sign = value.toString();
        if (!sign.equals(laneKey)) {
            laneKey = sign;
            String preferred = any && allRight ? "left" : "right", other = preferred.equals("left") ? "right" : "left";
            int here = hits(route, laneRect(value.size(), preferred));
            side = here > 0 && hits(route, laneRect(value.size(), other)) < here ? other : preferred;
        } else {
            String other = side.equals("left") ? "right" : "left";
            int here = hits(route, laneRect(value.size(), side));
            if (here > 0 && hits(route, laneRect(value.size(), other)) * 2 <= here) side = other;
        }
    }

    private float[] laneRect(int n, String corner) throws JSONException {
        JSONObject t = lanes.getJSONArray("templates").getJSONObject(n - 1);
        int margin = lanes.getInt("margin"), w = t.getInt("w");
        return new float[]{corner.equals("left") ? margin : 300 - margin - w, margin, w, t.getInt("h")};
    }

    /** Route samples inside the rectangle inflated by 3 px, walking the first "reach" pixels in 4 px steps. */
    private int hits(float[] route, float[] rect) throws JSONException {
        double reach = lanes.getDouble("reach"), walked = 0;
        int hits = 0;
        for (int i = 0; route != null && i + 3 < route.length && walked <= reach; i += 2) {
            float ax = route[i], ay = route[i + 1], bx = route[i + 2], by = route[i + 3];
            double length = Math.hypot(bx - ax, by - ay);
            int steps = Math.max(1, (int) Math.ceil(length / 4));
            for (int k = i == 0 ? 0 : 1; k <= steps && walked + length * k / steps <= reach; k++) {
                float x = ax + (bx - ax) * k / steps, y = ay + (by - ay) * k / steps;
                if (x >= rect[0] - 3 && x <= rect[0] + rect[2] + 3 && y >= rect[1] - 3 && y <= rect[1] + rect[3] + 3) hits++;
            }
            walked += length;
        }
        return hits;
    }

    /** Draws the lane badge into the static overlay and returns its rectangle. */
    private float[] laneBadge(List<NavigatorSource.Lane> value) throws JSONException {
        JSONObject t = lanes.getJSONArray("templates").getJSONObject(value.size() - 1);
        int w = t.getInt("w"), h = t.getInt("h");
        float scale = (float) t.getDouble("scale");
        JSONArray cells = t.getJSONArray("cells");
        float[] rect = laneRect(value.size(), side);
        float x = rect[0], y = rect[1];
        JSONArray ops = new JSONArray();
        ops.put(new JSONObject().put("op", "roundRect").put("x", 0).put("y", 0).put("w", w).put("h", h)
                .put("r", lanes.getDouble("radius")).put("fill", palette.getString("plate")));
        JSONObject m = lanes.getJSONObject("marking");
        float gap = (float) lanes.getDouble("gap");
        for (int i = 1; i < value.size(); i++) {
            float mx = round2((float) cells.getDouble(i) - gap * scale / 2);
            ops.put(new JSONObject().put("op", "shape").put("stroke", palette.getString("white")).put("width", m.getDouble("width"))
                    .put("alpha", m.getDouble("alpha")).put("dash", new JSONArray().put(m.getDouble("dash")).put(m.getDouble("gap")))
                    .put("d", new JSONArray().put(new JSONArray().put("M").put(mx).put(m.getDouble("inset")))
                            .put(new JSONArray().put("L").put(mx).put(h - m.getDouble("inset")))));
        }
        JSONObject glyphs = lanes.getJSONObject("glyphs");
        double dim = lanes.getDouble("dim"), stroke = lanes.getDouble("glyphStroke");
        JSONArray reserved = lanes.getJSONArray("reserved");
        for (int i = 0; i < value.size(); i++) {
            NavigatorSource.Lane lane = value.get(i);
            if (!lane.plain) {
                for (int k = 0; k < reserved.length(); k++) if (reserved.getString(k).equals(lane.kind))
                    ops.put(new JSONObject().put("op", "group").put("x", cells.getDouble(i)).put("y", t.getDouble("top"))
                            .put("scale", scale).put("ops", new JSONArray().put(lanes.getJSONObject("reservedGlyph"))));
                continue;
            }
            JSONArray children = new JSONArray();
            List<String> order = new ArrayList<>();
            for (String direction : lane.directions) if (!direction.equals(lane.highlight)) order.add(direction);
            if (lane.highlight != null) order.add(lane.highlight);
            for (String direction : order) {
                double alpha = direction.equals(lane.highlight) ? 1 : dim;
                JSONObject g = glyphs.getJSONObject(direction);
                children.put(new JSONObject().put("op", "shape").put("d", g.getJSONArray("stroke")).put("stroke", palette.getString("white"))
                        .put("width", stroke).put("alpha", alpha));
                children.put(new JSONObject().put("op", "shape").put("d", g.getJSONArray("head")).put("fill", palette.getString("white"))
                        .put("alpha", alpha));
            }
            ops.put(new JSONObject().put("op", "group").put("x", cells.getDouble(i)).put("y", t.getDouble("top"))
                    .put("scale", scale).put("ops", children));
        }
        overlayCanvas.save();
        try { overlayCanvas.translate(x, y); draw(overlayCanvas, ops, 1, null); }
        finally { overlayCanvas.restore(); }
        return new float[]{x, y, w, h};
    }

    /** Nearest first; an icon covering the lanes, the cursor or a nearer icon is left out. */
    private List<Object[]> placeEvents(List<HudMap.Mark> marks) throws JSONException {
        List<Object[]> placed = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        int max = events.getInt("max"), edge = events.getInt("edge");
        for (HudMap.Mark mark : marks) {
            if (placed.size() >= max) break;
            String kind = mark.kind.equals("camera") && mark.limit > 0 ? "camera-limit" : mark.kind;
            JSONObject icon = icons.optJSONObject(kind);
            if (icon == null) continue;
            int x = Math.round(mark.x), y = Math.round(mark.y);
            float[] b = rect(icon.getJSONArray("box"));
            float[] box = {x + b[0], y + b[1], b[2], b[3]};
            if (x < edge || x > 300 - edge || y < edge || y > 180 - edge) continue;
            if (overlaps(box, cursor, 2) || (badge != null && overlaps(box, badge, 2))) continue;
            boolean free = true;
            for (float[] other : boxes) if (overlaps(box, other, 1)) { free = false; break; }
            if (!free) continue;
            boxes.add(box);
            placed.add(new Object[]{kind, x, y, mark.limit > 0 ? String.valueOf(mark.limit) : null});
        }
        return placed;
    }

    private static boolean overlaps(float[] a, float[] b, float pad) {
        return a[0] < b[0] + b[2] + pad && b[0] < a[0] + a[2] + pad && a[1] < b[1] + b[3] + pad && b[1] < a[1] + a[3] + pad;
    }

    private static float[] rect(JSONArray value) throws JSONException {
        return new float[]{(float) value.getDouble(0), (float) value.getDouble(1), (float) value.getDouble(2), (float) value.getDouble(3)};
    }

    private static float round2(float value) { return Math.round(value * 100) / 100f; }

    /* ---------- executor, mirrors HudScene.paint ---------- */

    private void group(Canvas target, float x, float y, JSONArray ops, String text) throws JSONException {
        target.save();
        try { target.translate(x, y); draw(target, ops, 1, text); }
        finally { target.restore(); }
    }

    private void draw(Canvas target, JSONArray ops, float alpha, String text) throws JSONException {
        for (int i = 0; i < ops.length(); i++) {
            JSONObject c = ops.getJSONObject(i);
            float a = alpha * (float) c.optDouble("alpha", 1);
            String op = c.getString("op");
            switch (op) {
                case "group":
                    target.save();
                    try {
                        target.translate((float) c.getDouble("x"), (float) c.getDouble("y"));
                        float s = (float) c.getDouble("scale");
                        target.scale(s, s);
                        draw(target, c.getJSONArray("ops"), a, text);
                    } finally { target.restore(); }
                    break;
                case "roundRect":
                    fill(c.getString("fill"), a);
                    float x = (float) c.getDouble("x"), y = (float) c.getDouble("y"), r = (float) c.getDouble("r");
                    target.drawRoundRect(x, y, x + (float) c.getDouble("w"), y + (float) c.getDouble("h"), r, r, paint);
                    break;
                case "circle": {
                    float cx = (float) c.getDouble("x"), cy = (float) c.getDouble("y"), radius = (float) c.getDouble("r");
                    if (c.has("fill")) { fill(c.getString("fill"), a); target.drawCircle(cx, cy, radius, paint); }
                    if (c.has("stroke")) { stroke(c.getString("stroke"), (float) c.getDouble("width"), a); target.drawCircle(cx, cy, radius, paint); }
                    break;
                }
                case "shape": {
                    Path path = path(c.getJSONArray("d"));
                    if (c.has("fill")) { fill(c.getString("fill"), a); target.drawPath(path, paint); }
                    if (c.has("stroke")) {
                        stroke(c.getString("stroke"), (float) c.getDouble("width"), a);
                        JSONArray dash = c.optJSONArray("dash");
                        if (dash != null) paint.setPathEffect(new DashPathEffect(new float[]{(float) dash.getDouble(0), (float) dash.getDouble(1)}, 0));
                        target.drawPath(path, paint);
                        paint.setPathEffect(null);
                    }
                    break;
                }
                case "text": {
                    String value = c.getString("value");
                    if (value.equals("{limit}")) value = text == null ? "" : text;
                    fill(c.getString("color"), a);
                    paint.setTextSize((float) (c.has("size3") && value.length() > 2 ? c.getDouble("size3") : c.getDouble("size")));
                    float tx = (float) c.getDouble("x"), ty = (float) c.getDouble("y");
                    if (c.optString("align").equals("center")) {
                        paint.setTextAlign(Paint.Align.CENTER);
                        Paint.FontMetrics metrics = paint.getFontMetrics();
                        ty -= (metrics.ascent + metrics.descent) / 2;
                    }
                    target.drawText(value, tx, ty, paint);
                    paint.setTextAlign(Paint.Align.LEFT);
                    break;
                }
                case "drawable":
                    drawable(target, c.getString("name"), (float) c.getDouble("x"), (float) c.getDouble("y"), (float) c.getDouble("w"),
                            (float) c.getDouble("h"), a, (float) c.getDouble("brightness"), (float) c.getDouble("rotation"));
                    break;
                default: throw new IllegalStateException("Unsupported drawing command " + op);
            }
        }
    }

    private void fill(String color, float alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor(color));
        paint.setAlpha(Math.round(alpha * 255));
    }

    private void stroke(String color, float width, float alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(Color.parseColor(color));
        paint.setAlpha(Math.round(alpha * 255));
    }

    private Path path(JSONArray commands) throws JSONException {
        Path path = paths.get(commands);
        if (path != null) return path;
        path = new Path();
        for (int i = 0; i < commands.length(); i++) {
            JSONArray c = commands.getJSONArray(i);
            switch (c.getString(0)) {
                case "M": path.moveTo(f(c, 1), f(c, 2)); break;
                case "L": path.lineTo(f(c, 1), f(c, 2)); break;
                case "Q": path.quadTo(f(c, 1), f(c, 2), f(c, 3), f(c, 4)); break;
                case "C": path.cubicTo(f(c, 1), f(c, 2), f(c, 3), f(c, 4), f(c, 5), f(c, 6)); break;
                case "Z": path.close(); break;
                default: throw new IllegalStateException("Unsupported path command " + c.getString(0));
            }
        }
        paths.put(commands, path);
        return path;
    }

    private static float f(JSONArray value, int index) throws JSONException { return (float) value.getDouble(index); }

    private void drawable(Canvas target, String name, float x, float y, float width, float height, float alpha, float brightness, float rotation) {
        Drawable value = drawables.get(name);
        if (value == null) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id == 0) throw new IllegalStateException("Missing drawable " + name);
            value = context.getDrawable(id).mutate();
            drawables.put(name, value);
        }
        value.setBounds(0, 0, Math.round(width), Math.round(height));
        value.setAlpha(Math.round(alpha * 255));
        if (brightness == 1) value.clearColorFilter();
        else value.setColorFilter(new ColorMatrixColorFilter(new float[]{brightness,0,0,0,0, 0,brightness,0,0,0, 0,0,brightness,0,0, 0,0,0,1,0}));
        target.save();
        try {
            target.translate(x + width / 2, y + height / 2);
            target.rotate(rotation);
            target.translate(-width / 2, -height / 2);
            value.draw(target);
        } finally { target.restore(); }
    }

    byte[] encode(boolean fixed) {
        if (fixed && staticPng != null) return staticPng;
        stream.reset();
        if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("PNG encoding failed");
        byte[] bytes = stream.toByteArray();
        if (fixed) staticPng = bytes;
        return bytes;
    }

    @Override public void close() { output.recycle(); overlay.recycle(); drawables.clear(); paths.clear(); staticPng = null; }
}
