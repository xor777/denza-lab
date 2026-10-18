package dev.denza.apps.feature.hud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudRadarWarning;

import java.io.ByteArrayOutputStream;

/**
 * HUD Speed's warning as HUD Speed draws it, at the bottom of the map window where the road is
 * already behind (docs/hud-output-architecture.md, 7.3): its tile for the type, its frame with the
 * limit in it, and the metres - or a section's average - in large white digits; and, apart, its
 * GPS bars. The artwork comes from the installed app ({@link HudRadarArt}); without it a plain
 * white tile in a red frame stands in, so the numbers still show.
 *
 * <p>Transparent around the shapes: on the HUD black is no light, and a filled card would cut a
 * hole out of the map.
 */
final class HudRadarPainter {
    /** A tile and the frame of the limit, square. */
    static final int TILE = 34;
    static final int GAP = 4;
    /** The whole strip ends here, left of the cursor (hud-scene.js EVENTS.cursor starts at 134). */
    static final int MAX_WIDTH = 124;
    static final int GPS = 22;
    /** The lane window's picture: three stock cells of 41×60 (layout_hud_laneinfo_arrow). */
    static final int LANE_CELLS = 3;
    static final int LANE_WIDTH = 41 * LANE_CELLS;
    static final int LANE_HEIGHT = 60;

    private static final int RED = 0xFFFF2A2A;
    private static final Typeface DIGITS = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    private HudRadarPainter() {
    }

    /** The warning strip, as wide as it needs and never wider than {@link #MAX_WIDTH}. */
    static HudImage warning(HudRadarWarning warning, HudRadarArt art) {
        Bitmap bitmap = strip(warning, art, MAX_WIDTH);
        try {
            return encode(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * The same strip for the lane window (docs/hud-output-architecture.md, 7.4): three of the
     * stock lane picture's 41×60 cells, black around the strip as the stock lane picture is, the
     * strip centred. Black is no light on the HUD; whether the lane window takes our picture at
     * all is scenario 5 of hud-surfaces-probe.
     */
    static HudImage lanes(HudRadarWarning warning, HudRadarArt art) {
        Bitmap strip = strip(warning, art, LANE_WIDTH);
        Bitmap bitmap = Bitmap.createBitmap(LANE_WIDTH, LANE_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(strip, (LANE_WIDTH - strip.getWidth()) / 2f,
                    (LANE_HEIGHT - strip.getHeight()) / 2f, null);
            return encode(bitmap);
        } finally {
            strip.recycle();
            bitmap.recycle();
        }
    }

    private static Bitmap strip(HudRadarWarning warning, HudRadarArt art, int maxWidth) {
        Paint text = digits(18f);
        String line = HudRadarBadge.caption(warning);
        int fixed = TILE + GAP + (warning.limit > 0 ? TILE + GAP + 1 : 0);
        float size = 18f;
        while (fixed + text.measureText(line) + 4 > maxWidth && size > 13f) {
            size -= 1f;
            text.setTextSize(size);
        }
        int width = Math.min(maxWidth, Math.round(fixed + text.measureText(line) + 4));
        Bitmap bitmap = Bitmap.createBitmap(width, TILE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float x = 0;
        tile(canvas, art == null ? null : art.drawable(warning.icon), x);
        x += TILE + GAP;
        if (warning.limit > 0) {
            tile(canvas, art == null ? null : art.drawable(warning.frame), x);
            limit(canvas, warning.limit, x + TILE / 2f);
            x += TILE + GAP + 1;
        }
        outlined(canvas, text, line, x, TILE / 2f - (text.ascent() + text.descent()) / 2f,
                HudRadarBadge.overLimit(warning) ? RED : Color.WHITE);
        return bitmap;
    }

    /** HUD Speed's GPS bars for {@code level}, or null when it has none to show. */
    static HudImage gps(int level, HudRadarArt art) {
        if (level <= HudRadarWarning.GPS_UNKNOWN || art == null) {
            return null;
        }
        Drawable bars = art.drawable("ic_signal_" + level);
        if (bars == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(GPS, GPS, Bitmap.Config.ARGB_8888);
        try {
            bars.setBounds(0, 0, GPS, GPS);
            bars.draw(new Canvas(bitmap));
            return encode(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static void tile(Canvas canvas, Drawable drawable, float x) {
        if (drawable != null) {
            drawable.setBounds(Math.round(x), 0, Math.round(x) + TILE, TILE);
            drawable.draw(canvas);
            return;
        }
        // Without HUD Speed's artwork: its frame's shape, white in red.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF box = new RectF(x + 1.5f, 1.5f, x + TILE - 1.5f, TILE - 1.5f);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(box, 6f, 6f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(RED);
        canvas.drawRoundRect(box, 6f, 6f, paint);
    }

    private static void limit(Canvas canvas, int limit, float cx) {
        String digits = Integer.toString(limit);
        Paint paint = digits(digits.length() >= 3 ? 15f : 19f);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(digits, cx, TILE / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    /** White digits with a black edge, so they read over a white road as over black ground. */
    private static void outlined(Canvas canvas, Paint paint, String text, float x, float baseline,
            int color) {
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawText(text, x + 2, baseline, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawText(text, x + 2, baseline, paint);
    }

    private static Paint digits(float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(DIGITS);
        paint.setTextSize(size);
        return paint;
    }

    private static HudImage encode(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        return HudImage.png(bytes.toByteArray());
    }
}
