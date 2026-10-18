package dev.denza.hudsurfaces.probe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The probe's pictures, PNG like the stock navigator's (quality 100). Manoeuvre-slot pictures are
 * 80×80 on transparent, like the arrows Denza Apps sends; map frames are opaque 300×180, the map
 * window's size. Black is dark on a HUD, so a black map frame empties the window.
 */
final class ProbeArt implements Plans.Art {
    static final int ICON = 80;
    static final int MAP_WIDTH = 300;
    static final int MAP_HEIGHT = 180;
    /** A cyan, B magenta: the glass shows at a glance which session's picture it holds. */
    private static final int COLOR_A = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int COLOR_B = Color.rgb(0xFF, 0x2B, 0xD6);
    private static final int AMBER = Color.rgb(0xFF, 0xB3, 0x00);

    private byte[] arrow;
    private byte[] camera;
    private byte[] black;
    private byte[] lanes;
    private final Map<String, byte[]> lanePictures = new HashMap<>();
    private final Map<SessionId, byte[]> letters = new EnumMap<>(SessionId.class);
    private final Map<SessionId, byte[]> maps = new EnumMap<>(SessionId.class);

    @Override
    public synchronized byte[] arrow() {
        if (arrow == null) {
            Bitmap bitmap = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint fill = fill(Color.WHITE);
            Path path = new Path();
            path.moveTo(40, 8);
            path.lineTo(66, 36);
            path.lineTo(49, 36);
            path.lineTo(49, 72);
            path.lineTo(31, 72);
            path.lineTo(31, 36);
            path.lineTo(14, 36);
            path.close();
            canvas.drawPath(path, fill);
            arrow = png(bitmap);
        }
        return arrow;
    }

    /** A box camera on a pole, white with an amber lens: unlike any arrow or stock glyph. */
    @Override
    public synchronized byte[] camera() {
        if (camera == null) {
            Bitmap bitmap = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint white = fill(Color.WHITE);
            canvas.save();
            canvas.rotate(-12f, 40f, 34f);
            canvas.drawRoundRect(new RectF(16, 20, 62, 44), 6, 6, white);
            canvas.drawRect(new RectF(62, 25, 70, 39), white);
            canvas.restore();
            canvas.drawCircle(27, 33, 7, fill(AMBER));
            canvas.drawRect(new RectF(37, 44, 43, 70), white);
            canvas.drawRect(new RectF(26, 68, 54, 74), white);
            camera = png(bitmap);
        }
        return camera;
    }

    @Override
    public synchronized byte[] letter(SessionId session) {
        byte[] cached = letters.get(session);
        if (cached == null) {
            Bitmap bitmap = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int color = color(session);
            Paint frame = stroke(color, 6f);
            canvas.drawRect(5, 5, ICON - 5, ICON - 5, frame);
            centered(canvas, session.name(), ICON / 2f, ICON / 2f, 56f, color);
            cached = png(bitmap);
            letters.put(session, cached);
        }
        return cached;
    }

