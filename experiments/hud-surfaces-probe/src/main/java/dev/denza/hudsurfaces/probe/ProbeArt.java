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
