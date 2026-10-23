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
        frame.locationClass = "FINE"; frame.fineFixRelativeMs = 1000;
        frame.hint = new NavigatorSource.Hint();
        frame.hint.action = "SLIGHT_RIGHT"; frame.hint.limitValue = 50.0 / 3.6;
        frame.hint.position = new ManoeuvrePosition(true);
        Map<String, Object> value = reader.read(frame, 1200, 100000);
        check(value.get("routeId").equals("route-a"), "selected route identity");
        check(value.get("observedStampAgeMs").equals(200L), "observed stamp age from the module's own clock");
        check(!value.containsKey("fixAgeMs"), "the ambiguous fixAgeMs name is gone");
        check("FINE".equals(value.get("locationClass")), "location class exported");
        check(value.get("lastFineFixAgeMs").equals(200L), "age of the last FINE fix from its relativeTimestamp");
        check(value.get("freshLocation").equals(true), "fresh active FINE fix");
        check(value.get("positionRouteId").equals("route-a") && value.get("positionMatchesRoute").equals(true), "position ownership exported");
        Map<?, ?> source = (Map<?, ?>) value.get("sourcePolylinePosition");
        check(source.get("segmentIndex").equals(2) && source.get("segmentPosition").equals(0.25), "polyline position exact");
        Map<?, ?> location = (Map<?, ?>) value.get("location");
        check(location.get("longitude").equals(37.6) && location.get("latitude").equals(55.75), "longitude and latitude, not swapped");
        check(location.get("accuracy").equals(3.0), "accuracy exact");
        check(location.get("absoluteTimestamp").equals(99800L) && location.get("relativeTimestamp").equals(1000L), "both timestamps exact");
        check(location.get("heading") == null, "missing heading stays unknown");
        check(location.get("speed").equals(5.0), "speed in m/s as MapKit gives it");
        check(value.get("distanceToFinishM").equals(1234.5), "distance to finish, not time");
        check(value.get("timeToFinishS").equals(120.25), "time to finish, not distance");
        check(value.get("speedLimitMps").equals(50.0 / 3.6), "limit in m/s, unconverted");
        Map<?, ?> next = (Map<?, ?>) value.get("nextManoeuvre");
        check(next.get("action").equals("SLIGHT_RIGHT"), "Yandex action preserved without HUD numeric translation");
        Map<?, ?> nextPosition = (Map<?, ?>) next.get("position");
        check(nextPosition != null && nextPosition.get("segmentIndex").equals(7) && nextPosition.get("segmentPosition").equals(0.5),
                "on-route manoeuvre position exact");
        check(((Map<?, ?>) value.get("issues")).isEmpty(), "complete fixture has no issues");
        frame.hint.position = new ManoeuvrePosition(false);
        value = reader.read(frame, 1200, 100000);
        check(((Map<?, ?>) value.get("nextManoeuvre")).get("position") == null, "off-route manoeuvre position is not exported");
        frame.hint.position = new ManoeuvrePosition(true);
        // CAP-1: an extrapolated location is a prediction; the last FINE fix ages on.
        frame.locationClass = "EXTRAPOLATED";
        value = reader.read(frame, 1800, 100600);
        check("EXTRAPOLATED".equals(value.get("locationClass")) && value.get("freshLocation").equals(false), "EXTRAPOLATED is never fresh");
        check(value.get("lastFineFixAgeMs").equals(800L), "FINE age keeps counting through extrapolation");
        check(value.get("speedLimitMps") == null, "no limit without a fresh FINE fix (CAP-7)");
        frame.locationClass = "FINE"; frame.fineFixRelativeMs = 1000;
        value = reader.read(frame, 3600, 102400);
        check(value.get("freshLocation").equals(false), "a FINE fix older than 2.5 s is not fresh");
        frame.fineFixRelativeMs = -1;
        value = reader.read(frame, 1200, 100000);
        check(value.get("lastFineFixAgeMs") == null && value.get("freshLocation").equals(false), "no FINE fix: unknown age, not fresh");
        frame.fineFixRelativeMs = 1000;
        ((Route) frame.route).id = "route-b";
        value = reader.read(frame, 1300, 100100);
        check(value.get("positionRouteId").equals("route-a") && value.get("positionMatchesRoute").equals(false), "route change preserves original fix owner");
        check(value.get("sourcePolylinePosition") == null && value.get("freshLocation").equals(false), "recent retained position cannot attach to replacement route");
        check(value.get("location") == null && value.get("distanceToFinishM") == null && value.get("timeToFinishS") == null,
                "a fix of another route never pairs with this route's ETA (CAP-7)");
        check(((Map<?, ?>) value.get("issues")).containsKey("sourcePolylinePosition") && ((Map<?, ?>) value.get("issues")).containsKey("location"),
                "ownership mismatch is explicit evidence");
        frame.positionRouteId = "route-b";
        value = reader.read(frame, 1400, 100200);
        check(value.get("sourcePolylinePosition") != null && value.get("freshLocation").equals(true), "new route-owned fix restores diagnostic pose");
        frame.positionRouteId = null;
        value = reader.read(frame, 1450, 100250);
        check(value.get("sourcePolylinePosition") == null && value.get("freshLocation").equals(false), "unknown owner fails closed");
        frame.positionRouteId = "route-b";
        frame.fixAt = 1000;
        value = reader.read(frame, 4500, 103300);
        check(value.get("freshLocation").equals(false), "HUD hold does not authorize a fresh pose");
        check(value.get("observedStampAgeMs").equals(3500L), "stale age remains visible");
        frame.mode = "rerouting";
        check(reader.read(frame, 1300, 100100).get("freshLocation").equals(false), "reroute is not a matched fresh route");
        frame.mode = "lost";
        check(reader.read(frame, 1300, 100100).get("speedLimitMps") == null, "no limit in lost mode");
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
    public static class NextPosition {
        public int getSegmentIndex() { return 7; }
        public double getSegmentPosition() { return 0.5; }
    }
    public static class ManoeuvrePosition {
        final boolean on;
        ManoeuvrePosition(boolean on) { this.on = on; }
        public boolean onRoute(String id) { return on; }
        public NextPosition positionOnRoute(String id) { return new NextPosition(); }
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
