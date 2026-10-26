package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replays route-specific URID annotation through the production Java ODD calculator. */
public final class OddAnnotationReplay {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            throw new IllegalArgumentException("annotation.json replay-expectations.json H0-odd-response.bin receipt.json");
        File annotationFile = new File(args[0]), expectationsFile = new File(args[1]), responseFile = new File(args[2]);
        Map<String, Object> annotation = (Map<String, Object>) Json.parse(
                new String(Files.readAllBytes(annotationFile.toPath()), StandardCharsets.UTF_8));
        Map<String, Object> expected = (Map<String, Object>) Json.parse(
                new String(Files.readAllBytes(expectationsFile.toPath()), StandardCharsets.UTF_8));
        require("denza-urid-route-annotation-v1".equals(annotation.get("schema")), "annotation schema");
        require("denza-urid-route-replay-expectations-v1".equals(expected.get("schema")), "expectations schema");
        require(annotation.get("routeId").equals(expected.get("routeId")), "route identity");
        Set<Integer> actualCities = OddDistance.decodeOpenCities(Files.readAllBytes(responseFile.toPath()));
        require(actualCities.size() == 374 && actualCities.contains(372), "actual ODD response identity");

        StockPath full = path((Map<String, Object>) annotation.get("stockPath"));
        Map<String, Object> excerptRow = (Map<String, Object>) annotation.get("mappedExcerpt");
        require(excerptRow != null, "mapped excerpt");
        StockPath excerpt = path((Map<String, Object>) excerptRow.get("stockPath"));
        checkCatalog(full);
        checkCatalog(excerpt);
        require(!OddCoverage.hasCompleteUrids(full), "the partially annotated full route must retain unknown URIDs");
        require(OddCoverage.hasCompleteUrids(excerpt), "the controlled excerpt must be complete");

