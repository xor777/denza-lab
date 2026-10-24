package dev.denza.hud.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread diagnostic copy of the pinned DrivingRoute. No ADAS or HUD output. */
final class RouteSnapshot {
    static final String SCHEMA = "denza-yandex-driving-route-v1";
    private static final int MAX_POINTS = 200_000, MAX_BYTES = 16 * 1024 * 1024;
    private final Reflect api;

    RouteSnapshot(Reflect api) { this.api = api; }

    /** Passive polling only: capture time is not the time the SDK obtained its conditions. */
    static final class Refresh {
        static final long INITIAL_RETRY_MS = 10_000, INTERVAL_MS = 30_000;
        private String routeId;
        private long generation, revision, attemptedAt;
        private int attempts;
        synchronized long route(String id) {
            if (!java.util.Objects.equals(routeId,id)) {
                routeId=id; generation++; revision=0; attempts=0;
            }
            return generation;
        }
        synchronized boolean current(long epoch) { return generation==epoch; }
        synchronized Map<String,Object> begin(String id,long now) {
            route(id);
            if (id==null || (attempts>0 && now-attemptedAt < (attempts==1?INITIAL_RETRY_MS:INTERVAL_MS))) return null;
            attemptedAt=now; attempts++;
            Map<String,Object> capture=map();
            capture.put("routeGeneration",generation);capture.put("revision",++revision);
            capture.put("startedAtElapsedMs",now);capture.put("policy","passive_poll_10s_then_30s");
            capture.put("sourceUpdatedAtKnown",false);
            return capture;
        }
    }

    static void captured(Map<String,Object> snapshot,Map<String,Object> capture,long elapsed,long wall) {
        Map<String,Object> provenance=new LinkedHashMap<>(capture);
        provenance.put("completedAtElapsedMs",elapsed);
        snapshot.put("conditionsCapture",provenance);
        snapshot.put("capturedAtMs",wall);
    }

    /** Only the identity and complete finite geometry are required; optional failures remain visible. */
    Map<String, Object> read(Object route) {
        return new Copy(api).read(route);
    }

    /** Same-directory atomic replacement: a failed copy never truncates the last usable snapshot. */
    static void writeAtomic(File destination, Map<String, Object> snapshot) throws IOException {
        writeAtomic(destination, json(snapshot));
    }

