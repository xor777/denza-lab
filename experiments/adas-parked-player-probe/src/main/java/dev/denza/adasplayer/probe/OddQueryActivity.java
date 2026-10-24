package dev.denza.adasplayer.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

/** One bounded read of the stock ODD region list. No scenario, server, route or vehicle setter. */
public final class OddQueryActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (!getIntent().getBooleanExtra("oddQueryOnce", false))
                throw new SecurityException("explicit oddQueryOnce required");
            File directory = getFilesDir();
            if (!new File(directory, "odd-query-started.once").createNewFile())
                throw new IllegalStateException("already queried; no repeat");
            PrintStream out = new PrintStream(new FileOutputStream(new File(directory, "odd-query.jsonl")), true, "UTF-8");
            Recorder recorder = new FileRecorder(out, "ODD_QUERY_ONLY");
            new Thread(() -> {
                AdasAnswerListener listener = new AdasAnswerListener(getApplicationContext(), recorder);
                try {
                    listener.start();
                    long deadline = SystemClock.elapsedRealtime() + 5000;
                    while (!listener.oddReady() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
                    recorder.note("odd_query_result", "result=" + listener.oddRequest());
                } catch (Exception failure) {
                    recorder.note("odd_query_error", failure.getClass().getSimpleName());
                } finally {
                    listener.close();
                    recorder.note("odd_query_finished", "read-only client closed; no server or route started");
                    out.close();
                    runOnUiThread(this::finish);
                }
            }, "adas-odd-query-only").start();
        } catch (Exception failure) {
            Log.e("DenzaParkedPlayer", "ODD-only query rejected", failure);
            finish();
        }
    }
}