        int samples = 0, matches = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> distributions = new LinkedHashMap<>();
        List<Object> failures = new ArrayList<>();
        for (Object item : (List<Object>) expected.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) item;
            String id = (String) c.get("id");
            boolean isFull = "full".equals(c.get("path"));
            StockPath path = path((Map<String, Object>) (isFull ? annotation.get("stockPath") : excerptRow.get("stockPath")));
            Set<Integer> cities = cities((List<Object>) c.get("cities"));
            if ("annotated-full-actual".equals(id) || "mapped-excerpt-actual".equals(id))
                require(cities.equals(actualCities), "controlled case must use the actual ODD response");
            Map<String, Object> overrides = (Map<String, Object>) c.get("uridOverrides");
            List<String> originalKeys = new ArrayList<>();
            if (!isFull) for (Object key : (List<Object>) excerptRow.get("linkKeys")) originalKeys.add((String) key);
            int flat = 0;
            for (StockPath.Segment segment : path.segments) for (StockPath.Link link : segment.links) {
                String key = isFull ? null : originalKeys.get(flat++);
                Object override = overrides.get(key);
                if (override != null) link.urid = ((Number) override).intValue();
            }
            require(OddCoverage.hasCompleteUrids(path) == Boolean.TRUE.equals(c.get("completeUrids")),
                    "case completeness: " + id);
            require(OddCoverage.hasCompleteUrids(path) != Boolean.TRUE.equals(c.get("productionFallbackApplied")),
                    "case fallback marker: " + id);
            int caseMatches = 0;
            Map<String, Object> distribution = new LinkedHashMap<>();
            for (Object sampleItem : (List<Object>) c.get("samples")) {
                Map<String, Object> sample = (Map<String, Object>) sampleItem;
                int s = ((Number) sample.get("segment")).intValue();
                int l = ((Number) sample.get("link")).intValue();
                int remain = ((Number) sample.get("remain")).intValue();
                int wanted = ((Number) sample.get("expectedDistance")).intValue();
                // StockFeed.oddDistance(): missing geographic annotation must never produce a finite exit distance.
                int actual = OddCoverage.hasCompleteUrids(path)
                        ? OddDistance.calculate(path, s, l, remain, cities) : OddDistance.MAX;
                samples++;
                if (actual == wanted) { matches++; caseMatches++; }
                else if (failures.size() < 20) {
                    Map<String, Object> failure = Json.object();
                    failure.put("case", id); failure.put("sample", sample); failure.put("javaDistance", (long) actual);
                    failures.add(failure);
                }
                String distanceKey = String.valueOf(actual);
                distribution.put(distanceKey, ((Number) distribution.getOrDefault(distanceKey, 0L)).longValue() + 1);
            }
            counts.put(id, caseMatches);
            distributions.put(id, distribution);
        }
        Map<String, Object> result = Json.object();
        result.put("schema", "denza-urid-annotation-java-replay-v1");
        result.put("status", failures.isEmpty() && matches == samples ? "PASS" : "FAIL");
        result.put("routeId", annotation.get("routeId"));
        result.put("samples", (long) samples); result.put("distanceMatches", (long) matches);
        result.put("cases", counts); result.put("actualDistanceDistributions", distributions);
        result.put("annotation", identity(annotationFile)); result.put("expectations", identity(expectationsFile));
        result.put("oddResponse", identity(responseFile)); result.put("failures", failures);
        result.put("vehicleOperations", 0L); result.put("publicationReady", false);
        result.put("scope", "Production Java OddDistance with route-specific geometric annotation and the actual retained ODD response. "
                + "The incomplete full route uses StockFeed's MAX fallback. The finite synthetic boundary is on a fully mapped excerpt only. "
                + "No original-Amap Yandex differential, ADAS ECU or NOA acceptance.");
        Files.write(new File(args[3]).toPath(), (Json.write(result) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println(Json.write(result));
        if (!failures.isEmpty() || matches != samples) throw new AssertionError("annotated ODD replay failed");
    }

    @SuppressWarnings("unchecked")
    private static StockPath path(Map<String, Object> source) {
        StockPath path = new StockPath();
        path.pathId = ((Number) source.get("pathId")).longValue();
        for (Object segmentItem : (List<Object>) source.get("segments")) {
            Map<String, Object> segmentRow = (Map<String, Object>) segmentItem;
            StockPath.Segment segment = new StockPath.Segment();
            segment.length = ((Number) segmentRow.get("length")).longValue();
            for (Object linkItem : (List<Object>) segmentRow.get("links")) {
                Map<String, Object> row = (Map<String, Object>) linkItem;
                StockPath.Link link = new StockPath.Link();
                link.length = ((Number) row.get("length")).intValue();
                link.adcode = ((Number) row.get("adcode")).longValue();
                link.urid = ((Number) row.get("urid")).intValue();
                segment.links.add(link);
            }
            path.segments.add(segment);
        }
        return path;
    }

    private static void checkCatalog(StockPath path) {
        int[] expected = new int[path.linkCount()];
        int index = 0;
        for (StockPath.Segment segment : path.segments) for (StockPath.Link link : segment.links)
            expected[index++] = link.urid;
        OddCoverage.stockMapCatalog().bind(path);
        index = 0;
        for (StockPath.Segment segment : path.segments) for (StockPath.Link link : segment.links) {
            require(link.urid == expected[index++], "adcode catalog must reproduce geometric annotation");
            if (link.urid != 0) require(link.adcode == 990001 && link.urid == 372, "unexpected bench binding");
        }
    }

    private static Set<Integer> cities(List<Object> rows) {
        Set<Integer> result = new LinkedHashSet<>();
        for (Object row : rows) result.add(((Number) row).intValue());
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }

    private static Map<String, Object> identity(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        Map<String, Object> result = Json.object();
        result.put("path", file.getAbsolutePath());
        result.put("sha256", Pb.hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return result;
    }

    private OddAnnotationReplay() { }
}
