package dev.denza.apps.feature.hud;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/** Polls the visible Yandex guidance model and publishes only fresh, validated instructions. */
public final class HudGuidanceAccessibilityMonitor {
    private static final long POLL_INTERVAL_MS = 350L;
    private static final long LOST_ROUTE_GRACE_MS = 6000L;
    private static final long HEARTBEAT_MS = 5000L;
    private static final long AR_HEARTBEAT_MS = 350L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HudSomeIpClient someIpClient;
    private final HudArLocationSource locationSource;
    private final HudArApproximationTracker arTracker = new HudArApproximationTracker();
    private final Runnable pollRunnable = this::poll;
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
    }

    public void attach() {
        attached = true;
        onSettingChanged();
    }

    public void detach() {
        attached = false;
        handler.removeCallbacks(pollRunnable);
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
            schedule(40L);
        }
    }

    public void onSettingChanged() {
        handler.removeCallbacks(pollRunnable);
        if (!attached) {
            return;
        }
        if (!HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            clearAndStop();
            return;
        }
        HudGuidanceRuntime.onWaiting();
        locationSource.start();
        schedule(0L);
    }

    private void poll() {
        if (!attached || !HudGuidanceSettings.INSTANCE.isEnabled(service)) {
            clearAndStop();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        HudGuidance guidance = YandexGuidanceAccessibilityReader.read(service);
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
