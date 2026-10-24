package dev.denza.adasplayer.probe;

import dev.denza.adaslink.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The refusal gate. The player must not play while the stock map could compete on the feed or while
 * Navigator's live bridge holds a valid arm, so it refuses unless com.byd.launchermap is absent and
 * disabled (enabledState 3) and no valid arm is held. The device facts are collected the way
 * {@code live_session.py} collects them (stock process/enabled state, the arm file's validity) and
 * handed to the player as a {@code preconditions.json}; this class is the pure decision over them,
 * so a missing or unsafe file refuses. Plain Java, host-testable.
 */
final class Preconditions {
    static final String SCHEMA = "denza-adas-parked-preconditions-v1";

    static final class Decision {
        final boolean safe;
        final List<String> reasons;

        Decision(boolean safe, List<String> reasons) { this.safe = safe; this.reasons = reasons; }
    }

    private Preconditions() {}

    static Decision evaluate(Map<String, Object> state) {
        List<String> reasons = new ArrayList<>();
        if (state == null) return new Decision(false, one("no preconditions file"));
        if (!SCHEMA.equals(state.get("schema"))) reasons.add("wrong/absent schema");
        Boolean running = asBool(state.get("stockRunning"));
        if (running == null) reasons.add("stockRunning unknown");
        else if (running) reasons.add("com.byd.launchermap is running");
        Object enabled = state.get("stockEnabledState");
        if (!(enabled instanceof Number)) reasons.add("stockEnabledState unknown");
        else if (((Number) enabled).intValue() != 3) reasons.add("com.byd.launchermap is enabled (state " + ((Number) enabled).intValue() + ")");
        Boolean armValid = asBool(state.get("armValid"));
        if (armValid == null) reasons.add("armValid unknown");
        else if (armValid) reasons.add("Navigator holds a valid live arm");
        return new Decision(reasons.isEmpty(), reasons);
    }

    static Decision evaluate(String json) {
        if (json == null) return new Decision(false, one("no preconditions file"));
        Object value;
        try {
            value = Json.parse(json);
        } catch (RuntimeException malformed) {
            return new Decision(false, one("malformed preconditions file"));
        }
        if (!(value instanceof Map)) return new Decision(false, one("preconditions must be a JSON object"));
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
        return evaluate(map);
    }

    private static Boolean asBool(Object value) { return value instanceof Boolean ? (Boolean) value : null; }

    private static List<String> one(String reason) {
        List<String> list = new ArrayList<>();
        list.add(reason);
        return list;
    }
}
