package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Navigation state only: visibility does not authorize a route, and preview is never guidance. */
final class NavigatorSource {
    static final class Lane {
        final boolean plain;
        final String kind;
        final List<String> directions;
        final String highlight;
        Lane(String kind, List<String> directions, String highlight) {
            this.kind = kind; this.plain = kind.equals("PLAIN_LANE"); this.directions = directions; this.highlight = highlight;
        }
        @Override public String toString() { return plain ? directions + ":" + highlight : kind; }
    }
    /**
     * What the HUD's own manoeuvre window shows (road packet fields 3/4, 8-11, 26-28), read from
     * the guidance itself rather than from Yandex's screen. icon is a theme manoeuvre picture, null
     * without a manoeuvre ahead; -1 means unknown; speedLimit 0 means no sign.
     */
    static final class Hint {
        String icon, road = "", limitText;
        int stockId, exit, distance = -1, remainingMeters = -1, remainingSeconds = -1, speedLimit;
        double limitValue = Double.NaN;
        String iconKey() { return icon == null ? null : icon.equals("roundabout") ? icon + exit : icon; }
    }
    static final class Frame {
        String mode = "off";
        boolean backgroundEnabled, resumed;
        Object route, position, point, windshield;
        double heading, latitude, cameraDistance = Double.NaN;
        List<Lane> lanes = Collections.emptyList();
        Hint hint;
        boolean active() { return !mode.equals("off"); }
        boolean drawMap() { return mode.equals("guidance"); }
    }
    private final Reflect api;
    /** The last reason a hint could not be read, for the module log; the map goes on without it. */
    String hintFailure;
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
        frame.windshield = api.call(automotive, "getWindshield");
        try {
            frame.hint = hint(frame.route, automotive, frame.windshield);
            hintFailure = null;
        } catch (RuntimeException error) {
            hintFailure = String.valueOf(error.getCause() != null ? error.getCause() : error);
        }
        Double laneDistance = (Double) api.call(guidance, "getNextLaneSignDistance");
        if (laneDistance == null || laneDistance < 0 || laneDistance > 800) return frame;
        List<?> signs = (List<?>) api.call(frame.windshield, "getLaneSigns");
        if (signs.isEmpty()) return frame;
        List<?> lanes = (List<?>) api.call(api.call(signs.get(0), "getLaneSign"), "getLanes");
        if (lanes.isEmpty() || lanes.size() > 6) return frame;
        List<Lane> result = new ArrayList<>();
        for (Object lane : lanes) {
            // A bus, tram or taxi lane keeps its place without arrows; a guessed direction would mislead.
            String kind = String.valueOf(api.call(lane, "getLaneKind"));
            if (!kind.equals("PLAIN_LANE")) {
                result.add(new Lane(kind, Collections.<String>emptyList(), null));
                continue;
            }
            List<String> directions = new ArrayList<>();
            for (Object value : (List<?>) api.call(lane, "getDirections")) {
                String name = laneName(String.valueOf(value));
                if (name == null) return frame;
                directions.add(name);
            }
            Object selected = api.call(lane, "getHighlightedDirection");
            String highlight = selected == null ? null : laneName(selected.toString());
            if (directions.isEmpty() || (selected != null && (highlight == null || !directions.contains(highlight)))) return frame;
            result.add(new Lane(kind, directions, highlight));
        }
        frame.lanes = result;
        return frame;
    }

    private Hint hint(Object route, Object automotive, Object windshield) {
        Hint hint = new Hint();
        Object here = api.call(route, "getRoutePosition");
        double toFinish = Double.NaN;
        if (here != null) {
            toFinish = (Double) api.call(here, "distanceToFinish");
            double time = (Double) api.call(here, "timeToFinish");
            if (toFinish >= 0) hint.remainingMeters = (int) Math.round(toFinish);
            if (time >= 0) hint.remainingSeconds = (int) Math.round(time);
        }
        Object limit = api.call(automotive, "getSpeedLimit");
        if (limit != null) {
            hint.limitValue = (Double) api.call(limit, "getValue");
            hint.limitText = String.valueOf(api.call(limit, "getText"));
            hint.speedLimit = speedLimit(hint.limitValue);
        }
        List<?> manoeuvres = windshield == null ? Collections.emptyList() : (List<?>) api.call(windshield, "getManoeuvres");
        for (int i = 0; i < manoeuvres.size(); i++) {
            Object manoeuvre = manoeuvres.get(i);
            double distance = toFinish - (Double) api.call(api.call(manoeuvre, "getPosition"), "distanceToFinish");
            if (!(distance >= 0)) continue; // passed, or no position to measure from
            Object annotation = api.call(manoeuvre, "getAnnotation");
            Object action = annotation == null ? null : api.call(annotation, "getAction");
            String[] kind = action == null ? null : manoeuvre(action.toString());
            if (kind == null) continue;
            hint.icon = kind[0];
            hint.stockId = Integer.parseInt(kind[1]);
            hint.distance = (int) Math.round(distance);
            Object toponym = api.call(annotation, "getToponym");
            hint.road = toponym == null ? "" : toponym.toString().trim();
            if (hint.icon.equals("roundabout")) {
                // The exit number rides on the manoeuvre that leaves the roundabout.
                hint.exit = exit(annotation);
                if (hint.exit == 0 && i + 1 < manoeuvres.size()) hint.exit = exit(api.call(manoeuvres.get(i + 1), "getAnnotation"));
            }
            break;
        }
        return hint;
    }

    private int exit(Object annotation) {
        if (annotation == null) return 0;
        Object metadata = api.call(annotation, "getActionMetadata");
        Object leave = metadata == null ? null : api.call(metadata, "getLeaveRoundaboutMetadata");
        return leave == null ? 0 : Math.max(0, (Integer) api.call(leave, "getExitNumber"));
    }

    /**
     * Navigator's Action → {theme manoeuvre picture, road packet field 28}. Field 28 follows the
     * stock BYD navigator read from the firmware (docs/instrument-display-findings.md, "Field 28"):
     * 13 enters and 24 leaves a roundabout, 48 is the destination, 45 a waypoint.
     */
    static String[] manoeuvre(String action) {
        switch (action) {
            case "STRAIGHT": case "BOARD_FERRY": case "LEAVE_FERRY": return new String[]{"straight", "11"};
            case "SLIGHT_LEFT": case "FORK_LEFT": case "EXIT_LEFT": return new String[]{"slight-left", "3"};
            case "SLIGHT_RIGHT": case "FORK_RIGHT": case "EXIT_RIGHT": return new String[]{"slight-right", "5"};
            case "LEFT": return new String[]{"left", "1"};
            case "RIGHT": return new String[]{"right", "2"};
            case "HARD_LEFT": return new String[]{"sharp-left", "7"};
            case "HARD_RIGHT": return new String[]{"sharp-right", "8"};
            case "UTURN_LEFT": return new String[]{"uturn-left", "9"};
            case "UTURN_RIGHT": return new String[]{"uturn-right", "10"};
            case "ENTER_ROUNDABOUT": return new String[]{"roundabout", "13"};
            case "LEAVE_ROUNDABOUT": return new String[]{"roundabout", "24"};
            case "FINISH": return new String[]{"finish", "48"};
            case "WAYPOINT": return new String[]{"finish", "45"};
            default: return null;
        }
    }

    /** MapKit reports speed limits in m/s; the sign shows km/h rounded to the posted step of 5. */
    static int speedLimit(Float metresPerSecond) {
        return metresPerSecond == null ? 0 : speedLimit(metresPerSecond.doubleValue());
    }

    static int speedLimit(double metresPerSecond) {
        if (!(metresPerSecond > 0) || metresPerSecond > 70) return 0;
        return (int) Math.round(metresPerSecond * 3.6 / 5) * 5;
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
            case "LEFT_SHIFT": return "leftshift";
            case "RIGHT_SHIFT": return "rightshift";
            case "LEFT_FROM_RIGHT": return "left90";
            case "RIGHT_FROM_LEFT": return "right90";
            default: return null;
        }
    }
}
