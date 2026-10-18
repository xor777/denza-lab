package dev.denza.hudsurfaces.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.denza.hudlink.HudProtocol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** The scenarios as data: what goes out, through which session, in which order. */
public class PlansTest {
    private final FakeArt art = new FakeArt();

    @Test
    public void everySessionIsOpenedBeforeItWritesStoppedBeforeUnbindAndNothingIsLeftBound() {
        for (Scenario scenario : Scenario.values()) {
            Set<SessionId> bound = EnumSet.noneOf(SessionId.class);
            Set<SessionId> started = EnumSet.noneOf(SessionId.class);
            for (Step step : Plans.plan(scenario, art)) {
                switch (step.kind) {
                    case OPEN:
                        assertFalse(scenario + ": " + step.session + " opened twice",
                                bound.contains(step.session));
                        bound.add(step.session);
                        started.add(step.session);
                        break;
                    case FIRE:
                        assertTrue(scenario + ": " + step.session + " writes unstarted",
                                started.contains(step.session));
                        break;
                    case STOP:
                        assertTrue(scenario + ": " + step.session + " stopped unstarted",
                                started.remove(step.session));
                        break;
                    case UNBIND:
                        assertFalse(scenario + ": " + step.session + " unbound while started",
                                started.contains(step.session));
                        assertTrue(scenario + ": " + step.session + " unbound unbound",
                                bound.remove(step.session));
                        break;
                    default:
                        break;
                }
            }
            assertTrue(scenario + " leaves " + bound + " bound", bound.isEmpty());
        }
    }

