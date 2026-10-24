package dev.denza.adasplayer.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

/** Explicit single-run entry: the host runner pushes scenario.json and preconditions.json into the
 * private files dir and launches this with {@code parkedOnce} true. Output goes to player.jsonl. */
public final class PlayerActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (!getIntent().getBooleanExtra("parkedOnce", false))
                throw new SecurityException("explicit parkedOnce required");
            File out = new File(getFilesDir(), "player.jsonl");
            PrintStream trace = new PrintStream(new FileOutputStream(out, true), true, "UTF-8");
            System.setOut(trace);
            System.setErr(trace);
            if (!new File(getFilesDir(), "player-started.once").createNewFile())
                throw new IllegalStateException("already started; no repeat");
            ParkedPlayer.startInApplication(getApplicationContext(), getFilesDir());
        } catch (Throwable failure) {
            Log.e("DenzaParkedPlayer", "startup rejected", failure);
        } finally {
            finish();
        }
    }
}
