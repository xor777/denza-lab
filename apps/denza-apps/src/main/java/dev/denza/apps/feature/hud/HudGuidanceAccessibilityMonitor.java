package dev.denza.apps.feature.hud;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Polls the visible Yandex guidance model and publishes only fresh, validated instructions. */
public final class HudGuidanceAccessibilityMonitor {
    private static final String TAG = "DenzaHudGuidance";
    private static final long POLL_INTERVAL_MS = 350L;
    private static final long EVENT_REFRESH_DELAY_MS = 40L;
    private static final long LOST_ROUTE_GRACE_MS = 6000L;
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
    private final HudArLocationSource locationSource;
    private final HudArApproximationTracker arTracker = new HudArApproximationTracker();
    private final Runnable pollRunnable = this::poll;
    private final Runnable eventPollRunnable = this::poll;
    private final SingleFlightReadRunner<HudGuidance> readRunner;
    private boolean attached;
    private boolean cleared = true;
    private long lastSeenMs;
    private long lastPublishedMs;
    private HudGuidance lastGuidance;
    private HudVehiclePose latestPose;
    private boolean lastArActive;

    public HudGuidanceAccessibilityMonitor(AccessibilityService service) {
        this.service = service;
        this.someIpClient = new HudSomeIpClient(service);
        this.locationSource = new HudArLocationSource(service, pose -> latestPose = pose);
        this.readRunner = new SingleFlightReadRunner<>(
                readerExecutor,
                runnable -> handler.post(runnable),
                () -> YandexGuidanceAccessibilityReader.read(service),
                this::onReadFinished);
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
        someIpClient.shutdown();
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
        HudGuidanceRuntime.onWaiting();
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
        if (guidance == null) {
            guidance = HudNotificationGuidanceRuntime.resolve(lastGuidance, now);
        }
        if (guidance != null) {
            lastSeenMs = now;
            HudNotificationArtworkRuntime.observe(guidance, now);
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
            cleared = false;
            HudGuidanceRuntime.onGuidance(guidance, now);
        } else if (!cleared && now - lastSeenMs >= LOST_ROUTE_GRACE_MS) {
            someIpClient.clear();
            cleared = true;
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
        if (!cleared) {
            someIpClient.clear();
        }
        locationSource.stop();
        arTracker.reset();
        latestPose = null;
        someIpClient.shutdown();
        cleared = true;
        lastGuidance = null;
        lastSeenMs = 0L;
        lastPublishedMs = 0L;
        lastArActive = false;
        HudGuidanceRuntime.onStopped();
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, delayMs);
    }
}
