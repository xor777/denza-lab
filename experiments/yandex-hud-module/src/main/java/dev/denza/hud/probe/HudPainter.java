package dev.denza.hud.probe;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Two owned buffers. UI thread composes; one worker encodes before the next frame may use them. */
final class HudPainter implements AutoCloseable {
    private final Context context;
    private final JSONObject theme;
    private final Bitmap output = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Bitmap overlay = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(output), overlayCanvas = new Canvas(overlay);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream(32768);
    private String key;
    private byte[] staticPng;
    int overlayBuilds;

    HudPainter(Context context, JSONObject theme) {
        this.context = context;
        this.theme = theme;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTypeface(Typeface.create("sans-serif-medium", 0));
    }

    void compose(Bitmap map, NavigatorSource.Frame frame) throws Exception {
        String next = frame.mode + frame.lanes;
        if (!next.equals(key)) {
            overlay.eraseColor(Color.TRANSPARENT);
            if (frame.drawMap()) {
                if (!frame.lanes.isEmpty()) {
                    float cell = Math.min(23f, 116f / frame.lanes.size());
                    float width = (float) Math.ceil(cell * frame.lanes.size() + 10), x = 294 - width;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.parseColor(theme.getJSONObject("palette").getString("blue")));
                    overlayCanvas.drawRoundRect(x, 6, x + width, 42, 5, 5, paint);
                    for (int i = 0; i < frame.lanes.size(); i++) {
                        NavigatorSource.Lane lane = frame.lanes.get(i);
                        for (String direction : lane.directions) if (!direction.equals(lane.highlight))
                            drawable("context_lane_" + direction + "_large", x + 5 + i * cell, 10, cell, 28, .4f, 1f, 0f);
                        if (lane.highlight != null) drawable("context_lane_" + lane.highlight + "_large", x + 5 + i * cell, 10, cell, 28, 1f, 1f, 0f);
                    }
                }
                JSONArray commands = theme.getJSONArray("cursor");
                for (int i = 0; i < commands.length(); i++) command(commands.getJSONObject(i));
            } else if (!frame.mode.equals("off")) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor(theme.getJSONObject("palette").getString("hint")));
                paint.setTextSize(16);
                overlayCanvas.drawText(frame.mode.equals("rerouting") ? "Маршрут…" : "Нет позиции", 10, 24, paint);
            }
            key = next;
            staticPng = null;
            overlayBuilds++;
        }
        output.eraseColor(Color.BLACK);
        if (map != null) canvas.drawBitmap(map, 0, 0, null);
        canvas.drawBitmap(overlay, 0, 0, null);
    }

    private void command(JSONObject command) throws Exception {
        if (command.getString("op").equals("path")) {
            Path path = new Path();
            JSONArray points = command.getJSONArray("points");
            for (int i = 0; i < points.length(); i++) {
                JSONArray p = points.getJSONArray(i);
                if (i == 0) path.moveTo((float) p.getDouble(0), (float) p.getDouble(1));
                else path.lineTo((float) p.getDouble(0), (float) p.getDouble(1));
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor(command.getString("color")));
            overlayCanvas.drawPath(path, paint);
        } else if (command.getString("op").equals("drawable")) {
            drawable(command.getString("name"), (float) command.getDouble("x"), (float) command.getDouble("y"),
                    (float) command.getDouble("w"), (float) command.getDouble("h"), (float) command.getDouble("alpha"),
                    (float) command.getDouble("brightness"), (float) command.getDouble("rotation"));
        } else throw new IllegalStateException("Unsupported cursor command");
    }

    private void drawable(String name, float x, float y, float width, float height, float alpha, float brightness, float rotation) {
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
        overlayCanvas.save();
        try {
            overlayCanvas.translate(x + width / 2, y + height / 2);
            overlayCanvas.rotate(rotation);
            overlayCanvas.translate(-width / 2, -height / 2);
            value.draw(overlayCanvas);
        } finally { overlayCanvas.restore(); }
    }

    byte[] encode(boolean fixed) {
        if (fixed && staticPng != null) return staticPng;
        stream.reset();
        if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("PNG encoding failed");
        byte[] bytes = stream.toByteArray();
        if (fixed) staticPng = bytes;
        return bytes;
    }

    @Override public void close() { output.recycle(); overlay.recycle(); drawables.clear(); staticPng = null; }
}
