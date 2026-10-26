package dev.denza.hud.probe;

import dev.denza.adaslink.YandexRoute;

/** Raw Yandex observations copied before leaving the Navigator sampling thread. */
final class NativeNavigationInput {
    long rawAtElapsedMs=-1;
    long monotonicMs, routeEpoch = -1, stamp = Long.MIN_VALUE, fixAgeMs = Long.MAX_VALUE;
    String mode = "off", routeId, offlineGeometrySha256, offlinePreparationKey, locationClass, routeStatus;
    YandexRoute route;
    boolean finished, positionOnRoute, simulated;
    double scalar = Double.NaN, timeToFinishS = Double.NaN, speedLimitMps = Double.NaN;
    double lon = Double.NaN, lat = Double.NaN, rawLon = Double.NaN, rawLat = Double.NaN;
    double speedMps = Double.NaN, heading = Double.NaN;
    NativeNavigationInput copy() {
        NativeNavigationInput o = new NativeNavigationInput();
        o.rawAtElapsedMs=rawAtElapsedMs;o.monotonicMs=monotonicMs; o.routeEpoch=routeEpoch; o.stamp=stamp; o.fixAgeMs=fixAgeMs;
        o.mode=mode; o.routeId=routeId; o.offlineGeometrySha256=offlineGeometrySha256;o.offlinePreparationKey=offlinePreparationKey;o.routeStatus=routeStatus;
        o.locationClass=locationClass; o.route=route; o.finished=finished;
        o.positionOnRoute=positionOnRoute; o.simulated=simulated; o.scalar=scalar;
        o.timeToFinishS=timeToFinishS; o.speedLimitMps=speedLimitMps;
        o.lon=lon; o.lat=lat; o.rawLon=rawLon; o.rawLat=rawLat;
        o.speedMps=speedMps; o.heading=heading;
        return o;
    }
    /** Actual Yandex automotive RouteStatus names, not an inference from a new route ID. */
    boolean offRoute() {return "NOT_ON_ROUTE".equals(routeStatus)||"ROUTE_LOST".equals(routeStatus);}
}
