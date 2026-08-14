package dev.denza.display.probe;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Requests the MediaProjection token and hands it to {@link DisplayProbeService}.
 *
 * The host can pre-approve the dialog with
 * {@code appops set dev.denza.display.probe PROJECT_MEDIA allow}, the same trick
 * the audio probe used, so the consent screen never appears.
 */
public final class DisplayProbeActivity extends Activity {
    private static final String TAG = "DisplayProbe";
    private static final int REQUEST_PROJECTION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            Log.w(TAG, "RESULT consent=no-projection-service");
            finish();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "RESULT consent=denied resultCode=" + resultCode);
            finish();
            return;
        }
        Log.i(TAG, "RESULT consent=granted");
        startForegroundService(new Intent(this, DisplayProbeService.class)
                .putExtra(DisplayProbeService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(DisplayProbeService.EXTRA_RESULT_DATA, data));
        finish();
    }
}
