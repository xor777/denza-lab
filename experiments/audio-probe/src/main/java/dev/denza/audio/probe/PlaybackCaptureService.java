package dev.denza.audio.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

/**
 * Samples the playback-capture stream and reports whether other apps' audio is
 * actually reaching it. A capture that initialises but only ever returns
 * silence is the expected outcome for apps that opt out of capture, so the
 * probe reports levels rather than a bare success flag.
 */
public final class PlaybackCaptureService extends Service {
    private static final String TAG = "AudioCaptureProbe";
    private static final String CHANNEL_ID = "audio-probe";

    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        new Thread(() -> {
            try {
                capture(resultCode, resultData);
            } catch (Throwable t) {
                Log.w(TAG, "RESULT playback-capture error="
                        + t.getClass().getName() + ": " + t.getMessage());
            } finally {
                stopSelf();
            }
        }, "playback-capture").start();
        return START_NOT_STICKY;
    }

    private void capture(int resultCode, Intent resultData) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            Log.w(TAG, "RESULT playback-capture projection=null");
            return;
        }
        AudioRecord record = null;
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(16384)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "RESULT playback-capture state=uninitialised");
                return;
            }
            record.startRecording();
            short[] buffer = new short[4096];
            double sumSquares = 0d;
            long counted = 0L;
            int peak = 0;
            for (int round = 0; round < 60; round++) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                for (int i = 0; i < read; i++) {
                    int sample = buffer[i];
                    sumSquares += (double) sample * sample;
                    peak = Math.max(peak, Math.abs(sample));
                }
                counted += read;
            }
            double rms = counted == 0 ? 0d : Math.sqrt(sumSquares / counted);
            double dbfs = rms <= 0d ? -120d : 20d * Math.log10(rms / 32768d);
            Log.i(TAG, "RESULT playback-capture frames=" + counted
                    + " rms=" + String.format("%.1f", rms)
                    + " dbfs=" + String.format("%.1f", dbfs)
                    + " peak=" + peak
                    + " signal=" + (peak > 32));
        } finally {
            if (record != null) {
                try {
                    record.stop();
                    record.release();
                } catch (Throwable ignored) {
                    // Best effort.
                }
            }
            projection.stop();
        }
    }

    private Notification buildNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Audio probe", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio capture probe")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
