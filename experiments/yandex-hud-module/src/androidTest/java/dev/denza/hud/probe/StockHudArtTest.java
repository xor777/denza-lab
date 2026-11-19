package dev.denza.hud.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;

/** Standalone app_process fixture: uses the installed candidate assets, sends nothing to Binder. */
public final class StockHudArtTest {
    public static void main(String[] args) throws Exception {
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish"))
            throw new IllegalStateException("Emulator only");
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("ru.yandex.yandexnavi", 0);
        StockHudArt art = new StockHudArt(context.getAssets(), laneArt(context));
        NavigatorSource.Lane selected = new NavigatorSource.Lane("PLAIN_LANE", Arrays.asList("straightahead", "right90"), "right90");
        NavigatorSource.Lane grey = new NavigatorSource.Lane("PLAIN_LANE", Arrays.asList("straightahead"), null);
        NavigatorSource.Lane bus = new NavigatorSource.Lane("BUS_LANE", Collections.<String>emptyList(), null);
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("Output directory");
        for (int count : new int[]{1, 3, 6}) {
            String[] symbols = StockHudSymbols.lanes(Collections.nCopies(count, selected));
            byte[] png = art.lanes(symbols);
            Bitmap image = BitmapFactory.decodeByteArray(png, 0, png.length);
            check(image.getWidth() == 41 * count && image.getHeight() == 60, "Dimensions " + count);
            check(colorCount(image, 0xff15c6ff) > 0 && colorCount(image, 0xff91959c) > 0, "Original cyan and grey " + count);
            for (int y = 0; y < 60; y++) check(image.getPixel(0, y) == 0xff000000, "Black optical background");
            image.recycle(); save(out, "lanes-" + count + ".png", png);
            int builds = art.laneBuilds;
            for (int repeat = 0; repeat < 1000; repeat++) check(png == art.lanes(symbols.clone()), "Unchanged sign returns cached PNG");
            check(builds == art.laneBuilds, "No encoding on unchanged frames");
        }
        String[] symbols = StockHudSymbols.lanes(Arrays.asList(grey, bus, selected));
        byte[] before = art.lanes(symbols);
        Bitmap image = BitmapFactory.decodeByteArray(before, 0, before.length);
        // Since v4 every lane is a cell of BYD's pixels: the bus lane, not the one to take, is its back picture.
        check(colorCount(image, 41, 82, 0xff000000) < 41 * 60, "Bus lane drawn");
        image.recycle(); save(out, "lanes-bus.png", before);
        check(art.lanes(new String[0]) == null, "Disappearance clears cached row");
        check(Arrays.equals(before, art.lanes(symbols)), "Reappearance matches original row");
        NavigatorSource.Hint hint = new NavigatorSource.Hint();hint.icon = "right";hint.stockId = 2;
        byte[] right = art.manoeuvre(hint);
        image = BitmapFactory.decodeByteArray(right, 0, right.length);
        check(image.getWidth() == 80 && image.getHeight() == 80 && colorCount(image, 0xff15c6ff) > 0, "Stock manoeuvre pixels");
        image.recycle(); save(out, "manoeuvre-right.png", right);
        check(right == art.manoeuvre(hint), "Original PNG reused");
        hint.icon = "roundabout";hint.stockId = 13;hint.exit = 3;
        save(out, "manoeuvre-roundabout3.png", art.manoeuvre(hint));
        System.out.println("Stock Android artwork passed: 1/3/6 lanes, palette, bus cell, cache, disappearance/reappearance, manoeuvres");
    }
    private static int colorCount(Bitmap image, int color) {
        return colorCount(image, 0, image.getWidth(), color);
    }
    private static int colorCount(Bitmap image, int from, int to, int color) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = from; x < to; x++) if (image.getPixel(x, y) == color) count++;
        return count;
    }
    /** The theme's laneArt from the installed APK, as HudModule.Engine reads it. */
    private static JSONObject laneArt(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("denza-hud/theme.json")) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) data.write(buffer, 0, count);
            return new JSONObject(data.toString("UTF-8")).getJSONObject("laneArt");
        }
    }
    private static void save(File dir, String name, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(data); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
