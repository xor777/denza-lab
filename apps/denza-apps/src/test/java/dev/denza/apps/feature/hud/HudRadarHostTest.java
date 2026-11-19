package dev.denza.apps.feature.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudMode;
import dev.denza.hudlink.HudRadarWarning;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudScene;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * HUD Speed on the host's side: its warning and red only while navigation is open, nothing of a
 * radar that left, the wheels read fast only for a camera that can reach the glass.
 */
public class HudRadarHostTest {
    private final HudScene scene = new HudScene();
    private long now;
    private final HudSink sink = new HudSink() {
        public void layer(HudSource s, String id, HudRole r, HudRect rect, HudImage image, float a, long ttl) { scene.layer(s, id, r, rect, image, a, ttl, now); }
        public void removeLayer(HudSource s, String id) { scene.removeLayer(s, id); }
        public void fields(HudSource s, HudRole r, HudFields f, long ttl) { scene.fields(s, r, f, ttl, now); }
        public void withdraw(HudSource s, HudRole r) { scene.withdraw(s, r); }
        public void withdraw(HudSource s) { scene.withdraw(s); }
    };
    private final List<Float> drawn = new ArrayList<>();
    private final HudRadarSource radar = new HudRadarSource(sink, new HudRadarSource.Renderer() {
        public HudImage lanes(HudRadarWarning w, float red) { drawn.add(red); return png(HudRadarPainter.LANE_WIDTH, HudRadarPainter.LANE_HEIGHT); }
        public HudImage gps(int level) { return png(HudRadarPainter.GPS, HudRadarPainter.GPS); }
    });
    private final List<Boolean> watched = new ArrayList<>();
    private Double kmh = 90.0;
    private final List<Runnable> timers = new ArrayList<>();
    private final HudRadarHost host = new HudRadarHost(radar, () -> new HudRadarHost.Wheels() {
        public void watch(boolean on) { watched.add(on); }
        public Double kmh(long at) { return kmh; }
    }, new HudRadarHost.Timer() {
        public void postDelayed(Runnable task, long delayMs) { timers.add(task); }
        public void remove(Runnable task) { timers.remove(task); }
    }, () -> now);

    private static HudImage png(int width, int height) {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R',
                0, 0, (byte) (width >> 8), (byte) width, 0, 0, (byte) (height >> 8), (byte) height, 8, 6, 0, 0, 0};
        return HudImage.png(png);
    }

    private static HudRadarWarning camera(long at) {
        return new HudRadarWarning(at, true, HudRadarWarning.GPS_FIX, true, 1, 1, "Камера", 200, 60, 1, false, -1, "cam_type_1", "cam_speed_box_red");
    }

    private void route() {
        scene.fields(HudSource.MODULE, HudRole.MANEUVER, new HudFields.Builder().number(9, 450).build(), 60_000, now);
        host.onNavigation(true);
    }

    /** The red's timer, [ticks] times, [stepMs] apart. */
    private void tick(int ticks, long stepMs) {
        for (int i = 0; i < ticks && !timers.isEmpty(); i++) {
            now += stepMs;
            timers.remove(0).run();
        }
    }

    @Test
    public void aRadarThatDetachesTakesItsRedWithItAndTheTickCannotDrawItBack() {
        now = 1_000;
        route();
        host.onRadar(camera(now));
        tick(3, 83);
        assertEquals(HudSource.RADAR, scene.compose(now).winner(HudRole.LANES));

        // The endpoint withdraws the radar, then the host hears it detach.
        scene.withdraw(HudSource.RADAR);
        Runnable pending = timers.isEmpty() ? null : timers.get(0);
        host.forget();
        assertTrue(timers.isEmpty());
        if (pending != null) {
            now += 83;
            pending.run(); // a step already on its way changes nothing
        }
        assertNull(scene.compose(now).winner(HudRole.LANES));
        assertTrue(scene.compose(now).layers(dev.denza.hudlink.HudWindow.MAP).isEmpty());
        assertFalse(watched.get(watched.size() - 1));
    }

    @Test
    public void withoutARouteNothingIsDrawnAndTheWheelsAreNotReadFast() {
        now = 1_000;
        host.onRadar(camera(now));
        tick(10, 83);

        assertTrue(drawn.isEmpty());
        assertTrue(watched.isEmpty() || !watched.contains(true));
        assertTrue(timers.isEmpty());
        assertEquals(HudMode.OFF, scene.compose(now).mode);
    }

    @Test
    public void aRouteThatOpensWithinTheObservationsLifeShowsItAtOnce() {
        now = 1_000;
        host.onRadar(camera(now));
        now = 2_000;
        route();

        assertEquals(HudSource.RADAR, scene.compose(now).winner(HudRole.LANES));
        assertEquals(Boolean.TRUE, watched.get(watched.size() - 1));
        assertFalse(timers.isEmpty());
    }

    @Test
    public void anObservationThatDiedBeforeTheRouteIsNotShown() {
        now = 1_000;
        host.onRadar(camera(now));
        now = 1_000 + HudRadarWarning.TTL_MS;
        route();

        assertTrue(drawn.isEmpty());
        assertNull(scene.compose(now).winner(HudRole.LANES));
    }

    @Test
    public void navigationClosingStopsTheRedAndTheFastReading() {
        now = 1_000;
        route();
        host.onRadar(camera(now));
        tick(3, 83);
        host.onNavigation(false);

        assertTrue(timers.isEmpty());
        assertFalse(watched.get(watched.size() - 1));
    }

    @Test
    public void theRedFollowsTheSpeedOnARoute() {
        now = 1_000;
        route();
        host.onRadar(camera(now));
        tick(30, 83);

        assertEquals(1f, drawn.get(drawn.size() - 1), 0f);
    }
}
