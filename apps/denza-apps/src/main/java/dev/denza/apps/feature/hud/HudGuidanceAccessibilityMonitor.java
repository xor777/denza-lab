package dev.denza.apps.feature.hud;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polls the visible Yandex guidance model and publishes only fresh, validated instructions, and
 * runs the map window ({@link HudMapRunner}) while a route is held and its switch is on.
 */
public final class HudGuidanceAccessibilityMonitor {
    private static final String TAG = "DenzaHudGuidance";
    private static final long POLL_INTERVAL_MS = 350L;
    private static final long EVENT_REFRESH_DELAY_MS = 40L;
    private static final long HEARTBEAT_MS = 5000L;
    private static final long AR_HEARTBEAT_MS = 350L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "denza-hud-guidance-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final HudSomeIpClient someIpClient;
    private final HudNativeSpeedLimitRunner speedLimit;
    private final HudArLocationSource locationSource;
    private final HudArApproximationTracker arTracker = new HudArApproximationTracker();
    private final HudRouteFreshness freshness =
            new HudRouteFreshness(HudNotificationGuidanceRuntime::resolve);
    private final Runnable pollRunnable = this::poll;
    private final Runnable eventPollRunnable = this::poll;
    private final SingleFlightReadRunner<HudGuidance> readRunner;
    private final HudMapRunner mapRunner;
    private boolean attached;
    private long lastPublishedMs;
    private HudGuidance lastGuidance;
    private HudVehiclePose latestPose;
    private boolean lastArActive;

    public HudGuidanceAccessibilityMonitor(AccessibilityService service) {
        this.service = service;
        this.someIpClient = new HudSomeIpClient(service);
        this.speedLimit = new HudNativeSpeedLimitRunner(service);
        this.locationSource = new HudArLocationSource(service, pose -> latestPose = pose);
        this.readRunner = new SingleFlightReadRunner<>(
                readerExecutor,
                runnable -> handler.post(runnable),
                () -> YandexGuidanceAccessibilityReader.read(service),
                this::onReadFinished);
        // The map's frames ride on the hints' session; both calls arrive on the main thread.
        this.mapRunner = new HudMapRunner(
                new HudMapScreenshotSource(service),
                new HudMapRunner.Publisher() {
                    @Override
                    public void publish(byte[] payload) {
                        someIpClient.publishMap(payload);
                    }

                    @Override
                    public void blank() {
                        someIpClient.clearMap(HudMapPng.getBlankPayload());
                    }
                },
                new File(service.getFilesDir(), HudMapCapture.DIRECTORY));
    }

    public void attach() {
        attached = true;
        onSettingChanged();
    }

    public void detach() {
        attached = false;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(eventPollRunnable);
        readRunner.deactivate();
        readerExecutor.shutdownNow();
        locationSource.stop();
        arTracker.reset();
        latestPose = null;
        // The map's black frame goes before the session's stop, and its thread ends here.
        mapRunner.shutdown();
        someIpClient.shutdown();
        speedLimit.shutdown();
        HudGuidanceRuntime.onStopped();
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!attached || !HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            return;
        }
        CharSequence packageName = event == null ? null : event.getPackageName();
        if (packageName == null || "ru.yandex.yandexnavi".contentEquals(packageName)) {
            handler.removeCallbacks(eventPollRunnable);
            handler.postDelayed(eventPollRunnable, EVENT_REFRESH_DELAY_MS);
        }
    }

    public void onSettingChanged() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(eventPollRunnable);
        if (!attached) {
            return;
        }
        if (!HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            clearAndStop();
            return;
        }
        // The map switch moved, or the hints came on. Off empties the window now; on waits for
        // the next poll that holds a route.
        if (HudGuidanceSettings.INSTANCE.isMapEnabled(service)) {
            if (!freshness.isHolding()) {
                mapRunner.pause(true);
            }
        } else {
            mapRunner.stop();
        }
        // A refresh while a route is shown - the map switch, an access repair - leaves the hints
        // as they are rather than saying «waiting» for one poll.
        if (!HudGuidanceRuntime.isActive()) {
            HudGuidanceRuntime.onWaiting();
        }
        locationSource.start();
        readRunner.activate();
        schedule(0L);
    }

    private void poll() {
        if (!attached || !HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            clearAndStop();
            return;
        }
        readRunner.request();
    }

    private void onReadFinished(HudGuidance guidance, Throwable error) {
        if (!attached || !HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            clearAndStop();
            return;
        }
        if (error != null) {
            Log.w(TAG, "accessibility guidance read failed", error);
            guidance = null;
        }
        long now = SystemClock.uptimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        HudGuidanceSample sample = freshness.select(guidance, lastGuidance, now);
        if (sample != null) {
            guidance = sample.getGuidance();
            HudNotificationArtworkRuntime.observe(guidance, sample.getCapturedAtMs());
            boolean changed = !guidance.equals(lastGuidance);
            HudArGeometry arGeometry = arTracker.resolve(guidance, latestPose, nowElapsed);
            boolean arActive = arGeometry != null;
            boolean arStateChanged = arActive != lastArActive;
            long heartbeat = arActive ? AR_HEARTBEAT_MS : HEARTBEAT_MS;
            if (changed || arStateChanged || now - lastPublishedMs >= heartbeat) {
                someIpClient.publish(guidance, arGeometry);
                lastGuidance = guidance;
                lastPublishedMs = now;
                lastArActive = arActive;
            }
            HudGuidanceRuntime.onGuidance(guidance, sample.getCapturedAtMs());
            // Only a visible read carries the sign; a background route leaves the car's sign alone.
            speedLimit.setTarget(guidance.getSpeedLimitKmh());
            // After the publish, which starts the session the map's frames ride on. A route read
            // from the notification has no Yandex on the screen; the map source finds that itself.
            mapRunner.run(HudGuidanceSettings.INSTANCE.isMapEnabled(service));
        } else if (freshness.routeLost(now)) {
            speedLimit.setTarget(null);
            // The map's black frame before the road clear, while the session still holds.
            mapRunner.pause(HudGuidanceSettings.INSTANCE.isMapEnabled(service));
            someIpClient.clear();
            lastGuidance = null;
            arTracker.reset();
            lastArActive = false;
            HudGuidanceRuntime.onWaiting();
        }
        schedule(POLL_INTERVAL_MS);
    }

    private void clearAndStop() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(eventPollRunnable);
        readRunner.deactivate();
        mapRunner.stop();
        if (freshness.isHolding()) {
            someIpClient.clear();
        }
        locationSource.stop();
        arTracker.reset();
        latestPose = null;
        someIpClient.shutdown();
        speedLimit.setTarget(null);
        freshness.reset();
        lastGuidance = null;
        lastPublishedMs = 0L;
        lastArActive = false;
        HudGuidanceRuntime.onStopped();
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, delayMs);
    }
}
