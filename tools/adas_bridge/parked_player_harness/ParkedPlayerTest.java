package dev.denza.adasplayer.probe;

import dev.denza.adaslink.Pb;
import dev.denza.adaslink.Topics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host tests for the parked player's pure core: the end sequence always runs (clean, fire error,
 * exception, watchdog stop), services are always released, a naviSt scenario has no player-side
 * NaviSt effect (the host runner owns it), the refusal gate rejects a competing stock map or a live arm, and every
 * generated scenario plays end to end. Fake transport/clock; no Android, no ADB. */
public final class ParkedPlayerTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    // ---- fakes ----

    private static final class VirtualClock implements Clock {
        long now = 100000, wall = 1_700_000_000_000L;
        public long nowMs() { return now; }
        public long wallMs() { return wall; }
        public void sleep(long ms) { now += Math.max(0, ms); wall += Math.max(0, ms); }
        public void sleepUntil(long elapsedMs) { if (elapsedMs > now) { wall += elapsedMs - now; now = elapsedMs; } }
    }

    private static final class Fired {
        final long topic; final byte[] payload;
        Fired(long topic, byte[] payload) { this.topic = topic; this.payload = payload; }
    }

    private static final class FakeTransport implements Transport {
        final List<Long> started = new ArrayList<>(), stopped = new ArrayList<>();
        final List<Fired> fires = new ArrayList<>();
        int oddResult = 0, oddCalls = 0;
        long throwTopic = -1, errorTopic = -1;
        int errorResult = -11;
        Runnable afterFire;
        public int startServer(long topic) { started.add(topic); return 0; }
        public int stopServer(long topic) { stopped.add(topic); return 0; }
        public int oddRequest() { oddCalls++; if (throwTopic == ScenarioPlan.ODD_TOPIC) throw new RuntimeException("odd boom"); return oddResult; }
        public int fire(long topic, byte[] payload) {
            if (topic == throwTopic) throw new RuntimeException("fire boom");
            fires.add(new Fired(topic, payload));
            if (afterFire != null) afterFire.run();
            return topic == errorTopic ? errorResult : 0;
        }
        List<Fired> withTopic(long topic) {
            List<Fired> out = new ArrayList<>();
            for (Fired f : fires) if (f.topic == topic) out.add(f);
            return out;
        }
    }

    private static final class FakeRecorder implements Recorder {
        final List<String> fires = new ArrayList<>(), services = new ArrayList<>(), notes = new ArrayList<>();
        int answers = 0;
        public void service(String op, long topic, int result, long e, long w) { services.add(op + ":" + topic + ":" + result); }
        public void fire(String label, long topic, int result, long e, long w) { fires.add(label + ":" + topic + ":" + result); }
        public void answer(long topic, byte[] payload, long e, long w) { answers++; }
        public void note(String event, String detail) { notes.add(event + ":" + detail); }
        boolean noted(String prefix) { for (String n : notes) if (n.startsWith(prefix)) return true; return false; }
        String fireResult(String label) { for (String f : fires) if (f.startsWith(label + ":")) return f; return null; }
    }

    private static int decodeStatus(byte[] payload) {
        Map<String, Object> top = Pb.NAVIGATION_STATUS_NOTIFY.decode(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) top.get("navigationStatusLinkInfoNotifyStruct");
        Object v = inner == null ? null : inner.get("navigationStatus");
        return v == null ? 0 : (int) ((Number) v).longValue();
    }

    private static void assertEndSequenceAndRelease(FakeTransport t) {
        List<Fired> statuses = t.withTopic(Topics.NAVIGATION_STATUS);
        check(statuses.size() >= 2, "at least the two end statuses fired");
        int last = decodeStatus(statuses.get(statuses.size() - 1).payload);
        int prev = decodeStatus(statuses.get(statuses.size() - 2).payload);
        check(prev == 110 && last == 100, "end sequence is 110 then 100, got " + prev + "," + last);
        check(t.stopped.size() == t.started.size(), "every started service released");
    }

    // ---- an inline minimal scenario (all field keys present; tiny route body) ----

    private static final String FIELDS =
        "\"position\":{\"lon\":37.0,\"lat\":55.0,\"roadLon\":37.0,\"roadLat\":55.0,\"vehicleSpeed\":0,"
        + "\"gpsSpeed\":0,\"course\":0,\"segIdx\":0,\"linkIdx\":0,\"segDist\":38,\"linkRemain\":38,\"ownership\":0},"
        + "\"trafficInfo\":{\"jamLength\":0,\"distToJam\":-1,\"jamStatus\":0,\"passTime\":0,\"construction\":0,"
        + "\"cLon\":0.0,\"cLat\":0.0,\"spdLmtSpeedValue\":0,\"eyeSpeed\":0,\"eyeDist\":0,\"iStartLon\":0.0,"
        + "\"iStartLat\":0.0,\"iEndLon\":0.0,\"iEndLat\":0.0,\"iSpeed\":0,\"offCourse\":false,\"distToDest\":4202,"
        + "\"timeToDest\":957,\"roadClass\":9},"
        + "\"region\":{\"regionId\":990001,\"country\":1,\"province\":990001,\"city\":990001,\"weather\":0},"
        + "\"light\":{\"exist\":1,\"lon\":0.0,\"lat\":0.0,\"state\":0,\"start\":0,\"end\":0,\"dir\":0,\"wait\":0},"
        + "\"tunnel\":{\"present\":false,\"state\":0,\"dist\":0,\"startLon\":0.0,\"startLat\":0.0,\"endLon\":0.0,\"endLat\":0.0},"
        + "\"speedIntersection\":{\"flow\":0,\"history\":25,\"parallel\":2,\"mix\":2,\"solid\":0,\"dist\":-1,\"voice\":\"\"},"
        + "\"advAutoFunInfo\":{\"noOddRegionDist\":2147483647},"
        + "\"tidalLane\":{\"ramp\":-1,\"tidal\":1},"
        + "\"action\":{\"icon\":3,\"main\":0,\"assistant\":0,\"distance\":38,\"cameraType\":0,\"cameraDistance\":-1},"
        + "\"rates\":{\"status\":1,\"position\":1,\"trafficInfo\":1,\"region\":1,\"light\":1,\"tunnel\":1,"
        + "\"speedIntersection\":1,\"advAuto\":1,\"tidal\":1,\"action\":1}";

    private static String scenario(boolean odd, boolean phase, String naviSt) {
        String pre = phase ? "[{\"status\":100,\"holdS\":1},{\"status\":106,\"holdS\":1}]" : "[]";
        return "{\"id\":\"T\",\"durationS\":2,\"oddRequest\":" + odd + ","
            + "\"route\":{\"pathIdToken\":100002,\"routeType\":1,\"payloadJson\":\"{\\\"data\\\":[{\\\"pathID\\\":100002}]}\"},"
            + "\"navStatus\":{\"preRoutePhase\":" + pre + ",\"loopStatus\":101},"
            + "\"endSequence\":[{\"status\":110,\"holdS\":3},{\"status\":100,\"holdS\":0}],"
            + FIELDS + ",\"naviSt\":" + naviSt + "}";
    }

    private static void normalEnd() {
        ScenarioPlan plan = ScenarioPlan.parse(scenario(true, true, "null"));
        FakeTransport t = new FakeTransport();
        FakeRecorder r = new FakeRecorder();
        new PlayerEngine().run(plan, t, new VirtualClock(), r, 777L);
        check(t.oddCalls == 1, "ODD request sent once");
        check(r.fireResult("route").endsWith(":0"), "route fired ok");
        check(r.fireResult("pathSelected") != null, "selection fired");
        check(!t.withTopic(Topics.ROUTE).isEmpty() && !t.withTopic(Topics.PATH_SELECTED).isEmpty(), "route+selection on wire");
        check(r.noted("released:"), "release noted");
        assertEndSequenceAndRelease(t);
    }

    private static void fireErrorStillEnds() {
        ScenarioPlan plan = ScenarioPlan.parse(scenario(true, false, "null"));
        FakeTransport t = new FakeTransport();
        t.errorTopic = Topics.ROUTE;
        FakeRecorder r = new FakeRecorder();
        new PlayerEngine().run(plan, t, new VirtualClock(), r, 1L);
        check(r.fireResult("route").endsWith(":-11"), "route fire error recorded, not fatal");
        assertEndSequenceAndRelease(t);
    }

    private static void exceptionStillEnds() {
        ScenarioPlan plan = ScenarioPlan.parse(scenario(true, false, "null"));
        FakeTransport t = new FakeTransport();
        t.throwTopic = ScenarioPlan.ODD_TOPIC;  // ODD request throws -> body aborts, finally still runs
        FakeRecorder r = new FakeRecorder();
        new PlayerEngine().run(plan, t, new VirtualClock(), r, 1L);
        check(r.noted("engine_error:"), "engine error noted");
        check(t.withTopic(Topics.ROUTE).isEmpty(), "route not fired after the abort");
        assertEndSequenceAndRelease(t);
    }

    private static void watchdogStopStillEnds() {
        ScenarioPlan plan = ScenarioPlan.parse(scenario(true, true, "null"));
        FakeTransport t = new FakeTransport();
        FakeRecorder r = new FakeRecorder();
        PlayerEngine engine = new PlayerEngine();
        engine.requestStop();  // as a watchdog / kill would, before the feed starts
        engine.run(plan, t, new VirtualClock(), r, 1L);
        check(t.withTopic(Topics.ROUTE).isEmpty(), "stop halts the normal feed");
        assertEndSequenceAndRelease(t);
    }

    private static void stopMidLoopStillEnds() {
        ScenarioPlan plan = ScenarioPlan.parse(scenario(false, false, "null"));
        FakeTransport t = new FakeTransport();
        FakeRecorder r = new FakeRecorder();
        PlayerEngine engine = new PlayerEngine();
        final int[] n = {0};
        t.afterFire = () -> { if (++n[0] == 5) engine.requestStop(); };
        engine.run(plan, t, new VirtualClock(), r, 1L);
        check(engine.stopRequested(), "stop requested mid-loop");
        assertEndSequenceAndRelease(t);
    }

    private static void naviStScenarioPlaysWithoutSideEffects() {
        // H6's naviSt is the host runner's (adb shell); the app-UID player just plays the feed.
        ScenarioPlan plan = ScenarioPlan.parse(scenario(true, true, "{\"value\":2}"));
        FakeTransport t = new FakeTransport();
        FakeRecorder r = new FakeRecorder();
        new PlayerEngine().run(plan, t, new VirtualClock(), r, 1L);
        check(!r.noted("naviSt"), "player records no NaviSt action");
        check(!t.withTopic(Topics.ROUTE).isEmpty(), "naviSt scenario still plays the route");
        assertEndSequenceAndRelease(t);
    }

    private static Map<String, Object> state(Boolean running, Integer enabled, Boolean arm) {
        Map<String, Object> m = new HashMap<>();
        m.put("schema", Preconditions.SCHEMA);
        if (running != null) m.put("stockRunning", running);
        if (enabled != null) m.put("stockEnabledState", (long) (int) enabled);
        if (arm != null) m.put("armValid", arm);
        return m;
    }

    private static void refusalPaths() {
        check(!Preconditions.evaluate((Map<String, Object>) null).safe, "no file refuses");
        check(!Preconditions.evaluate(state(true, 3, false)).safe, "stock running refuses");
        check(!Preconditions.evaluate(state(false, 0, false)).safe, "stock enabled refuses");
        check(!Preconditions.evaluate(state(false, 1, false)).safe, "stock enabled(1) refuses");
        check(!Preconditions.evaluate(state(false, 3, true)).safe, "valid arm refuses");
        Preconditions.Decision ok = Preconditions.evaluate(state(false, 3, false));
        check(ok.safe && ok.reasons.isEmpty(), "absent+disabled stock and no arm is safe");
        check(!Preconditions.evaluate("not json {").safe, "malformed file refuses");
    }

    private static void generatedScenarios() throws IOException {
        String dir = System.getenv("DENZA_SCENARIO_DIR");
        if (dir == null || dir.isEmpty()) { System.out.println("  (generated-scenario play skipped: no capture)"); return; }
        int played = 0;
        for (Path p : Files.newDirectoryStream(Paths.get(dir), "scenario-*.json")) {
            ScenarioPlan plan = ScenarioPlan.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            FakeTransport t = new FakeTransport();
            FakeRecorder r = new FakeRecorder();
            new PlayerEngine().run(plan, t, new VirtualClock(), r, 1_700_123L);
            check(!t.withTopic(Topics.ROUTE).isEmpty(), p + ": route fired");
            check(!t.withTopic(Topics.PATH_SELECTED).isEmpty(), p + ": selection fired");
            check(!r.noted("engine_error:"), p + ": no engine error");
            assertEndSequenceAndRelease(t);
            played++;
        }
        check(played >= 1, "at least one generated scenario played");
        System.out.println("  played " + played + " generated scenarios");
    }

    public static void main(String[] args) throws Exception {
        normalEnd();
        fireErrorStillEnds();
        exceptionStillEnds();
        watchdogStopStillEnds();
        stopMidLoopStillEnds();
        naviStScenarioPlaysWithoutSideEffects();
        refusalPaths();
        generatedScenarios();
        System.out.println("ParkedPlayer pure-core host harness: " + checks + " checks PASS");
    }
}
