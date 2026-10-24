package dev.denza.hud.probe;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/**
 * The ADAS overlay drawn on a Canvas, a line-for-line port of its board
 * (tools/design-canvas/adas-overlay/adas.js): the same verbs (beam, glowFill, word, the wide
 * figures), the same numbers in board pixels (the main display's, density 2) and the same additive
 * compositing ('lighter' is PorterDuff ADD). The board and this class move together.
 *
 * <p>Chrome's canvas shadow is converted the way Denza Apps' LightPen does it: the shadow's
 * Gaussian has sigma = blur / 2 and Skia's blur radius r gives sigma = 0.57735 r + 0.5; a shadow
 * carries the alpha of what casts it times the shadow colour's alpha.
 *
 * <p>Drawing goes into an offscreen bitmap that starts transparent (see {@link #render}): additive
 * light there is exactly the board's on black, and the bitmap is laid over Navigator's map.
 */
final class AdasPainter {
    // Luminofor's colours (spec.json): [halo, core].
    static final int[] BLUE = {0xFF439FFC, 0xFF80BEFF}, WHITE = {0xFFFFFFFF, 0xFFFFFFFF}, GREY = {0xFF86909B, 0xFF86909B};
    static final int[] ORANGE = {0xFFFF9F19, 0xFFFFC882}, RED = {0xFFFF4046, 0xFFFFA0A0};
    // alarm words keep their hue (adas.js ORANGE_W / RED_W)
    static final int[] ORANGE_W = {0xFFFF9F19, 0xFFFFA42E}, RED_W = {0xFFFF4046, 0xFFFF5A5F};

    // The overlay's own numbers (adas.js K), px on the 2560 display.
    static final float H = 136, PAD = 30, RADIUS = 30, GAP = 16, GLYPH = 80, BIG = 68, MID = 40, SMALL = 34, SEP = 26,
            DETAILS_H = 62, ALARM_H = 168;
    /** Room around the stack for halos and the plate's shadow. */
    static final int MARGIN = 64;

    private final Typeface regular, medium;
    private final Path[] glyphs = new Path[AdasDigits.GLYPHS.length];
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final Matrix matrix = new Matrix();
    private final Path scratch = new Path();
    private Canvas c;

    /** The board's Roboto 400 and 500 from the module's assets; the system's sans-serif without them. */
    AdasPainter(AssetManager assets) {
        regular = font(assets, "Roboto-400.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        medium = font(assets, "Roboto-500.ttf", Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.SANS_SERIF, 500, false)
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < glyphs.length; i++) glyphs[i] = AdasDigits.GLYPHS[i].isEmpty() ? null : path(AdasDigits.GLYPHS[i]);
    }

    private static Typeface font(AssetManager assets, String name, Typeface fallback) {
        try { return Typeface.createFromAsset(assets, "denza-hud/fonts/" + name); }
        catch (RuntimeException missing) { return fallback; }
    }

    static Path path(String d) {
        final Path p = new Path();
        AdasSvgPath.parse(d, new AdasSvgPath.Sink() {
            public void moveTo(float x, float y) { p.moveTo(x, y); }
            public void lineTo(float x, float y) { p.lineTo(x, y); }
            public void quadTo(float x1, float y1, float x, float y) { p.quadTo(x1, y1, x, y); }
            public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { p.cubicTo(x1, y1, x2, y2, x, y); }
            public void close() { p.close(); }
        });
        return p;
    }

    /* ------------------------------------------------------------------ the stack */

    /** A plate measured, ready to draw at its top-left. */
    private abstract static class Item { float w, h; abstract void draw(float x, float y); }

