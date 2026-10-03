package dev.denza.fsehud.probe;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Retains this probe's Java crash and its own Android process-exit metadata for export. */
public final class ProbeApplication extends Application {
    private static final String CRASH = "last-java-crash.json";

    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            AtomicFile file = new AtomicFile(new File(getFilesDir(), CRASH));
            FileOutputStream out = null;
            try {
                JSONObject record = new JSONObject();
                record.put("version", "0.2.1");
                record.put("epoch_ms", System.currentTimeMillis());
                record.put("elapsed_ms", SystemClock.elapsedRealtime());
                record.put("pid", android.os.Process.myPid());
                record.put("thread", thread.getName());
                record.put("stack", Log.getStackTraceString(error));
                out = file.startWrite();
                out.write(record.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out);
            } catch (Throwable ignored) {
                if (out != null) file.failWrite(out);
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            }
        });
    }

    static void append(Context context, JSONObject report) throws Exception {
        JSONArray exits = new JSONArray();
        try {
            for (ApplicationExitInfo info : context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(context.getPackageName(), 0, 5)) {
                exits.put(LocalHudProbe.json("epoch_ms", info.getTimestamp(), "pid", info.getPid(),
                        "reason", info.getReason(), "status", info.getStatus(),
                        "description", info.getDescription()));
            }
            report.put("own_process_exits", exits);
        } catch (Exception e) { report.put("exit_info_error", e.toString()); }
        File crash = new File(context.getFilesDir(), CRASH);
        if (crash.isFile()) report.put("last_java_crash", new JSONObject(new String(
                new AtomicFile(crash).readFully(), StandardCharsets.UTF_8)));
    }
}
