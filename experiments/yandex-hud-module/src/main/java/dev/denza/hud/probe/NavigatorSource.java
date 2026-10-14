package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Navigation state only: visibility does not authorize a route, and preview is never guidance. */
final class NavigatorSource {
    static final class Lane {
        final List<String> directions;
        final String highlight;
        Lane(List<String> directions, String highlight) { this.directions = directions; this.highlight = highlight; }
        @Override public String toString() { return directions + ":" + highlight; }
    }
    static final class Frame {
        String mode = "off";
        boolean backgroundEnabled, resumed;
        Object route, position, point;
        double heading, latitude, cameraDistance = Double.NaN;
        List<Lane> lanes = Collections.emptyList();
        boolean active() { return !mode.equals("off"); }
        boolean drawMap() { return mode.equals("guidance"); }
    }
    private final Reflect api;
    private String lastStamp;
    private long lastChanged;
    NavigatorSource(Reflect api) { this.api = api; }

    @SuppressWarnings("unchecked") Frame read(Object guidance, boolean visible, long now) {
        Frame frame = new Frame();
        if (!Boolean.TRUE.equals(api.call(guidance, "isValid"))) return frame;
        frame.route = api.call(guidance, "route");
        frame.resumed = (Boolean) api.call(guidance, "isGuidanceResumed");
        frame.backgroundEnabled = (Boolean) api.call(api.call(guidance, "bgConfigurator"), "isBackgroundOnRouteEnabled");
        // Respect Navigator's real route/resume state and the owner's background preference.
        // getRouteStatus() is undefined before guidance has a route; on debug ART
        // its native enum conversion can abort the process, not throw a catchable error.
        if (!frame.resumed || frame.route == null || (!visible && !frame.backgroundEnabled)) {
            lastStamp = null;
            return frame;
        }
        Object automotive = api.call(api.call(guidance, "navigation"), "getGuidance");
        String status = String.valueOf(api.call(automotive, "getRouteStatus"));
        if (status.equals("ROUTE_FINISHED")) { lastStamp = null; return frame; }
        if (status.equals("NOT_ON_ROUTE") || status.equals("ROUTE_LOST")) { frame.mode = "rerouting"; return frame; }
        frame.mode = "waiting-location";
        Object classified = api.call(guidance, "getLocation");
        Object location = classified == null ? null : api.call(classified, "getLocation");
        String accuracy = classified == null ? "NONE" : String.valueOf(api.call(classified, "getLocationClass"));
        frame.position = api.call(guidance, "getRoutePosition");
        if (location == null || frame.position == null || !(accuracy.equals("FINE") || accuracy.equals("EXTRAPOLATED"))) return frame;
        String stamp = String.valueOf(api.call(location, "getAbsoluteTimestamp"));
        if (!stamp.equals(lastStamp)) { lastStamp = stamp; lastChanged = now; }
        if (now - lastChanged > 3000) return frame;
        frame.mode = "guidance";
        frame.point = api.call(location, "getPosition");
        frame.latitude = (Double) api.call(frame.point, "getLatitude");
        Double heading = (Double) api.call(location, "getHeading");
        frame.heading = heading == null ? 0 : heading;
        Object next = api.call(guidance, "getNextManeuver");
        if (next != null) frame.cameraDistance = ((Number) api.call(next, "getDistance")).doubleValue();
        Double laneDistance = (Double) api.call(guidance, "getNextLaneSignDistance");
        if (laneDistance == null || laneDistance < 0 || laneDistance > 800) return frame;
        List<?> signs = (List<?>) api.call(api.call(automotive, "getWindshield"), "getLaneSigns");
        if (signs.isEmpty()) return frame;
        List<?> lanes = (List<?>) api.call(api.call(signs.get(0), "getLaneSign"), "getLanes");
        if (lanes.isEmpty() || lanes.size() > 6) return frame;
        List<Lane> result = new ArrayList<>();
        for (Object lane : lanes) {
            if (!String.valueOf(api.call(lane, "getLaneKind")).equals("PLAIN_LANE")) return frame;
            List<String> directions = new ArrayList<>();
            for (Object value : (List<?>) api.call(lane, "getDirections")) {
                String name = laneName(String.valueOf(value));
                if (name == null) return frame;
                directions.add(name);
            }
            Object selected = api.call(lane, "getHighlightedDirection");
            String highlight = selected == null ? null : laneName(selected.toString());
            if (directions.isEmpty() || (selected != null && (highlight == null || !directions.contains(highlight)))) return frame;
            result.add(new Lane(directions, highlight));
        }
        frame.lanes = result;
        return frame;
    }

    private static String laneName(String value) {
        switch (value) {
            case "STRAIGHT_AHEAD": return "straightahead";
            case "LEFT90": return "left90";
            case "RIGHT90": return "right90";
            case "LEFT45": return "left45";
            case "RIGHT45": return "right45";
            case "LEFT135": return "left135";
            case "RIGHT135": return "right135";
            case "LEFT180": return "left180";
            case "RIGHT180": return "right180";
            default: return null;
        }
    }
}
