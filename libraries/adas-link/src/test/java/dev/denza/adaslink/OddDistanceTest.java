package dev.denza.adaslink;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.denza.adaslink.AdasLinkTest.check;
import static dev.denza.adaslink.AdasLinkTest.equal;

/** The original controller's boundaries, the real ADAS list and feed integration. */
public final class OddDistanceTest {
    private static final String REAL_RESPONSE = "captures/adas-h0-listen-20261024/H0-odd-response.bin";

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        byte[] response = Files.readAllBytes(new File(root, REAL_RESPONSE).toPath());
        Set<Integer> actual = OddDistance.decodeOpenCities(response);
        equal(374, actual.size(), "actual ADAS ODD list count");
        check(actual.contains(372), "actual ADAS ODD list contains the custom stock URID 372");

        StockPath path = path(new int[]{372, 372, 999});
        equal(OddDistance.MAX, OddDistance.calculate(path, 0, 0, 100, Collections.emptySet()), "empty ODD set -> MAX");
        equal(300, OddDistance.calculate(path, 0, 0, 100, actual), "covered -> excluded: boundary distance, across a segment");
        StockPath coveredTransition = path(new int[]{372, 372, 1});
        equal(OddDistance.MAX, OddDistance.calculate(coveredTransition, 0, 0, 100, new HashSet<>(Arrays.asList(1, 372))),
                "covered -> covered -> MAX");
        equal(0, OddDistance.calculate(path, 0, 0, 100, Collections.singleton(1)), "uncovered current region -> zero");
        StockPath coveredEnd = path(new int[]{372, 372, 372});
        equal(OddDistance.MAX, OddDistance.calculate(coveredEnd, 1, 0, 300, actual), "covered at route end -> MAX");
        equal(0, OddDistance.calculate(path, 1, 0, 300, actual), "uncovered at route end -> zero");

        StockPath horizon = path(new int[]{372, 372, 372});
        horizon.segments.get(0).links.get(1).length = 2000;
        equal(OddDistance.MAX, OddDistance.calculate(horizon, 0, 0, 100, Collections.singleton(999)),
                "same-URID 2 km horizon wins even when the URID is excluded");
        StockPath immediate = path(new int[]{372, 999, 999});
        equal(75, OddDistance.calculate(immediate, 0, 0, 75, actual), "transition checked before adding next link length");

        StockPath bound = path(new int[]{0, 0, 0});
        bound.segments.get(0).links.get(0).adcode = 990001;
        bound.segments.get(0).links.get(1).adcode = 990002;
        bound.segments.get(1).links.get(0).adcode = 990003;
        Map<Long,Integer> bindings = new HashMap<>();
        bindings.put(990001L, 372); bindings.put(990002L, 373);
        new OddCoverage.Catalog(bindings).bind(bound);
        equal(372, bound.segments.get(0).links.get(0).urid, "catalog maps the Moscow bench adcode");
        equal(373, bound.segments.get(0).links.get(1).urid, "catalog is not limited to Moscow");
        equal(0, bound.segments.get(1).links.get(0).urid, "unmapped adcode remains unknown");
        check(!OddCoverage.hasCompleteUrids(bound), "partial URID data is incomplete");

        feed(response, new int[]{372, 372, 999}, 300, "feed uses actual ODD list and internal URIDs");
        feed(response, new int[]{0, 372, 999}, OddDistance.MAX, "feed never invents a finite distance with missing URIDs");
        feed(response, new int[]{372, 372, 372}, OddDistance.MAX, "fully covered route preserves the stock MAX output");
    }

    private static StockPath path(int[] urids) {
        StockPath path = new StockPath();
        int[] lengths = {100, 200, 300};
        for (int s = 0; s < 2; s++) {
            StockPath.Segment segment = new StockPath.Segment();
            segment.length = 300; segment.travelTime = 30;
            for (int l = 0; l < (s == 0 ? 2 : 1); l++) {
                int index = s == 0 ? l : 2;
                StockPath.Link link = new StockPath.Link();
                link.length = lengths[index]; link.urid = urids[index]; link.roadName = "R" + index; link.topoId = index + 1;
                link.lon = new int[]{135000000 + index * 1000, 135000000 + (index + 1) * 1000};
                link.lat = new int[]{200000000, 200000000};
                segment.links.add(link);
            }
            path.segments.add(segment);
        }
        return path;
    }

    private static RouteConverter.Converted converted(StockPath path) {
        return new RouteConverter.Converted("odd", path, 600,
                new int[]{0, 0, 1}, new int[]{0, 1, 0},
                new double[]{0, 100, 300}, new double[]{100, 300, 600},
                new double[]{0, 1.0 / 6, 0.5}, new double[]{1.0 / 6, 0.5, 1},
                new double[]{0, 300}, new double[]{300, 600}, null, new double[]{0, 600},
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), new double[]{30, 30});
    }

    private static void feed(byte[] response, int[] urids, int expected, String why) {
        ManualScheduler clock = new ManualScheduler(1_000_000);
        DryRunRecorder recorder = new DryRunRecorder(null, true);
        StockFeed.Config config = new StockFeed.Config(); config.emulatedAckDelayMs = 110;
        StockFeed feed = new StockFeed(clock, recorder, config);
        feed.start(true, true, true); feed.onNavStart();
        check(feed.onOddRegionCodes(response), why + ": response accepted");
        feed.onRouteResult(converted(path(urids)), 1); clock.drain();
        feed.onProgress(new StockFeed.Progress(0, 60, 10)); clock.drain();
        List<Map<String,Object>> odd = new ArrayList<>();
        for (Map<String,Object> row : recorder.records())
            if ("fire".equals(row.get("kind")) && ((Number) row.get("topic")).longValue() == Topics.ADV_AUTO_FUN_INFO) odd.add(row);
        check(!odd.isEmpty(), why + ": packet sent");
        if (!odd.isEmpty()) equal((long) expected, DynamicTests.field(odd.get(odd.size() - 1), "noODDRegionDist"), why);
        feed.close();
    }
}
