package dev.denza.adasplayer.probe;

import dev.denza.adaslink.Json;
import dev.denza.adaslink.Topics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One parked scenario parsed from its JSON file into the payloads and the timeline the
 * {@link PlayerEngine} plays. Plain Java (host-testable): the JSON is read with the stock
 * {@code adas-link} reader, payloads are built by {@link PlayerMessages}. Payloads for the parked
 * window do not change over time (the car is stationary), so each is built once per run, with the
 * run's fresh pathId; only {@code time} in the route envelope is fresh beyond the pathId.
 */
final class ScenarioPlan {
    /** The ODD region-code request topic (service 13 / method 0x0002), for the record only. */
    static final long ODD_TOPIC = 563005788848130L;

    static final class Tick {
        final long tMs;
        final String name;
        final long topic;
        final byte[] payload;

        Tick(long tMs, String name, long topic, byte[] payload) {
            this.tMs = tMs; this.name = name; this.topic = topic; this.payload = payload;
        }
    }

    final String id;
    final long durationMs;
    final boolean oddRequest;
    final int loopStatus;
    final Integer naviStValue;
    private final int[][] preRoutePhase;     // {status, holdMs}
    private final int[][] endSequence;        // {status, holdMs}
    private final String routePayloadJson;
    private final long pathIdToken;
    private final int routeType;
    private final Map<String, Object> position, trafficInfo, region, light, tunnel, speedIntersection,
            advAutoFunInfo, tidalLane, action, rates;

    @SuppressWarnings("unchecked")
    ScenarioPlan(Map<String, Object> scenario) {
        this.id = String.valueOf(scenario.get("id"));
        long durationS = PlayerMessages.num(scenario, "durationS");
        if (durationS < 1 || durationS > 300) throw new IllegalArgumentException("durationS 1..300");
        this.durationMs = durationS * 1000L;
        this.oddRequest = PlayerMessages.b(scenario, "oddRequest");
        Map<String, Object> route = (Map<String, Object>) scenario.get("route");
        this.routePayloadJson = String.valueOf(route.get("payloadJson"));
        this.pathIdToken = PlayerMessages.num(route, "pathIdToken");
        this.routeType = PlayerMessages.i(route, "routeType");
        Map<String, Object> navStatus = (Map<String, Object>) scenario.get("navStatus");
        this.loopStatus = PlayerMessages.i(navStatus, "loopStatus");
        this.preRoutePhase = phase((List<Object>) navStatus.get("preRoutePhase"));
        this.endSequence = phase((List<Object>) scenario.get("endSequence"));
        this.position = (Map<String, Object>) scenario.get("position");
        this.trafficInfo = (Map<String, Object>) scenario.get("trafficInfo");
        this.region = (Map<String, Object>) scenario.get("region");
        this.light = (Map<String, Object>) scenario.get("light");
        this.tunnel = (Map<String, Object>) scenario.get("tunnel");
        this.speedIntersection = (Map<String, Object>) scenario.get("speedIntersection");
        this.advAutoFunInfo = (Map<String, Object>) scenario.get("advAutoFunInfo");
        this.tidalLane = (Map<String, Object>) scenario.get("tidalLane");
        this.action = (Map<String, Object>) scenario.get("action");
        this.rates = (Map<String, Object>) scenario.get("rates");
        Object navi = scenario.get("naviSt");
        this.naviStValue = navi == null ? null : (Integer) (int) PlayerMessages.num((Map<String, Object>) navi, "value");
    }

    static ScenarioPlan parse(String json) {
        Object value = Json.parse(json);
        if (!(value instanceof Map)) throw new IllegalArgumentException("scenario must be a JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
        return new ScenarioPlan(map);
    }

    @SuppressWarnings("unchecked")
    private static int[][] phase(List<Object> steps) {
        if (steps == null) return new int[0][];
        int[][] out = new int[steps.size()][2];
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = (Map<String, Object>) steps.get(i);
            out[i][0] = PlayerMessages.i(step, "status");
            Object hold = step.get("holdS");
            double holdS = hold instanceof Number ? ((Number) hold).doubleValue() : 1.0;
            out[i][1] = (int) Math.round(holdS * 1000);
        }
        return out;
    }

    long[] services() { return PlayerMessages.services(); }

    int[][] preRoutePhase() { return preRoutePhase; }

    int[][] endSequence() { return endSequence; }

    byte[] routePayload(long pathId, long wallMs) {
        return PlayerMessages.route(routePayloadJson, pathIdToken, pathId, wallMs, 1);
    }

    byte[] selectionPayload(long pathId) { return PlayerMessages.selection(pathId); }

    private double hz(String name) {
        Object value = rates.get(name);
        return value instanceof Number ? ((Number) value).doubleValue() : 1.0;
    }

    private void schedule(List<Tick> out, String name, long topic, byte[] payload, double hz,
                          long loopStart, long endMs) {
        if (hz <= 0) {                     // once, at the loop start (e.g. stock Region)
            out.add(new Tick(loopStart, name, topic, payload));
            return;
        }
        long period = Math.max(1, Math.round(1000.0 / hz));
        for (long t = loopStart; t < endMs; t += period) out.add(new Tick(t, name, topic, payload));
    }

    /** The merged, time-sorted periodic feed from {@code loopStart} to {@code endMs}. */
    List<Tick> loopTicks(long pathId, long loopStart, long endMs) {
        List<Tick> out = new ArrayList<>();
        schedule(out, "NavigationStatus", Topics.NAVIGATION_STATUS,
                PlayerMessages.navigationStatus(loopStatus), hz("status"), loopStart, endMs);
        schedule(out, "VehicleLocation", Topics.SD_VEHICLE_LOCATION,
                PlayerMessages.position(position, pathId), hz("position"), loopStart, endMs);
        schedule(out, "TrafficInfo", Topics.TRAFFIC_INFO,
                PlayerMessages.trafficInfo(trafficInfo), hz("trafficInfo"), loopStart, endMs);
        schedule(out, "Region", Topics.SD_REGION,
                PlayerMessages.region(region), hz("region"), loopStart, endMs);
        schedule(out, "TrafficLight", Topics.SD_TRAFFIC_LIGHT,
                PlayerMessages.light(light), hz("light"), loopStart, endMs);
        schedule(out, "Tunnel", Topics.SD_TUNNEL,
                PlayerMessages.tunnel(tunnel), hz("tunnel"), loopStart, endMs);
        schedule(out, "SpeedIntersection", Topics.SD_SPEED_INTERSECTION,
                PlayerMessages.speedIntersection(speedIntersection), hz("speedIntersection"), loopStart, endMs);
        schedule(out, "AdvAutoFunInfo", Topics.ADV_AUTO_FUN_INFO,
                PlayerMessages.advAutoFunInfo(advAutoFunInfo), hz("advAuto"), loopStart, endMs);
        schedule(out, "TidalLane", Topics.SD_TIDAL_LANE,
                PlayerMessages.tidalLane(tidalLane), hz("tidal"), loopStart, endMs);
        schedule(out, "NextAction", Topics.SD_ACTION_CAMERA,
                PlayerMessages.action(action), hz("action"), loopStart, endMs);
        out.sort((a, b) -> a.tMs != b.tMs ? Long.compare(a.tMs, b.tMs) : a.name.compareTo(b.name));
        return out;
    }
}