    /**
     * The overlay drawn into a fresh bitmap: its bottom-right corner is the anchor, in board pixels.
     * Returns the bitmap and, in {@code origin}, where its top-left lands relative to the anchor.
     * {@code reuse}, the previous one, is cleared and drawn again when it has the same size.
     */
    Bitmap render(AdasOverlayState st, float scale, int[] origin, Bitmap reuse) {
        List<Item> stack = stack(st);
        float w = 0, h = 0;
        for (Item it : stack) { w = Math.max(w, it.w); h += it.h; }
        h += GAP * (stack.size() - 1);
        // The anchor lands on a whole pixel of the bitmap, so the plates keep the board's fractions.
        int ax = (int) Math.ceil((w + MARGIN) * scale), ay = (int) Math.ceil((h + MARGIN) * scale);
        int bw = ax + (int) Math.ceil(MARGIN * scale), bh = ay + (int) Math.ceil(MARGIN * scale);
        Bitmap bitmap;
        if (reuse != null && !reuse.isRecycled() && reuse.getWidth() == Math.max(1, bw) && reuse.getHeight() == Math.max(1, bh)) {
            bitmap = reuse;
            bitmap.eraseColor(0);
        } else {
            if (reuse != null) reuse.recycle();
            bitmap = Bitmap.createBitmap(Math.max(1, bw), Math.max(1, bh), Bitmap.Config.ARGB_8888);
        }
        c = new Canvas(bitmap);
        c.scale(scale, scale);
        float right = ax / scale, y = ay / scale;
        for (Item it : stack) { y -= it.h; it.draw(right - it.w, y); y -= GAP; }
        c = null;
        origin[0] = -ax;
        origin[1] = -ay;
        return bitmap;
    }

    private List<Item> stack(AdasOverlayState st) {
        List<Item> out = new ArrayList<>();
        for (AdasOverlayState.Plate p : st.plates()) {
            if (p == AdasOverlayState.Plate.ROW) out.add(capsule(st));
            else if (p == AdasOverlayState.Plate.ALARM) out.add(alarmPlate(st));
            else out.add(takeoverPlate(st));
        }
        return out;
    }

    /* -------------------------------------------------------------------- the row */

    private interface El { float w(); void draw(float x, float cy); }

    private List<El> rowElements(final AdasOverlayState st) {
        final boolean stale = st.stale;
        final boolean icc = st.iccLeads();
        final String pState = stale ? "stale" : name(icc ? st.icc : st.noa);
        List<El> els = new ArrayList<>();
        {
            final String name = icc ? "ICC" : "NOA";
            final String sub = stale ? null : !icc && st.noa != AdasOverlayState.Fn.OFF && st.mode != null
                    ? (st.mode == AdasOverlayState.Mode.CITY ? "город" : "трасса") : null;
            final String age = stale && st.staleSeconds >= 0 ? String.valueOf(st.staleSeconds) : null;
            final float wName = tw(name, BIG, true);
            final float wSub = age != null ? figure(age, "с", 0, 0, MID, WHITE, 1, true) : sub != null ? tw(sub, MID, false) : 0;
            final float w = GLYPH + 20 + wName + (wSub > 0 ? 18 + wSub : 0);
            els.add(new El() {
                public float w() { return w; }
                public void draw(float x, float cy) {
                    pilotGlyph(icc ? "icc" : "noa", pState, x, cy, GLYPH);
                    float bx = x + GLYPH + 20, base = cy + 24;
                    int[] col = pState.equals("active") ? BLUE : pState.equals("available") ? WHITE : GREY;
                    float in = pState.equals("active") || pState.equals("available") ? 1 : pState.equals("off") ? 0.6f : 0.42f;
                    word(name, bx, base, BIG, col, in, pState.equals("active") ? 1 : 0, true);
                    if (age != null) figure(age, "с", bx + wName + 18, base, MID, WHITE, 0.95f, false);
                    else if (sub != null) word(sub, bx + wName + 18, base, MID, pState.equals("active") ? BLUE : WHITE,
                            pState.equals("active") ? 0.95f : 0.8f, 0, false);
                }
            });
        }
        if (!st.narrow) {
            final String name = icc ? "NOA" : "ICC";
            final String s2 = stale ? "stale" : name(icc ? st.noa : st.icc);
            final float w = 28 + 12 + tw(name, MID, true);
            els.add(null);
            els.add(new El() {
                public float w() { return w; }
                public void draw(float x, float cy) {
                    stateMark(s2, x, cy, 14);
                    int[] col = s2.equals("active") ? BLUE : s2.equals("available") ? WHITE : GREY;
                    float in = s2.equals("active") ? 1 : s2.equals("available") ? 0.9f : s2.equals("off") ? 0.6f : 0.42f;
                    word(name, x + 40, cy + 14, MID, col, in, s2.equals("active") ? 0.7f : 0, true);
                }
            });
        }
        if (st.route != null) {
            final AdasOverlayState.Route route = st.route;
            final float w = 60 + 12 + (route == AdasOverlayState.Route.MATCHED ? 50 : route == AdasOverlayState.Route.SELECTED ? 38 : 40);
            els.add(null);
            els.add(new El() {
                public float w() { return w; }
                public void draw(float x, float cy) { routeGlyph(route, stale, x, cy); }
            });
        }
        return els;
    }

