package dev.denza.hudlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The session policy, on a clock the test moves and a port that writes down every call. */
public final class HudSessionTest {
    private static final byte[] ROAD_A = bytes("road-A");
    private static final byte[] ROAD_B = bytes("road-B");
    private static final byte[] ROAD_C = bytes("road-C");
    private static final byte[] CLEAR = bytes("clear");
    private static final byte[] FRAME_1 = bytes("frame-1");
    private static final byte[] FRAME_2 = bytes("frame-2");
    private static final byte[] FRAME_3 = bytes("frame-3");

    private final TestClock clock = new TestClock();
    private final RecordingPort port = new RecordingPort();
    private final List<String> events = new ArrayList<>();
    private final HudSession session = new HudSession(clock, port, new Events(), bytes("black"));
    private long due = HudSession.IDLE;

    @Test
    public void bindsStartsAndSendsTheRoadThenTheFrameAtOnce() {
        session.show(ROAD_A, CLEAR, FRAME_1, 1);
        changed();
        assertCalls("0 bind");

        at(40);
        session.onConnected();
        changed();

        assertCalls("0 bind", "40 start", "40 road road-A", "40 map frame-1");
    }

    @Test
    public void aChangedRoadPacketGoesAtOnceButAtMostFourTimesASecondAndAgainEveryHalfSecond() {
        startedAt(0, ROAD_A, null);

        at(100);
        session.show(ROAD_B, CLEAR, null, 0);
        changed();
        runUntil(1_300);
        session.show(ROAD_C, CLEAR, null, 0);
        changed();
        runUntil(1_600);

        assertCalls("0 road road-A",
                "250 road road-B", "750 road road-B", "1250 road road-B",
                "1500 road road-C");
    }

    @Test
    public void framesGoAtMostTwelveASecondAndTheNewestWins() {
        startedAt(0, ROAD_A, FRAME_1);

        at(30);
        session.show(ROAD_A, CLEAR, FRAME_2, 2);
        changed();
        at(60);
        session.show(ROAD_A, CLEAR, FRAME_3, 3);
        changed();
        runUntil(200);

        assertCalls(onlyMaps(), "0 map frame-1", "83 map frame-3");
    }

    @Test
    public void theLastFrameGoesAgainEverySecondUntilANewOneComes() {
        startedAt(0, ROAD_A, FRAME_1);

        runUntil(2_100);
        session.show(ROAD_A, CLEAR, FRAME_2, 2);
        changed();
        runUntil(3_200);

        assertCalls(onlyMaps(),
                "0 map frame-1", "1000 map frame-1", "2000 map frame-1",
                "2100 map frame-2", "3100 map frame-2");
    }

    @Test
    public void aMapWindowThatStopsBeingFedGetsThreeBlackFrames120msApart() {
        startedAt(0, ROAD_A, FRAME_1);

        at(200);
        session.show(ROAD_A, CLEAR, null, 2);
        changed();
        runUntil(3_000);

        assertCalls(onlyMaps(), "0 map frame-1", "200 map black", "320 map black", "440 map black");
    }

    @Test
    public void aNewFrameEndsTheBlackFramesStillToGo() {
        startedAt(0, ROAD_A, FRAME_1);
        at(200);
        session.show(ROAD_A, CLEAR, null, 2);
        changed();
        runUntil(330);

        session.show(ROAD_A, CLEAR, FRAME_2, 3);
        changed();
        runUntil(900);

        assertCalls(onlyMaps(), "0 map frame-1", "200 map black", "320 map black", "330 map frame-2");
    }

    @Test
    public void aWindowNeverFedGetsNoBlackFrames() {
        startedAt(0, ROAD_A, null);
        runUntil(2_000);

        assertCalls(onlyMaps());
    }

    @Test
    public void withNothingToShowTheClearGoesThreeTimesThenTheSessionStopsAt350ms() {
        startedAt(0, ROAD_A, FRAME_1);

        at(1_100);
        session.show(null, null, null, 2);
        changed();
        runUntil(5_000);

        assertCalls(since(1_100),
                "1100 map black", "1100 road clear",
                "1220 map black", "1220 road clear",
                "1340 map black", "1340 road clear",
                "1450 stop", "1450 unbind");
        assertEquals(Arrays.asList("binding", "started 0", "clearing", "stopped"), events);
    }

