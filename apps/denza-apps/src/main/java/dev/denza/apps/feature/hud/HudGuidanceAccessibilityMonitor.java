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

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "denza-hud-guidance-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final HudCaptureSource capture;
    private final HudArLocationSource locationSource;
    private final HudArApproximationTracker arTracker = new HudArApproximationTracker();
    private final HudRouteFreshness freshness =
            new HudRouteFreshness(HudNotificationGuidanceRuntime::resolve);
    private final Runnable pollRunnable = this::poll;
    private final Runnable eventPollRunnable = this::poll;
    private final SingleFlightReadRunner<HudGuidance> readRunner;
    private final HudMapRunner mapRunner;
    private boolean attached;
    private HudGuidance lastGuidance;
    private HudVehiclePose latestPose;
    /**
     * «YandexHUD» is installed: it brings its own source - through this app's host while it is
     * attached ({@link HudHostOutput}), by itself otherwise - so only Yandex's limit is read for
     * the HUD here, for the car's sign.
     */
    private boolean navigatorDrawsHud;
    private boolean routeHeld;

    public HudGuidanceAccessibilityMonitor(AccessibilityService service) {
        this.service = service;
        this.capture = new HudCaptureSource(service);
        this.locationSource = new HudArLocationSource(service, pose -> latestPose = pose);
        this.readRunner = new SingleFlightReadRunner<>(
                readerExecutor,
                runnable -> handler.post(runnable),
                () -> YandexGuidanceAccessibilityReader.read(service),
                this::onReadFinished);
        // The map's frames go to the compositor beside the hints; both calls arrive on the main thread.
        this.mapRunner = new HudMapRunner(
                HudMapAutoSource.create(service),
                new HudMapRunner.Publisher() {
                    @Override
                    public void publish(byte[] png) {
                        capture.publishMap(png);
                    }

                    @Override
                    public void clear() {
                        capture.clearMap();
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
        // The map's thread ends here; the compositor clears the HUD and stops the session.
        mapRunner.shutdown();
        capture.shutdown();
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
        readNavigatorBuild();
        // The map switch moved, or the hints came on. Off empties the window now; on waits for
        // the next poll that holds a route.
        if (isMapOurs()) {
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
            if (!routeHeld) {
                routeHeld = true;
                readNavigatorBuild();
            }
            guidance = sample.getGuidance();
            if (navigatorDrawsHud) {
                // Only the limit goes to the compositor, for the car's sign; a background read
                // still builds on the last reading.
                capture.publishLimit(guidance);
                lastGuidance = guidance;
            } else {
                publishToHud(guidance, sample, nowElapsed);
            }
            HudGuidanceRuntime.onGuidance(guidance, sample.getCapturedAtMs());
            // The car's sign follows the compositor's limit (HudHostOutput): only a visible read
            // carries one, so a background route leaves the car's sign alone, and the module's,
            // while it publishes one through the host, comes first.
            // After the publish, which starts the session the map's frames ride on. A route read
            // from the notification has no Yandex on the screen; the map source finds that itself.
            mapRunner.run(isMapOurs());
        } else if (freshness.routeLost(now)) {
            routeHeld = false;
            // The map and the hints go together, and the compositor sends one clear for both;
            // nothing without a session, so with «YandexHUD» nothing reaches its HUD from here.
            mapRunner.pause(isMapOurs());
            capture.clear();
            lastGuidance = null;
            arTracker.reset();
            HudGuidanceRuntime.onWaiting();
        }
        schedule(POLL_INTERVAL_MS);
    }

    /**
     * The hints, AR with them, on every poll: the compositor's session sends a change at once and
     * repeats an unchanged packet itself.
     */
    private void publishToHud(HudGuidance guidance, HudGuidanceSample sample, long nowElapsed) {
        HudNotificationArtworkRuntime.observe(guidance, sample.getCapturedAtMs());
        // The packet carries the step's held distance, which never grows within a maneuver;
        // the hints panel and the speed sign keep the reading as it is.
        HudArFrame frame = arTracker.resolve(guidance, latestPose, nowElapsed);
        HudGuidance published = frame.getGuidance();
        capture.publish(published, frame.getGeometry());
        lastGuidance = published;
    }

    private boolean isMapOurs() {
        return !navigatorDrawsHud && HudGuidanceSettings.INSTANCE.isMapEnabled(service);
    }

    /**
     * Which Navigator is installed ({@link HudNavigatorBuild}). «YandexHUD» has the hints, the AR
     * and the map itself and publishes them - into this app's host when attached - so this
     * source sends nothing and the AR needs no position; the car's own speed sign stays. Read on
     * a switch and once a route: swapping the two builds means reinstalling Yandex, which ends its
     * route.
     */
    private void readNavigatorBuild() {
        boolean draws = HudNavigatorBuild.INSTANCE.drawsHud(service);
        if (draws != navigatorDrawsHud) {
            Log.i(TAG, draws
                    ? "YandexHUD brings its own source; only Yandex's limit is read from here"
                    : "ordinary Yandex; the hints go to the HUD from here");
        }
        navigatorDrawsHud = draws;
        if (draws) {
            locationSource.stop();
        } else {
            locationSource.start();
        }
    }

    private void clearAndStop() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(eventPollRunnable);
        readRunner.deactivate();
        mapRunner.stop();
        locationSource.stop();
        arTracker.reset();
        latestPose = null;
        capture.shutdown();
        freshness.reset();
        routeHeld = false;
        lastGuidance = null;
        HudGuidanceRuntime.onStopped();
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, delayMs);
    }
}
