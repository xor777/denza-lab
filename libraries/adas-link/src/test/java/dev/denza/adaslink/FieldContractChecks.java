package dev.denza.adaslink;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * research/adas-bridge/field-contract-20261024.json against the encoders. Every field the feed can
 * put on the wire (each Pb field reachable from an outbound topic, each key of the route JSON and of
 * the 3 km lane beans) has a contract row; every row names fields and code that still exist; a row
 * that differs from the stock is DECIDED only with a reason and evidence, else OPEN with an
 * experiment. Each check returns its problems, empty when the contract holds. Plain Java 8 (build.py
 * and the Python oracle compile this directory without JUnit); oracle.FieldContractTest runs it
 * under Gradle. Needs no capture corpus.
 */
public final class FieldContractChecks {
    public static final String CONTRACT = "research/adas-bridge/field-contract-20261024.json";

    private final File root;
    private final Map<String, Object> contract;

    @SuppressWarnings("unchecked")
    public FieldContractChecks(File root) throws IOException {
        this.root = root;
        String text = new String(Files.readAllBytes(new File(root, CONTRACT).toPath()), StandardCharsets.UTF_8);
        this.contract = (Map<String, Object>) Json.parse(text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows() { return (List<Map<String, Object>>) (List<?>) contract.get("rows"); }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) { return o == null ? new ArrayList<String>() : (List<String>) (List<?>) o; }

    /** Topic name -> topic for every event the feed emits (the inbound selection answer excluded). */
    static Map<String, Long> outboundTopics() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            for (Field f : Topics.class.getFields())
                if (f.getType() == long.class && Modifier.isStatic(f.getModifiers()) && !f.getName().equals("PATH_SELECT_CONFIRM")) {
                    long topic = f.getLong(null);
                    if (Topics.schema(topic) != null) out.put(Topics.name(topic), topic);
                }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        return out;
    }

    static void walk(Pb.Msg msg, Set<String> out, Set<Pb.Msg> seen) {
        seen.add(msg);
        for (Pb.Field f : msg.fields) {
            if (f.type == Pb.MESSAGE) { walk(f.message, out, seen); continue; }
            out.add("pb:" + msg.name + "." + f.name);
            if (f.type == Pb.MESSAGE_LIST) walk(f.message, out, seen);
        }
    }

