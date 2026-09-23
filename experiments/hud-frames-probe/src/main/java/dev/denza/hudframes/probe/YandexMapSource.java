package dev.denza.hudframes.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;

import java.nio.ByteBuffer;

/**
 * An app-owned virtual display whose pixels this process can read, for a navigator task the host
 * moves onto it. Named and flagged exactly like Denza Apps' cluster display ("Denza Navigation",
 * PUBLIC | PRESENTATION | OWN_CONTENT_ONLY), because the shell-side `ClusterProxyMain` that moves
 * the task creates a projection root only on a display with that name.
 */
final class YandexMapSource implements AutoCloseable {
    static final String DISPLAY_NAME = "Denza Navigation";
    private static final int FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    final int width;
    final int height;
    private final ImageReader reader;
    private final VirtualDisplay display;
    private Bitmap last;

    YandexMapSource(Context context, int width, int height, int densityDpi) {
        this.width = width;
        this.height = height;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        display = manager.createVirtualDisplay(DISPLAY_NAME, width, height, densityDpi,
                reader.getSurface(), FLAGS);
    }

    int displayId() {
        return display.getDisplay().getDisplayId();
    }

    /** The newest frame the display produced, or the previous one if nothing new arrived. */
    Bitmap latest() {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return last;
        }
        try {
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = plane.getPixelStride();
            int rowPixels = plane.getRowStride() / pixelStride;
            ByteBuffer buffer = plane.getBuffer();
            Bitmap padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap frame = rowPixels == width ? padded : Bitmap.createBitmap(padded, 0, 0, width, height);
            if (frame != padded) {
                padded.recycle();
            }
            if (last != null) {
                last.recycle();
            }
            last = frame;
            return frame;
        } finally {
            image.close();
        }
    }

    /**
     * The HUD picture: a rectangle of the output's aspect, centred at (`centerX`, `centerY`) as
     * fractions of the display and `widthFraction` of its width, scaled to `outW`×`outH`.
     * `invert` turns a light map dark, since black is what a HUD does not draw.
     */
    static Bitmap crop(Bitmap frame, float centerX, float centerY, float widthFraction,
            int outW, int outH, boolean invert, Rect cropOut) {
        int cropW = Math.max(16, Math.min(frame.getWidth(), Math.round(frame.getWidth() * widthFraction)));
        int cropH = Math.max(16, Math.min(frame.getHeight(), Math.round(cropW * outH / (float) outW)));
        int left = clamp(Math.round(frame.getWidth() * centerX - cropW / 2f), 0, frame.getWidth() - cropW);
        int top = clamp(Math.round(frame.getHeight() * centerY - cropH / 2f), 0, frame.getHeight() - cropH);
        cropOut.set(left, top, left + cropW, top + cropH);
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (invert) {
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0,
            })));
        }
        canvas.drawBitmap(frame, cropOut, new Rect(0, 0, outW, outH), paint);
        return out;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    @Override
    public void close() {
        display.release();
        reader.close();
        if (last != null) {
            last.recycle();
            last = null;
        }
    }
}
