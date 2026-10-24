package com.yandex.mapkit.directions.driving;
/** Compile-only exact listener descriptors. NEVER package this stub. */
public interface DrivingSession {
    interface DrivingRouteListener {
        void onDrivingRoutes(java.util.List<DrivingRoute> routes);
        void onDrivingRoutesError(com.yandex.runtime.Error error);
    }
}
