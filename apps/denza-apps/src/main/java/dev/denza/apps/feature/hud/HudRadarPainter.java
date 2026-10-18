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
 * HUD Speed's warning as HUD Speed draws it, in the lane window (docs/hud-output-architecture.md,
 * 7.4): its tile for the type, its frame with the limit in it, and the metres - or a section's
 * average - in large white digits; and, on the map, its GPS bars. The artwork comes from the
 * installed app ({@link HudRadarArt}); without it a plain white tile in a red frame stands in, so
 * the numbers still show.
 */
final class HudRadarPainter {
    /** The tile size HUD Speed's frame and limit digits were measured at; they scale from it. */
    private static final int ART = 34;
    static final int GAP = 4;
    static final int GPS = 26;
    /**
     * The lane window's picture: six stock cells of 41×60 (layout_hud_laneinfo_arrow). The glass
     * shows 246×60 whole with field 5 = 6 (hud-surfaces-probe, 2026-10-18).
     */
    static final int LANE_CELLS = 6;
    static final int LANE_WIDTH = 41 * LANE_CELLS;
    static final int LANE_HEIGHT = 60;
    /**
     * HUD Speed's tiles in the lane window, with room above and below: at the window's full
     * height they ran into the street name on the glass (owner, 2026-10-18).
     */
    static final int LANE_TILE = 46;
    /** The distance as large as the width allows, from this size down. */
    static final float LANE_DIGITS = 46f;
    private static final float LANE_DIGITS_MIN = 30f;
    /** The unit beside the distance: this much of the digits' size, light, close to them. */
    private static final float LANE_UNIT = 0.5f;
    private static final float LANE_UNIT_GAP = 2f;

    private static final int RED = 0xFFFF2A2A;
    private static final Typeface DIGITS = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private static final Typeface UNIT = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private HudRadarPainter() {
    }

    /**
     * The warning as the lane window's picture (docs/hud-output-architecture.md, 7.4), as the owner
     * chose it on the glass on 2026-10-18: HUD Speed's tiles the window's full height, the distance
     * as large as the width allows, its unit small, light and close to the digits; black around,
     * as the stock lane picture is.
     */
    static HudImage lanes(HudRadarWarning warning, HudRadarArt art) {
        Bitmap bitmap = Bitmap.createBitmap(LANE_WIDTH, LANE_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            String amount = HudRadarBadge.amount(warning);
            String unit = HudRadarBadge.unit(warning);
            int tile = LANE_TILE;
            float fixed = tile + GAP + (warning.limit > 0 ? tile + GAP : 0);
            Paint digits = digits(LANE_DIGITS);
            Paint light = light(LANE_DIGITS * LANE_UNIT);
            while (fixed + caption(digits, light, amount, unit) > LANE_WIDTH
                    && digits.getTextSize() > LANE_DIGITS_MIN) {
                digits.setTextSize(digits.getTextSize() - 1f);
                light.setTextSize(digits.getTextSize() * LANE_UNIT);
            }
            float x = Math.max(0f, (LANE_WIDTH - fixed - caption(digits, light, amount, unit)) / 2f);
            float top = (LANE_HEIGHT - tile) / 2f;
            tile(canvas, art == null ? null : art.drawable(warning.icon), x, top, tile);
            x += tile + GAP;
            if (warning.limit > 0) {
                tile(canvas, art == null ? null : art.drawable(warning.frame), x, top, tile);
                limit(canvas, warning.limit, x + tile / 2f, LANE_HEIGHT / 2f, tile);
                x += tile + GAP;
            }
            float baseline = LANE_HEIGHT / 2f - (digits.ascent() + digits.descent()) / 2f;
            int color = HudRadarBadge.overLimit(warning) ? RED : Color.WHITE;
            outlined(canvas, digits, amount, x, baseline, color, 5f);
            if (!unit.isEmpty()) {
                outlined(canvas, light, unit, x + 2 + digits.measureText(amount) + LANE_UNIT_GAP,
                        baseline, color, 3f);
            }
            return encode(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** The width of the distance and its unit as {@link #lanes} draws them, with the edge. */
    private static float caption(Paint digits, Paint light, String amount, String unit) {
        float width = 2 + digits.measureText(amount) + 2;
        return unit.isEmpty() ? width : width + LANE_UNIT_GAP + light.measureText(unit);
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

    private static void tile(Canvas canvas, Drawable drawable, float x, float top, int size) {
        if (drawable != null) {
            drawable.setBounds(Math.round(x), Math.round(top), Math.round(x) + size,
                    Math.round(top) + size);
            drawable.draw(canvas);
            return;
        }
        // Without HUD Speed's artwork: its frame's shape, white in red.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF box = new RectF(x + 1.5f, top + 1.5f, x + size - 1.5f, top + size - 1.5f);
        float corner = size * 6f / ART;
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(box, corner, corner, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(RED);
        canvas.drawRoundRect(box, corner, corner, paint);
    }

    /** The limit's digits in the middle of its frame, {@code size} square. */
    private static void limit(Canvas canvas, int limit, float cx, float cy, int size) {
        String digits = Integer.toString(limit);
        Paint paint = digits(size * (digits.length() >= 3 ? 15f : 19f) / ART);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(digits, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    /** Digits with a black edge {@code edge} wide, so they read over a white road as over black ground. */
    private static void outlined(Canvas canvas, Paint paint, String text, float x, float baseline,
            int color, float edge) {
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(edge);
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

    private static Paint light(float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(UNIT);
        paint.setTextSize(size);
        return paint;
    }

    private static HudImage encode(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        return HudImage.png(bytes.toByteArray());
    }
}
