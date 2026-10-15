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
 * only road-event placement is computed here, mirroring build().
 */
final class HudPainter implements AutoCloseable {
    private final Context context;
    private final JSONObject theme, palette, icons, events;
    private final Bitmap output = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Bitmap overlay = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(output), overlayCanvas = new Canvas(overlay);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final Map<JSONArray, Path> paths = new IdentityHashMap<>();
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream(32768);
    private final float[] cursor;
    private String key;
    private byte[] staticPng;
    int overlayBuilds, placedMarks;

    HudPainter(Context context, JSONObject theme) throws JSONException {
        this.context = context;
        this.theme = theme;
        palette = theme.getJSONObject("palette");
        icons = theme.getJSONObject("icons");
        events = theme.getJSONObject("events");
        cursor = rect(events.getJSONArray("cursor"));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void compose(HudMap.Shot shot, NavigatorSource.Frame frame) throws Exception {
        String next = frame.mode;
        if (!next.equals(key)) {
            overlay.eraseColor(Color.TRANSPARENT);
            if (frame.drawMap()) {
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

    /** Nearest first; an icon covering the cursor or a nearer icon is left out. */
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
            if (overlaps(box, cursor, 2)) continue;
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
