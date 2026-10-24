package dev.denza.hud.probe;

import com.yandex.mapkit.directions.driving.DrivingRoute;
import com.yandex.mapkit.directions.driving.DrivingSession;
import java.util.List;

/** A real SDK interface implementation. Native callbacks must never receive a Proxy. */
final class OfflineDrivingRouteListener implements DrivingSession.DrivingRouteListener {
    interface Sink {
        void routes(List<?> routes);
        void error(Object error);
    }
    private final Sink sink;
    OfflineDrivingRouteListener(Sink sink) { this.sink = sink; }
    @Override public void onDrivingRoutes(List<DrivingRoute> routes) { sink.routes(routes); }
    @Override public void onDrivingRoutesError(com.yandex.runtime.Error error) { sink.error(error); }
}
