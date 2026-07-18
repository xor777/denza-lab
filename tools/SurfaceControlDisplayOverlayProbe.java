package dev.denza.tools;

import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shell-UID research probe that places a cropped mirror of one logical display
 * on another display's layer stack. It never calls the vendor AVC Binder API.
 */
public final class SurfaceControlDisplayOverlayProbe {
    private SurfaceControlDisplayOverlayProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            throw new IllegalArgumentException(
                    "usage: SurfaceControlDisplayOverlayProbe SOURCE_DISPLAY TARGET_DISPLAY "
                            + "SRC_LEFT SRC_TOP SRC_RIGHT SRC_BOTTOM "
                            + "DST_LEFT DST_TOP DST_RIGHT DST_BOTTOM DURATION_MS");
        }
        int sourceDisplayId = integer(args[0], "source display");
        int targetDisplayId = integer(args[1], "target display");
        Rect source = rect(args, 2, "source crop");
        Rect target = rect(args, 6, "target bounds");
        int durationMs = integer(args[10], "duration");
        if (durationMs < 250 || durationMs > 30_000) {
            throw new IllegalArgumentException("duration must be between 250 and 30000 ms");
        }

        Object sourceInfo = displayInfo(sourceDisplayId);
        Object targetInfo = displayInfo(targetDisplayId);
        validateInside(source, sourceInfo, "source crop");
        validateInside(target, targetInfo, "target bounds");
        int targetLayerStack = intField(targetInfo, "layerStack");

        Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
        Class<?> transactionClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object mirror = surfaceControlClass.getConstructor().newInstance();
        try {
            Object windowManager = windowManagerService();
            Method mirrorDisplay = windowManager.getClass().getMethod(
                    "mirrorDisplay", int.class, surfaceControlClass);
            mirrorDisplay.setAccessible(true);
            boolean mirrored = (Boolean) mirrorDisplay.invoke(
                    windowManager, sourceDisplayId, mirror);
            if (!mirrored) throw new IllegalStateException("window manager refused display mirror");

            float scaleX = target.width() / (float) source.width();
            float scaleY = target.height() / (float) source.height();
            float positionX = target.left - source.left * scaleX;
            float positionY = target.top - source.top * scaleY;

            Object show = transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("setLayerStack", surfaceControlClass, int.class)
                    .invoke(show, mirror, targetLayerStack);
            transactionClass.getMethod("setLayer", surfaceControlClass, int.class)
                    .invoke(show, mirror, Integer.MAX_VALUE);
            transactionClass.getMethod("setCrop", surfaceControlClass, Rect.class)
                    .invoke(show, mirror, source);
            transactionClass.getMethod(
                            "setMatrix", surfaceControlClass,
                            float.class, float.class, float.class, float.class)
                    .invoke(show, mirror, scaleX, 0f, 0f, scaleY);
            transactionClass.getMethod(
                            "setPosition", surfaceControlClass, float.class, float.class)
                    .invoke(show, mirror, positionX, positionY);
            transactionClass.getMethod("show", surfaceControlClass).invoke(show, mirror);
            transactionClass.getMethod("apply").invoke(show);

            System.out.println("showing display " + sourceDisplayId + " crop=" + source
                    + " on display " + targetDisplayId + " bounds=" + target);
            Thread.sleep(durationMs);

            Object remove = transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("remove", surfaceControlClass).invoke(remove, mirror);
            transactionClass.getMethod("apply").invoke(remove);
        } finally {
            surfaceControlClass.getMethod("release").invoke(mirror);
        }
    }

    private static Rect rect(String[] args, int offset, String label) {
        Rect value = new Rect(
                integer(args[offset], label + " left"),
                integer(args[offset + 1], label + " top"),
                integer(args[offset + 2], label + " right"),
                integer(args[offset + 3], label + " bottom"));
        if (value.width() <= 0 || value.height() <= 0) {
            throw new IllegalArgumentException(label + " must have positive size");
        }
        return value;
    }

    private static void validateInside(Rect rect, Object displayInfo, String label) throws Exception {
        int width = intField(displayInfo, "logicalWidth");
        int height = intField(displayInfo, "logicalHeight");
        if (rect.left < 0 || rect.top < 0 || rect.right > width || rect.bottom > height) {
            throw new IllegalArgumentException(
                    label + " " + rect + " is outside " + width + "x" + height);
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

    private static int integer(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " must be an integer", error);
        }
    }
}
