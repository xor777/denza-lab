package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The parts of a Yandex {@code DrivingRoute} the converter reads, copied in bounded snapshots
 * (WGS-84 lon/lat, polyline positions as {@code segmentIndex + segmentPosition}).
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
        /** Free-flow speed, km/h: weight distance over weight time without traffic; NaN when unknown. */
        public final double freeFlowKmh;
        public Section(double begin, double end, String action, String toponym, double timeS) {
            this(begin, end, action, toponym, timeS, Double.NaN);
        }
        public Section(double begin, double end, String action, String toponym, double timeS, double freeFlowKmh) {
            this.begin = begin; this.end = end; this.action = action; this.toponym = toponym; this.timeS = timeS; this.freeFlowKmh = freeFlowKmh;
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
    /** Yandex highway ranges; null when not reported. */
    public List<double[]> highways;
    /** Junction lane signs: {position, number of lanes}; null when not reported. */
    public List<double[]> laneSigns;
    /** Complete, bounded junction signs; per-lane raw enums, projections and losses are retained. */
    public List<LaneGuidance.Sign> laneGuidanceSigns;
    public LaneGuidance.Source laneGuidanceSource;
    /** Other junction positions: direction signs, restricted turns and entries; null when not reported. */
    public double[] junctions;
    /** Captured traffic inputs retained separately from legal limits and unproved Amap wire semantics. */
    public YandexTrafficContext trafficContext;
    public RoadFacilities.Source roadFacilitiesSource;
    public List<Map<String,Object>> optionalParseIssues=Collections.emptyList();
    /** Passive copy provenance, never a claim about source-condition age. Legacy models are unknown. */
    public long captureGeneration=-1, captureRevision=-1, capturedElapsedMs=-1;
    public String snapshotKey;
    private Map<String,Object> conditionsProvenance=Collections.emptyMap();
    public Map<String,Object> conditionsProvenance() { return new LinkedHashMap<>(conditionsProvenance); }

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
            double time = Double.NaN, free = Double.NaN;
            Map<String, Object> weight = metadata == null ? null : (Map<String, Object>) metadata.get("weight");
            if (weight != null) {
                Map<String, Object> t = (Map<String, Object>) (weight.get("timeWithTraffic") != null ? weight.get("timeWithTraffic") : weight.get("time"));
                if (t != null && t.get("value") instanceof Number) time = num(t.get("value"));
                Map<String, Object> d = (Map<String, Object>) weight.get("distance"), f = (Map<String, Object>) weight.get("time");
                if (d != null && f != null && d.get("value") instanceof Number && f.get("value") instanceof Number && num(f.get("value")) > 0)
                    free = num(d.get("value")) / num(f.get("value")) * 3.6;
            }
            sections.add(new Section(position(geometry.get("begin"), edges), position(geometry.get("end"), edges),
                    annotation == null ? null : (String) annotation.get("action"),
                    annotation == null ? null : (String) annotation.get("toponym"), time, free));
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
        List<Map<String,Object>> parseIssues=new ArrayList<>();
        List<Camera> cameras = new ArrayList<>();
        if (known(s, "events") && s.get("events") instanceof List) {
          int index=0;
          for (Object o : (List<Object>) s.get("events")) {
            if(!(o instanceof Map)) { optionalIssue(parseIssues,"events",index++,"not_object");continue; }
            Map<String, Object> e = (Map<String, Object>) o;
            List<String> tags = new ArrayList<>();
            if (e.get("tags") instanceof List) for (Object t : (List<Object>) e.get("tags")) tags.add(String.valueOf(t));
            Object limit = e.get("speedLimit");
            try {
                double at=position(e.get("polylinePosition"),edges);
                double speed=limit instanceof Number && Double.isFinite(((Number)limit).doubleValue())?num(limit):Double.NaN;
                cameras.add(new Camera(at,speed,tags));
            } catch(RuntimeException malformed) { optionalIssue(parseIssues,"events",index,"invalid_position"); }
            index++;
          }
        }
        YandexRoute route = new YandexRoute((String) s.get("routeId"), lon, lat, sections, limits, lights,
                ranges(s, "tunnels", edges), ranges(s, "ferries", edges), wayPoints, cameras);
        route.highways = ranges(s, "highways", edges);
        List<Double> junctions = new ArrayList<>();
        for (String key : new String[]{"directionSigns", "restrictedTurns", "restrictedEntries"}) {
            if (!known(s, key) || !(s.get(key) instanceof List)) continue;
            int index=0;
            for (Object o : (List<Object>) s.get(key)) {
                try {
                    if(!(o instanceof Map))throw new IllegalArgumentException();
                    junctions.add(position(((Map<String,Object>)o).get("position"),edges));
                } catch(RuntimeException malformed) { optionalIssue(parseIssues,key,index,"invalid_position_or_object"); }
                index++;
            }
        }
        if (!junctions.isEmpty()) {
            route.junctions = new double[junctions.size()];
            for (int i = 0; i < junctions.size(); i++) route.junctions[i] = junctions.get(i);
        }
        route.laneGuidanceSource = LaneGuidance.readSource(s, edges);
        route.laneGuidanceSigns = route.laneGuidanceSource.signs;
        if (known(s,"laneSigns")) {
            route.laneSigns = new ArrayList<>();
            for (LaneGuidance.Sign sign : route.laneGuidanceSigns)
                if (sign.supported()) route.laneSigns.add(new double[]{sign.scalar,sign.back.length});
        }
        route.trafficContext = YandexTrafficContext.fromSnapshot(s, edges);
        route.roadFacilitiesSource = RoadFacilities.readSource(s,edges);
        route.optionalParseIssues=Collections.unmodifiableList(parseIssues);
        route.readProvenance(s);
        return route;
    }

    private void readProvenance(Map<String,Object> snapshot) {
        Map<String,Object> values=new LinkedHashMap<>(snapshot);
        values.remove("conditionsCapture");values.remove("capturedAtMs");
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(Json.write(values).getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            snapshotKey=key.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        Map<String,Object> p=Json.object();p.put("routeId",routeId);p.put("snapshotKey",snapshotKey);
        p.put("sourceUpdatedAtKnown",false);p.put("sourceAgeKnown",false);
        p.put("conditionsOutdated",snapshot.get("conditionsOutdated"));
        Object capture=snapshot.get("conditionsCapture");
        if(capture instanceof Map) {
            Map<?,?> c=(Map<?,?>)capture;
            captureGeneration=nonnegative(c.get("routeGeneration"));captureRevision=nonnegative(c.get("revision"));
            capturedElapsedMs=nonnegative(c.get("completedAtElapsedMs"));
            p.put("capture",new LinkedHashMap<>((Map<?,?>)capture));
        }
        p.put("capturedAtMs",snapshot.get("capturedAtMs"));
        Map<String,Object> availability=Json.object();Object raw=snapshot.get("availability");
        for(String key:new String[]{"conditionsOutdated","speedLimits","laneSigns","events","jamSegments","sections"})
            availability.put(key,raw instanceof Map?((Map<?,?>)raw).get(key):snapshot.get(key)==null?"unknown":"reported");
        p.put("optionalParseIssues",optionalParseIssues);
        p.put("availability",availability);conditionsProvenance=Collections.unmodifiableMap(p);
    }

    private static void optionalIssue(List<Map<String,Object>> issues,String field,int index,String reason) {
        if(issues.size()>=64)return;
        Map<String,Object> item=Json.object();item.put("field",field);item.put("index",index);item.put("reason",reason);issues.add(item);
    }

    private static long nonnegative(Object value) {
        if(!(value instanceof Number))return -1;
        double d=((Number)value).doubleValue();long n=((Number)value).longValue();
        return Double.isFinite(d)&&d==n&&n>=0?n:-1;
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
