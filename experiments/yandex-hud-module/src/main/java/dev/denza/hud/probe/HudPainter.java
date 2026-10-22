package dev.denza.hud.probe;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Two owned buffers. UI thread composes; one worker encodes before the next frame may use them.
 * Draws the theme's templates op for op as research/yandex-hud-renderer/hud-scene.js paints them.
 * Road-event icons are not painted on the frame: {@link #icon} renders each as a small bitmap that
 * HudMap hands to MapKit as a placemark, so they turn with the map in the same frame.
 */
final class HudPainter implements AutoCloseable {
    /** An icon with its lead line and the dot on the road; anchor is the dot, as a fraction of the bitmap. */
    static final class EventIcon {
        final String key; final Bitmap bitmap; final float anchorX, anchorY;
        EventIcon(String key, Bitmap bitmap, float anchorX, float anchorY) { this.key = key; this.bitmap = bitmap; this.anchorX = anchorX; this.anchorY = anchorY; }
    }
    private final Context context, day;
    private final JSONObject theme, palette, icons, events;
    private final Bitmap output = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Bitmap overlay = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(output), overlayCanvas = new Canvas(overlay);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final Map<JSONArray, Path> paths = new IdentityHashMap<>();
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream(32768);
    private final Map<String, EventIcon> eventIcons = new HashMap<>();
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL), sign;
    private final Path glyphs = new Path();
    private final RectF inkF = new RectF();
    private final float mapDim;
    /** Window pixels per frame pixel of the map's placemarks: 2 for the stock camera's halved window. */
    private final float iconScale;
    final HudEvents placement;
    private String key;
    private byte[] staticPng;
    int overlayBuilds, placedMarks;

    HudPainter(Context context, JSONObject theme) throws JSONException {
        this.context = context;
        sign = signFace(context);
        // Navigator's icons in their day variant: its night ones are dark plates, invisible on the glass.
        Configuration dayMode = new Configuration(context.getResources().getConfiguration());
        dayMode.uiMode = (dayMode.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        day = context.createConfigurationContext(dayMode);
        this.theme = theme;
        palette = theme.getJSONObject("palette");
        icons = theme.getJSONObject("icons");
        events = theme.getJSONObject("events");
        mapDim = (float) theme.getJSONObject("states").getDouble("mapDim");
        JSONObject camera = theme.optJSONObject("camera");
        iconScale = camera != null && "stock".equals(camera.optString("law")) ? 1 / dev.denza.hudlink.camera.StockCameraOptics.SCALE : 1;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTypeface(medium);
        Map<String, float[]> sizes = new HashMap<>();
        for (java.util.Iterator<String> names = icons.keys(); names.hasNext(); ) {
            String name = names.next();
            JSONArray size = icons.getJSONObject(name).getJSONArray("size");
            sizes.put(name, new float[]{(float) size.getDouble(0), (float) size.getDouble(1)});
        }
        JSONArray sideList = events.getJSONArray("sides");
        String[] sides = new String[sideList.length()];
        for (int i = 0; i < sides.length; i++) sides[i] = sideList.getString(i);
        JSONObject size = theme.getJSONObject("size");
        placement = new HudEvents(events.getInt("max"), size.getInt("width"), size.getInt("height"), (float) events.getDouble("edge"),
                (float) events.getDouble("cover"), (float) events.getDouble("gap"), (float) events.getDouble("merge"), (float) events.getDouble("span"),
                events.getInt("graze"), rect(events.getJSONArray("cursor")), sides, sizes);
    }

    void compose(HudMap.Shot shot, NavigatorSource.Frame frame) throws Exception {
        // The window never prints a word: no position yet is black, everything else keeps the car.
        String next = frame.drawMap() ? "car" : "empty";
        if (!next.equals(key)) {
            overlay.eraseColor(Color.TRANSPARENT);
            if (frame.drawMap()) draw(overlayCanvas, theme.getJSONArray("cursor"), 1, null);
            key = next;
            staticPng = null;
            overlayBuilds++;
        }
        output.eraseColor(Color.BLACK);
        if (shot != null && shot.bitmap != null) {
            Paint map = null;
            if (frame.mode.equals("lost")) { map = new Paint(); map.setAlpha(Math.round(mapDim * 255)); }
            canvas.drawBitmap(shot.bitmap, 0, 0, map);
            if (shot.finish != null && frame.mode.equals("guidance"))
                group(canvas, Math.round(shot.finish[0]), Math.round(shot.finish[1]), theme.getJSONObject("finish").getJSONArray("ops"), null);
            placedMarks = shot.placed;
        }
        canvas.drawBitmap(overlay, 0, 0, null);
    }

    /**
     * The icon, its lead line and the dot, as eventCommands() in hud-scene.js lays them out, in a bitmap
     * whose anchor is the dot. Its shape depends only on kind, limit and side, so it is made once.
     */
    EventIcon icon(HudEvents.Placed p) throws JSONException {
        String limit = p.kind.equals("camera-limit") ? String.valueOf(p.mark.limit) : null;
        String id = p.kind + ":" + (limit == null ? "" : limit) + ":" + p.side;
        EventIcon cached = eventIcons.get(id);
        if (cached != null) return cached;
        float[] box = {p.box[0] - p.mark.x, p.box[1] - p.mark.y, p.box[2], p.box[3]};
        float[] end = HudEvents.leadEnd(p);
        float ex = end[0] - p.mark.x, ey = end[1] - p.mark.y;
        JSONObject lead = events.getJSONObject("lead");
        JSONArray dot = events.getJSONArray("dot");
        float margin = (float) dot.getDouble(0) + 2;
        float left = Math.min(box[0], -margin), top = Math.min(box[1], -margin);
        float right = Math.max(box[0] + box[2], margin), bottom = Math.max(box[1] + box[3], margin);
        int width = (int) Math.ceil(right - left), height = (int) Math.ceil(bottom - top);
        // MapKit draws the placemark in window pixels; the stock window is halved into the frame.
        Bitmap bitmap = Bitmap.createBitmap(Math.round(width * iconScale), Math.round(height * iconScale), Bitmap.Config.ARGB_8888);
        Canvas target = new Canvas(bitmap);
        target.scale(iconScale, iconScale);
        target.translate(-left, -top);
        Path line = new Path(); line.moveTo(0, 0); line.lineTo(ex, ey);
        stroke("#000000", (float) lead.getDouble("halo"), 1); target.drawPath(line, paint);
        stroke(palette.getString("white"), (float) lead.getDouble("width"), 1); target.drawPath(line, paint);
        fill("#000000", 1); target.drawCircle(0, 0, (float) dot.getDouble(0), paint);
        fill(palette.getString("white"), 1); target.drawCircle(0, 0, (float) dot.getDouble(1), paint);
        group(target, box[0] + box[2] / 2, box[1] + box[3] / 2, icons.getJSONObject(p.kind).getJSONArray("ops"), limit);
        EventIcon icon = new EventIcon(id, bitmap, -left / width, -top / height);
        eventIcons.put(id, icon);
        return icon;
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
                case "roundRect": {
                    float x = (float) c.getDouble("x"), y = (float) c.getDouble("y"), r = (float) c.getDouble("r");
                    float x2 = x + (float) c.getDouble("w"), y2 = y + (float) c.getDouble("h");
                    if (c.has("fill")) { fill(c.getString("fill"), a); target.drawRoundRect(x, y, x2, y2, r, r, paint); }
                    if (c.has("stroke")) { stroke(c.getString("stroke"), (float) c.getDouble("width"), a); target.drawRoundRect(x, y, x2, y2, r, r, paint); }
                    break;
                }
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
                        if (c.optString("cap").equals("butt")) paint.setStrokeCap(Paint.Cap.BUTT);
                        JSONArray dash = c.optJSONArray("dash");
                        if (dash != null) paint.setPathEffect(new DashPathEffect(new float[]{(float) dash.getDouble(0), (float) dash.getDouble(1)}, 0));
                        target.drawPath(path, paint);
                        paint.setPathEffect(null);
                        paint.setStrokeCap(Paint.Cap.ROUND);
                    }
                    break;
                }
                case "text": {
                    String value = c.getString("value");
                    if (value.equals("{limit}")) value = text == null ? "" : text;
                    fill(c.getString("color"), a);
                    // The sign face: a road sign's digits are bold and narrow.
                    paint.setTypeface("HudSign".equals(c.optString("family")) ? sign : medium);
                    paint.setTextSize((float) (c.has("size3") && value.length() > 2 ? c.getDouble("size3") : c.getDouble("size")));
                    float tx = (float) c.getDouble("x"), ty = (float) c.getDouble("y");
                    paint.setTextAlign(Paint.Align.LEFT);
                    if (c.optString("align").equals("center")) {
                        // Centred on the ink of the glyphs, not on the face's ascent and descent: those
                        // differ from face to face and put a sign's digits off centre on the car. The
                        // outline is measured and drawn as a path, so neither the whole-pixel text
                        // bounds nor the text's pixel snapping move it.
                        glyphs.reset();
                        paint.getTextPath(value, 0, value.length(), 0, 0, glyphs);
                        glyphs.computeBounds(inkF, true);
                        glyphs.offset(tx - inkF.centerX(), ty - inkF.centerY());
                        target.drawPath(glyphs, paint);
                    } else target.drawText(value, tx, ty, paint);
                    paint.setTypeface(medium);
                    break;
                }
                case "drawable": {
                    JSONArray clip = c.optJSONArray("clip");
                    target.save();
                    try {
                        // Navigator's pin without its tail: the plate alone.
                        if (clip != null) target.clipRect(f(clip, 0), f(clip, 1), f(clip, 0) + f(clip, 2), f(clip, 1) + f(clip, 3));
                        drawable(target, c.getString("name"), (float) c.getDouble("x"), (float) c.getDouble("y"), (float) c.getDouble("w"),
                                (float) c.getDouble("h"), a, (float) c.getDouble("brightness"), (float) c.getDouble("rotation"));
                    } finally { target.restore(); }
                    break;
                }
                default: throw new IllegalStateException("Unsupported drawing command " + op);
            }
        }
    }

    /**
     * A sign's digits in Navigator's own bold face from its APK, so the car draws what the emulator was
     * checked with; the system's condensed bold only if that asset is ever missing.
     */
    private static Typeface signFace(Context context) {
        try { return Typeface.createFromAsset(context.getAssets(), "metrokit/fonts/YandexSans/YandexSansText-Bold.ttf"); }
        catch (RuntimeException missing) { return Typeface.create("sans-serif-condensed", Typeface.BOLD); }
    }

    /** Emulator check: every icon kind once, then the limit sign for each limit, in one row of 48-px cells. */
    Bitmap sheet(String[] kinds, int[] limits) throws JSONException {
        Bitmap bitmap = Bitmap.createBitmap((kinds.length + limits.length) * 48, 48, Bitmap.Config.ARGB_8888);
        Canvas target = new Canvas(bitmap);
        target.drawColor(Color.BLACK);
        for (int i = 0; i < kinds.length; i++) group(target, i * 48 + 24, 24, icons.getJSONObject(kinds[i]).getJSONArray("ops"), "60");
        for (int i = 0; i < limits.length; i++)
            group(target, (kinds.length + i) * 48 + 24, 24, icons.getJSONObject("camera-limit").getJSONArray("ops"), String.valueOf(limits[i]));
        return bitmap;
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
            int id = day.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id == 0) throw new IllegalStateException("Missing drawable " + name);
            value = day.getDrawable(id).mutate();
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

    @Override public void close() {
        output.recycle(); overlay.recycle(); drawables.clear(); paths.clear(); staticPng = null;
        for (EventIcon icon : eventIcons.values()) icon.bitmap.recycle();
        eventIcons.clear();
    }
}