    /** As above, for JSON already serialized off the main thread. */
    static void writeAtomic(File destination, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Route snapshot exceeds size bound");
        File temporary = File.createTempFile("route-snapshot-", ".tmp", destination.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private interface Value { Object copy(Object value, String path); }

    private static final class Copy {
        final Reflect api;
        final Map<String, Object> availability = map(), issues = map();
        int segmentCount;

        Copy(Reflect api) { this.api = api; }

        Map<String, Object> read(Object route) {
            String id = (String) string(api.call(route, "getRouteId"), "routeId");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty route identity");
            List<?> raw = list(api.call(api.call(route, "getGeometry"), "getPoints"));
            if (raw.size() < 2 || raw.size() > MAX_POINTS) throw new IllegalArgumentException("Route geometry size");
            List<Object> points = new ArrayList<>(raw.size());
            for (Object point : raw) points.add(point(point, "points"));
            segmentCount = points.size() - 1;
            Map<String, Object> out = map();
            out.put("schema", SCHEMA);
            out.put("coordinateFrame", "wgs84");
            out.put("pointOrder", "lon,lat");
            Map<String, Object> units = map();
            units.put("speed", "m/s"); units.put("distance", "m"); units.put("time", "s");
            units.put("segmentPosition", "fraction of original geometry segment [0,1]");
            out.put("units", units);
            out.put("routeId", id); out.put("points", points);
            availability.put("routeId", "known"); availability.put("points", "known");
            field(out, "", "metadata", route, "getMetadata", this::routeMetadata);
            field(out, "", "sections", route, "getSections", array(this::section));
            field(out, "", "speedLimits", route, "getSpeedLimits", alignedArray(this::number, true));
            field(out, "", "laneSigns", route, "getLaneSigns", array(this::laneSign));
            field(out, "", "trafficLights", route, "getTrafficLights", array(this::trafficLight));
            for (String key : Arrays.asList("highways", "tunnels", "tollRoads", "hdSections", "standingSegments",
                    "ferries", "fordCrossings", "zoneCrossings"))
                field(out, "", key, route, getter(key), array(this::rangeItem));
            field(out,"","ruggedRoads",route,"getRuggedRoads",array(this::ruggedRange));
            field(out, "", "jamSegments", route, "getJamSegments", alignedArray(this::jam, false));
            field(out, "", "events", route, "getEvents", array(this::event));
            for (String key : Arrays.asList("speedBumps", "pedestrianCrossings", "checkpoints", "restrictedEntries", "restrictedTurns"))
                field(out, "", key, route, getter(key), array(this::positionItem));
            field(out, "", "railwayCrossings", route, "getRailwayCrossings", array(this::railway));
            field(out, "", "directionSigns", route, "getDirectionSigns", array(this::directionSign));
            field(out, "", "zlevels", route, "getZlevels", array(this::integer));
            field(out, "", "zlevelBreakpoints", route, "getZlevelBreakpoints", array(this::position));
            field(out, "", "wayPoints", route, "getWayPoints", array(this::position));
            field(out, "", "legIndex", route, "getLegIndex", this::integer);
            field(out, "", "conditionsOutdated", route, "isAreConditionsOutdated", this::bool);
            if (!id.equals(api.call(route, "getRouteId"))) throw new IllegalStateException("Route identity changed during copy");
            out.put("availability", availability); out.put("issues", issues);
            out.put("complete", issues.isEmpty());
            out.put("notExported", Arrays.asList("roadClass", "formway", "linkType", "perLinkLaneCount", "roadOwnership",
                    "globallyMeaningfulLinkIds", "trafficLightSignalState", "liveProgress"));
            return out;
        }

        private Map<String, Object> routeMetadata(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "weight", source, "getWeight", this::weight);
            field(out, path, "tags", source, "getTags", array(this::scalar));
            field(out, path, "description", source, "getDescription", this::description);
            field(out, path, "flags", source, "getFlags", this::flags);
            field(out, path, "descriptorBase64", source, "getDescriptor", this::binary);
            field(out, path, "traitsBase64", source, "getTraits", this::binary);
            for (String key : Arrays.asList("descriptor", "traits")) {
                Object encoded = out.get(key + "Base64");
                String text = encoded instanceof String ? (String) encoded : null;
                int padding = text == null ? 0 : text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
                out.put(key + "ByteLength", text == null ? null : text.length() / 4 * 3 - padding);
                availability.put(path + "." + key + "ByteLength", availability.get(path + "." + key + "Base64"));
            }
            nullable(out, path, "uri", source, "getUri", this::string);
            return out;
        }

        private Map<String, Object> description(Object source, String path) {
            Map<String, Object> out = map();
            for (String key : Arrays.asList("explanation", "via")) nullable(out, path, key, source, getter(key), this::string);
            field(out, path, "labels", source, "getLabels", array(this::scalar));
            return out;
        }

        private Map<String, Object> flags(Object source, String path) {
            Map<String, Object> out = map();
            for (String key : Arrays.asList("blocked", "builtOffline", "deadJam", "forParking", "futureBlocked", "hasCheckpoints",
                    "hasFerries", "hasFordCrossing", "hasHighways", "hasInPoorConditionRoads", "hasNonTransactionalTolls",
                    "hasRailwayCrossing", "hasRuggedRoads", "hasTolls", "hasTunnels", "hasUnpavedRoads",
                    "hasVehicleRestrictions", "predicted", "requiresAccessPass", "scheduledDeparture"))
                field(out, path, key, source, getter(key), this::bool);
            return out;
        }

        private Map<String, Object> section(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "geometry", source, "getGeometry", this::range);
            field(out, path, "metadata", source, "getMetadata", this::sectionMetadata);
            return out;
        }

        private Map<String, Object> sectionMetadata(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "legIndex", source, "getLegIndex", this::integer);
            nullable(out, path, "schemeId", source, "getSchemeId", this::enumeration);
            field(out, path, "viaPointPositions", source, "getViaPointPositions", array(this::position));
            field(out, path, "annotation", source, "getAnnotation", this::annotation);
            field(out, path, "weight", source, "getWeight", this::weight);
            return out;
        }

        private Map<String, Object> weight(Object source, String path) {
            Map<String, Object> out = map();
            for (String key : Arrays.asList("distance", "time", "timeWithTraffic"))
                field(out, path, key, source, getter(key), this::localized);
            return out;
        }

