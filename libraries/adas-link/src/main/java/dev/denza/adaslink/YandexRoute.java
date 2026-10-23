package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The parts of a Yandex {@code DrivingRoute} the converter reads, as immutable values copied once
 * per route (WGS-84 lon/lat, polyline positions as {@code segmentIndex + segmentPosition}).
 * {@link #fromSnapshot} reads the module's {@code denza-yandex-driving-route-v1} map.
 * A list that Navigator did not report is {@code null}; an empty list is a known empty list.
 */
public final class YandexRoute {
    public static final class Section {
        public final double begin, end;
        /** Yandex annotation action at the section's entry, null when unknown. */
        public final String action;
        /** The road entered there, null when unnamed. */
        public final String toponym;
        /** Travel time with traffic (else without), seconds; NaN when unknown. */
        public final double timeS;
        public Section(double begin, double end, String action, String toponym, double timeS) {
            this.begin = begin; this.end = end; this.action = action; this.toponym = toponym; this.timeS = timeS;
        }
    }

    public static final class Camera {
        public final double position;
        public final double speedLimitMps;
        public final List<String> tags;
        public Camera(double position, double speedLimitMps, List<String> tags) {
            this.position = position; this.speedLimitMps = speedLimitMps; this.tags = tags;
        }
    }

    public final String routeId;
    public final double[] lon, lat;
    public final List<Section> sections;
    /** One legal limit per edge in m/s, entries may be null; null when not reported. */
    public final Double[] speedLimits;
    public final double[] trafficLights;
    public final List<double[]> tunnels, ferries;
    public final double[] wayPoints;
    public final List<Camera> cameras;

    public YandexRoute(String routeId, double[] lon, double[] lat, List<Section> sections, Double[] speedLimits, double[] trafficLights,
                       List<double[]> tunnels, List<double[]> ferries, double[] wayPoints, List<Camera> cameras) {
        if (routeId == null || routeId.isEmpty()) throw new IllegalArgumentException("routeId");
        if (lon.length < 2 || lon.length != lat.length) throw new IllegalArgumentException("at least two points");
        this.routeId = routeId; this.lon = lon; this.lat = lat;
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.speedLimits = speedLimits; this.trafficLights = trafficLights;
        this.tunnels = tunnels; this.ferries = ferries; this.wayPoints = wayPoints;
        this.cameras = cameras == null ? Collections.<Camera>emptyList() : cameras;
    }

    public int edges() { return lon.length - 1; }

    // --- from the module's snapshot map ---

    @SuppressWarnings("unchecked")
    public static YandexRoute fromSnapshot(Map<String, Object> s) {
        if (!"wgs84".equals(s.getOrDefault("coordinateFrame", "wgs84"))) throw new IllegalArgumentException("route must be WGS-84");
        if (!"lon,lat".equals(s.getOrDefault("pointOrder", "lon,lat"))) throw new IllegalArgumentException("point order must be lon,lat");
        List<Object> points = (List<Object>) s.get("points");
        double[] lon = new double[points.size()], lat = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            List<Object> p = (List<Object>) points.get(i);
            lon[i] = num(p.get(0)); lat[i] = num(p.get(1));
        }
        int edges = lon.length - 1;
        List<Section> sections = new ArrayList<>();
        if (known(s, "sections")) for (Object o : (List<Object>) s.get("sections")) {
            Map<String, Object> section = (Map<String, Object>) o;
            Map<String, Object> geometry = (Map<String, Object>) section.get("geometry");
            Map<String, Object> metadata = (Map<String, Object>) section.get("metadata");
            Map<String, Object> annotation = metadata == null ? null : (Map<String, Object>) metadata.get("annotation");
            double time = Double.NaN;
            Map<String, Object> weight = metadata == null ? null : (Map<String, Object>) metadata.get("weight");
            if (weight != null) {
                Map<String, Object> t = (Map<String, Object>) (weight.get("timeWithTraffic") != null ? weight.get("timeWithTraffic") : weight.get("time"));
                if (t != null && t.get("value") instanceof Number) time = num(t.get("value"));
            }
            sections.add(new Section(position(geometry.get("begin"), edges), position(geometry.get("end"), edges),
                    annotation == null ? null : (String) annotation.get("action"),
                    annotation == null ? null : (String) annotation.get("toponym"), time));
        }
        Double[] limits = null;
        if (known(s, "speedLimits")) {
            List<Object> raw = (List<Object>) s.get("speedLimits");
            if (raw.size() != edges) throw new IllegalArgumentException("speedLimits: one per edge");
            limits = new Double[edges];
            for (int i = 0; i < edges; i++) limits[i] = raw.get(i) == null ? null : num(raw.get(i));
        }
        double[] lights = null;
        if (known(s, "trafficLights")) {
            List<Object> raw = (List<Object>) s.get("trafficLights");
            lights = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) lights[i] = position(((Map<String, Object>) raw.get(i)).get("position"), edges);
        }
        double[] wayPoints = null;
        if (known(s, "wayPoints")) {
            List<Object> raw = (List<Object>) s.get("wayPoints");
            wayPoints = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) wayPoints[i] = position(raw.get(i), edges);
        }
        List<Camera> cameras = new ArrayList<>();
        if (known(s, "events")) for (Object o : (List<Object>) s.get("events")) {
            Map<String, Object> e = (Map<String, Object>) o;
            List<String> tags = new ArrayList<>();
            if (e.get("tags") instanceof List) for (Object t : (List<Object>) e.get("tags")) tags.add(String.valueOf(t));
            Object limit = e.get("speedLimit");
            if (e.get("polylinePosition") == null) continue;
            cameras.add(new Camera(position(e.get("polylinePosition"), edges), limit instanceof Number ? num(limit) : Double.NaN, tags));
        }
        return new YandexRoute((String) s.get("routeId"), lon, lat, sections, limits, lights,
                ranges(s, "tunnels", edges), ranges(s, "ferries", edges), wayPoints, cameras);
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> ranges(Map<String, Object> s, String key, int edges) {
        if (!known(s, key)) return null;
        List<double[]> out = new ArrayList<>();
        for (Object o : (List<Object>) s.get(key)) {
            Map<String, Object> row = (Map<String, Object>) o;
            Map<String, Object> sub = row.containsKey("position") ? (Map<String, Object>) row.get("position") : row;
            double b = position(sub.get("begin"), edges), e = position(sub.get("end"), edges);
            if (!(e > b)) throw new IllegalArgumentException(key + ": empty or reversed range");
            out.add(new double[]{b, e});
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static boolean known(Map<String, Object> s, String key) {
        if (!s.containsKey(key) || s.get(key) == null) return false;
        Object availability = s.get("availability");
        return !(availability instanceof Map && "unknown".equals(((Map<String, Object>) availability).get(key)));
    }

    @SuppressWarnings("unchecked")
    static double position(Object value, int edges) {
        Map<String, Object> p = (Map<String, Object>) value;
        if (p == null) throw new IllegalArgumentException("missing polyline position");
        int index = (int) num(p.get("segmentIndex"));
        double fraction = num(p.containsKey("segmentPosition") ? p.get("segmentPosition") : p.get("fraction"));
        if (!(fraction >= 0 && fraction <= 1) || index < 0 || index > edges || (index == edges && fraction != 0))
            throw new IllegalArgumentException("polyline position outside geometry");
        return index + fraction;
    }

    static double num(Object o) {
        if (!(o instanceof Number)) throw new IllegalArgumentException("expected number, got " + o);
        double d = ((Number) o).doubleValue();
        if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite number");
        return d;
    }
}
