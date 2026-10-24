package dev.denza.adaslink;

import java.util.*;

/** Bounded forward route corridor in the native ellipsoidal 30-bit world grid, with no fixed region. */
public final class RouteVectorTilePlan {
    public static final class Config {
        public final int zoom, neighborRadius, maxTiles, maxSamples;
        public final double aheadMetres, sampleStepMetres;
        public Config() { this(15, 6000, 120, 1, 64, 2048); }
        public Config(int zoom, double aheadMetres, double step, int neighbors, int tiles, int samples) {
            if ((zoom != 14 && zoom != 15) || !Double.isFinite(aheadMetres) || aheadMetres <= 0 || aheadMetres > 50000
                    || !Double.isFinite(step) || step <= 0 || step > 1000 || neighbors < 0 || neighbors > 2
                    || tiles < 1 || tiles > 256 || samples < 1 || samples > 10000) throw new IllegalArgumentException("bounded route tile configuration required");
            this.zoom = zoom; this.aheadMetres = aheadMetres; sampleStepMetres = step;
            neighborRadius = neighbors; maxTiles = tiles; maxSamples = samples;
        }
    }
    public static final class Window {
        public final List<VectorRoadTile.TileId> required, missing;
        public final double fromMetres, toMetres, requestedToMetres;
        public final int samples;
        public final boolean truncated;
        private Window(Set<VectorRoadTile.TileId> required, Set<VectorRoadTile.TileId> observed,
                       double from, double to, double requestedTo, int samples, boolean truncated) {
            this.required = Collections.unmodifiableList(new ArrayList<>(required)); List<VectorRoadTile.TileId> miss = new ArrayList<>();
            for (VectorRoadTile.TileId id : required) if (!observed.contains(id)) miss.add(id);
            missing = Collections.unmodifiableList(miss); fromMetres = from; toMetres = to;
            requestedToMetres = requestedTo; this.samples = samples; this.truncated = truncated;
        }
    }
    public static final class Center {
        public final double lon, lat;
        private Center(double lon, double lat) { this.lon = lon; this.lat = lat; }
    }
    public final Config config;
    private final double[] lon, lat, cumulative;
    public RouteVectorTilePlan(double[] longitude, double[] latitude) { this(longitude, latitude, new Config()); }
    public RouteVectorTilePlan(double[] longitude, double[] latitude, Config config) {
        if (longitude == null || latitude == null || longitude.length != latitude.length || longitude.length < 2 || longitude.length > 200000 || config == null)
            throw new IllegalArgumentException("bounded route geometry required");
        lon = longitude.clone(); lat = latitude.clone(); cumulative = new double[lon.length]; this.config = config;
        for (int i = 0; i < lon.length; i++) {
            coordinate(lon[i], lat[i]);
            if (i > 0) cumulative[i] = cumulative[i - 1] + distance(lon[i - 1], lat[i - 1], lon[i], lat[i]);
        }
    }
    /** Progress uses Navigator's segmentIndex+fraction convention, including the final vertex. */
    public Window window(double scalar, Set<VectorRoadTile.TileId> observed) {
        if (!Double.isFinite(scalar) || scalar < 0 || scalar > lon.length - 1 || observed == null)
            throw new IllegalArgumentException("current route progress and observed tile set required");
        int index = Math.min((int) scalar, lon.length - 2); double fraction = scalar - index;
        double from = cumulative[index] + (cumulative[index + 1] - cumulative[index]) * fraction;
        double requested = Math.min(cumulative[cumulative.length - 1], from + config.aheadMetres);
        return scan(from,requested,index,config.maxTiles,config.maxSamples,observed);
    }
    /** Catalog queries over the whole selected route. Resource caps are not a moving horizon;
     * truncation is explicit and never called full-route completion. */
    public Window wholeRoute(int maximumTiles,int maximumSamples) {
        if(maximumTiles<1||maximumTiles>4096||maximumSamples<1||maximumSamples>500000)
            throw new IllegalArgumentException("bounded full-route plan required");
        return scan(0,cumulative[cumulative.length-1],0,maximumTiles,maximumSamples,Collections.emptySet());
    }
    private Window scan(double from,double requested,int index,int maximumTiles,int maximumSamples,Set<VectorRoadTile.TileId> observed) {
        double at=from,covered=from;
        LinkedHashSet<VectorRoadTile.TileId> required = new LinkedHashSet<>();
        boolean truncated = false; int samples = 0, segment = index;
        while (true) {
            if (samples >= maximumSamples) { truncated = true; break; }
            while (segment < lon.length - 2 && cumulative[segment + 1] < at) segment++;
            double length = cumulative[segment + 1] - cumulative[segment];
            double f = length == 0 ? 0 : Math.max(0, Math.min(1, (at - cumulative[segment]) / length));
            double longitude = wrap(lon[segment] + delta(lon[segment], lon[segment + 1]) * f);
            double latitude = lat[segment] + (lat[segment + 1] - lat[segment]) * f;
            VectorRoadTile.TileId center = tile(longitude, latitude, config.zoom);
            // Admit a whole neighbor group or report the sampled corridor as truncated.
            LinkedHashSet<VectorRoadTile.TileId> group = neighbors(center, config.neighborRadius);
            int extra = 0; for (VectorRoadTile.TileId id : group) if (!required.contains(id)) extra++;
            if (extra > maximumTiles - required.size()) { truncated = true; break; }
            required.addAll(group); samples++; covered = at;
            if (at >= requested) break;
            // Retain route vertices as well as regular samples so a tight bend is not skipped.
            // A fixed metre step can skip several z15 columns at high latitudes. Bound the
            // step by half the local tile width; the existing neighbor group covers edges.
            double localWidth = 40030228.88407185 * Math.max(1e-6, Math.cos(Math.toRadians(latitude))) / (1L << config.zoom);
            double next = Math.min(requested, at + Math.min(config.sampleStepMetres, Math.max(.001, localWidth / 2)));
            int vertex = segment + 1;
            while (vertex < cumulative.length && cumulative[vertex] <= at + 1e-7) vertex++;
            if (vertex < cumulative.length) next = Math.min(next, cumulative[vertex]);
            if (next <= at) { truncated = true; break; }
            at = next;
        }
        return new Window(required, observed, from, covered, requested, samples, truncated);
    }
    private static LinkedHashSet<VectorRoadTile.TileId> neighbors(VectorRoadTile.TileId center, int radius) {
        LinkedHashSet<VectorRoadTile.TileId> result = new LinkedHashSet<>(); result.add(center); int n = 1 << center.z;
        for (int distance = 1; distance <= radius; distance++) for (int dy = -distance; dy <= distance; dy++) for (int dx = -distance; dx <= distance; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != distance) continue;
            int y = center.y + dy; if (y < 0 || y >= n) continue;
            int x = (center.x + dx + n) % n; result.add(new VectorRoadTile.TileId(x, y, center.z));
        }
        return result;
    }
    public static VectorRoadTile.TileId tile(double longitude, double latitude, int zoom) {
        coordinate(longitude, latitude); if (zoom < 0 || zoom > 30) throw new IllegalArgumentException("tile zoom");
        double phi = Math.toRadians(Math.max(-89.3, Math.min(89.3, latitude))), s = Math.sin(phi), e = 0.08181919092890692;
        double q = Math.tan(Math.PI / 4 + phi / 2) * Math.pow((1 - e * s) / (1 + e * s), e / 2);
        double u = Math.max(-Math.PI, Math.min(Math.PI, Math.log(q))); long n = 1L << zoom;
        long x = (long) Math.floor(n * (longitude + 180) / 360), y = (long) Math.floor(n * (Math.PI - u) / (2 * Math.PI));
        return new VectorRoadTile.TileId((int) Math.max(0, Math.min(n - 1, x)), (int) Math.max(0, Math.min(n - 1, y)), zoom);
    }
    public static Center tileCenter(VectorRoadTile.TileId id) {
        if (id == null) throw new IllegalArgumentException("observed tile ID required");
        double n = 1L << id.z, u = Math.PI - 2 * Math.PI * (id.y + .5) / n;
        double chi = Math.PI / 2 - 2 * Math.atan(Math.exp(-u));
        double[] c = {0.003356551468879694, 6.57187271079536e-6, 1.764564338702e-8, 5.328478445e-11};
        double correction = 0; for (int i = 0; i < c.length; i++) correction += c[i] * Math.sin(2 * (i + 1) * chi);
        return new Center(360 * (id.x + .5) / n - 180, Math.toDegrees(chi + correction));
    }
    private static double delta(double a, double b) { double d = b - a; return d > 180 ? d - 360 : d < -180 ? d + 360 : d; }
    private static double wrap(double v) { return v > 180 ? v - 360 : v < -180 ? v + 360 : v; }
    private static void coordinate(double lon, double lat) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90)
            throw new IllegalArgumentException("finite WGS84 coordinate required");
    }
    private static double distance(double a, double b, double c, double d) {
        // Route distance approximation only. XYZ selection above always uses the native ellipsoid.
        double x = Math.sin(Math.toRadians(d - b) / 2), y = Math.sin(Math.toRadians(delta(a, c)) / 2);
        double h = Math.min(1, x * x + Math.cos(Math.toRadians(b)) * Math.cos(Math.toRadians(d)) * y * y);
        return 12742017.6 * Math.asin(Math.sqrt(h));
    }
}
