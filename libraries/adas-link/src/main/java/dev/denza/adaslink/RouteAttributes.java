package dev.denza.adaslink;

import java.util.Map;

/** Immutable additional attributes on the exact selected route. Acquisition (files, SDK or
 * captured tiles) is outside the converter; missing fields remain individually unknown. */
public interface RouteAttributes {
    boolean matches(YandexRoute route);
    /** Scalar boundaries at attribute changes, in segmentIndex + fraction coordinates. */
    double[] cuts();
    /** Only fields supported throughout this interval; never a midpoint-only assertion. */
    RoadNetwork.KnownAttributes attributesBetween(double begin, double end);
    Map<String, Object> summary();
}