    @Test
    public void aLateClockStillSendsAllThreeClearsBeforeTheStop() {
        startedAt(0, ROAD_A, null);

        at(1_100);
        session.show(null, null, null, 0);
        changed();
        // The thread was busy: the second clear is 380 ms late, past the stop's time.
        clock.now = 1_500;
        changed();
        runUntil(2_000);

        assertCalls(since(1_100),
                "1100 map black", "1100 road clear",
                "1500 map black", "1500 road clear",
                "1620 map black", "1620 road clear",
                "1620 stop", "1620 unbind");
    }

    @Test
    public void somethingToShowDuringTheClearEndsItAndGoesAtOnce() {
        startedAt(0, ROAD_A, FRAME_1);

        at(1_100);
        session.show(null, null, null, 2);
        changed();
        at(1_200);
        session.show(ROAD_A, CLEAR, FRAME_1, 3);
        changed();
        runUntil(1_800);

        assertCalls(since(1_100),
                "1100 map black", "1100 road clear",
                "1200 road road-A", "1200 map frame-1",
                "1700 road road-A");
    }

    @Test
    public void theClearIsTheOneLastGivenWhileShowing() {
        startedAt(0, ROAD_A, null);
        session.show(ROAD_A, bytes("clear-with-lanes"), null, 0);
        changed();

        at(1_100);
        session.show(null, null, null, 0);
        changed();

        assertCalls(since(1_100), "1100 map black", "1100 road clear-with-lanes");
    }

    @Test
    public void nothingToShowWhileStillBindingJustUnbinds() {
        session.show(ROAD_A, CLEAR, FRAME_1, 1);
        changed();
        at(100);
        session.show(null, null, null, 1);
        changed();
        runUntil(5_000);

        assertCalls("0 bind", "100 unbind");
    }

    @Test
    public void aRefusedBindIsTriedAgainAfterASecond() {
        port.bindAnswer = false;
        session.show(ROAD_A, CLEAR, null, 0);
        changed();
        runUntil(999);
        port.bindAnswer = true;
        runUntil(1_000);

        assertCalls("0 bind", "1000 bind");
        assertEquals(Arrays.asList("binding", "trouble BIND_REFUSED 0", "binding"), events);
    }

    @Test
    public void aBindThatThrowsIsTriedAgainAfterASecond() {
        port.bindThrows = true;
        session.show(ROAD_A, CLEAR, null, 0);
        changed();
        port.bindThrows = false;
        runUntil(1_000);

        assertCalls("0 bind", "1000 bind");
        assertEquals("trouble BIND_FAILED 0", events.get(1));
    }

    @Test
    public void aBindingThatNeverAnswersIsDroppedAfterThreeSeconds() {
        session.show(ROAD_A, CLEAR, null, 0);
        changed();
        runUntil(4_000);

        assertCalls("0 bind", "3000 unbind", "4000 bind");
        assertEquals("trouble BIND_TIMEOUT 0", events.get(1));
    }

    @Test
    public void aFailedStartIsTriedAgainAfterASecond() {
        port.startAnswer = -200;
        session.show(ROAD_A, CLEAR, null, 0);
        changed();
        at(10);
        session.onConnected();
        changed();
        runUntil(1_010);

        assertCalls("0 bind", "10 start", "10 unbind", "1010 bind");
        assertEquals("trouble START_FAILED -200", events.get(2));
    }

    @Test
    public void aLostServiceIsBoundAgainAfterASecondAndGetsEverythingAtOnce() {
        startedAt(0, ROAD_A, FRAME_1);

        at(300);
        session.onDisconnected();
        changed();
        runUntil(1_300);
        session.onConnected();
        changed();

        assertCalls(since(300), "300 unbind", "1300 bind", "1300 start", "1300 road road-A", "1300 map frame-1");
        assertEquals("trouble DISCONNECTED 0", events.get(2));
    }