    /** Opaque: the session's letter, its name and diagonal stripes in its colour on black. */
    @Override
    public synchronized byte[] map(SessionId session) {
        byte[] cached = maps.get(session);
        if (cached == null) {
            Bitmap bitmap = Bitmap.createBitmap(MAP_WIDTH, MAP_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            int color = color(session);
            Paint stripe = stroke(color, 4f);
            for (int x = -MAP_HEIGHT; x < MAP_WIDTH; x += 40) {
                canvas.drawLine(x, MAP_HEIGHT, x + MAP_HEIGHT, 0, stripe);
            }
            Paint plate = fill(Color.BLACK);
            canvas.drawRect(66, 20, 234, 162, plate);
            canvas.drawRect(4, 4, MAP_WIDTH - 4, MAP_HEIGHT - 4, stroke(color, 8f));
            centered(canvas, session.name(), MAP_WIDTH / 2f, 80f, 110f, color);
            centered(canvas, "СЕССИЯ " + session.name(), MAP_WIDTH / 2f, 146f, 26f, Color.WHITE);
            cached = png(bitmap);
            maps.put(session, cached);
        }
        return cached;
    }

    @Override
    public synchronized byte[] blackMap() {
        if (black == null) {
            Bitmap bitmap = Bitmap.createBitmap(MAP_WIDTH, MAP_HEIGHT, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            black = png(bitmap);
        }
        return black;
    }

    /** The stock lane picture's look: three arrows on black, the recommended one white. */
    @Override
    public synchronized byte[] lanes() {
        if (lanes == null) {
            Bitmap bitmap = Bitmap.createBitmap(123, 60, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            canvas.scale(123f / 240, 60f / 96);
            Paint grey = stroke(Color.GRAY, 7f);
            Paint white = stroke(Color.WHITE, 7f);
            canvas.drawLine(40, 80, 40, 30, grey);
            canvas.drawLine(40, 30, 16, 30, grey);
            canvas.drawLine(16, 30, 29, 17, grey);
            canvas.drawLine(16, 30, 29, 43, grey);
            canvas.drawLine(120, 80, 120, 16, white);
            canvas.drawLine(120, 16, 104, 32, white);
            canvas.drawLine(120, 16, 136, 32, white);
            canvas.drawLine(200, 80, 200, 30, grey);
            canvas.drawLine(200, 30, 224, 30, grey);
            canvas.drawLine(224, 30, 211, 17, grey);
            canvas.drawLine(224, 30, 211, 43, grey);
            lanes = png(bitmap);
        }
        return lanes;
    }

    /**
     * On black: a white frame on the very edge and an amber mark in each corner, so a crop shows
     * which side went; inside, a camera tile, a «60» tile and «300 м» where it fits.
     */
    @Override
    public synchronized byte[] lanesPicture(int width, int height) {
        String key = width + "x" + height;
        byte[] cached = lanePictures.get(key);
        if (cached == null) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            canvas.drawRect(1, 1, width - 1, height - 1, stroke(Color.WHITE, 2f));
            float mark = Math.min(width, height) / 3f;
            Paint corner = stroke(AMBER, 5f);
            float[][] corners = {{3, 3, 1, 1}, {width - 3, 3, -1, 1}, {3, height - 3, 1, -1},
                    {width - 3, height - 3, -1, -1}};
            for (float[] c : corners) {
                canvas.drawLine(c[0], c[1], c[0] + c[2] * mark, c[1], corner);
                canvas.drawLine(c[0], c[1], c[0], c[1] + c[3] * mark, corner);
            }
            float tile = Math.min(40f, height - 16f);
            float top = (height - tile) / 2f;
            float x = 10f;
            Paint white = fill(Color.WHITE);
            Paint red = stroke(Color.RED, 3f);
            for (int i = 0; i < 2; i++) {
                RectF box = new RectF(x, top, x + tile, top + tile);
                canvas.drawRoundRect(box, 6, 6, white);
                box.inset(1.5f, 1.5f);
                canvas.drawRoundRect(box, 6, 6, red);
                if (i == 0) {
                    Paint black = fill(Color.BLACK);
                    canvas.drawRect(x + tile * 0.22f, top + tile * 0.36f, x + tile * 0.78f,
                            top + tile * 0.72f, black);
                    canvas.drawRect(x + tile * 0.38f, top + tile * 0.27f, x + tile * 0.6f,
                            top + tile * 0.37f, black);
                    canvas.drawCircle(x + tile * 0.5f, top + tile * 0.54f, tile * 0.11f, white);
                } else {
                    centered(canvas, "60", x + tile / 2f, top + tile / 2f, tile * 0.5f,
                            Color.BLACK);
                }
                x += tile + 4f;
            }
            Paint text = fill(Color.WHITE);
            text.setTextSize(tile * 0.6f);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            String metres = "300 м";
            if (x + 4f + text.measureText(metres) <= width - 10f) {
                Paint.FontMetrics metrics = text.getFontMetrics();
                float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(metres, x + 4f, baseline, text);
            }
            cached = png(bitmap);
            lanePictures.put(key, cached);
        }
        return cached;
    }

    private static int color(SessionId session) {
        return session == SessionId.A ? COLOR_A : COLOR_B;
    }

    private static Paint fill(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        return paint;
    }

    private static Paint stroke(int color, float width) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        return paint;
    }

    private static void centered(Canvas canvas, String text, float x, float y, float size,
            int color) {
        Paint paint = fill(color);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
