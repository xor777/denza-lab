package dev.denza.hud.probe;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Worker-owned artwork. No resource-density scaling; only the current lane row is encoded/cached.
 * Cells are drawn from the keys of {@link StockHudSymbols#cell} with the pixel rects of the theme's
 * laneArt (hud-scene.js), without smoothing, in BYD's grid.
 */
final class StockHudArt {
    private final AssetManager assets;
    private final JSONObject art;
    private final Map<String, byte[]> originals = new HashMap<>();
    private final Paint pixels = new Paint();
    private String[] laneKey;
    private byte[] lanePng;
    int laneBuilds;

    StockHudArt(AssetManager assets, JSONObject art) { this.assets = assets; this.art = art; }

    byte[] manoeuvre(NavigatorSource.Hint hint) throws IOException {
        String name = StockHudSymbols.manoeuvre(hint);
        return name == null ? null : original(name);
    }

    byte[] lanes(String[] names) throws IOException {
        if (names.length == 0) { laneKey = null; lanePng = null; return null; }
        if (Arrays.equals(names, laneKey)) return lanePng;
        if (names.length > StockHudSymbols.MAX_LANES) throw new IllegalArgumentException("Lane count");
        Bitmap row = Bitmap.createBitmap(names.length * StockHudSymbols.LANE_PITCH,
                StockHudSymbols.LANE_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            row.eraseColor(Color.BLACK); // Known optical background of the physical HUD.
            Canvas canvas = new Canvas(row);
            for (int i = 0; i < names.length; i++) {
                if (names[i] == null) continue;
                canvas.save();
                try {
                    canvas.translate(i * StockHudSymbols.LANE_PITCH + 2, 0);
                    cell(canvas, names[i]);
                } finally { canvas.restore(); }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            if (!row.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Lane PNG encoding");
            lanePng = bytes.toByteArray();
            laneKey = names.clone();
            laneBuilds++;
            return lanePng;
        } finally { row.recycle(); }
    }

    private void cell(Canvas canvas, String key) throws IOException {
        try {
            if (key.startsWith("tram:")) {
                boolean on = key.endsWith("1");
                stock(canvas, on ? "landfront_hud_15" : "landback_hud_15", false, false);
                rects(canvas, art.getJSONArray("pantograph"), on);
            } else if (key.startsWith("taxi:")) {
                rects(canvas, art.getJSONArray("checker"), key.endsWith("1"));
            } else if (key.startsWith("shift:")) {
                stock(canvas, "landfront_hud_d", key.charAt(6) == 'R', !key.endsWith("1"));
            } else stock(canvas, key, false, false);
        } catch (JSONException error) { throw new IOException("Lane art", error); }
    }

    /** A BYD picture, mirrored for a right shift; grey turns its cyan into BYD's grey, coverage kept. */
    private void stock(Canvas canvas, String name, boolean mirror, boolean grey) throws IOException {
        byte[] data = original(name);
        Bitmap glyph = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (glyph == null) throw new IOException("Invalid lane PNG: " + name);
        try {
            if (glyph.getWidth() != 37 || glyph.getHeight() != 60) throw new IOException("Lane dimensions");
            Paint paint = null;
            if (grey) {
                // BYD's cyan has full blue; blue is the pixel's coverage, laid onto the grey.
                paint = new Paint();
                paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                        0, 0, 0x91 / 255f, 0, 0, 0, 0, 0x95 / 255f, 0, 0, 0, 0, 0x9C / 255f, 0, 0, 0, 0, 0, 1, 0})));
            }
            canvas.save();
            try {
                if (mirror) { canvas.translate(37, 0); canvas.scale(-1, 1); }
                canvas.drawBitmap(glyph, 0, 0, paint);
            } finally { canvas.restore(); }
        } finally { glyph.recycle(); }
    }

    private void rects(Canvas canvas, JSONArray list, boolean on) throws JSONException {
        pixels.setColor(Color.parseColor(art.getString(on ? "cyan" : "grey")));
        for (int i = 0; i < list.length(); i++) {
            JSONArray r = list.getJSONArray(i);
            float x = (float) r.getDouble(0), y = (float) r.getDouble(1);
            canvas.drawRect(x, y, x + (float) r.getDouble(2), y + (float) r.getDouble(3), pixels);
        }
    }

    private byte[] original(String name) throws IOException {
        byte[] cached = originals.get(name);
        if (cached != null) return cached;
        try (InputStream input = assets.open("denza-hud/stock/" + name + ".png")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            cached = bytes.toByteArray();
        }
        originals.put(name, cached); // Bounded by the fixed StockHudSymbols table, never route data.
        return cached;
    }
}
