package dev.denza.hudlink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * A layered window drawn with Android's Canvas: black, then each layer's PNG scaled into its
 * rectangle at its opacity, encoded as PNG. Only for more than one layer; a single whole opaque
 * one goes to the HUD as it came ({@link HudComposition#passThrough}).
 */
final class HudCanvasRenderer implements HudCompositor.Renderer {
    private final String tag;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);

    HudCanvasRenderer(String tag) {
        this.tag = tag;
    }

    @Override
    public byte[] render(HudWindow window, List<HudLayer> layers) {
        Bitmap bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            for (HudLayer layer : layers) {
                byte[] png = layer.image.png();
                Bitmap picture = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (picture == null) {
                    Log.w(tag, "layer " + layer.source + "/" + layer.id + " is not a picture");
                    continue;
                }
                paint.setAlpha(Math.round(layer.opacity * 255f));
                HudRect rect = layer.rect;
                canvas.drawBitmap(picture, null,
                        new Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height), paint);
                picture.recycle();
            }
            return encode(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    @Override
    public byte[] black(HudWindow window) {
        Bitmap bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.BLACK);
            return encode(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private byte[] encode(Bitmap bitmap) {
        out.reset();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
