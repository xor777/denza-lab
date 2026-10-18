package dev.denza.hudlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Sources publish, the compositor composes and the session sends: the whole path on a test clock,
 * a port that writes down each call and a renderer that only says what it was given.
 */
public final class HudCompositorTest {
    private static final HudRect WHOLE = HudRect.whole(HudWindow.MAP);
    private static final long LONG = 60_000L;
    private static final byte[] BLACK = Pngs.header(300, 180);
    private static final byte[] DRAWN = Pngs.header(300, 181);

    private final HudSessionTest.TestClock clock = new HudSessionTest.TestClock();
    private final List<String> calls = new ArrayList<>();
    private final List<List<HudLayer>> rendered = new ArrayList<>();
    private final HudCompositor compositor = new HudCompositor(clock, new Port(), new Quiet(), new Renderer());
    private final List<byte[]> frames = new ArrayList<>();
    private long due = HudSession.IDLE;

    @Test
    public void theHintsOpenTheSessionAndTheRoadPacketCarriesTheWinners() {
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), LONG);
        compositor.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 60).build(), LONG);
        connect();

        assertEquals(Arrays.asList("bind", "start", "0 road 0a0a100280010248fa01583c"), calls);
    }

    @Test
    public void aMapLayerThatIsNotRenewedDiesAfterOneAndAHalfSecondsAndTheWindowGoesBlack() {
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), LONG);
        frame(HudScene.LAYER_TTL_MS);
        connect();
        runUntil(3_000);

        assertEquals(Arrays.asList("0 map frame-0", "1000 map frame-0",
                "1500 map black", "1620 map black", "1740 map black"), maps());
    }

    @Test
    public void aRenewedLayerStaysAndIsRepeatedOnlyOnTheSessionsSecond() {
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), LONG);
        HudImage image = frame(HudScene.LAYER_TTL_MS);
        connect();
        for (long time = 700; time <= 3_500; time += 700) {
            runUntil(time);
            compositor.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, image, 1f, HudScene.LAYER_TTL_MS);
            changed();
        }
        runUntil(4_000);

        assertEquals(Arrays.asList("0 map frame-0", "1000 map frame-0", "2000 map frame-0",
                "3000 map frame-0", "4000 map frame-0"), maps());
    }

    @Test
    public void aSingleWholeFrameGoesAsItCameAndOnlyLayersTogetherAreDrawn() {
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), LONG);
        frame(LONG);
        connect();
        assertEquals(0, rendered.size());

        runUntil(100);
        HudImage badge = Pngs.image(80, 60);
        compositor.layer(HudSource.RADAR, "camera", HudRole.MAP_BADGE, new HudRect(220, 0, 80, 60),
                badge, 0.9f, LONG);
        changed();

        assertEquals(1, rendered.size());
        assertEquals(2, rendered.get(0).size());
        assertSame(badge, rendered.get(0).get(1).image);
        assertEquals(Arrays.asList("0 map frame-0", "100 map drawn"), maps());
    }

    @Test
    public void aRouteLostWithItsMapInOneTurnIsOneClearThenTheStop() {
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), LONG);
        frame(LONG);
        connect();
        runUntil(1_100);
        calls.clear();

        compositor.withdraw(HudSource.CAPTURE, HudRole.MAP);
        compositor.withdraw(HudSource.CAPTURE, HudRole.MANEUVER);
        changed();
        runUntil(3_000);

        assertEquals(Arrays.asList(
                "1100 map black", "1100 road 0a051002800101",
                "1220 map black", "1220 road 0a051002800101",
                "1340 map black", "1340 road 0a051002800101",
                "stop", "unbind"), calls);
    }

    @Test
    public void theLimitAloneReachesTheSignAndOpensNothing() {
        List<Integer> sign = new ArrayList<>();
        compositor.setLimitListener(sign::add);
        compositor.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 60).build(), 1_000);
        changed();
        runUntil(3_000);

        assertEquals(Arrays.asList(null, 60, null), sign);
        assertEquals(new ArrayList<String>(), calls);
    }

    @Test
    public void theSignFollowsTheModuleFirstAndTheCaptureAfterIt() {
        List<Integer> sign = new ArrayList<>();
        compositor.setLimitListener(sign::add);
        compositor.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 60).build(), LONG);
        compositor.fields(HudSource.MODULE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 90).build(), LONG);
        changed();
        compositor.withdraw(HudSource.MODULE);
        changed();
        changed();

        assertEquals(Arrays.asList(null, 90, 60), sign);
    }

    @Test
    public void theMapWindowsPictureReachesAScreenBesideTheHud() {
        List<String> seen = new ArrayList<>();
        compositor.setFrameListener((window, png) -> seen.add(window + " " + (png == null ? "none" : frameName(HudProtocol.mapPayload(png)))));
        compositor.fields(HudSource.CAPTURE, HudRole.MANEUVER, maneuver(250), 1_000);
        frame(LONG);
        connect();
        // The hints end: the window shows nothing, and the listener is told so once.
        runUntil(2_000);

        assertEquals(Arrays.asList("MAP none", "MAP frame-0", "MAP none"), seen);
    }

    @Test
    public void aMapWithoutHintsNeverOpensTheSession() {
        frame(LONG);
        changed();
        runUntil(3_000);

        assertEquals(new ArrayList<String>(), calls);
    }

    private static HudFields maneuver(int meters) {
        return new HudFields.Builder().number(Road.MANEUVER_METERS, meters).build();
    }

    /** A whole-window frame from the capture, a new picture each time. */
    private HudImage frame(long ttlMs) {
        byte[] png = Pngs.header(300, 180);
        png[png.length - 1] = (byte) (frames.size() + 1);
        frames.add(png);
        HudImage image = HudImage.png(png);
        compositor.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, image, 1f, ttlMs);
        return image;
    }

    /** The session binds on the first pump and the service answers at once. */
    private void connect() {
        changed();
        compositor.onConnected();
        changed();
    }

    private void changed() {
        due = compositor.pump();
    }

    private void runUntil(long time) {
        int pumps = 0;
        while (due <= time) {
            if (++pumps > 10_000) {
                fail("the compositor asks for the same time again and again");
            }
            clock.now = Math.max(clock.now, due);
            due = compositor.pump();
        }
        clock.now = Math.max(clock.now, time);
    }

    private List<String> maps() {
        List<String> out = new ArrayList<>();
        for (String call : calls) {
            if (call.contains(" map ")) {
                out.add(call);
            }
        }
        return out;
    }

    private String frameName(byte[] payload) {
        if (Arrays.equals(payload, HudProtocol.mapPayload(BLACK))) {
            return "black";
        }
        if (Arrays.equals(payload, HudProtocol.mapPayload(DRAWN))) {
            return "drawn";
        }
        for (int index = 0; index < frames.size(); index++) {
            if (Arrays.equals(payload, HudProtocol.mapPayload(frames.get(index)))) {
                return "frame-" + index;
            }
        }
        return "?";
    }

    private final class Port implements HudSession.Port {
        @Override
        public boolean bind() {
            calls.add("bind");
            return true;
        }

        @Override
        public void unbind() {
            calls.add("unbind");
        }

        @Override
        public int start() {
            calls.add("start");
            return 0;
        }

        @Override
        public int stop() {
            calls.add("stop");
            return 0;
        }

        @Override
        public int fire(long topic, byte[] payload) {
            calls.add(clock.now + (topic == HudProtocol.TOPIC_MAP
                    ? " map " + frameName(payload)
                    : " road " + Pngs.hex(payload)));
            return 0;
        }
    }

    private final class Renderer implements HudCompositor.Renderer {
        @Override
        public byte[] render(HudWindow window, List<HudLayer> layers) {
            rendered.add(new ArrayList<>(layers));
            return DRAWN;
        }

        @Override
        public byte[] black(HudWindow window) {
            return BLACK;
        }
    }

    private static final class Quiet implements HudSession.Listener {
        @Override
        public void onBinding() {
        }

        @Override
        public void onStarted(int result) {
        }

        @Override
        public void onFired(long topic, int result, boolean clearing) {
        }

        @Override
        public void onTrouble(HudSession.Trouble trouble, int result) {
        }

        @Override
        public void onClearing() {
        }

        @Override
        public void onStopped() {
        }
    }
}
