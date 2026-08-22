package dev.denza.speakerlift.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Exact normal-UID form of the stock LOCAL MediaCenter call proven on the car. */
final class MediaCenterPulse {
    static final long PULSE_DURATION_MS = 1_000L;

    private static final String TAG = "SpeakerLiftYandexProbe";
    private static final String ACTION_START_MEDIA = "byd.intent.action.START_MEDIA";
    private static final ComponentName MEDIA_SERVICE = new ComponentName(
            "com.byd.mediacenter",
            "com.byd.mediacenter.main.MediaService");
    private static final String CANONICAL_TRACK_PATH =
            "/storage/FFFF-FFFF/Music/denza-speaker-lift-probe-20260822.ogg";
    private static final int MEDIA_MODE_MUSIC = 1;
    private static final int MEDIA_ACTION_PAUSE = 2;
    private static final int MEDIA_ACTION_PLAY_BY_ID = 14;
    private static final int SDK_VERSION = 501000;

    private MediaCenterPulse() {}

    static void play(Context context) {
        Bundle params = baseParams(context);
        int musicId = CANONICAL_TRACK_PATH.hashCode();
        params.putLong("media_id", musicId);
        params.putInt("media_list_type", 0);
        start(context, MEDIA_ACTION_PLAY_BY_ID, params);
        Log.i(TAG, "pulse play requested musicId=" + musicId);
    }

    static void pause(Context context) {
        start(context, MEDIA_ACTION_PAUSE, baseParams(context));
        Log.i(TAG, "pulse pause requested");
    }

    private static Bundle baseParams(Context context) {
        Bundle params = new Bundle();
        params.putInt("source", 0);
        params.putString("package", context.getPackageName());
        return params;
    }

    private static void start(Context context, int mediaAction, Bundle params) {
        Intent intent = new Intent(ACTION_START_MEDIA)
                .setComponent(MEDIA_SERVICE)
                .putExtra("MediaMode", MEDIA_MODE_MUSIC)
                .putExtra("MediaAction", mediaAction)
                .putExtra("sdkVersion", SDK_VERSION)
                .putExtra("MediaParams", params);
        ComponentName started = context.startForegroundService(intent);
        if (started == null) {
            throw new IllegalStateException("MediaCenter rejected action " + mediaAction);
        }
    }
}