    private static String name(AdasOverlayState.Fn fn) {
        return fn == AdasOverlayState.Fn.ACTIVE ? "active" : fn == AdasOverlayState.Fn.AVAILABLE ? "available" : "off";
    }

    private Item capsule(final AdasOverlayState st) {
        final List<El> els = rowElements(st);
        float w = 2 * PAD;
        for (El e : els) w += e == null ? 2 * SEP + 2 : e.w();
        float h = H;
        final List<AdasOverlayState.Detail> details = st.details;
        if (details != null) { w = Math.max(w, 2 * PAD + detailsWidth(details)); h += DETAILS_H; }
        final float fw = w, fh = h;
        Item it = new Item() {
            void draw(float x, float y) {
                plate(x, y, fw, fh, null, 0, 0);
                float cx = x + PAD, cy = y + H / 2;
                for (El e : els) {
                    if (e == null) { rect(cx + SEP, cy - 34, 2, 68, 0x1FFFFFFF); cx += 2 * SEP + 2; }
                    else { e.draw(cx, cy); cx += e.w(); }
                }
                if (details != null) {
                    rect(x + PAD, y + H - 2, fw - 2 * PAD, 2, 0x1AFFFFFF);
                    drawDetails(details, x + PAD, y + H + 42);
                }
            }
        };
        it.w = fw; it.h = fh;
        return it;
    }

    private float detailsWidth(List<AdasOverlayState.Detail> items) {
        float w = 0;
        for (int i = 0; i < items.size(); i++) {
            AdasOverlayState.Detail d = items.get(i);
            w += tw(d.label, SMALL, false) + 12 + figure(d.value, "с", 0, 0, SMALL, WHITE, 1, true);
            if (i < items.size() - 1) w += 44;
        }
        return w;
    }

    private void drawDetails(List<AdasOverlayState.Detail> items, float x, float base) {
        float cx = x;
        for (int i = 0; i < items.size(); i++) {
            AdasOverlayState.Detail d = items.get(i);
            int[] tone = d.warn ? ORANGE_W : WHITE;
            cx += word(d.label, cx, base, SMALL, tone, d.warn ? 1 : 0.62f, 0, false) + 12;
            cx += figure(d.value, "с", cx, base, SMALL, tone, d.warn ? 1 : 0.9f, false);
            if (i < items.size() - 1) {
                fill.setXfermode(null); fill.setMaskFilter(null); fill.setShader(null); fill.setColor(0x59FFFFFF);
                c.drawCircle(cx + 22, base - 11, 2.6f, fill);
                cx += 44;
            }
        }
    }

    /* ------------------------------------------------------------ stacked plates */

