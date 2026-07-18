package dev.denza.tools;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shell-UID research probe for capturing a logical display through the window
 * manager's display-mirror layer, without touching the AVC camera Binder API.
 * Compile to dex and run with app_process.
 */
public final class SurfaceControlMirrorProbe {
    private SurfaceControlMirrorProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: SurfaceControlMirrorProbe DISPLAY_ID OUTPUT_PNG [SCALE]");
        }
        int displayId = Integer.parseInt(args[0]);
        String outputPath = args[1];
        float scale = args.length == 3 ? Float.parseFloat(args[2]) : 0.5f;
        if (!(scale > 0f && scale <= 1f)) {
            throw new IllegalArgumentException("scale must be in (0, 1]");
        }

        Object displayInfo = displayInfo(displayId);
        int layerStack = intField(displayInfo, "layerStack");
        int width = intField(displayInfo, "logicalWidth");
        int height = intField(displayInfo, "logicalHeight");
        System.out.println("source display=" + displayId + " layerStack=" + layerStack
                + " size=" + width + "x" + height);

        Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
        Object mirror = surfaceControlClass.getConstructor().newInstance();
        try {
            Object windowManager = windowManagerService();
            Method mirrorDisplay = windowManager.getClass().getMethod(
                    "mirrorDisplay", int.class, surfaceControlClass);
            mirrorDisplay.setAccessible(true);
            boolean mirrored = (Boolean) mirrorDisplay.invoke(windowManager, displayId, mirror);
            if (!mirrored) throw new IllegalStateException("window manager refused display mirror");

            Class<?> transactionClass = Class.forName("android.view.SurfaceControl$Transaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("show", surfaceControlClass).invoke(transaction, mirror);
            transactionClass.getMethod("apply").invoke(transaction);

            Object screenshot = surfaceControlClass.getMethod(
                            "captureLayers",
                            surfaceControlClass,
                            Rect.class,
                            float.class)
                    .invoke(null, mirror, new Rect(0, 0, width, height), scale);
            if (screenshot == null) throw new IllegalStateException("captureLayers returned null");
            Bitmap hardwareBitmap = (Bitmap) screenshot.getClass().getMethod("asBitmap")
                    .invoke(screenshot);
            if (hardwareBitmap == null) throw new IllegalStateException("captured bitmap is null");
            Bitmap bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) throw new IllegalStateException("bitmap copy failed");
            try (FileOutputStream output = new FileOutputStream(outputPath)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG encoder returned false");
                }
            } finally {
                bitmap.recycle();
                hardwareBitmap.recycle();
            }
            System.out.println("saved " + outputPath);
        } finally {
            surfaceControlClass.getMethod("release").invoke(mirror);
        }
    }

    private static Object displayInfo(int displayId) throws Exception {
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = globalClass.getMethod("getInstance").invoke(null);
        Object info = globalClass.getMethod("getDisplayInfo", int.class).invoke(global, displayId);
        if (info == null) throw new IllegalArgumentException("display " + displayId + " not found");
        return info;
    }

    private static Object windowManagerService() throws Exception {
        Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
        return globalClass.getMethod("getWindowManagerService").invoke(null);
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        return field.getInt(target);
    }
}
