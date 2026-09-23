package dev.denza.hudframes.probe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;

/**
 * Test frames for the two SOME/IP picture slots. Every frame differs from the previous one in
 * three ways a person can read off the windshield: a hand that turns 30 degrees, a large digit
 * that counts 0..9, and (map only) a bar that sweeps left to right. A frozen picture, a skipped
 * frame and a late frame therefore all look different.
 */
final class FrameArt {
    private FrameArt() {
    }

    /** Maneuver-slot frame: white on transparent, like the arrows Denza Apps already sends. */
    static byte[] icon(int frame, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float center = size / 2f;
        Paint stroke = stroke(size / 16f);
        canvas.drawCircle(center, center, size * 0.42f, stroke);
        drawHand(canvas, stroke, center, center, size * 0.36f, frame);
        Paint text = text(size * 0.42f);
        drawCentered(canvas, text, Integer.toString(frame % 10), center, center);
        return png(bitmap);
    }

    /**
     * A still marker for the maneuver slot while the map channel plays: a square with a cross,
     * unlike both the moving map pattern and any stock arrow, so the owner can tell which of
     * the three the maneuver window shows.
     */
    static byte[] marker(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint stroke = stroke(size / 16f);
        float inset = size * 0.15f;
        canvas.drawRect(inset, inset, size - inset, size - inset, stroke);
        canvas.drawLine(inset, inset, size - inset, size - inset, stroke);
        canvas.drawLine(size - inset, inset, inset, size - inset, stroke);
        return png(bitmap);
    }

    /**
     * Map-window frame. The stock sends an opaque RGB_565 screenshot, so this one is opaque
     * too: black (dark on a HUD) with white marks.
     */
    static byte[] map(int frame, int width, int height, int fps) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        float unit = Math.min(width, height) / 12f;
        Paint stroke = stroke(unit / 2f);
        canvas.drawRect(unit / 2f, unit / 2f, width - unit / 2f, height - unit / 2f, stroke);
        int steps = 10;
        float barX = unit + (width - 2 * unit) * (frame % steps) / (steps - 1f);
        canvas.drawLine(barX, unit, barX, height - unit, stroke(unit));
        float handCenterX = width * 0.25f;
        drawHand(canvas, stroke, handCenterX, height / 2f, height * 0.3f, frame);
        canvas.drawCircle(handCenterX, height / 2f, height * 0.34f, stroke);
        drawCentered(canvas, text(height * 0.42f), Integer.toString(frame % 10),
                width * 0.68f, height / 2f);
        drawCentered(canvas, text(unit * 1.3f), fps + " fps", width * 0.68f, height * 0.84f);
        return png(bitmap);
    }

    /**
     * Calibration frame for the map window: a grid every 20 px, heavier every 100 px, with the
     * pixel coordinate written along the top and left edges and again along the middle row and
     * column (in case the edges are the part that is cut off), plus a centre cross. Sent larger
     * than the window, the last number visible at the right and bottom edges is the window's
     * size, and whether the cross sits in the middle tells a centred crop from a corner one.
     */
    static byte[] grid(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint fine = stroke(1f);
        Paint heavy = stroke(3f);
        for (int x = 0; x <= width; x += 20) {
            canvas.drawLine(x, 0, x, height, x % 100 == 0 ? heavy : fine);
        }
        for (int y = 0; y <= height; y += 20) {
            canvas.drawLine(0, y, width, y, y % 100 == 0 ? heavy : fine);
        }
        Paint label = text(22f);
        label.setTextAlign(Paint.Align.LEFT);
        int midX = width / 2 / 100 * 100;
        int midY = height / 2 / 100 * 100;
        for (int x = 100; x < width; x += 100) {
            canvas.drawText(Integer.toString(x), x + 4, 24, label);
            canvas.drawText(Integer.toString(x), x + 4, midY + 24, label);
        }
        for (int y = 100; y < height; y += 100) {
            canvas.drawText(Integer.toString(y), 4, y + 24, label);
            canvas.drawText(Integer.toString(y), midX + 4, y + 24, label);
        }
        Paint cross = stroke(6f);
        float cx = width / 2f;
        float cy = height / 2f;
        canvas.drawLine(cx - 30, cy, cx + 30, cy, cross);
        canvas.drawLine(cx, cy - 30, cx, cy + 30, cross);
        return png(bitmap);
    }

    /** An all-black frame: what a HUD shows as nothing. */
    static byte[] black(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        return png(bitmap);
    }

    /** PNG of a bitmap the caller keeps (not recycled). */
    static byte[] pngOf(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return output.toByteArray();
    }

    private static void drawHand(Canvas canvas, Paint paint, float cx, float cy, float length,
            int frame) {
        double angle = Math.toRadians(-90 + 30 * (frame % 12));
        canvas.drawLine(cx, cy,
                (float) (cx + length * Math.cos(angle)),
                (float) (cy + length * Math.sin(angle)),
                paint);
    }

    private static void drawCentered(Canvas canvas, Paint paint, String value, float cx, float cy) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private static Paint stroke(float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        return paint;
    }

    private static Paint text(float size) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        return paint;
    }

    private static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }
}
