package dev.denza.hud.probe;

import java.util.Map;

/** Tests the diagnostic distinction between a held HUD picture and a current navigation fix. */
public final class GuidanceSnapshotTest {
    private static int checks;
    public static void main(String[] args) {
        GuidanceSnapshot reader = new GuidanceSnapshot(new Reflect());
        NavigatorSource.Frame frame = new NavigatorSource.Frame();
        frame.mode = "guidance"; frame.resumed = true; frame.fixAt = 1000;
        frame.route = new Route(); frame.position = new Position(); frame.location = new Location();
        frame.positionRouteId = "route-a";
        frame.hint = new NavigatorSource.Hint();
        frame.hint.action = "SLIGHT_RIGHT"; frame.hint.limitValue = 50.0 / 3.6;
        Map<String, Object> value = reader.read(frame, 1200, 100000);
        check(value.get("routeId").equals("route-a"), "selected route identity");
        check(value.get("fixAgeMs").equals(200L), "age from fresh fix, not held HUD state");
        check(value.get("freshLocation").equals(true), "fresh active fix");
        check(value.get("positionRouteId").equals("route-a") && value.get("positionMatchesRoute").equals(true), "position ownership exported");
        check(((Map<?, ?>) value.get("sourcePolylinePosition")).get("segmentPosition").equals(0.25), "fraction preserved");
        check(((Map<?, ?>) value.get("location")).get("heading") == null, "missing heading stays unknown");
        check(((Map<?, ?>) value.get("nextManoeuvre")).get("action").equals("SLIGHT_RIGHT"), "Yandex action preserved without HUD numeric translation");
        ((Route)frame.route).id = "route-b";
        value = reader.read(frame, 1300, 100100);
        check(value.get("positionRouteId").equals("route-a") && value.get("positionMatchesRoute").equals(false), "route change preserves original fix owner");
        check(value.get("sourcePolylinePosition") == null && value.get("freshLocation").equals(false), "recent retained position cannot attach to replacement route");
        check(((Map<?, ?>)value.get("issues")).containsKey("sourcePolylinePosition"), "ownership mismatch is explicit evidence");
        frame.positionRouteId = "route-b";
        value = reader.read(frame, 1400, 100200);
        check(value.get("sourcePolylinePosition") != null && value.get("freshLocation").equals(true), "new route-owned fix restores diagnostic pose");
        frame.positionRouteId = null;
        value = reader.read(frame, 1450, 100250);
        check(value.get("sourcePolylinePosition") == null && value.get("freshLocation").equals(false), "unknown owner fails closed");
        frame.positionRouteId = "route-b";
        value = reader.read(frame, 4500, 103300);
        check(value.get("freshLocation").equals(false), "HUD hold does not authorize a fresh pose");
        check(value.get("fixAgeMs").equals(3500L), "stale age remains visible");
        frame.mode = "rerouting";
        check(reader.read(frame, 1300, 100100).get("freshLocation").equals(false), "reroute is not a matched fresh route");
        frame.mode = "off";
        value = reader.read(frame, 1500, 100300);
        check(value.get("routeId") == null && value.get("location") == null
                && value.get("sourcePolylinePosition") == null && value.get("nextManoeuvre") == null
                && value.get("positionRouteId") == null && value.get("positionMatchesRoute").equals(false),
                "cancel clears route-owned data even if the frame has old references");
        frame.mode = "guidance"; frame.position = new BadPosition();
        value = reader.read(frame, 1500, 100300);
        check(value.get("sourcePolylinePosition") == null && !((Map<?, ?>) value.get("issues")).isEmpty(), "bad fraction is rejected with evidence");
        String json = RouteSnapshot.json(value);
        check(!json.contains("NaN") && !json.contains("Infinity"), "missing values serialize as null, not non-JSON numbers");
        System.out.println("GuidanceSnapshotTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
    public static class Position {
        public int getSegmentIndex() { return 2; }
        public double getSegmentPosition() { return 0.25; }
    }
    public static class BadPosition extends Position {
        public double getSegmentPosition() { return 1.1; }
    }
    public static class Route {
        String id = "route-a";
        public String getRouteId() { return id; }
        public Route getRoutePosition() { return this; }
        public double distanceToFinish() { return 1234.5; }
        public double timeToFinish() { return 120.25; }
    }
    public static class Location {
        public Point getPosition() { return new Point(); }
        public Double getAccuracy() { return 3.0; }
        public Double getAltitude() { return null; }
        public Double getHeading() { return null; }
        public Double getSpeed() { return 5.0; }
        public Double getConfidence() { return null; }
        public long getAbsoluteTimestamp() { return 99800; }
        public long getRelativeTimestamp() { return 1000; }
    }
    public static class Point {
        public double getLongitude() { return 37.6; }
        public double getLatitude() { return 55.75; }
    }
}