    private Item alarmPlate(AdasOverlayState st) {
        final String big = st.alarmTitle();
        String subtitle = st.alarmSubtitle().replace('\n',' ').replace('\r',' ');
        final String small = subtitle.length()>96?subtitle.substring(0,95)+"…":subtitle;
        final float w = 2 * PAD + 80 + 26 + Math.max(tw(big, BIG, true), tw(small, MID, false));
        Item it = new Item() {
            void draw(float x, float y) {
                plate(x, y, w, ALARM_H, ORANGE, 0.55f, 0.6f);
                haze(x + PAD + 40, y + ALARM_H / 2, 120, ORANGE, 0.16f);
                triangle(x + PAD, y + ALARM_H / 2, 80, ORANGE);
                float tx = x + PAD + 80 + 26;
                word(big, tx, y + 34 + 48, BIG, ORANGE_W, 1, 0.8f, true);
                word(small, tx, y + 34 + 48 + 22 + 30, MID, WHITE, 0.88f, 0, false);
            }
        };
        it.w = w; it.h = ALARM_H;
        return it;
    }

    private Item takeoverPlate(AdasOverlayState st) {
        final String t = st.takeover;
        final float w = 2 * PAD + 88 + 24 + tw(t, BIG, true), h = H + 16;
        Item it = new Item() {
            void draw(float x, float y) {
                plate(x, y, w, h, RED, 0.85f, 1);
                haze(x + PAD + 44, y + h / 2, 150, RED, 0.22f);
                wheel(x + PAD, y + h / 2, 88, RED);
                word(t, x + PAD + 88 + 24, y + h / 2 + 24, BIG, RED_W, 1, 1, true);
            }
        };
        it.w = w; it.h = h;
        return it;
    }

    private void plate(float x, float y, float w, float h, int[] edge, float edgeA, float glow) {
        Path p = new Path();
        p.addRoundRect(new RectF(x, y, x + w, y + h), RADIUS, RADIUS, Path.Direction.CW);
        // the drop shadow: rgba(0,0,0,0.45), blur 28, 6 down, cast by a fill at 0.95
        fill.setXfermode(null); fill.setShader(null);
        c.save(); c.translate(0, 6);
        fill.setColor(argb(0xFF000000, 0.45f * 0.95f)); fill.setMaskFilter(blur(28)); c.drawPath(p, fill);
        c.restore();
        fill.setMaskFilter(null); fill.setColor(argb(0xFF07080B, 0.95f)); c.drawPath(p, fill);
        Path e = new Path();
        e.addRoundRect(new RectF(x + 1, y + 1, x + w - 1, y + h - 1), RADIUS - 1, RADIUS - 1, Path.Direction.CW);
        if (edge != null) beam(e, 2.4f, edge, edgeA, glow, null);
        else {
            stroke.setXfermode(null); stroke.setPathEffect(null); stroke.setMaskFilter(null);
            stroke.setStrokeWidth(2); stroke.setColor(0x1AFFFFFF); c.drawPath(e, stroke);
        }
    }

    private void haze(float x, float cy, float r, int[] col, float a) {
        fill.setXfermode(add); fill.setMaskFilter(null);
        fill.setShader(new RadialGradient(x, cy, r, argb(col[0], a), argb(col[0], 0), Shader.TileMode.CLAMP));
        c.drawRect(x - r, cy - r, x + r, cy + r, fill);
        fill.setShader(null);
    }

    /* ------------------------------------------------------------------- glyphs */

