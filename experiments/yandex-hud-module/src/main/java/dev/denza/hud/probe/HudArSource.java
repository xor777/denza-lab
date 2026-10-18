package dev.denza.hud.probe;

import java.util.List;

/** Main-thread adapter to actual MapKit route positions. No location subscription or ADB. */
final class HudArSource {
    private final Reflect api;
    private String routeId;
    private HudAr.Route route;
    String failure;
    int geometryBuilds;

    HudArSource(Reflect api) { this.api = api; }

    HudAr.Frame read(NavigatorSource.Frame frame, long elapsedNow, long wallNow) {
        failure = null;
        // AR needs the car where it is: never on a held fix, off the route or while it is rebuilt.
        if (!frame.mode.equals("guidance") || frame.staleMs > 0) { reset(); return null; }
        try {
            NavigatorSource.Hint hint = frame.hint;
            if (hint == null || !HudAr.supported(hint.action)) return null;
            Object location = frame.location;
            HudAr.Pose pose = HudAr.Pose.read(point(frame.point), (Double) api.call(location, "getHeading"),
                    (Double) api.call(location, "getSpeed"), (Double) api.call(location, "getAccuracy"),
                    (Double) api.call(location, "getAltitude"), (Long) api.call(location, "getAbsoluteTimestamp"), wallNow, elapsedNow);
            if (pose == null) return null;
            String id = (String) api.call(frame.route, "getRouteId");
            // All positions must belong to exactly this route, especially while it is replaced.
            HudAr.Position turn = position(hint.position, id);
            HudAr.Position following = hint.followingPosition == null ? null : position(hint.followingPosition, id);
            if (turn == null || (hint.followingPosition != null && following == null)) return null;
            if (!id.equals(routeId)) {
                List<?> points = (List<?>) api.call(api.call(frame.route, "getGeometry"), "getPoints");
                if (points.size() < 2 || points.size() > 200_000) throw new IllegalArgumentException("AR route size");
                HudAr.Point[] copy = new HudAr.Point[points.size()];
                for (int i = 0; i < copy.length; i++) copy[i] = point(points.get(i));
                route = new HudAr.Route(copy);
                routeId = id;
                geometryBuilds++;
            }
            return route.frame(polylinePosition(frame.position), turn, following, pose);
        } catch (RuntimeException error) {
            failure = error.toString();
            reset();
            return null; // Optional AR must never stop the map, lanes or compact manoeuvre.
        }
    }

    void reset() { route = null; routeId = null; }
    private HudAr.Position position(Object position, String id) {
        if (position == null || !Boolean.TRUE.equals(api.call(position, "onRoute", id))) return null;
        Object polyline = api.call(position, "positionOnRoute", id);
        return polyline == null ? null : polylinePosition(polyline);
    }
    private HudAr.Position polylinePosition(Object position) {
        return new HudAr.Position((Integer) api.call(position, "getSegmentIndex"), (Double) api.call(position, "getSegmentPosition"));
    }
    private HudAr.Point point(Object point) {
        return new HudAr.Point((Double) api.call(point, "getLongitude"), (Double) api.call(point, "getLatitude"));
    }
}
