package dev.denza.adaslink;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Internal Amap map-package bindings used only by the ODD calculator, never serialized to ADAS. */
public final class OddCoverage {
    public static final int URID_UNKNOWN = 0;
    /** The owner's Moscow bench binding, not a Russia-wide default. */
    public static final int STOCK_MAP_ADCODE = 990001;
    public static final int STOCK_MAP_URID = 372;

    /** A complete catalog can cover any country; geographic adcode assignment is a separate input. */
    public static final class Catalog {
        private final Map<Long,Integer> byAdcode;
        public Catalog(Map<Long,Integer> bindings) {
            if (bindings == null) throw new IllegalArgumentException("ODD catalog");
            Map<Long,Integer> copy = new HashMap<>();
            for (Map.Entry<Long,Integer> entry : bindings.entrySet()) {
                Long adcode = entry.getKey(); Integer urid = entry.getValue();
                if (adcode == null || adcode <= 0 || urid == null || urid <= 0)
                    throw new IllegalArgumentException("ODD catalog binding");
                copy.put(adcode, urid);
            }
            byAdcode = Collections.unmodifiableMap(copy);
        }
        public int uridForAdcode(long adcode) {
            Integer urid = byAdcode.get(adcode);
            return urid == null ? URID_UNKNOWN : urid;
        }
        public void bind(StockPath path) {
            for (StockPath.Segment segment : path.segments) for (StockPath.Link link : segment.links)
                link.urid = uridForAdcode(link.adcode);
        }
    }

    /** Only the confirmed global.db pair, for the differential bench and controlled experiments. */
    public static Catalog stockMapCatalog() {
        return new Catalog(Collections.singletonMap((long) STOCK_MAP_ADCODE, STOCK_MAP_URID));
    }

    /** Incomplete internal coverage data cannot produce a meaningful finite ODD-exit distance. */
    public static boolean hasCompleteUrids(StockPath path) {
        if (path == null || path.linkCount() == 0) return false;
        for (StockPath.Segment segment : path.segments) for (StockPath.Link link : segment.links)
            if (link.urid == URID_UNKNOWN) return false;
        return true;
    }

    private OddCoverage() { }
}
