package dev.denza.speakerlift.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Shell-only manual pulse/pause seam for diagnosis; it never opens an Activity. */
public final class ProbeCommandReceiver extends BroadcastReceiver {
    static final String ACTION_PULSE = "dev.denza.speakerlift.yandexprobe.PULSE";
    static final String ACTION_PAUSE = "dev.denza.speakerlift.yandexprobe.PAUSE";

    private static final String TAG = "SpeakerLiftYandexProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            MediaCenterPulse.pause(context);
            return;
        }
        if (!ACTION_PULSE.equals(action)) {
            Log.w(TAG, "ignored command action=" + action);
            return;
        }

        PendingResult pending = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            MediaCenterPulse.play(context);
            handler.postDelayed(() -> {
                try {
                    MediaCenterPulse.pause(context);
                } catch (Throwable error) {
                    Log.e(TAG, "manual pulse pause failed", error);
                } finally {
                    pending.finish();
                }
            }, MediaCenterPulse.PULSE_DURATION_MS);
        } catch (Throwable error) {
            Log.e(TAG, "manual pulse start failed", error);
            pending.finish();
        }
    }
}
