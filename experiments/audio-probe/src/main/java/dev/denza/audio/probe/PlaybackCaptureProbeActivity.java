package dev.denza.audio.probe;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Requests the MediaProjection consent token that
 * {@link android.media.AudioPlaybackCaptureConfiguration} needs, then hands it
 * to {@link PlaybackCaptureService}.
 *
 * The host can pre-approve the dialog with
 * {@code appops set dev.denza.audio.probe PROJECT_MEDIA allow}; where that
 * works the consent screen never appears.
 */
public final class PlaybackCaptureProbeActivity extends Activity {
    private static final String TAG = "AudioCaptureProbe";
    private static final int REQUEST_PROJECTION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            Log.w(TAG, "RESULT playback-capture consent=no-projection-service");
            finish();
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
        } catch (Throwable t) {
            Log.w(TAG, "RESULT playback-capture consent=failed error="
                    + t.getClass().getName() + ": " + t.getMessage());
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "RESULT playback-capture consent=denied resultCode=" + resultCode);
            finish();
            return;
        }
        Log.i(TAG, "RESULT playback-capture consent=granted");
        // The foreground service must be running before getMediaProjection().
        Intent service = new Intent(this, PlaybackCaptureService.class)
                .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        finish();
    }
}