    private void pilotGlyph(String kind, String state, float x, float cy, float g) {
        float u = g / 88, t = cy - g / 2;
        Path edges = new Path();
        edges.moveTo(x + 8 * u, t + 84 * u); edges.lineTo(x + 31 * u, t + 6 * u);
        edges.moveTo(x + 80 * u, t + 84 * u); edges.lineTo(x + 57 * u, t + 6 * u);
        Path chev = new Path();
        chev.moveTo(x + 44 * u, t + 40 * u); chev.lineTo(x + 63 * u, t + 74 * u); chev.lineTo(x + 44 * u, t + 63 * u);
        chev.lineTo(x + 25 * u, t + 74 * u); chev.close();
        Path extra = new Path();
        boolean noa = kind.equals("noa");
        if (noa) { extra.moveTo(x + 44 * u, t + 34 * u); extra.cubicTo(x + 44 * u, t + 22 * u, x + 46 * u, t + 15 * u, x + 54 * u, t + 6 * u); }
        else extra.addRoundRect(new RectF(x + 36 * u, t + 10 * u, x + 52 * u, t + 25 * u), 3 * u, 3 * u, Path.Direction.CW);
        if (state.equals("active")) {
            beam(edges, 3.6f, BLUE, 0.85f, 0.7f, null);
            glowFill(chev, BLUE, 1, 22);
            if (noa) beam(extra, 4.5f, BLUE, 1, 1, null); else glowFill(extra, BLUE, 1, 14);
        } else if (state.equals("available")) {
            beam(edges, 3, WHITE, 0.55f, 0, null);
            beam(chev, 3.2f, WHITE, 0.95f, 0, null);
            beam(extra, 3, WHITE, 0.6f, 0, noa ? new float[]{2, 9} : null);
        } else if (state.equals("off")) {
            beam(edges, 3, GREY, 0.4f, 0, null);
        } else {
            beam(edges, 3, GREY, 0.4f, 0, new float[]{9, 11});
        }
    }

    private void stateMark(String state, float x, float cy, float r) {
        Path p = new Path();
        if (state.equals("active")) { p.addCircle(x + r, cy, r * 0.62f, Path.Direction.CW); glowFill(p, BLUE, 1, 14); }
        else if (state.equals("available")) { p.addCircle(x + r, cy, r * 0.6f, Path.Direction.CW); beam(p, 3, WHITE, 0.9f, 0, null); }
        else if (state.equals("off")) { p.moveTo(x + r * 0.35f, cy); p.lineTo(x + r * 1.65f, cy); beam(p, 3.5f, GREY, 0.55f, 0, null); }
        else { p.addCircle(x + r, cy, r * 0.6f, Path.Direction.CW); beam(p, 3, GREY, 0.4f, 0, new float[]{4, 6}); }
    }

    private void routeGlyph(AdasOverlayState.Route route, boolean stale, float x, float cy) {
        float s = 60, t = cy - s / 2;
        Path line = new Path(); line.moveTo(x + 8, t + 52); line.cubicTo(x + 8, t + 26, x + 50, t + 40, x + 50, t + 12);
        Path start = new Path(); start.addCircle(x + 8, t + 52, 5.5f, Path.Direction.CW);
        Path end = new Path(); end.addCircle(x + 50, t + 10, 7.5f, Path.Direction.CW);
        boolean waiting = route == AdasOverlayState.Route.WAITING, matched = route == AdasOverlayState.Route.MATCHED;
        int[] col = stale ? GREY : matched ? BLUE : route == AdasOverlayState.Route.REJECTED ? ORANGE : WHITE;
        float in = stale ? 0.4f : 1;
        float g = !stale && matched ? 1 : 0;
        beam(line, 4, col, in * (waiting ? 0.75f : 1), g, waiting ? new float[]{3, 11} : null);
        beam(start, 3, col, in * 0.9f, 0, null);
        if (waiting) beam(end, 3, col, in * 0.8f, 0, null); else glowFill(end, col, in, g > 0 ? 16 : 0.01f);
        float rx = x + s + 12, ry = cy;
        Path tk = new Path();
        if (waiting) {
            tk.addCircle(rx + 20, ry, 16, Path.Direction.CW);
            tk.moveTo(rx + 20, ry - 9); tk.lineTo(rx + 20, ry); tk.lineTo(rx + 27, ry + 5);
            beam(tk, 3.4f, col, in * 0.85f, 0, null);
            return;
        }
        if (route == AdasOverlayState.Route.REJECTED) {
            tk.moveTo(rx + 6, ry - 14); tk.lineTo(rx + 34, ry + 14); tk.moveTo(rx + 34, ry - 14); tk.lineTo(rx + 6, ry + 14);
            beam(tk, 5, col, in, 1, null);
            return;
        }
        tk.moveTo(rx + 2, ry + 1); tk.lineTo(rx + 13, ry + 13); tk.lineTo(rx + 36, ry - 14);
        if (matched) { tk.moveTo(rx + 24, ry + 11); tk.lineTo(rx + 26, ry + 13); tk.lineTo(rx + 49, ry - 14); }
        beam(tk, 5, col, in, g, null);
    }