        private Map<String, Object> localized(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "value", source, "getValue", this::number);
            field(out, path, "text", source, "getText", this::string);
            return out;
        }

        private Map<String, Object> annotation(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "action", source, "getAction", this::enumeration);
            for (String key : Arrays.asList("toponym", "descriptionText")) nullable(out, path, key, source, getter(key), this::string);
            field(out, path, "inSeriesWithNext", source, "getInSeriesWithNext", this::bool);
            nullable(out, path, "actionMetadata", source, "getActionMetadata", this::actionMetadata);
            nullable(out, path, "annotationLanes", source, "getAnnotationLanes", (value, name) -> {
                Map<String, Object> lanes = map(); field(lanes, name, "lanes", value, "getLanes", array(this::annotationLane)); return lanes;
            });
            nullable(out, path, "hdAnnotation", source, "getHdAnnotation", (value, name) -> {
                Map<String, Object> hd = map();
                nullable(hd, name, "actionArea", value, "getActionArea", this::geometryPoints);
                nullable(hd, name, "trajectory", value, "getTrajectory", this::geometryPoints);
                return hd;
            });
            return out;
        }

        private Map<String, Object> annotationLane(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "laneKind", source, "getLaneKind", this::enumeration);
            nullable(out, path, "placement", source, "getPlacement", this::enumeration);
            field(out, path, "annotated", source, "getAnnotated", this::bool);
            return out;
        }

        private Map<String, Object> actionMetadata(Object source, String path) {
            Map<String, Object> out = map();
            nullable(out, path, "leaveRoundabout", source, "getLeaveRoundaboutMetadata", (value, name) -> one(value, name, "exitNumber", this::integer));
            nullable(out, path, "turn", source, "getTurnMetadata", (value, name) -> one(value, name, "turnNumber", this::integer));
            nullable(out, path, "uturn", source, "getUturnMetadata", (value, name) -> one(value, name, "length", this::number));
            nullable(out, path, "exit", source, "getExitMetadata", (value, name) -> {
                Map<String, Object> result = map();
                nullable(result, name, "sequentialNumber", value, "getSequentialNumber", this::integer);
                nullable(result, name, "numerationProximityMask", value, "getNumerationProximityMask", this::integer);
                return result;
            });
            return out;
        }

        private Map<String, Object> one(Object source, String path, String key, Value copy) {
            Map<String, Object> out = map(); field(out, path, key, source, getter(key), copy); return out;
        }

        private Map<String, Object> laneSign(Object source, String path) {
            Map<String, Object> out = positionItem(source, path);
            nullable(out, path, "annotated", source, "getAnnotated", this::bool);
            field(out, path, "lanes", source, "getLanes", array(this::lane));
            return out;
        }

        private Map<String, Object> lane(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "laneKind", source, "getLaneKind", this::enumeration);
            field(out, path, "directions", source, "getDirections", array(this::enumeration));
            nullable(out, path, "highlightedDirection", source, "getHighlightedDirection", this::enumeration);
            return out;
        }

        private Map<String, Object> trafficLight(Object source, String path) {
            Map<String, Object> out = positionItem(source, path);
            nullable(out, path, "id", source, "getId", this::string);
            return out;
        }

        private Map<String, Object> jam(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "jamType", source, "getJamType", this::enumeration);
            field(out, path, "speed", source, "getSpeed", this::number);
            return out;
        }

        private Map<String, Object> event(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "polylinePosition", source, "getPolylinePosition", this::position);
            nullable(out, path, "eventId", source, "getEventId", this::string);
            nullable(out, path, "descriptionText", source, "getDescriptionText", this::string);
            field(out, path, "tags", source, "getTags", array(this::enumeration));
            nullable(out, path, "location", source, "getLocation", this::point);
            nullable(out, path, "speedLimit", source, "getSpeedLimit", this::number);
            nullable(out, path, "annotationSchemeId", source, "getAnnotationSchemeId", this::enumeration);
            nullable(out, path, "cameraData", source, "getCameraData", (value, name) -> {
                Map<String, Object> result = map();
                field(result, name, "inFace", value, "getInFace", this::bool);
                field(result, name, "inBack", value, "getInBack", this::bool);
                return result;
            });
            return out;
        }

        private Map<String, Object> railway(Object source, String path) {
            Map<String, Object> out = positionItem(source, path);
            field(out, path, "type", source, "getType", this::enumeration); return out;
        }

        private Map<String, Object> directionSign(Object source, String path) {
            Map<String, Object> out = positionItem(source, path);
            field(out, path, "direction", source, "getDirection", this::enumeration);
            field(out, path, "items", source, "getItems", array((value, name) -> {
                Map<String, Object> item = map();
                nullable(item, name, "icon", value, "getIcon", this::directionSignIcon);
                for (String key : Arrays.asList("exit", "road", "toponym"))
                    nullable(item, name, key, value, getter(key), (sign, signPath) -> {
                        Map<String, Object> text = map();
                        String textKey = key.equals("toponym") ? "text" : "name";
                        field(text, signPath, textKey, sign, getter(textKey), this::string);
                        nullable(text, signPath, "style", sign, "getStyle", this::directionSignStyle);
                        return text;
                    });
                return item;
            }));
            return out;
        }

        private Map<String, Object> directionSignIcon(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "image", source, "getImage", this::enumeration);
            nullable(out, path, "style", source, "getStyle", this::directionSignStyle);
            return out;
        }

        private Map<String, Object> directionSignStyle(Object source, String path) {
            Map<String, Object> out = map();
            field(out, path, "bgColor", source, "getBgColor", this::integer);
            field(out, path, "textColor", source, "getTextColor", this::integer);
            return out;
        }

        private Map<String, Object> positionItem(Object source, String path) {
            return one(source, path, "position", this::position);
        }

        private Map<String,Object> ruggedRange(Object source,String path) {
            Map<String,Object> out=rangeItem(source,path);
            field(out,path,"inPoorCondition",source,"getInPoorCondition",this::bool);
            field(out,path,"unpaved",source,"getUnpaved",this::bool);
            return out;
        }

        private Map<String, Object> rangeItem(Object source, String path) {
            return one(source, path, "position", this::range);
        }

        private Map<String, Object> range(Object source, String path) {
            Map<String, Object> begin = position(api.call(source, "getBegin"), path + ".begin");
            Map<String, Object> end = position(api.call(source, "getEnd"), path + ".end");
            if (along(begin) > along(end)) throw new IllegalArgumentException("Reversed subpolyline");
            Map<String, Object> out = map(); out.put("begin", begin); out.put("end", end); return out;
        }

        private Map<String, Object> position(Object source, String path) {
            Number index = integer(api.call(source, "getSegmentIndex"), path);
            Number fraction = number(api.call(source, "getSegmentPosition"), path);
            int i = index.intValue(); double f = fraction.doubleValue();
            if (index.longValue() != i || i < 0 || i > segmentCount || f < 0 || f > 1
                    || (i == segmentCount && f != 0))
                throw new IllegalArgumentException("Invalid polyline position");
            Map<String, Object> out = map(); out.put("segmentIndex", i); out.put("segmentPosition", f); return out;
        }

        private static double along(Map<String, Object> position) {
            return ((Number) position.get("segmentIndex")).doubleValue() + ((Number) position.get("segmentPosition")).doubleValue();
        }

        private List<Object> point(Object source, String path) {
            double lon = number(api.call(source, "getLongitude"), path).doubleValue();
            double lat = number(api.call(source, "getLatitude"), path).doubleValue();
            if (Math.abs(lon) > 180 || Math.abs(lat) > 90) throw new IllegalArgumentException("Coordinate bounds");
            return Arrays.asList(lon, lat);
        }

        private Object geometryPoints(Object source, String path) {
            return array(this::point).copy(api.call(source, "getPoints"), path);
        }

        private Value array(Value copy) { return array(copy, false); }
        private Value array(Value copy, boolean nullable) {
            return (value, path) -> {
                List<?> source = list(value);
                if (source.size() > MAX_POINTS) throw new IllegalArgumentException("List size bound");
                List<Object> out = new ArrayList<>(source.size());
                for (int i = 0; i < source.size(); i++) {
                    String itemPath = path + "[" + i + "]";
                    try {
                        Object item = source.get(i);
                        if (item == null && !nullable) throw new IllegalArgumentException("Null list item");
                        out.add(item == null ? null : copy.copy(item, itemPath));
                    } catch (RuntimeException error) { out.add(null); unknown(itemPath, error); }
                }
                return out;
            };
        }

        private Value alignedArray(Value copy, boolean nullable) {
            return (value, path) -> {
                Object result = array(copy, nullable).copy(value, path);
                if (list(value).size() != segmentCount) {
                    Map<String, Object> issue = map(); issue.put("kind", "segment_count_mismatch");
                    issue.put("expected", segmentCount); issue.put("actual", list(value).size());
                    issues.put(path + ".alignment", issue);
                }
                return result;
            };
        }

        private void field(Map<String, Object> out, String path, String key, Object source, String getter, Value copy) {
            field(out, path, key, source, getter, copy, false);
        }
        private void nullable(Map<String, Object> out, String path, String key, Object source, String getter, Value copy) {
            field(out, path, key, source, getter, copy, true);
        }
        private void field(Map<String, Object> out, String path, String key, Object source, String getter, Value copy, boolean nullable) {
            String name = path.isEmpty() ? key : path + "." + key;
            try {
                Object value = api.call(source, getter);
                if (value == null && !nullable) throw new IllegalArgumentException("Null required value");
                int before = issues.size();
                out.put(key, value == null ? null : copy.copy(value, name));
                availability.put(name, issues.size() == before ? "known" : "partial");
            } catch (RuntimeException error) { out.put(key, null); unknown(name, error); }
        }

        private void unknown(String path, RuntimeException error) {
            availability.put(path, "unknown");
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            Map<String, Object> issue = map();
            boolean missing = error.getMessage() != null && error.getMessage().startsWith("Missing method ");
            issue.put("kind", missing ? "missing_getter" : "read_or_validation_error");
            issue.put("exception", cause.getClass().getName());
            issues.put(path, issue); // No opaque object's toString(), exception messages or private handles.
        }

        private Object scalar(Object value, String path) {
            if (value instanceof Enum) return enumeration(value, path);
            if (value instanceof String) return string(value, path);
            if (value instanceof Boolean) return bool(value, path);
            return number(value, path);
        }
        private String string(Object value, String path) {
            if (!(value instanceof String)) throw new IllegalArgumentException("Expected string");
            return (String) value;
        }
        private String enumeration(Object value, String path) {
            if (!(value instanceof Enum)) throw new IllegalArgumentException("Expected enum");
            return ((Enum<?>) value).name();
        }
        private Boolean bool(Object value, String path) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
            return (Boolean) value;
        }
        private Number number(Object value, String path) {
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double) || !Double.isFinite(((Number) value).doubleValue()))
                throw new IllegalArgumentException("Expected finite number");
            return (Number) value;
        }
        private Number integer(Object value, String path) {
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
                throw new IllegalArgumentException("Expected integer");
            return (Number) value;
        }
        private Object binary(Object value, String path) {
            if (!(value instanceof byte[]) || ((byte[]) value).length > 4 * 1024 * 1024)
                throw new IllegalArgumentException("Opaque descriptor size/type");
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
    }

    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
    private static List<?> list(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected list");
        return (List<?>) value;
    }
    private static String getter(String key) { return "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1); }

    /** Dependency-free JSON for the explicit scalar tree; the Android build and host tests share it. */
    static String json(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString();
    }
    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) {
            out.append('"');
            for (char c : ((String) value).toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32 || Character.isSurrogate(c)) {
                    out.append("\\u"); String hex = Integer.toHexString(c);
                    for (int i = hex.length(); i < 4; i++) out.append('0'); out.append(hex);
                } else out.append(c);
            }
            out.append('"');
        } else if (value instanceof Boolean) out.append((Boolean) value ? "true" : "false");
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            if (!Double.isFinite(((Number) value).doubleValue())) throw new IllegalArgumentException("Nonfinite JSON number");
            out.append(value);
        } else if (value instanceof List) {
            out.append('['); boolean comma = false;
            for (Object item : (List<?>) value) { if (comma) out.append(','); append(out, item); comma = true; }
            out.append(']');
        } else if (value instanceof Map) {
            out.append('{'); boolean comma = false;
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (!(item.getKey() instanceof String)) throw new IllegalArgumentException("Non-string JSON key");
                if (comma) out.append(','); append(out, item.getKey()); out.append(':'); append(out, item.getValue()); comma = true;
            }
            out.append('}');
        } else throw new IllegalArgumentException("Opaque JSON value");
    }
}
