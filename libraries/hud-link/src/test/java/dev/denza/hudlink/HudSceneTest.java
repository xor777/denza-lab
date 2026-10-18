package dev.denza.hudlink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.List;
import org.junit.Test;

/** The compositor's decisions: layer order and lifetime, field winners, the mode. */
public final class HudSceneTest {
    private static final HudRect WHOLE = HudRect.whole(HudWindow.MAP);
    private static final HudRect CORNER = new HudRect(220, 0, 80, 60);
    private static final long LONG = 60_000L;

    private final HudScene scene = new HudScene();

    @Test
    public void layersAreDrawnByZWhateverOrderTheyCameIn() {
        navigating(0);
        HudImage badge = Pngs.image(80, 60);
        HudImage map = Pngs.image(300, 180);
        scene.layer(HudSource.RADAR, "camera", HudRole.MAP_BADGE, CORNER, badge, 1f, LONG, 0);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, map, 1f, LONG, 0);

        List<HudLayer> drawn = scene.compose(0).layers(HudWindow.MAP);

        assertEquals(2, drawn.size());
        assertSame(map, drawn.get(0).image);
        assertSame(badge, drawn.get(1).image);
        assertEquals(100, drawn.get(0).z());
        assertEquals(300, drawn.get(1).z());
    }

    @Test
    public void layersAtOneZKeepTheOrderTheirIdsFirstCameIn() {
        navigating(0);
        scene.layer(HudSource.CAPTURE, "b", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.layer(HudSource.CAPTURE, "a", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 1);
        scene.layer(HudSource.CAPTURE, "b", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 2);

        List<HudLayer> drawn = scene.compose(2).layers(HudWindow.MAP);

        assertEquals("b", drawn.get(0).id);
        assertEquals("a", drawn.get(1).id);
    }

    @Test
    public void theRadarsWarningTakesTheLaneWindowOverTheNavigatorsLanes() {
        navigating(0);
        scene.fields(HudSource.MODULE, HudRole.LANES, new HudFields.Builder()
                .number(Road.LANE_COUNT, 4).bytes(Road.LANES_PNG, Pngs.header(164, 60)).build(), LONG, 0);
        radarInTheLaneWindow(0, 500);

        HudComposition warning = scene.compose(100);
        assertEquals(HudSource.RADAR, warning.winner(HudRole.LANES));
        assertEquals(6L, warning.fields(HudRole.LANES).value(Road.LANE_COUNT));
        assertTrue("nothing of the warning on the map", warning.layers(HudWindow.MAP).isEmpty());

        // The warning is over: the navigator's lanes come back.
        HudComposition after = scene.compose(500);
        assertEquals(HudSource.MODULE, after.winner(HudRole.LANES));
        assertEquals(4L, after.fields(HudRole.LANES).value(Road.LANE_COUNT));
    }

    @Test
    public void theModulesNoLanesComesBackWhenTheWarningEnds() {
        // YandexHUD on a route: «no lanes» (5 = 0) in every frame, the whole way.
        scene.fields(HudSource.MODULE, HudRole.MANEUVER, new HudFields.Builder().build(), LONG, 0);
        scene.fields(HudSource.MODULE, HudRole.LANES, new HudFields.Builder()
                .number(Road.LANE_COUNT, 0).build(), LONG, 0);
        radarInTheLaneWindow(0, 500);

        assertEquals(HudSource.RADAR, scene.compose(0).winner(HudRole.LANES));

        HudComposition after = scene.compose(500);
        assertEquals(HudSource.MODULE, after.winner(HudRole.LANES));
        assertEquals(0L, after.fields(HudRole.LANES).value(Road.LANE_COUNT));
        assertTrue("no picture", !after.fields(HudRole.LANES).has(Road.LANES_PNG));
    }

    @Test
    public void theRoadLimitStandsWithoutNavigationAndTheModulesComesFirst() {
        scene.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 60).build(), LONG, 0);

        HudComposition alone = scene.compose(0);
        assertEquals(HudMode.OFF, alone.mode);
        assertNull(alone.winner(HudRole.ROAD_LIMIT));
        assertNull(alone.road());
        assertEquals(Integer.valueOf(60), alone.roadLimitKmh());

        scene.fields(HudSource.MODULE, HudRole.ROAD_LIMIT,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 80).build(), LONG, 0);
        assertEquals(Integer.valueOf(80), scene.compose(0).roadLimitKmh());
        scene.withdraw(HudSource.CAPTURE);
        scene.withdraw(HudSource.MODULE);
        assertNull(scene.compose(0).roadLimitKmh());
    }

    @Test
    public void theMapsBaseIsOneSourceAndTheModuleWinsOverTheCapture() {
        navigating(0);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.layer(HudSource.MODULE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, 500, 0);

        HudComposition both = scene.compose(100);
        assertEquals(HudSource.MODULE, both.winner(HudRole.MAP));
        assertEquals(1, both.layers(HudWindow.MAP).size());
        assertEquals(HudSource.MODULE, both.layers(HudWindow.MAP).get(0).source);

        // The module goes quiet: its map dies, and the capture's is drawn.
        HudComposition later = scene.compose(500);
        assertEquals(HudSource.CAPTURE, later.winner(HudRole.MAP));
        assertEquals(HudSource.CAPTURE, later.layers(HudWindow.MAP).get(0).source);
    }

    @Test
    public void marksAreDrawnOnlyFromTheSourceWhoseMapIsShown() {
        navigating(0);
        scene.layer(HudSource.MODULE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.layer(HudSource.CAPTURE, "marks", HudRole.MAP_MARKS, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);

        HudComposition composition = scene.compose(0);

        assertNull(composition.winner(HudRole.MAP_MARKS));
        assertEquals(1, composition.layers(HudWindow.MAP).size());

        scene.withdraw(HudSource.MODULE);
        HudComposition captureOnly = scene.compose(0);
        assertEquals(HudSource.CAPTURE, captureOnly.winner(HudRole.MAP_MARKS));
        assertEquals(2, captureOnly.layers(HudWindow.MAP).size());
    }

    @Test
    public void aLayerDiesAtTheEndOfItsLifetimeUnlessPublishedAgain() {
        navigating(0);
        HudImage frame = Pngs.image(300, 180);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, frame, 1f, HudScene.LAYER_TTL_MS, 0);

        assertEquals(1, scene.compose(1_499).layers(HudWindow.MAP).size());
        assertEquals(1_500, scene.compose(1_499).nextChange);
        assertTrue(scene.compose(1_500).layers(HudWindow.MAP).isEmpty());

        // Published again in time, it lives on.
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, frame, 1f, HudScene.LAYER_TTL_MS, 2_000);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, frame, 1f, HudScene.LAYER_TTL_MS, 3_000);
        assertEquals(1, scene.compose(4_499).layers(HudWindow.MAP).size());
        assertTrue(scene.compose(4_500).layers(HudWindow.MAP).isEmpty());
    }

    @Test
    public void theWindowsVersionMovesWhenWhatItShowsChangesAndNotOnARenewal() {
        navigating(0);
        HudImage frame = Pngs.image(300, 180);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, frame, 1f, LONG, 0);
        long first = scene.compose(0).version(HudWindow.MAP);

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, frame, 1f, LONG, 100);
        assertEquals(first, scene.compose(100).version(HudWindow.MAP));

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 200);
        long second = scene.compose(200).version(HudWindow.MAP);
        assertNotEquals(first, second);

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 0.5f, LONG, 300);
        long third = scene.compose(300).version(HudWindow.MAP);
        assertNotEquals(second, third);

        scene.removeLayer(HudSource.CAPTURE, "map");
        assertNotEquals(third, scene.compose(400).version(HudWindow.MAP));
    }

    @Test
    public void aWholeOpaqueLayerOfTheWindowsSizePassesThroughUntouched() {
        navigating(0);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        HudLayer alone = scene.compose(0).passThrough(HudWindow.MAP);
        assertEquals("map", alone.id);

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 0.8f, LONG, 0);
        assertNull("not opaque", scene.compose(0).passThrough(HudWindow.MAP));

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(600, 360), 1f, LONG, 0);
        assertNull("scaled", scene.compose(0).passThrough(HudWindow.MAP));

        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.layer(HudSource.RADAR, "camera", HudRole.MAP_BADGE, CORNER, Pngs.image(80, 60), 1f, LONG, 0);
        assertNull("two layers", scene.compose(0).passThrough(HudWindow.MAP));
    }

    @Test
    public void aRolesFieldsHaveOneWinnerByRankAndALapsedWinnerGivesWay() {
        navigating(0);
        scene.fields(HudSource.CAPTURE, HudRole.AR, ar(1.0), LONG, 0);
        scene.fields(HudSource.MODULE, HudRole.AR, ar(2.0), 1_000, 10);

        HudComposition both = scene.compose(20);
        assertEquals(HudSource.MODULE, both.winner(HudRole.AR));
        assertEquals(2.0, (Double) both.fields(HudRole.AR).value(Road.HEADING), 0.0);

        HudComposition lapsed = scene.compose(1_010);
        assertEquals(HudSource.CAPTURE, lapsed.winner(HudRole.AR));
        assertEquals(1.0, (Double) lapsed.fields(HudRole.AR).value(Road.HEADING), 0.0);
    }

    @Test
    public void publishingARoleAgainReplacesItsFieldsWhole() {
        scene.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.MANEUVER_METERS, 300).text(Road.ROAD_NAME, "Тверская").build(), LONG, 0);
        scene.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.MANEUVER_METERS, 250).build(), LONG, 1);

        HudFields shown = scene.compose(1).fields(HudRole.MANEUVER);

        assertEquals(250L, shown.value(Road.MANEUVER_METERS));
        assertNull(shown.value(Road.ROAD_NAME));
    }

    @Test
    public void navigationOpensOnlyWithAFilledManoeuvreBlock() {
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT, new HudFields.Builder()
                .number(Road.SPEED_LIMIT, 60).build(), LONG, 0);

        HudComposition mapAlone = scene.compose(0);
        assertEquals("a map alone would open an empty manoeuvre block", HudMode.OFF, mapAlone.mode);
        assertNull(mapAlone.road());
        assertTrue(mapAlone.layers(HudWindow.MAP).isEmpty());

        // A block with the remaining route but no manoeuvre is still empty.
        scene.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.REMAINING_METERS, 5_000).text(Road.SUMMARY_TIME, "10 мин").build(), LONG, 0);
        assertEquals(HudMode.OFF, scene.compose(0).mode);

        navigating(0);
        HudComposition navigation = scene.compose(0);
        assertEquals(HudMode.NAVIGATION, navigation.mode);
        assertEquals(1, navigation.layers(HudWindow.MAP).size());
        assertEquals(HudSource.CAPTURE, navigation.winner(HudRole.ROAD_LIMIT));
        assertEquals("0a091002800102483c583c", Pngs.hex(navigation.road()));
    }

    @Test
    public void withoutNavigationNothingIsShownAndTheLastClearStands() {
        navigating(0);
        scene.fields(HudSource.MODULE, HudRole.LANES, new HudFields.Builder()
                .number(Road.LANE_COUNT, 0).build(), LONG, 0);
        assertEquals("0a0710028001012800", Pngs.hex(scene.compose(0).clear()));

        scene.withdraw(HudSource.CAPTURE, HudRole.MANEUVER);
        HudComposition off = scene.compose(0);
        assertEquals(HudMode.OFF, off.mode);
        assertNull(off.road());
        assertNull(off.clear());
        assertNull(off.winner(HudRole.LANES));
    }

    @Test
    public void theRoadPacketTakesItsFieldsInOneOrderWhoeverPublishedThem() {
        scene.fields(HudSource.CAPTURE, HudRole.AR, new HudFields.Builder()
                .decimal(Road.HEADING, 1.0).number(Road.SPEED, 13).build(), LONG, 0);
        scene.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT, new HudFields.Builder()
                .number(Road.SPEED_LIMIT, 60).build(), LONG, 0);
        scene.fields(HudSource.MODULE, HudRole.LANES, new HudFields.Builder()
                .bytes(Road.LANES_PNG, new byte[] {7}).number(Road.LANE_COUNT, 2).build(), LONG, 0);
        scene.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.STOCK_MANEUVER, 2).number(Road.MANEUVER_METERS, 90)
                .number(Road.REMAINING_METERS, 900).build(), LONG, 0);

        // 2, 16, then 5, 7, 3, 9, 11, 28, 21, 32.
        assertEquals("0a2110028001022802" + "3a0107" + "188407" + "485a" + "583c" + "e00102"
                        + "a8010d" + "8102000000000000f03f",
                Pngs.hex(scene.compose(0).road()));
    }

    @Test
    public void everythingOfASourceCanBeWithdrawnAtOnce() {
        navigating(0);
        scene.layer(HudSource.CAPTURE, "map", HudRole.MAP, WHOLE, Pngs.image(300, 180), 1f, LONG, 0);
        scene.fields(HudSource.CAPTURE, HudRole.AR, ar(1.0), LONG, 0);

        scene.withdraw(HudSource.CAPTURE);

        HudComposition composition = scene.compose(0);
        assertEquals(HudMode.OFF, composition.mode);
        assertEquals(Long.MAX_VALUE, composition.nextChange);
    }

    @Test
    public void whatTheTableDoesNotAllowIsRefused() {
        refused(() -> scene.fields(HudSource.RADAR, HudRole.MANEUVER,
                new HudFields.Builder().number(Road.MANEUVER_METERS, 1).build(), LONG, 0));
        refused(() -> scene.fields(HudSource.CAPTURE, HudRole.MANEUVER,
                new HudFields.Builder().number(Road.SPEED_LIMIT, 60).build(), LONG, 0));
        refused(() -> scene.fields(HudSource.CAPTURE, HudRole.MAP,
                new HudFields.Builder().build(), LONG, 0));
        refused(() -> scene.layer(HudSource.CAPTURE, "x", HudRole.AR, WHOLE,
                Pngs.image(300, 180), 1f, LONG, 0));
        refused(() -> scene.layer(HudSource.CAPTURE, "x", HudRole.MAP_BADGE, CORNER,
                Pngs.image(80, 60), 1f, LONG, 0));
        refused(() -> scene.layer(HudSource.CAPTURE, "x", HudRole.MAP, WHOLE,
                Pngs.image(300, 180), 1.5f, LONG, 0));
        refused(() -> scene.layer(HudSource.CAPTURE, "x", HudRole.MAP, WHOLE,
                Pngs.image(300, 180), 1f, 0, 0));
    }

    @Test
    public void anImageKnowsItsSizeFromThePngHeader() {
        HudImage image = Pngs.image(300, 180);
        assertEquals(300, image.width);
        assertEquals(180, image.height);
        assertArrayEquals(Pngs.header(300, 180), image.png());
        refused(() -> HudImage.png(new byte[] {1, 2, 3}));
    }

    /** A filled manoeuvre block from the capture, which opens navigation. */
    /** The radar's warning in the lane window, six cells wide, for {@code ttl} ms. */
    private void radarInTheLaneWindow(long now, long ttl) {
        scene.fields(HudSource.RADAR, HudRole.LANES, new HudFields.Builder()
                .number(Road.LANE_COUNT, 6).bytes(Road.LANES_PNG, Pngs.header(246, 60)).build(), ttl, now);
    }

    private void navigating(long now) {
        scene.fields(HudSource.CAPTURE, HudRole.MANEUVER, new HudFields.Builder()
                .number(Road.MANEUVER_METERS, 60).build(), LONG, now);
    }

    private static HudFields ar(double heading) {
        return new HudFields.Builder().decimal(Road.HEADING, heading).build();
    }

    private static void refused(Runnable call) {
        try {
            call.run();
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // The table said no.
        }
    }
}
