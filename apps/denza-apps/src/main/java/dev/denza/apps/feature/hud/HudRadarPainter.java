package dev.denza.apps.feature.hud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudRadarWarning;

import java.io.ByteArrayOutputStream;

/**
 * A radar warning as the glass already draws cameras (research/yandex-hud-renderer/hud-scene.js,
 * `ICONS.camera` and `camera-limit`): the warning's glyph in a red-ringed white disc beside the
 * limit as the real sign has it, black digits on white in a red ring, and under them the distance
 * in large white digits. The map window never prints a word; the units are the only letters.
 *
 * <p>Transparent around the shapes: on the HUD black is no light, so a filled card would cut a
 * hole of its size out of the map. Each shape carries its own thin black edge instead, which keeps
 * it readable over a bright road. 72×58 of the 300×180 window, in a top corner ({@link
 * HudRadarSource}).
 */
final class HudRadarPainter {
    static final int WIDTH = 72;
    static final int HEIGHT = 58;

    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.BLACK;
    /** hud-scene.js `R`, the ring of every sign and camera on the glass. */
    private static final int RED = 0xFFFF2A2A;
    /** hud-scene.js `PALETTE.amber`. */
    private static final int AMBER = 0xFFFFB21A;
    private static final Typeface SIGN = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    private static final float ROW = 19f;
    private static final float GLYPH_X = 16f;
    private static final float SIGN_X = 53f;

    private HudRadarPainter() {
    }

    static HudImage draw(HudRadarWarning warning) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean sign = warning.limit > 0;
            float glyphX = sign ? GLYPH_X : WIDTH / 2f;
            glyph(canvas, paint, HudRadarBadge.kind(warning), glyphX, ROW);
            if (sign) {
                limit(canvas, paint, warning.limit, SIGN_X, ROW);
            }
            caption(canvas, paint, HudRadarBadge.caption(warning), WIDTH / 2f, 55f,
                    HudRadarBadge.overLimit(warning) ? RED : WHITE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return HudImage.png(bytes.toByteArray());
        } finally {
            bitmap.recycle();
        }
    }

    private static void glyph(Canvas canvas, Paint paint, HudRadarBadge.Kind kind, float cx, float cy) {
        if (kind == HudRadarBadge.Kind.HAZARD) {
            Path triangle = new Path();
            triangle.moveTo(cx, cy - 14f);
            triangle.lineTo(cx + 14.5f, cy + 11.5f);
            triangle.lineTo(cx - 14.5f, cy + 11.5f);
            triangle.close();
            fill(paint, BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(triangle, paint);
            fill(paint, AMBER);
            canvas.drawPath(triangle, paint);
            fill(paint, BLACK);
            canvas.drawRoundRect(new RectF(cx - 1.6f, cy - 6f, cx + 1.6f, cy + 3.2f), 1.2f, 1.2f, paint);
            canvas.drawCircle(cx, cy + 6.8f, 1.8f, paint);
            return;
        }
        // hud-scene.js ICONS.camera: a black edge, the white disc, the red ring.
        fill(paint, BLACK);
        canvas.drawCircle(cx, cy, 15.2f, paint);
        fill(paint, WHITE);
        canvas.drawCircle(cx, cy, 14f, paint);
        ring(paint, RED, 3.4f);
        canvas.drawCircle(cx, cy, 12.3f, paint);
        fill(paint, BLACK);
        switch (kind) {
            case PATROL:
                camera(canvas, paint, cx, cy - 2.2f, 1.0f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - 1.5f, cy + 2.6f, cx - 5f, cy + 8f, paint);
                canvas.drawLine(cx + 1.5f, cy + 2.6f, cx + 5f, cy + 8f, paint);
                canvas.drawLine(cx, cy + 2.6f, cx, cy + 8.4f, paint);
                break;
            case SECTION: {
                // A span with an arrowhead at either end: the stretch the average is taken over.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - 5f, cy, cx + 5f, cy, paint);
                fill(paint, BLACK);
                Path heads = new Path();
                heads.moveTo(cx - 8.5f, cy);
                heads.lineTo(cx - 3.5f, cy - 4.5f);
                heads.lineTo(cx - 3.5f, cy + 4.5f);
                heads.close();
                heads.moveTo(cx + 8.5f, cy);
                heads.lineTo(cx + 3.5f, cy - 4.5f);
                heads.lineTo(cx + 3.5f, cy + 4.5f);
                heads.close();
                canvas.drawPath(heads, paint);
                break;
            }
            default:
                camera(canvas, paint, cx, cy, 1.25f);
                break;
        }
    }

    /** hud-scene.js camGlyph: the body, its top and the lens. */
    private static void camera(Canvas canvas, Paint paint, float cx, float cy, float k) {
        fill(paint, BLACK);
        canvas.drawRoundRect(new RectF(cx - 6.4f * k, cy - 3.8f * k, cx + 6.4f * k, cy + 4.8f * k),
                1.6f * k, 1.6f * k, paint);
        canvas.drawRoundRect(new RectF(cx - 2.6f * k, cy - 5.8f * k, cx + 2.6f * k, cy - 3.2f * k),
                0.8f * k, 0.8f * k, paint);
        fill(paint, WHITE);
        canvas.drawCircle(cx, cy + 0.5f * k, 2.9f * k, paint);
        fill(paint, BLACK);
        canvas.drawCircle(cx, cy + 0.5f * k, 1.6f * k, paint);
    }

    /** hud-scene.js `camera-limit`'s sign. */
    private static void limit(Canvas canvas, Paint paint, int limit, float cx, float cy) {
        fill(paint, BLACK);
        canvas.drawCircle(cx, cy, 18.2f, paint);
        fill(paint, WHITE);
        canvas.drawCircle(cx, cy, 17f, paint);
        ring(paint, RED, 5.1f);
        canvas.drawCircle(cx, cy, 14.4f, paint);
        String digits = Integer.toString(limit);
        fill(paint, BLACK);
        paint.setTypeface(SIGN);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(digits.length() >= 3 ? 13.5f : 17.5f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(digits, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    /** White digits with a black edge, so they read over a white road as over black ground. */
    private static void caption(Canvas canvas, Paint paint, String text, float cx, float baseline,
            int color) {
        paint.setTypeface(SIGN);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(17f);
        paint.setColor(BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawText(text, cx, baseline, paint);
        fill(paint, color);
        canvas.drawText(text, cx, baseline, paint);
    }

    private static void fill(Paint paint, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private static void ring(Paint paint, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }
}
