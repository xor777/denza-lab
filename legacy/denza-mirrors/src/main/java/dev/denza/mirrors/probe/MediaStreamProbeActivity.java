package dev.denza.mirrors.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MediaStreamProbeActivity extends Activity {
    private static final String TAG = "DenzaMediaProbe";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BydMediaStreamServer mediaStreamServer = new BydMediaStreamServer();
    private File statusFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusFile = new File(getFilesDir(), "media_stream_status.txt");
        resetStatus();
        handler.post(this::runProbe);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        mediaStreamServer.release();
        super.onDestroy();
    }

    private void runProbe() {
        int width = getIntent().getIntExtra("width", 1280);
        int height = getIntent().getIntExtra("height", 720);
        try {
            log("probe init " + width + "x" + height);
            Surface surface = mediaStreamServer.initialize(this, width, height);
            log("probe surface valid=" + (surface != null && surface.isValid())
                    + " surface=" + surface);
            if (getIntent().getBooleanExtra("start", false)) {
                mediaStreamServer.start();
                log("probe start ok");
            }
        } catch (Throwable e) {
            log("probe failed: " + shortError(e));
            Log.i(TAG, "probe failed", e);
        } finally {
            handler.postDelayed(this::finishAndRemoveTask,
                    getIntent().getLongExtra("duration_ms", 1500L));
        }
    }

    private void log(String message) {
        String line = System.currentTimeMillis() + " " + message;
        Log.i(TAG, message);
        try (FileOutputStream output = new FileOutputStream(statusFile, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "status write failed", e);
        }
    }

    private void resetStatus() {
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(new byte[0]);
        } catch (IOException e) {
            Log.w(TAG, "status reset failed", e);
        }
    }

    private static String shortError(Throwable error) {
        if (error == null) {
            return "null";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
