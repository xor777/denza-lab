package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replays the original Amap calculator's captured samples through the production Java port. */
public final class OddDistanceReplay {
    private static final long TOPIC = 1125929972105220L;
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "factory-observed-city-list", "factory-native-cities", "factory-empty-city-list",
            "factory-excluded-cities", "synthetic-covered-to-excluded", "synthetic-excluded-to-covered"));

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("events.jsonl receipt.json");
        File events = new File(args[0]);
        boolean ready = false, done = false;
        int links = 0, samples = 0, distanceMatches = 0, byteMatches = 0;
        Map<String, Object> route = null;
        Map<String, StockPath> paths = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(events.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            Map<String, Object> envelope = (Map<String, Object>) Json.parse(line);
            Map<String, Object> p = (Map<String, Object>) envelope.get("payload");
            if (p == null) continue;
            String kind = (String) p.get("kind");
            if ("odd_ready".equals(kind)) ready = Boolean.TRUE.equals(p.get("emulatorOnly"));
            else if ("odd_done".equals(kind)) {
                done = true;
                links = ((Number) p.get("links")).intValue();
            } else if ("odd_error".equals(kind)) failure(failures, lineNumber, "oracle error", p);
            else if ("odd_factory_route".equals(kind)) {
                route = p;
            } else if ("odd_case".equals(kind)) {
                if (route == null) throw new IllegalArgumentException("case before route");
                String id = (String) p.get("id");
                paths.put(id, path(route, p));
                counts.put(id, 0);
            } else if ("odd_sample".equals(kind)) {
                samples++;
                String id = (String) p.get("caseId");
                StockPath path = paths.get(id);
                if (path == null) throw new IllegalArgumentException("sample before case at " + lineNumber);
                Set<Integer> cities = cityLists.get(id);
                int actual = OddDistance.calculate(path, ((Number) p.get("segment")).intValue(),
                        ((Number) p.get("link")).intValue(), ((Number) p.get("remain")).intValue(), cities);
                List<Object> packets = (List<Object>) p.get("packets");
                List<Object> oddPackets = new ArrayList<>();
                if (packets != null) for (Object item : packets) {
                    Map<String, Object> packet = (Map<String, Object>) item;
                    if (Long.parseLong((String) packet.get("topic")) == TOPIC) oddPackets.add(packet);
                }
                boolean distanceOk = oddPackets.size() == 1;
                boolean bytesOk = distanceOk;
                if (distanceOk) {
                    Map<String, Object> packet = (Map<String, Object>) oddPackets.get(0);
                    byte[] expected = StockMessages.noOddRegionDistance(actual);
                    byte[] observed = Base64.getDecoder().decode((String) packet.get("payload"));
                    bytesOk = distanceOk && Arrays.equals(expected, observed);
                    if (distanceOk) {
                        Map<String, Object> decoded = Pb.ADV_AUTO_FUN_INFO_NOTIFY.decode(observed);
                        Map<String, Object> inner = (Map<String, Object>) decoded.get("advAutoFunInfo");
                        Object wireDistance = inner == null ? null : inner.get("noODDRegionDist");
                        distanceOk = inner != null && (wireDistance == null ? 0 : ((Number) wireDistance).intValue()) == actual;
                    }
                }
                if (p.get("lastDistance") != null)
                    distanceOk = distanceOk && ((Number) p.get("lastDistance")).intValue() == actual;
                if (distanceOk) distanceMatches++;
                if (bytesOk) byteMatches++;
                counts.put(id, counts.get(id) + 1);
                if (!distanceOk || !bytesOk) {
                    Map<String, Object> failure = Json.object();
                    failure.put("line", (long) lineNumber); failure.put("case", id);
                    failure.put("javaDistance", (long) actual); failure.put("observed", p);
                    failure(failures, lineNumber, "sample mismatch", failure);
                }
            }
        }
        if (!ready || !done || route == null || !counts.keySet().containsAll(REQUIRED) || links == 0)
            failure(failures, 0, "incomplete oracle run", null);
        Map<String, Object> result = Json.object();
        result.put("schema", "denza-odd-java-replay-v1");
        result.put("status", failures.isEmpty() ? "PASS" : "FAIL");
        result.put("samples", (long) samples); result.put("links", (long) links);
        result.put("distanceMatches", (long) distanceMatches); result.put("byteMatches", (long) byteMatches);
        result.put("cases", counts); result.put("failures", failures);
        result.put("oracle", identity(events));
        result.put("vehicleOperations", 0L); result.put("publicationReady", false);
        result.put("scope", "Production Java OddDistance and StockMessages replayed against original Amap calculator packets. "
                + "Native factory Beijing links and controlled ODD inputs; no ECU or NOA acceptance.");
        Files.write(new File(args[1]).toPath(), (Json.write(result) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println(Json.write(result));
        if (!failures.isEmpty()) throw new AssertionError("ODD Java replay failed: " + failures.size());
    }

    private static final Map<String, Set<Integer>> cityLists = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    private static StockPath path(Map<String, Object> route, Map<String, Object> c) {
        StockPath path = new StockPath();
        Map<String, Object> overrides = (Map<String, Object>) c.get("overrides");
        Set<Integer> cities = new LinkedHashSet<>();
        for (Object city : (List<Object>) c.get("cities")) cities.add(((Number) city).intValue());
        cityLists.put((String) c.get("id"), cities);
        for (Object so : (List<Object>) route.get("segments")) {
            StockPath.Segment segment = new StockPath.Segment();
            for (Object lo : (List<Object>) so) {
                Map<String, Object> row = (Map<String, Object>) lo;
                StockPath.Link link = new StockPath.Link();
                link.length = ((Number) row.get("length")).intValue();
                Object override = overrides.get(row.get("key"));
                link.urid = ((Number) (override == null ? row.get("urid") : override)).intValue();
                segment.links.add(link);
            }
            path.segments.add(segment);
        }
        return path;
    }

    private static void failure(List<Object> failures, int line, String error, Object detail) {
        if (failures.size() >= 20) return;
        Map<String, Object> row = Json.object();
        row.put("line", (long) line); row.put("error", error); row.put("detail", detail);
        failures.add(row);
    }

    private static Map<String, Object> identity(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        Map<String, Object> result = Json.object();
        result.put("path", file.getAbsolutePath()); result.put("sha256", Pb.hex(hash));
        return result;
    }

    private OddDistanceReplay() { }
}
