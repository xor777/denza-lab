package dev.denza.hud.probe;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Worker-owned artwork. No resource-density scaling; only the current lane row is encoded/cached. */
final class StockHudArt {
    private final AssetManager assets;
    private final Map<String, byte[]> originals = new HashMap<>();
    private String[] laneKey;
    private byte[] lanePng;
    int laneBuilds;

    StockHudArt(AssetManager assets) { this.assets = assets; }

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
                byte[] data = original(names[i]);
                Bitmap glyph = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (glyph == null) throw new IOException("Invalid lane PNG: " + names[i]);
                try {
                    if (glyph.getWidth() != 37 || glyph.getHeight() != 60) throw new IOException("Lane dimensions");
                    canvas.drawBitmap(glyph, i * StockHudSymbols.LANE_PITCH + 2, 0, null);
                } finally { glyph.recycle(); }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            if (!row.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Lane PNG encoding");
            lanePng = bytes.toByteArray();
            laneKey = names.clone();
            laneBuilds++;
            return lanePng;
        } finally { row.recycle(); }
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
