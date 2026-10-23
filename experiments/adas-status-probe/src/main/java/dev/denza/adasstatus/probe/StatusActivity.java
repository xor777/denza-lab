package dev.denza.adasstatus.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class StatusActivity extends Activity {
    private final Handler main = new Handler();
    private TextView status;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24,24,24,24);
        layout.setBackgroundColor(0xFF101820);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(19); status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(status);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        button(layout, "Начать наблюдение", () -> startForegroundService(new Intent(this, StatusService.class)));
        button(layout, "Показать поверх карты", () -> {
            if (!Settings.canDrawOverlays(this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            else startService(new Intent(this, StatusService.class).setAction(StatusService.OVERLAY).putExtra("enabled", true));
        });
        button(layout, "Скрыть оверлей", () -> startService(new Intent(this, StatusService.class).setAction(StatusService.OVERLAY).putExtra("enabled", false)));
        button(layout, "Остановить наблюдение", () -> startService(new Intent(this, StatusService.class).setAction(StatusService.STOP)));
        TextView files = new TextView(this); files.setTextColor(0xFFAAAAAA); files.setText("Логи: adas-status.jsonl · adas-status-changes.jsonl"); layout.addView(files);
        setContentView(layout); startForegroundService(new Intent(this, StatusService.class)); applyIntent(getIntent());
    }
    private void button(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); applyIntent(intent); }
    private void applyIntent(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra("fixture")) startService(new Intent(this, StatusService.class).setAction(StatusService.FIXTURE).putExtra("fixture", intent.getStringExtra("fixture")));
        if (intent.getBooleanExtra("overlay", false)) startService(new Intent(this, StatusService.class).setAction(StatusService.OVERLAY).putExtra("enabled", true));
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() { status.setText(StatusService.textNow()); main.postDelayed(this, 500); }
    };
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