    private void triangle(float x, float cy, float g, int[] col) {
        float u = g / 80, t = cy - g / 2;
        Path tri = new Path(); tri.moveTo(x + 40 * u, t + 6 * u); tri.lineTo(x + 77 * u, t + 72 * u); tri.lineTo(x + 3 * u, t + 72 * u); tri.close();
        Path bang = new Path(); bang.moveTo(x + 40 * u, t + 30 * u); bang.lineTo(x + 40 * u, t + 50 * u);
        Path dot = new Path(); dot.addCircle(x + 40 * u, t + 61 * u, 3.6f * u, Path.Direction.CW);
        beam(tri, 5, col, 1, 1, null); beam(bang, 6, col, 1, 0.6f, null); glowFill(dot, col, 1, 8);
    }

    private void wheel(float x, float cy, float g, int[] col) {
        float u = g / 88, cx = x + 44 * u;
        Path rim = new Path(); rim.addCircle(cx, cy, 36 * u, Path.Direction.CW);
        Path hub = new Path(); hub.addCircle(cx, cy, 9 * u, Path.Direction.CW);
        Path sp = new Path();
        sp.moveTo(cx - 35 * u, cy + 2 * u); sp.lineTo(cx - 9 * u, cy + 2 * u);
        sp.moveTo(cx + 9 * u, cy + 2 * u); sp.lineTo(cx + 35 * u, cy + 2 * u);
        sp.moveTo(cx, cy + 9 * u); sp.lineTo(cx, cy + 35 * u);
        beam(rim, 6, col, 1, 1, null); beam(hub, 4, col, 1, 0.5f, null); beam(sp, 4, col, 0.9f, 0.4f, null);
        for (int sd = -1; sd <= 1; sd += 2) {
            double a = -Math.PI / 2 + sd * 0.95;
            float hx = cx + (float) Math.cos(a) * 36 * u, hy = cy + (float) Math.sin(a) * 36 * u;
            Path h = new Path();
            h.addOval(new RectF(hx - 12 * u, hy - 7 * u, hx + 12 * u, hy + 7 * u), Path.Direction.CW);
            matrix.setRotate((float) Math.toDegrees(a + Math.PI / 2), hx, hy);
            h.transform(matrix);
            glowFill(h, col, 1, 18);
        }
    }

    /* ---------------------------------------------------------------- primitives */

    private void beam(Path path, float sw, int[] col, float in, float g, float[] dash) {
        if (in <= 0.01f) return;
        stroke.setXfermode(add); stroke.setShader(null);
        stroke.setPathEffect(dash == null ? null : new DashPathEffect(dash, 0));
        if (g > 0) {
            stroke.setMaskFilter(null);
            stroke.setStrokeWidth(sw * 4.5f); stroke.setColor(argb(col[0], 0.04f * in * g)); c.drawPath(path, stroke);
            stroke.setStrokeWidth(sw * 1.8f);
            float source = 0.18f * in * g;
            shadow(path, stroke, col[0], 0.8f * in * g * source, Math.min(34, sw * 3.2f));
            stroke.setColor(argb(col[0], source)); c.drawPath(path, stroke);
        }
        stroke.setMaskFilter(null);
        stroke.setStrokeWidth(sw); stroke.setColor(argb(col[1], in)); c.drawPath(path, stroke);
        stroke.setPathEffect(null);
    }

