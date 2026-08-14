package dev.denza.display.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * The question this probe exists to answer: can an ordinary app host another
 * app's activity on a display it owns?
 *
 * The earlier spike used `overlay_display_devices`, which creates a display
 * owned by the *system*. A display an app creates itself is private to its own
 * UID, and other apps' activities cannot be placed on it — so that spike proved
 * rendering, not the product shape. Here the display is created through
 * MediaProjection with VIRTUAL_DISPLAY_FLAG_PUBLIC, which is the only route an
 * unprivileged app has to a display others can be launched onto.
 *
 * Content is drawn into an ImageReader rather than a view, so the probe needs no
 * UI at all and can dump exactly what arrived to a PNG.
 */
public final class DisplayProbeService extends Service {
    private static final String TAG = "DisplayProbe";
    private static final String CHANNEL_ID = "display-probe";

    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 1440;
    private static final int DENSITY = 320;
    private static final long SAVE_INTERVAL_MS = 2_000L;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread thread;
    private long lastSaveUptimeMs;
    private int savedFrames;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        try {
            start(resultCode, resultData);
        } catch (Throwable t) {
            Log.w(TAG, "RESULT display=failed error=" + t.getClass().getName() + ": " + t.getMessage(), t);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void start(int resultCode, Intent resultData) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            Log.w(TAG, "RESULT display=failed reason=projection-null");
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "projection stopped");
            }
        }, null);

        thread = new HandlerThread("display-probe");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        reader = ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onFrame, handler);

        // PUBLIC is the flag that decides this experiment: without it the display
        // is visible only to this UID and nothing else can be launched onto it.
        // OWN_CONTENT_ONLY stops it mirroring the main screen.
        int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

        virtualDisplay = projection.createVirtualDisplay(
                "denza-host-probe", WIDTH, HEIGHT, DENSITY, displayFlags,
                reader.getSurface(), null, handler);

        if (virtualDisplay == null) {
            Log.w(TAG, "RESULT display=failed reason=createVirtualDisplay-null");
            stopSelf();
            return;
        }
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Log.i(TAG, "RESULT display=created displayId=" + displayId
                + " flags=0x" + Integer.toHexString(virtualDisplay.getDisplay().getFlags())
                + " size=" + WIDTH + "x" + HEIGHT
                + " frames=" + getExternalFilesDir("frames"));
    }

    private void onFrame(ImageReader source) {
        Image image = source.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastSaveUptimeMs < SAVE_INTERVAL_MS) {
                return;
            }
            lastSaveUptimeMs = now;
            save(image);
        } catch (Throwable t) {
            Log.w(TAG, "frame save failed", t);
        } finally {
            image.close();
        }
    }

    private void save(Image image) throws Exception {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * WIDTH;
        ByteBuffer buffer = plane.getBuffer();

        Bitmap padded = Bitmap.createBitmap(
                WIDTH + rowPadding / pixelStride, HEIGHT, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap frame = Bitmap.createBitmap(padded, 0, 0, WIDTH, HEIGHT);

        File directory = getExternalFilesDir("frames");
        File file = new File(directory, "frame.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            frame.compress(Bitmap.CompressFormat.PNG, 90, out);
        }
        padded.recycle();
        frame.recycle();
        savedFrames++;
        Log.i(TAG, "frame #" + savedFrames + " -> " + file.getAbsolutePath());
    }

    private Notification buildNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Display probe", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Display host probe")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (reader != null) {
            reader.close();
        }
        if (projection != null) {
            projection.stop();
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
