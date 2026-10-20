package dev.denza.clusterglow.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * One Contour scene with the zero's glow drawn over it, the way luminofor.js centre() and
 * ClusterDashboardRenderer draw it: a radial gradient of the halo colour centred on zero, flattened
 * to reach from the axis to the stock band and forty units past, composited additively. The scene
 * is the board rendered without its glow, so at strength ×1, the design's shape and size ×1 this is
 * the board's own picture.
 *
 * What the owner changes: the strength multiplies the design's alpha, the soft shape swaps the
 * design's linear fall-off (a cone with a crease at its rim) for 1 - smoothstep (flat at the centre
 * and at the rim), and the size scales both radii.
 */
final class GlowView extends View {

    /** The luminofor.js threshold: at -3 kW and below the glow is blue, energy coming back. */
    private static final float INTO_KW = -3f;
    private static final int SOFT_STOPS = 17;

    private final Scenes.Geometry geometry;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect frame = new Rect();

    private Bitmap scene;
    private float powerKw;
    private float strength = 1f;
    private boolean soft;
    private float size = 1f;

    GlowView(Context context, Scenes.Geometry geometry) {
        super(context);
        this.geometry = geometry;
        glow.setStyle(Paint.Style.FILL);
        glow.setBlendMode(BlendMode.PLUS);
        glow.setDither(true);
    }

    void show(Bitmap scene, float powerKw, float strength, boolean soft, float size) {
        this.scene = scene;
        this.powerKw = powerKw;
        this.strength = strength;
        this.soft = soft;
        this.size = size;
        invalidate();
    }

    /** The glow's alpha at its centre: the design's 0.13·√(|P| / 120 kW), times the strength. */
    static float centreAlpha(Scenes.Geometry g, float powerKw, float strength) {
        float a = g.glowMax * (float) Math.sqrt(Math.min(1f, Math.abs(powerKw) / g.glowFullKw));
        return Math.min(1f, a * strength);
    }

    static boolean into(float powerKw) {
        return powerKw <= INTO_KW;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(Color.BLACK);
        if (w <= 0 || h <= 0) return;
        if (scene != null) {
            frame.set(0, 0, w, h);
            canvas.drawBitmap(scene, null, frame, image);
        }
        float alpha = centreAlpha(geometry, powerKw, strength);
        // luminofor.js stops drawing it under 0.005, and so does the app.
        if (alpha <= 0.005f) return;

        // The board's units: its height is the view's, its width centred on the view.
        float unit = h / geometry.H;
        float originX = geometry.W / 2f - w / (2f * unit);
        float cx = (geometry.W / 2f - originX) * unit;
        float cy = geometry.axis * unit;
        float r = geometry.glowRadius * unit * size;
        float squash = (geometry.stockBottom - geometry.axis + geometry.glowReachBelow) / geometry.glowRadius;
        int colour = into(powerKw) ? geometry.blue : geometry.ink;

        glow.setShader(shader(r, colour));
        glow.setAlpha(Math.round(alpha * 255f));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(1f, squash);
        canvas.drawCircle(0f, 0f, r, glow);
        canvas.restore();
    }

    private Shader shader(float r, int colour) {
        int clear = colour & 0x00FFFFFF;
        if (!soft) return new RadialGradient(0f, 0f, r, colour | 0xFF000000, clear, Shader.TileMode.CLAMP);
        int[] colours = new int[SOFT_STOPS];
        float[] stops = new float[SOFT_STOPS];
        for (int i = 0; i < SOFT_STOPS; i++) {
            float t = i / (float) (SOFT_STOPS - 1);
            float a = 1f - t * t * (3f - 2f * t);
            stops[i] = t;
            colours[i] = (Math.round(a * 255f) << 24) | clear;
        }
        return new RadialGradient(0f, 0f, r, colours, stops, Shader.TileMode.CLAMP);
    }
}