    private void glowFill(Path path, int[] col, float in, float blurPx) {
        if (in <= 0.01f) return;
        fill.setXfermode(add); fill.setShader(null);
        float source = 0.6f * in;
        shadow(path, fill, col[0], 0.85f * in * source, blurPx);
        fill.setColor(argb(col[0], source)); c.drawPath(path, fill);
        fill.setColor(argb(col[1], 0.9f * in)); c.drawPath(path, fill);
    }

    private void shadow(Path path, Paint paint, int color, float alpha, float blurPx) {
        paint.setColor(argb(color, alpha));
        paint.setMaskFilter(blur(blurPx));
        c.drawPath(path, paint);
        paint.setMaskFilter(null);
    }

    /** Chrome's shadowBlur as a mask filter; null below half a pixel of sigma (drawn sharp). */
    static BlurMaskFilter blur(float shadowBlurPx) {
        float r = blurRadius(shadowBlurPx);
        return r > 0 ? new BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) : null;
    }

    static float blurRadius(float shadowBlurPx) {
        float sigma = shadowBlurPx / 2;
        return sigma <= 0.5f ? 0 : (sigma - 0.5f) / 0.57735f;
    }

    private void setType(float px, boolean strong) {
        type.setTypeface(strong ? medium : regular);
        type.setTextSize(px);
    }

    private float tw(String s, float px, boolean strong) { setType(px, strong); return type.measureText(s); }

    /** A word; lit words glow in their colour, calm ones are flat. Returns its width. */
    private float word(String s, float x, float base, float px, int[] col, float in, float glow, boolean strong) {
        if (in <= 0.01f || s == null || s.isEmpty()) return 0;
        setType(px, strong);
        type.setXfermode(add); type.setMaskFilter(null);
        if (glow > 0) {
            float source = 0.55f * in;
            type.setColor(argb(col[0], 0.9f * in * glow * source)); type.setMaskFilter(blur(26)); c.drawText(s, x, base, type);
            type.setMaskFilter(null);
            type.setColor(argb(col[0], source)); c.drawText(s, x, base, type);
            // the core over its own halo, not added to it: a lit word keeps its hue
            type.setXfermode(null);
        }
        type.setColor(argb(col[1], in)); c.drawText(s, x, base, type);
        type.setXfermode(null);
        return type.measureText(s);
    }

    private void num(String s, float x, float base, float size, int[] col, float in) {
        float k = AdasDigits.k(size), sw = AdasDigits.stroke(size), cx = x;
        for (int i = 0; i < s.length(); i++) {
            int g = AdasDigits.glyph(s.charAt(i));
            if (g < 0) continue;
            if (glyphs[g] != null) {
                matrix.setScale(k, k); matrix.postTranslate(cx, base - AdasDigits.CAP * k);
                glyphs[g].transform(matrix, scratch);
                beam(scratch, sw, col, in, 0, null);
            }
            cx += (AdasDigits.ADVANCE[g] + AdasDigits.TRACK) * k;
        }
    }

    /** A figure with its unit on the baseline: «12 с», «0,4 с». */
    private float figure(String value, String unit, float x, float base, float size, int[] col, float in, boolean measureOnly) {
        float w1 = AdasDigits.width(value, size), gap = size * 0.22f, w2 = tw(unit, size * 0.9f, false);
        if (!measureOnly) {
            num(value, x, base, size, col, in);
            word(unit, x + w1 + gap, base, size * 0.9f, col, in * 0.85f, 0, false);
        }
        return w1 + gap + w2;
    }

    private void rect(float x, float y, float w, float h, int color) {
        fill.setXfermode(null); fill.setMaskFilter(null); fill.setShader(null); fill.setColor(color);
        c.drawRect(x, y, x + w, y + h, fill);
    }

    static int argb(int color, float a) {
        int v = Math.round(Math.max(0, Math.min(1, a)) * 255);
        return (color & 0x00FFFFFF) | (v << 24);
    }
}
