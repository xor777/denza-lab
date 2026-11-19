package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRadarWarning;

/**
 * HUD Speed's warnings on the host's side (docs/hud-output-architecture.md, 7.3 to 7.5): what the
 * radar says goes to {@link HudRadarSource} while navigation is open, and while a camera with a
 * limit is ahead the wheels are read at a screen's cadence and the warning turns as red as
 * {@link HudSpeeding} says. Plain Java on the host's main thread; {@link HudHostOutput} gives it
 * the clock, the timer and the wheels.
 *
 * <ul>
 * <li>Navigation closed: nothing of the radar can reach the glass ({@code HudRole}: a route is
 *     always needed), so nothing is drawn, encoded or read fast for it. The latest observation is
 *     kept, and a route that opens within its life shows it at once (2026-11-19).
 * <li>The radar detached, died or was replaced: its warning and its red go with it. Before
 *     2026-11-19 the red's next step drew the dead source's card back onto the glass until the
 *     observation's own deadline.
 * </ul>
 */
final class HudRadarHost {
    /** The car's speed from its wheels. */
    interface Wheels {
        /** A camera with a limit is ahead, or no longer is: the fast reading on or off. */
        void watch(boolean on);

        /** km/h now, or null when unknown or stale. */
        Double kmh(long now);
    }

    interface WheelsFactory {
        Wheels create();
    }

    /** The host's main-thread timer. */
    interface Timer {
        void postDelayed(Runnable task, long delayMs);

        void remove(Runnable task);
    }

    interface Clock {
        long now();
    }

    /** How often the red follows the speed; the card reaches the HUD at most every HudSession.ROAD_MIN_INTERVAL_MS. */
    static final long RED_TICK_MS = 83L;

    private final HudRadarSource radar;
    private final WheelsFactory wheelsFactory;
    private final Timer timer;
    private final Clock clock;
    private final HudSpeeding speeding = new HudSpeeding();
    private final Runnable redTick = this::followSpeed;
    private Wheels wheels;
    private HudRadarWarning latest;
    private boolean navigating;

    HudRadarHost(HudRadarSource radar, WheelsFactory wheelsFactory, Timer timer, Clock clock) {
        this.radar = radar;
        this.wheelsFactory = wheelsFactory;
        this.timer = timer;
        this.clock = clock;
    }

    /** An observation the panel lets through. */
    void onRadar(HudRadarWarning warning) {
        latest = warning;
        if (navigating) {
            radar.publish(warning, clock.now());
            followSpeed();
        }
    }

    /** Navigation opened or closed on the glass. */
    void onNavigation(boolean on) {
        if (on == navigating) {
            return;
        }
        navigating = on;
        if (!on) {
            stopRed();
            return;
        }
        HudRadarWarning kept = latest;
        long now = clock.now();
        if (kept != null && kept.remainingMs(now) > 0) {
            radar.publish(kept, now);
            followSpeed();
        }
    }

    /** The radar's attach ended - detached, dead or replaced - or the cameras went off. */
    void forget() {
        latest = null;
        stopRed();
    }

    /** The red after the speed: while a camera with a limit is ahead, and after it until the red has gone. */
    private void followSpeed() {
        timer.remove(redTick);
        long now = clock.now();
        HudRadarWarning camera = navigating ? radar.current(now) : null;
        int limit = camera == null ? 0 : camera.limit;
        if (!navigating || (limit <= 0 && speeding.settled())) {
            stopRed();
            return;
        }
        if (wheels == null) {
            wheels = wheelsFactory.create();
        }
        wheels.watch(limit > 0);
        radar.setRed(speeding.update(camera, wheels.kmh(now), now), now);
        if (limit > 0 || !speeding.settled()) {
            timer.postDelayed(redTick, RED_TICK_MS);
        } else {
            wheels.watch(false);
        }
    }

    /** No red, and no fast reading of the speed for it. */
    private void stopRed() {
        timer.remove(redTick);
        speeding.reset();
        radar.forget();
        if (wheels != null) {
            wheels.watch(false);
        }
    }
}