    @Test
    public void aRefusedSendDropsTheSessionAndStartsAgain() {
        startedAt(0, ROAD_A, null);

        port.fireAnswer = HudSomeIp.NOT_HANDLED;
        runUntil(500);
        port.fireAnswer = 0;
        runUntil(1_500);

        assertCalls(since(500), "500 road road-A", "500 unbind", "1500 bind");
        assertEquals("trouble FIRE_FAILED -201", events.get(2));
    }

    @Test
    public void losingTheServiceDuringTheClearEndsTheSessionThere() {
        startedAt(0, ROAD_A, null);
        at(1_100);
        session.show(null, null, null, 0);
        changed();

        at(1_150);
        session.onDisconnected();
        changed();
        runUntil(3_000);

        assertCalls(since(1_100), "1100 map black", "1100 road clear", "1150 unbind");
        assertEquals("stopped", events.get(events.size() - 1));
    }

    /** Bound, started and the first sends out at {@code time}; the port forgets those calls. */
    private void startedAt(long time, byte[] road, byte[] frame) {
        at(time);
        session.show(road, CLEAR, frame, 1);
        changed();
        session.onConnected();
        changed();
        List<String> sends = new ArrayList<>();
        for (String call : port.calls) {
            if (!call.endsWith(" bind") && !call.endsWith(" start")) {
                sends.add(call);
            }
        }
        port.calls.clear();
        port.calls.addAll(sends);
    }

    private void at(long time) {
        runUntil(time);
    }

    /** After a change at the current time: the session acts and says when next. */
    private void changed() {
        due = session.pump();
    }

    /** Pumps at every time the session asked for, up to {@code time}. */
    private void runUntil(long time) {
        int pumps = 0;
        while (due <= time) {
            if (++pumps > 10_000) {
                fail("the session asks for the same time again and again");
            }
            clock.now = Math.max(clock.now, due);
            due = session.pump();
        }
        clock.now = Math.max(clock.now, time);
    }

    private void assertCalls(String... expected) {
        assertEquals(Arrays.asList(expected), port.calls);
    }

    private void assertCalls(List<String> actual, String... expected) {
        assertEquals(Arrays.asList(expected), actual);
    }

    private List<String> onlyMaps() {
        List<String> out = new ArrayList<>();
        for (String call : port.calls) {
            if (call.contains(" map ")) {
                out.add(call);
            }
        }
        return out;
    }

    private List<String> since(long time) {
        List<String> out = new ArrayList<>();
        for (String call : port.calls) {
            if (Long.parseLong(call.substring(0, call.indexOf(' '))) >= time) {
                out.add(call);
            }
        }
        return out;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static final class TestClock implements HudClock {
        long now;

        @Override
        public long now() {
            return now;
        }
    }

    private final class RecordingPort implements HudSession.Port {
        final List<String> calls = new ArrayList<>();
        boolean bindAnswer = true;
        boolean bindThrows;
        int startAnswer;
        int fireAnswer;

        @Override
        public boolean bind() {
            record("bind");
            if (bindThrows) {
                throw new SecurityException("not allowed");
            }
            return bindAnswer;
        }

        @Override
        public void unbind() {
            record("unbind");
        }

        @Override
        public int start() {
            record("start");
            return startAnswer;
        }

        @Override
        public int stop() {
            record("stop");
            return 0;
        }

        @Override
        public int fire(long topic, byte[] payload) {
            String name = topic == HudProtocol.TOPIC_ROAD ? "road" : topic == HudProtocol.TOPIC_MAP ? "map" : "?";
            record(name + " " + new String(payload, StandardCharsets.UTF_8));
            return fireAnswer;
        }

        private void record(String call) {
            calls.add(clock.now + " " + call);
        }
    }

    private final class Events implements HudSession.Listener {
        @Override
        public void onBinding() {
            events.add("binding");
        }

        @Override
        public void onStarted(int result) {
            events.add("started " + result);
        }

        @Override
        public void onFired(long topic, int result, boolean clearing) {
            // The calls say it.
        }

        @Override
        public void onTrouble(HudSession.Trouble trouble, int result) {
            events.add("trouble " + trouble + " " + result);
        }

        @Override
        public void onClearing() {
            events.add("clearing");
        }

        @Override
        public void onStopped() {
            events.add("stopped");
        }
    }
}
