package dev.denza.adasparkedprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import dev.denza.hud.probe.ParkedRouteProbe;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

/** Explicit single-run parked probe using a registered APK's actual Context. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (!getIntent().getBooleanExtra("parkedOnce", false)) throw new SecurityException("Explicit parkedOnce required");
            int chunks = getIntent().getIntExtra("routeCount", 0);
            if (chunks < 1 || chunks > 8) throw new IllegalArgumentException("routeCount1..8");
            File output = new File(getFilesDir(), "probe.jsonl");
            PrintStream trace = new PrintStream(new FileOutputStream(output, true), true, "UTF-8");
            System.setOut(trace); System.setErr(trace);
            if (!new File(getFilesDir(), "probe-started.once").createNewFile()) throw new IllegalStateException("Already started; no repeat");
            ParkedRouteProbe.startInApplication(getApplicationContext(), getFilesDir(), chunks);
        } catch (Throwable failure) {
            failure.printStackTrace(); Log.e("DenzaParkedRouteProbe", "Startup rejected", failure); finish();
        }
    }
}
