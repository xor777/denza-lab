package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-hash LRU for immutable decoded Yandex graph tiles, with no geographic
 * bounds or device/network dependencies. Call put on the host's background
 * worker: hashing and parsing never hold the cache lock. A lifecycle epoch fences
 * an in-flight decode across clear/release; snapshots already handed to readers
 * remain valid after eviction/release. The byte budget charges a conservative
 * decoded retention estimate, not just raw input bytes or measured VM heap.
 */
public final class GraphTileCache {
    public static final int DEFAULT_MAX_TILES = 16;
    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    public static final class Snapshot {
        public final long generation, retainedBytes, sourceBytes;
        public final boolean released;
        public final List<GraphTile> tiles;
        private Snapshot(long generation, long retained, long source, boolean released, List<GraphTile> tiles) {
            this.generation = generation; retainedBytes = retained; sourceBytes = source; this.released = released;
            this.tiles = Collections.unmodifiableList(tiles);
        }
    }

    private final int maxTiles;
    private final long maxBytes;
    private final GraphTile.Limits limits;
    interface Decoder { GraphTile decode(byte[] raw, String source, GraphTile.Limits limits); }
    private final Decoder decoder;
    private final LinkedHashMap<String, GraphTile> tiles = new LinkedHashMap<>(16, .75f, true);
    private long generation, epoch, retainedBytes, sourceBytes, accepted, duplicates, evictions, rejected;
    private boolean released;

    public GraphTileCache() { this(DEFAULT_MAX_TILES, DEFAULT_MAX_BYTES, new GraphTile.Limits()); }
    public GraphTileCache(int maxTiles, long maxBytes) { this(maxTiles, maxBytes, new GraphTile.Limits()); }
    public GraphTileCache(int maxTiles, long maxBytes, GraphTile.Limits limits) {
        this(maxTiles, maxBytes, limits, GraphTile::decode);
    }
    GraphTileCache(int maxTiles, long maxBytes, GraphTile.Limits limits, Decoder decoder) {
        if (maxTiles <= 0 || maxBytes <= 0 || limits == null || decoder == null) throw new IllegalArgumentException("positive cache bounds and parser limits required");
        this.maxTiles = maxTiles; this.maxBytes = maxBytes; this.limits = limits; this.decoder = decoder;
    }

    /**
     * Existing/new accepted tile; null if released, cleared during decoding, or
     * larger than the cache budget. Malformed/budget-invalid protobuf throws
     * TileException and leaves current contents intact. Caller-supplied arrays
     * are copied; no input buffer is retained.
     */
    public GraphTile put(byte[] input, String source) {
        if (input == null) throw new IllegalArgumentException("tile input required");
        final long startEpoch;
        synchronized (this) {
            if (released) return null;
            startEpoch = epoch;
            if (input.length > maxBytes) { rejected++; return null; }
        }
        if (input.length > limits.maxBytes) throw new GraphTile.TileException("tile byte budget exceeded");
        byte[] raw = input.clone();
        String hash = GraphTile.sha256(raw);
        synchronized (this) {
            if (released || startEpoch != epoch) return null;
            GraphTile existing = tiles.get(hash);
            if (existing != null) { duplicates++; return existing; }
        }
        GraphTile decoded;
        try { decoded = decoder.decode(raw, source, limits); }
        catch (GraphTile.TileException error) {
            synchronized (this) { rejected++; }
            throw error;
        }
        synchronized (this) {
            if (released || startEpoch != epoch) return null;
            GraphTile existing = tiles.get(hash);
            if (existing != null) { duplicates++; return existing; }
            long charge = decoded.estimatedRetainedBytes();
            if (charge > maxBytes) { rejected++; return null; }
            Iterator<Map.Entry<String, GraphTile>> it = tiles.entrySet().iterator();
            while (tiles.size() >= maxTiles || charge > maxBytes - retainedBytes) {
                GraphTile old = it.next().getValue();
                retainedBytes -= old.estimatedRetainedBytes(); sourceBytes -= old.sourceBytes(); it.remove(); evictions++;
            }
            tiles.put(hash, decoded); retainedBytes += charge; sourceBytes += decoded.sourceBytes(); accepted++; generation++;
            return decoded;
        }
    }

    public synchronized long generation() { return generation; }
    public synchronized Snapshot snapshot() {
        return new Snapshot(generation, retainedBytes, sourceBytes, released, new ArrayList<>(tiles.values()));
    }
    public synchronized void clear() {
        tiles.clear(); retainedBytes = 0; sourceBytes = 0; epoch++; generation++;
    }
    public synchronized void release() {
        if (released) return;
        released = true; tiles.clear(); retainedBytes = 0; sourceBytes = 0; epoch++; generation++;
    }

    /** Detached immutable evidence, safe to hand to a UI or JSON writer. */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "denza-yandex-graph-tile-cache-v1"); out.put("generation", generation);
        out.put("released", released); out.put("tiles", tiles.size()); out.put("maxTiles", maxTiles);
        out.put("retainedBytes", retainedBytes); out.put("sourceBytes", sourceBytes); out.put("maxBytes", maxBytes);
        out.put("byteAccounting", "conservative decoded-retention estimate; not measured VM heap; external snapshots own their lifetime");
        out.put("accepted", accepted); out.put("duplicates", duplicates); out.put("evictions", evictions); out.put("rejected", rejected);
        long edges = 0, vertices = 0, resolved = 0, unresolved = 0;
        for (GraphTile tile : tiles.values()) {
            edges += tile.edges().size(); vertices += tile.vertices().size();
            resolved += ((Number) tile.summary().get("edgesWithResolvedEndpoints")).longValue();
            unresolved += ((Number) tile.summary().get("unresolvedEndpoints")).longValue();
        }
        out.put("directedEdges", edges); out.put("vertices", vertices);
        out.put("edgesWithResolvedEndpoints", resolved); out.put("unresolvedEndpoints", unresolved);
        return Collections.unmodifiableMap(out);
    }
}
