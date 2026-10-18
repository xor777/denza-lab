package dev.denza.apps.feature.hud;

import android.graphics.*;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudRadarWarning;
import java.io.ByteArrayOutputStream;

/** Opaque backing keeps the warning legible over either navigator's map. No audio here. */
final class HudRadarPainter {
    static HudImage draw(HudRadarWarning warning) {
        Bitmap bitmap = Bitmap.createBitmap(170, 78, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(0, 0, 170, 78, 6, 6, paint);
        paint.setColor(0xffffd166);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(15);
        String title = warning.title;
        while (paint.measureText(title) > 158 && title.length() > 1) {
            title = title.substring(0, title.length() - 1);
        }
        canvas.drawText(title, 6, 18, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(23);
        canvas.drawText(warning.meters + " м", 6, 46, paint);
        if (warning.limit > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(0xffffd166);
            canvas.drawCircle(143, 43, 20, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(21);
            canvas.drawText(Integer.toString(warning.limit), 143, 50, paint);
            paint.setTextSize(11);
            canvas.drawText(warning.unit == 2 ? "mph" : "км/ч", 143, 75, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        paint.setTextSize(13);
        canvas.drawText(warning.section && warning.averageSpeed >= 0
                ? "ср. " + warning.averageSpeed + (warning.unit == 2 ? " mph" : " км/ч")
                : "HUD Speed", 6, 69, paint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return HudImage.png(bytes.toByteArray());
    }
}