    @Test
    public void everyScenarioEndsWithTheReset() {
        for (Scenario scenario : Scenario.values()) {
            List<Step> steps = Plans.plan(scenario, art);
            int firstStop = -1;
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).kind == Step.Kind.STOP && isAfterLastWrite(steps, index)) {
                    firstStop = index;
                    break;
                }
            }
            assertTrue(scenario + " has a final stop", firstStop > 0);
            List<Step> fires = new ArrayList<>();
            long sinceFirstClear = 0L;
            for (int index = firstStop - 1; index >= 0 && fires.size() < 6; index--) {
                Step step = steps.get(index);
                if (step.kind == Step.Kind.FIRE) {
                    fires.add(0, step);
                } else if (step.kind == Step.Kind.PAUSE) {
                    sinceFirstClear += step.millis;
                }
            }
            for (int round = 0; round < 3; round++) {
                Step map = fires.get(2 * round);
                Step road = fires.get(2 * round + 1);
                assertEquals(HudProtocol.TOPIC_MAP, map.topic);
                assertArrayEquals(SurfacePackets.map(FakeArt.BLACK), map.payload);
                assertEquals(HudProtocol.TOPIC_ROAD, road.topic);
                assertArrayEquals(SurfacePackets.clear(), road.payload);
                assertEquals(map.session, road.session);
            }
            // The pauses after the first clear round: 120 + 120 + 110 = 350 ms to the stop.
            assertEquals(scenario.toString(), Plans.STOP_DELAY_MS, sinceFirstClear);
        }
    }

    @Test
    public void scenarioOneNeverRaisesNavigationNorSendsAMapFrame() {
        List<Map<Integer, Object>> roads = roadsBeforeReset(Scenario.CAMERAS_OFF);
        assertEquals(4 * Plans.TICKS_PER_PHASE, roads.size());
        for (Map<Integer, Object> road : roads) {
            assertEquals(1L, road.get(16));
        }
        assertEquals(0, mapsBeforeReset(Scenario.CAMERAS_OFF).size());
        assertPhaseOrder(Scenario.CAMERAS_OFF, 17, 11, 13, 23);
    }

    @Test
    public void scenarioTwoNavigatesWithAFullManeuverBlockThroughout() {
        List<Map<Integer, Object>> roads = roadsBeforeReset(Scenario.CAMERAS_ON);
        assertEquals(4 * Plans.TICKS_PER_PHASE, roads.size());
        for (Map<Integer, Object> road : roads) {
            assertEquals(2L, road.get(16));
            assertArrayEquals(FakeArt.ARROW, (byte[]) road.get(8));
            assertTrue(road.containsKey(9) && road.containsKey(26) && road.containsKey(27));
        }
        assertEquals(0, mapsBeforeReset(Scenario.CAMERAS_ON).size());
        assertPhaseOrder(Scenario.CAMERAS_ON, 17, 11, 13, 23);
    }

    @Test
    public void scenarioThreeOpensOnlyForTheWarningAndWatchesAfterTheReset() {
        List<Step> steps = Plans.plan(Scenario.WARNING, art);
        int open = indexOf(steps, Step.Kind.OPEN, 0);
        long before = 0L;
        for (int index = 0; index < open; index++) {
            assertFalse("fires before the session", steps.get(index).kind == Step.Kind.FIRE);
            if (steps.get(index).kind == Step.Kind.PAUSE) {
                before += steps.get(index).millis;
            }
        }
        assertEquals(Plans.BASELINE_MS, before);
        List<Map<Integer, Object>> roads = roadsBeforeReset(Scenario.WARNING);
        assertEquals(Plans.WARNING_TICKS, roads.size());
        for (int tick = 0; tick < roads.size(); tick++) {
            Map<Integer, Object> road = roads.get(tick);
            assertEquals(2L, road.get(16));
            assertArrayEquals(FakeArt.CAMERA, (byte[]) road.get(8));
            assertEquals((long) SurfacePackets.cameraMeters(tick), road.get(9));
            assertEquals("60", Proto.text(road.get(10)));
            assertFalse(road.containsKey(26));
            assertFalse(road.containsKey(27));
        }
        assertEquals(0, mapsBeforeReset(Scenario.WARNING).size());
        int lastUnbind = lastIndexOf(steps, Step.Kind.UNBIND);
        long after = 0L;
        for (int index = lastUnbind + 1; index < steps.size(); index++) {
            assertFalse("fires after the reset", steps.get(index).kind == Step.Kind.FIRE);
            if (steps.get(index).kind == Step.Kind.PAUSE) {
                after += steps.get(index).millis;
            }
        }
        assertEquals(Plans.AFTER_WARNING_MS, after);
    }

    @Test
    public void quietHandoverKeepsASessionOpenAndNeverClearsBeforeTheEnd() {
        List<Step> steps = Plans.plan(Scenario.HANDOVER_QUIET, art);
        int openA = indexOfSession(steps, Step.Kind.OPEN, SessionId.A);
        int openB = indexOfSession(steps, Step.Kind.OPEN, SessionId.B);
        assertTrue(openA < openB);
        for (int index = openA; index < openB; index++) {
            assertFalse("A stops before B starts",
                    steps.get(index).kind == Step.Kind.STOP);
        }
        assertEquals(Plans.PHASE_MS, pausesBetween(steps, lastFire(steps, SessionId.A, openB), openB));
        assertNoClearBeforeReset(steps);
        assertHandoverWrites(steps, openA, openB);
        // The reset goes through B; A is stopped first, so B's stop withdraws the offer.
        int stopA = indexOfSession(steps, Step.Kind.STOP, SessionId.A);
        int stopB = indexOfSession(steps, Step.Kind.STOP, SessionId.B);
        assertTrue(stopA > openB && stopA < stopB);
        assertEquals(SessionId.B, lastFireStep(steps).session);
    }

    @Test
    public void gapHandoverStopsAndUnbindsAWithoutAClearBeforeBStarts() {
        List<Step> steps = Plans.plan(Scenario.HANDOVER_GAP, art);
        int openA = indexOfSession(steps, Step.Kind.OPEN, SessionId.A);
        int stopA = indexOfSession(steps, Step.Kind.STOP, SessionId.A);
        int unbindA = indexOfSession(steps, Step.Kind.UNBIND, SessionId.A);
        int openB = indexOfSession(steps, Step.Kind.OPEN, SessionId.B);
        assertTrue(openA < stopA && stopA < unbindA && unbindA < openB);
        assertEquals(stopA - 1, lastFire(steps, SessionId.A, steps.size()));
        assertEquals(Plans.PHASE_MS, pausesBetween(steps, unbindA, openB));
        assertNoClearBeforeReset(steps);
        assertHandoverWrites(steps, openA, openB);
        for (Step step : steps) {
            if (step.kind == Step.Kind.FIRE && step.session == SessionId.A) {
                assertFalse("A clears", Arrays.equals(SurfacePackets.clear(), step.payload));
            }
        }
    }

    @Test
    public void resetButtonOnlyClears() {
        List<Step> steps = Plans.plan(Scenario.RESET, art);
        assertEquals(Step.Kind.OPEN, steps.get(0).kind);
        int fires = 0;
        for (Step step : steps) {
            if (step.kind == Step.Kind.FIRE) {
                fires++;
                assertTrue(Arrays.equals(SurfacePackets.clear(), step.payload)
                        || Arrays.equals(SurfacePackets.map(FakeArt.BLACK), step.payload));
            }
        }
        assertEquals(6, fires);
        assertEquals(Plans.STOP_DELAY_MS, Plans.durationMs(steps));
    }

    @Test
    public void scenariosHoldAFixedShortTime() {
        for (Scenario scenario : Scenario.values()) {
            if (scenario == Scenario.RESET) {
                continue;
            }
            long millis = Plans.durationMs(Plans.plan(scenario, art));
            assertTrue(scenario + " " + millis, millis >= 15_000L && millis <= 25_000L);
        }
    }

    @Test
    public void everyPhaseIsAsLongAsItsPauses() {
        for (Scenario scenario : Scenario.values()) {
            List<Step> steps = Plans.plan(scenario, art);
            for (int index = 0; index < steps.size(); index++) {
                Step phase = steps.get(index);
                if (phase.kind != Step.Kind.PHASE) {
                    continue;
                }
                assertNotNull(phase.text);
                assertNotNull(phase.hint);
                int next = indexOf(steps, Step.Kind.PHASE, index + 1);
                long pauses = pausesBetween(steps, index, next < 0 ? steps.size() : next);
                assertEquals(scenario + " «" + phase.text + "»", phase.millis, pauses);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void theClearerMustHoldTheService() {
        Plans.clearAndClose(SessionId.B, Collections.singletonList(SessionId.A), art);
    }

    @Test
    public void scenarioIdsRoundTrip() {
        for (Scenario scenario : Scenario.values()) {
            assertEquals(scenario, Scenario.byId(scenario.id));
        }
        assertEquals(null, Scenario.byId("nope"));
    }

    /** A writes road and map, B first road only, then road and map; each 10 times. */
    private static void assertHandoverWrites(List<Step> steps, int openA, int openB) {
        int firstStop = lastWriteBeforeReset(steps);
        List<Step> fromA = new ArrayList<>();
        List<Step> fromB = new ArrayList<>();
        for (int index = openA; index < firstStop; index++) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.FIRE) {
                (step.session == SessionId.A ? fromA : fromB).add(step);
                if (step.session == SessionId.A) {
                    assertTrue("A writes after B opened", index < openB);
                }
            }
        }
        assertEquals(2 * Plans.TICKS_PER_PHASE, fromA.size());
        for (Step step : fromA) {
            byte[] expected = step.topic == HudProtocol.TOPIC_MAP
                    ? SurfacePackets.map(FakeArt.MAP_A)
                    : SurfacePackets.sessionManeuver(SessionId.A, FakeArt.LETTER_A);
            assertArrayEquals(expected, step.payload);
        }
        assertEquals(3 * Plans.TICKS_PER_PHASE, fromB.size());
        for (int index = 0; index < fromB.size(); index++) {
            Step step = fromB.get(index);
            if (index < Plans.TICKS_PER_PHASE) {
                assertEquals("B sends no map at first", HudProtocol.TOPIC_ROAD, step.topic);
            }
            byte[] expected = step.topic == HudProtocol.TOPIC_MAP
                    ? SurfacePackets.map(FakeArt.MAP_B)
                    : SurfacePackets.sessionManeuver(SessionId.B, FakeArt.LETTER_B);
            assertArrayEquals(expected, step.payload);
        }
    }

    private static void assertNoClearBeforeReset(List<Step> steps) {
        int end = lastWriteBeforeReset(steps);
        for (int index = 0; index < end; index++) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.FIRE && step.topic == HudProtocol.TOPIC_ROAD) {
                assertEquals("a clear before the reset", 2L, Proto.road(step.payload).get(16));
            }
            if (step.kind == Step.Kind.FIRE && step.topic == HudProtocol.TOPIC_MAP) {
                assertFalse("a black frame before the reset",
                        Arrays.equals(SurfacePackets.map(FakeArt.BLACK), step.payload));
            }
        }
    }

    private void assertPhaseOrder(Scenario scenario, int... fields) {
        List<Map<Integer, Object>> roads = roadsBeforeReset(scenario);
        for (int phase = 0; phase < fields.length; phase++) {
            for (int tick = 0; tick < Plans.TICKS_PER_PHASE; tick++) {
                Map<Integer, Object> road = roads.get(phase * Plans.TICKS_PER_PHASE + tick);
                assertTrue(scenario + " phase " + phase + " lacks " + fields[phase],
                        road.containsKey(fields[phase]));
            }
        }
    }

    private List<Map<Integer, Object>> roadsBeforeReset(Scenario scenario) {
        List<Step> steps = Plans.plan(scenario, art);
        int end = lastWriteBeforeReset(steps);
        List<Map<Integer, Object>> roads = new ArrayList<>();
        for (int index = 0; index < end; index++) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.FIRE && step.topic == HudProtocol.TOPIC_ROAD) {
                roads.add(Proto.road(step.payload));
            }
        }
        return roads;
    }

    private List<Step> mapsBeforeReset(Scenario scenario) {
        List<Step> steps = Plans.plan(scenario, art);
        int end = lastWriteBeforeReset(steps);
        List<Step> maps = new ArrayList<>();
        for (int index = 0; index < end; index++) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.FIRE && step.topic == HudProtocol.TOPIC_MAP) {
                maps.add(step);
            }
        }
        return maps;
    }

    /** Index of the reset's phase step: everything before it is the scenario proper. */
    private static int lastWriteBeforeReset(List<Step> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.PHASE && "Сброс".equals(step.text)) {
                return index;
            }
        }
        fail("no reset");
        return -1;
    }

    private static boolean isAfterLastWrite(List<Step> steps, int index) {
        for (int next = index; next < steps.size(); next++) {
            if (steps.get(next).kind == Step.Kind.FIRE) {
                return false;
            }
        }
        return true;
    }

    private static int lastFire(List<Step> steps, SessionId session, int before) {
        for (int index = before - 1; index >= 0; index--) {
            Step step = steps.get(index);
            if (step.kind == Step.Kind.FIRE && step.session == session) {
                // The pause that follows the last write belongs to the tick, not the gap.
                return steps.get(index + 1).kind == Step.Kind.PAUSE ? index + 1 : index;
            }
        }
        return -1;
    }

    private static Step lastFireStep(List<Step> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            if (steps.get(index).kind == Step.Kind.FIRE) {
                return steps.get(index);
            }
        }
        return null;
    }

    private static long pausesBetween(List<Step> steps, int from, int to) {
        long total = 0L;
        for (int index = from + 1; index < to; index++) {
            if (steps.get(index).kind == Step.Kind.PAUSE) {
                total += steps.get(index).millis;
            }
        }
        return total;
    }

    private static int indexOf(List<Step> steps, Step.Kind kind, int from) {
        for (int index = from; index < steps.size(); index++) {
            if (steps.get(index).kind == kind) {
                return index;
            }
        }
        return -1;
    }

    private static int lastIndexOf(List<Step> steps, Step.Kind kind) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            if (steps.get(index).kind == kind) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfSession(List<Step> steps, Step.Kind kind, SessionId session) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).kind == kind && steps.get(index).session == session) {
                return index;
            }
        }
        fail("no " + kind + " of " + session);
        return -1;
    }
}