    @SuppressWarnings("unchecked")
    static void keys(String path, Object value, Set<String> out) {
        if (value instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) keys(path + "." + e.getKey(), e.getValue(), out);
        } else if (value instanceof List && !((List<?>) value).isEmpty() && ((List<?>) value).get(0) instanceof Map) {
            for (Object item : (List<?>) value) keys(path + "[]", item, out);
        } else out.add("json:" + path);
    }

    /** A path that reaches every branch of the serializer: groups or none, two segments, points, a light. */
    static StockPath samplePath(boolean groups) {
        StockPath p = new StockPath();
        p.pathId = 100002;
        for (int s = 0; s < 2; s++) {
            StockPath.Segment seg = new StockPath.Segment();
            seg.length = 120; seg.travelTime = 10; seg.mainAction = s == 0 ? 2 : 0; seg.assistantAction = s == 0 ? 0 : 36;
            StockPath.Link l = new StockPath.Link();
            l.topoId = 7 + s; l.length = 120; l.roadName = "Road";
            l.lon = new int[]{135000000 + s, 135000100 + s}; l.lat = new int[]{199800000, 199800100};
            seg.links.add(l);
            p.segments.add(seg);
        }
        if (groups) p.groups.add(new Object[]{0, 2, "Road"});
        p.trafficLights.add(new double[]{37.5, 55.5});
        return p;
    }

    /** Every key path the route serializer and the 3 km lane beans can write. */
    static Set<String> jsonKeys() {
        Set<String> out = new TreeSet<>();
        for (boolean groups : new boolean[]{true, false}) {
            String payload = StockRouteJson.messageSend(samplePath(groups), 1, StockRouteJson.Strings.EN);
            keys("MessageSend", Json.parse(payload), out);
            for (String chunk : StockRouteJson.chunks(payload, 1, 1L)) keys("NaviDataDetail", Json.parse(chunk), out);
        }
        Map<String, Object> s = LaneGuidanceTest.snapshot(Arrays.<Object>asList(
                LaneGuidanceTest.sign(4, LaneGuidanceTest.lane("PLAIN_LANE", "LEFT90", "LEFT90", "STRAIGHT_AHEAD"))), .001);
        String lanes = LaneGuidanceTest.convert(s).laneGuidance.aheadJson(0, 100002, 1L);
        if (lanes.length() <= 2) throw new IllegalStateException("lane bean sample is empty: " + lanes);
        keys("Lanes3km", Json.parse(lanes), out);
        return out;
    }

    /** Every pb:/json:/topic: key an encoder can write; fails on a Pb message reachable from no topic. */
    static Set<String> encoderKeys() {
        Set<String> out = new TreeSet<>();
        Set<Pb.Msg> seen = new HashSet<>();
        for (long topic : outboundTopics().values()) walk(Topics.schema(topic), out, seen);
        walk(Pb.PATH_SELECT_CONFIRM_NOTIFY, new TreeSet<String>(), seen);
        try {
            for (Field f : Pb.class.getFields())
                if (f.getType() == Pb.Msg.class && !seen.contains(f.get(null)))
                    throw new IllegalStateException("Pb." + f.getName() + " is reachable from no topic");
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        out.addAll(jsonKeys());
        for (String name : outboundTopics().keySet()) out.add("topic:" + name);
        return out;
    }

    public List<String> fieldsWithoutRow() {
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> r : rows()) covered.addAll(strings(r.get("wire")));
        List<String> missing = new ArrayList<>();
        for (String key : encoderKeys()) if (!covered.contains(key)) missing.add(key);
        return missing;
    }

    public List<String> rowsNamingUnknownFields() {
        Set<String> known = encoderKeys();
        List<String> stale = new ArrayList<>();
        for (Map<String, Object> r : rows())
            for (String key : strings(r.get("wire")))
                if (!key.startsWith("side:") && !known.contains(key)) stale.add(r.get("id") + " -> " + key);
        return stale;
    }

    @SuppressWarnings("unchecked")
    public List<String> brokenCodeLocations() throws IOException {
        Map<String, Object> prefixes = (Map<String, Object>) contract.get("pathPrefixes");
        List<String> broken = new ArrayList<>();
        for (Map<String, Object> r : rows()) {
            List<Map<String, Object>> code = (List<Map<String, Object>>) (List<?>) r.get("code");
            Map<String, Object> prov = (Map<String, Object>) r.get("provenance");
            if ((code == null || code.isEmpty()) && !"NOT_SENT".equals(prov.get("class"))) broken.add(r.get("id") + ": no code location");
            if (code == null) continue;
            for (Map<String, Object> at : code) {
                String file = (String) at.get("file"), anchor = (String) at.get("anchor");
                int line = ((Number) at.get("line")).intValue(), slash = file.indexOf('/');
                Object full = slash > 0 ? prefixes.get(file.substring(0, slash)) : null;
                File f = new File(root, full == null ? file : full + file.substring(slash + 1));
                if (!f.isFile()) { broken.add(r.get("id") + ": " + file + " missing"); continue; }
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (anchor == null || anchor.length() < 10) broken.add(r.get("id") + ": anchor too short in " + file);
                else if (!String.join("\n", lines).contains(anchor)) broken.add(r.get("id") + ": " + file + " no longer contains «" + anchor + "»");
                if (line < 1 || line > lines.size()) broken.add(r.get("id") + ": " + file + ":" + line + " outside the file");
            }
        }
        return broken;
    }

    @SuppressWarnings("unchecked")
    public List<String> decisionsWithoutEvidence() {
        Map<String, Object> experiments = (Map<String, Object>) contract.get("experiments");
        List<Map<String, Object>> contra = (List<Map<String, Object>>) (List<?>) contract.get("contradictions");
        Set<String> ids = new HashSet<>(), contradictions = new HashSet<>();
        for (Map<String, Object> x : contra) contradictions.add((String) x.get("id"));
        Set<String> marks = new HashSet<>(Arrays.asList("MATCHES_STOCK", "DIFFERS_FROM_STOCK", "STOCK_UNKNOWN"));
        List<String> bad = new ArrayList<>();
        for (Map<String, Object> r : rows()) {
            String id = (String) r.get("id"), mark = (String) r.get("mark"), status = (String) r.get("status");
            if (!ids.add(id)) bad.add(id + ": duplicate id");
            if (!marks.contains(mark)) bad.add(id + ": mark " + mark);
            Map<String, Object> risk = (Map<String, Object>) r.get("noaRisk");
            if (risk == null || !Arrays.asList("high", "medium", "low").contains(risk.get("level"))) bad.add(id + ": noaRisk");
            if ("DECIDED".equals(status)) {
                Map<String, Object> d = (Map<String, Object>) r.get("decision");
                String reason = d == null ? null : (String) d.get("reason");
                if (reason == null || reason.trim().isEmpty()) bad.add(id + ": DECIDED without a reason");
                if ("DIFFERS_FROM_STOCK".equals(mark) && (d == null || strings(d.get("evidence")).isEmpty()))
                    bad.add(id + ": DIFFERS_FROM_STOCK DECIDED without evidence");
            } else if ("OPEN".equals(status)) {
                if (!experiments.containsKey(r.get("experiment"))) bad.add(id + ": OPEN without a known experiment");
            } else bad.add(id + ": status " + status);
            for (String x : strings(r.get("contradictions"))) if (!contradictions.contains(x)) bad.add(id + ": unknown contradiction " + x);
        }
        for (Map<String, Object> x : contra)
            for (String row : strings(x.get("rows"))) if (!ids.contains(row)) bad.add(x.get("id") + ": unknown row " + row);
        return bad;
    }

    public static void main(String[] args) throws IOException {
        FieldContractChecks c = new FieldContractChecks(new File(args.length > 0 ? args[0] : "."));
        List<String> all = new ArrayList<>();
        all.addAll(c.fieldsWithoutRow());
        all.addAll(c.rowsNamingUnknownFields());
        all.addAll(c.brokenCodeLocations());
        all.addAll(c.decisionsWithoutEvidence());
        for (String s : all) System.out.println("FAIL " + s);
        System.out.println("field contract: " + all.size() + " problems");
        if (!all.isEmpty()) System.exit(1);
    }
}
