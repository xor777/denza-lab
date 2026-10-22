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
     * the guidance itself rather than from Yandex's screen. icon is a semantic manoeuvre key, null
     * without a manoeuvre ahead; -1 means unknown; speedLimit 0 means no sign.
     */
    static final class Hint {
        String icon, action, road = "", limitText;
        Object position, followingPosition;
        int stockId, exit, distance = -1, remainingMeters = -1, remainingSeconds = -1, speedLimit;
        double limitValue = Double.NaN;
        String iconKey() { return icon == null ? null : icon.equals("roundabout") ? icon + exit : icon; }
    }
    /**
     * mode: off; waiting (a route but no position yet: nothing drawn, nothing written); guidance (a
     * position, fresh or held for up to {@link #DIM_AFTER_MS}); lost (held longer: the map dims, the
     * icons go); rerouting (Navigator rebuilds the route: the old line dims, no hint). staleMs counts
     * from the last fresh fix, 0 while fixes keep coming.
     */
    static final class Frame {
        String mode = "off";
        boolean backgroundEnabled, resumed;
        Object route, position, point, windshield, location;
        double heading, latitude, cameraDistance = Double.NaN;
        /** When the last fresh fix arrived; the stock camera sets its targets once per fix. */
        long staleMs, fixAt = -1;
        List<Lane> lanes = Collections.emptyList();
        Hint hint;
        boolean active() { return !mode.equals("off"); }
        boolean drawMap() { return mode.equals("guidance") || mode.equals("lost") || mode.equals("rerouting"); }
        boolean onRoute() { return mode.equals("guidance") || mode.equals("lost"); }
    }
    /** A fix older than this no longer counts as fresh; the picture holds on the last good one. */
    static final long FRESH_MS = 3000;
    /** Mirrors STATES.dimAfter in hud-scene.js: past it the held picture dims. */
    static final long DIM_AFTER_MS = 30000;
    private final Reflect api;
    /** The last reason a hint could not be read, for the module log; the map goes on without it. */
    String hintFailure;
    private String lastStamp;
    private long lastChanged;
    // The last good fix, held through a gap: satellites drop out under bridges and between towers.
    private Object heldLocation, heldPoint, heldPosition;
    private double heldHeading, heldLatitude;
    private boolean held;
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
            forget();
            return frame;
        }
        Object automotive = api.call(api.call(guidance, "navigation"), "getGuidance");
        String status = String.valueOf(api.call(automotive, "getRouteStatus"));
        if (status.equals("ROUTE_FINISHED")) { forget(); return frame; }
        boolean rerouting = status.equals("NOT_ON_ROUTE") || status.equals("ROUTE_LOST");
        Object classified = api.call(guidance, "getLocation");
        Object location = classified == null ? null : api.call(classified, "getLocation");
        String accuracy = classified == null ? "NONE" : String.valueOf(api.call(classified, "getLocationClass"));
        Object position = api.call(guidance, "getRoutePosition");
        if (location != null && position != null && (accuracy.equals("FINE") || accuracy.equals("EXTRAPOLATED"))) {
            String stamp = String.valueOf(api.call(location, "getAbsoluteTimestamp"));
            if (!stamp.equals(lastStamp)) {
                lastStamp = stamp; lastChanged = now; held = true;
                heldLocation = location; heldPosition = position;
                heldPoint = api.call(location, "getPosition");
                heldLatitude = (Double) api.call(heldPoint, "getLatitude");
                Double heading = (Double) api.call(location, "getHeading");
                if (heading != null) heldHeading = heading;
            }
        }
        if (!held) { frame.mode = "waiting"; return frame; }
        long age = now - lastChanged;
        frame.staleMs = age > FRESH_MS ? age : 0;
        frame.fixAt = lastChanged;
        frame.location = heldLocation;
        frame.point = heldPoint;
        frame.latitude = heldLatitude;
        frame.heading = heldHeading;
        frame.position = heldPosition;
        if (rerouting) { frame.mode = "rerouting"; return frame; }
        frame.mode = age > DIM_AFTER_MS ? "lost" : "guidance";
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
        if (laneDistance == null || !(laneDistance >= 0 && laneDistance <= 800)) return frame;
        List<?> signs = (List<?>) api.call(frame.windshield, "getLaneSigns");
        if (signs.isEmpty()) return frame;
        List<?> lanes = (List<?>) api.call(api.call(signs.get(0), "getLaneSign"), "getLanes");
        if (lanes.isEmpty() || lanes.size() > 6) return frame;
        List<Lane> result = new ArrayList<>();
        for (Object lane : lanes) {
            // A bus, tram or taxi lane is drawn by its kind; its arrows are not ours to take.
            String kind = String.valueOf(api.call(lane, "getLaneKind"));
            List<String> directions = new ArrayList<>();
            String highlight = null;
            if (kind.equals("PLAIN_LANE") || kind.equals("UNKNOWN_KIND")) {
                for (Object value : (List<?>) api.call(lane, "getDirections")) directions.add(laneName(String.valueOf(value)));
                Object selected = api.call(lane, "getHighlightedDirection");
                highlight = selected == null ? null : laneName(selected.toString());
                if (highlight != null && !directions.contains(highlight)) highlight = null;
                if (!directions.isEmpty()) kind = "PLAIN_LANE";
            }
            result.add(new Lane(kind, directions, highlight));
        }
        frame.lanes = result;
        return frame;
    }

    private void forget() {
        lastStamp = null; held = false;
        heldLocation = heldPoint = heldPosition = null;
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
            hint.action = action.toString();
            hint.position = api.call(manoeuvre, "getPosition");
            if (i + 1 < manoeuvres.size()) hint.followingPosition = api.call(manoeuvres.get(i + 1), "getPosition");
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
     * Navigator's Action → {semantic manoeuvre key, road packet field 28}. Field 28 follows the
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
            case "LEFT_FROM_RIGHT": return "leftfromright";
            case "RIGHT_FROM_LEFT": return "rightfromleft";
            default: return "unknown";
        }
    }
}
