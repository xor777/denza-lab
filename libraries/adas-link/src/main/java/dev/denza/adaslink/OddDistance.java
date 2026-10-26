package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stock {@code DnpOddDistanceController} distance calculation over our internal PathInfo model.
 * URIDs are deliberately not part of the ADAS route wire format: they exist only in this model.
 */
public final class OddDistance {
    private static final Pb.Msg ODD_REGION_CODE_INFO = new Pb.Msg("ODDRegionCodeInfo",
            Pb.f(1, Pb.INT32, "oDDRegionCodeValue"));
    private static final Pb.Msg ODD_REGION_CODE_RESP = new Pb.Msg("ODDRegionCodeResp",
            Pb.list(1, "oDDRegionCodeRspInfo", ODD_REGION_CODE_INFO));

    public static final int MAX = Integer.MAX_VALUE;
    /** The stock's finite same-city look-ahead; beyond it the controller reports MAX. */
    public static final int LOOK_AHEAD_M = 2000;

    /**
     * Port of {@code k.h.e0.c.f.e()/d()}. A transition is checked before adding the next link's
     * length. Thus covered -> uncovered returns the remaining distance at the boundary,
     * covered -> covered returns MAX, and an uncovered current region returns zero at a transition
     * or at the route end. Java int overflow is intentional stock behavior.
     */
    public static int calculate(StockPath path, int segmentIndex, int linkIndex, int linkRemainM, Set<Integer> openCities) {
        if (openCities == null || openCities.isEmpty()) return MAX;
        if (path == null) throw new IllegalArgumentException("ODD path");
        List<StockPath.Segment> segments = path.segments;
        if (segmentIndex < 0 || segmentIndex >= segments.size()) throw new IllegalArgumentException("ODD segment");
        StockPath.Segment currentSegment = segments.get(segmentIndex);
        if (linkIndex < 0 || linkIndex >= currentSegment.links.size()) throw new IllegalArgumentException("ODD link");
        int current = currentSegment.links.get(linkIndex).urid;
        int distance = linkRemainM;
        for (int s = segmentIndex; s < segments.size(); s++) {
            List<StockPath.Link> links = segments.get(s).links;
            for (int l = s == segmentIndex ? linkIndex + 1 : 0; l < links.size(); l++) {
                StockPath.Link next = links.get(l);
                if (next.urid != current) {
                    if (!openCities.contains(current)) return 0;
                    return openCities.contains(next.urid) ? MAX : distance;
                }
                distance += next.length;
                if (distance > LOOK_AHEAD_M) return MAX;
            }
        }
        return openCities.contains(current) ? MAX : 0;
    }

    /** Decode the actual service-13/method-0x0002 response; no cloud cache or synthetic city list. */
    @SuppressWarnings("unchecked")
    public static Set<Integer> decodeOpenCities(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("ODD response");
        Map<String, Object> decoded = ODD_REGION_CODE_RESP.decode(payload);
        Object rows = decoded.get("oDDRegionCodeRspInfo");
        Set<Integer> result = new LinkedHashSet<>();
        if (rows == null) return result;
        for (Map<String, Object> row : (ArrayList<Map<String, Object>>) rows) {
            Object value = row.get("oDDRegionCodeValue");
            result.add(value == null ? 0 : ((Number) value).intValue());
        }
        return result;
    }

    private OddDistance() { }
}
