package dev.denza.adaslink;

import java.util.*;

/** Geography-independent, worker-decoded LRU. One latest content per observed TileId, immutable snapshots. */
public final class VectorRoadTileCache {
    public static final int DEFAULT_MAX_TILES = 16;
    public static final long DEFAULT_MAX_BYTES = 16L << 20;
    public static final class Snapshot {
        public final long generation, epoch, retainedBytes, sourceBytes;
        public final boolean released;
        public final List<VectorRoadTile> tiles;
        private Snapshot(long g, long e, long retained, long source, boolean released, List<VectorRoadTile> tiles) {
            generation = g; epoch = e; retainedBytes = retained; sourceBytes = source; this.released = released;
            this.tiles = Collections.unmodifiableList(tiles);
        }
    }
    interface Decoder { VectorRoadTile decode(byte[] raw, VectorRoadTile.TileId wrapper, VectorRoadTile.TileId render,
                                             VectorRoadTile.TileId source, VectorRoadTile.Limits limits); }
    private final int maxTiles; private final long maxBytes; private final VectorRoadTile.Limits limits; private final Decoder decoder;
    private final LinkedHashMap<VectorRoadTile.TileId, VectorRoadTile> tiles = new LinkedHashMap<>(16, .75f, true);
    // Native slot polling can deliver different TileIds out of sequence. Track each
    // recent TileId separately; retire bounded history behind a conservative floor.
    private final LinkedHashMap<VectorRoadTile.TileId, Long> sequences = new LinkedHashMap<>();
    private final int maxSequenceEntries;
    private long generation, epoch, retainedBytes, sourceBytes, accepted, duplicates, replacements, evictions, rejected;
    private long retirementFloor = -1, highestSequenceSeen = -1, staleSequenceRejected, retiredSequenceRejected,
            sequenceTrackingRetirements, unsequencedRejected, staleCompletions;
    private boolean sequencedMode;
    private boolean released;
    public VectorRoadTileCache() { this(DEFAULT_MAX_TILES, DEFAULT_MAX_BYTES); }
    public VectorRoadTileCache(int maxTiles, long maxBytes) { this(maxTiles, maxBytes, new VectorRoadTile.Limits()); }
    public VectorRoadTileCache(int maxTiles, long maxBytes, VectorRoadTile.Limits limits) {
        this(maxTiles, maxBytes, limits, VectorRoadTile::decode);
    }
    VectorRoadTileCache(int maxTiles, long maxBytes, VectorRoadTile.Limits limits, Decoder decoder) {
        if (maxTiles <= 0 || maxBytes <= 0 || limits == null || decoder == null) throw new IllegalArgumentException("positive cache bounds required");
        this.maxTiles = maxTiles; this.maxBytes = maxBytes; this.limits = limits; this.decoder = decoder;
        maxSequenceEntries = (int) Math.max(16, Math.min(4096L, (long) maxTiles * 4));
    }
    /**
     * Legacy worker input without a native sequence. Once this cache has admitted
     * a sequenced packet, unsequenced calls return null to prevent an ordering bypass.
     * No lock is held while copying, hashing or decoding.
     */
    public VectorRoadTile put(byte[] input, VectorRoadTile.TileId wrapper, VectorRoadTile.TileId render, VectorRoadTile.TileId source) {
        return putInternal(input, wrapper, render, source, null);
    }
    /**
     * Native process-lifetime sequence, nonnegative and never reset by clear().
     * Different TileIds may arrive out of order. Recent per-TileId watermarks
     * reject stale replacements; untracked IDs at/below the retirement floor
     * are conservatively rejected with an explicit counter, even if useful.
     */
    public VectorRoadTile put(byte[] input, VectorRoadTile.TileId wrapper, VectorRoadTile.TileId render,
                              VectorRoadTile.TileId source, long sequence) {
        if (sequence < 0) throw new VectorRoadTile.TileException("nonnegative native process-lifetime sequence required");
        return putInternal(input, wrapper, render, source, sequence);
    }
    private VectorRoadTile putInternal(byte[] input, VectorRoadTile.TileId wrapper, VectorRoadTile.TileId render,
                                       VectorRoadTile.TileId source, Long sequence) {
        VectorRoadTile.contexts(wrapper, render, source);
        if (input == null) throw new VectorRoadTile.TileException("tile input required");
        final long startEpoch;
        synchronized (this) {
            if (released) return null;
            startEpoch = epoch;
            if (input.length > maxBytes) { rejected++; return null; }
            if (sequence == null) {
                if (sequencedMode) { unsequencedRejected++; rejected++; return null; }
            } else {
                Long prior = sequences.get(wrapper);
                if (prior != null && sequence <= prior) { staleSequenceRejected++; rejected++; return null; }
                if (prior == null && sequence <= retirementFloor) { retiredSequenceRejected++; rejected++; return null; }
                sequencedMode = true; highestSequenceSeen = Math.max(highestSequenceSeen, sequence);
                sequences.remove(wrapper); sequences.put(wrapper, sequence);
                if (sequences.size() > maxSequenceEntries) {
                    Iterator<Map.Entry<VectorRoadTile.TileId, Long>> retired = sequences.entrySet().iterator();
                    retirementFloor = Math.max(retirementFloor, retired.next().getValue()); retired.remove(); sequenceTrackingRetirements++;
                }
            }
        }
        if (input.length > limits.maxBytes) throw new VectorRoadTile.TileException("tile byte budget exceeded");
        byte[] raw = input.clone(); String hash = VectorRoadTile.digest(raw);
        synchronized (this) {
            if (!current(startEpoch, wrapper, sequence)) return null;
            VectorRoadTile old = tiles.get(wrapper);
            if (old != null && old.sha256().equals(hash)) { duplicates++; return old; }
        }
        VectorRoadTile decoded;
        try { decoded = decoder.decode(raw, wrapper, render, source, limits); }
        catch (VectorRoadTile.TileException e) { synchronized (this) { if (epoch == startEpoch) rejected++; } throw e; }
        synchronized (this) {
            if (!current(startEpoch, wrapper, sequence)) return null;
            VectorRoadTile old = tiles.get(wrapper);
            if (old != null && old.sha256().equals(hash)) { duplicates++; return old; }
            long charge = decoded.estimatedRetainedBytes();
            if (charge > maxBytes) { rejected++; return null; }
            if (old != null) { tiles.remove(wrapper); removeCharge(old); replacements++; }
            Iterator<VectorRoadTile> it = tiles.values().iterator();
            while (tiles.size() >= maxTiles || charge > maxBytes - retainedBytes) {
                VectorRoadTile gone = it.next(); removeCharge(gone); it.remove(); evictions++;
            }
            tiles.put(wrapper, decoded); retainedBytes += charge; sourceBytes += decoded.sourceBytes(); accepted++; generation++;
            return decoded;
        }
    }
    /** Called only under the cache lock; old same-tile decode completions cannot overwrite a newer admission. */
    private boolean current(long startEpoch, VectorRoadTile.TileId id, Long sequence) {
        if (released || epoch != startEpoch) return false;
        if (sequence == null ? sequencedMode : !sequence.equals(sequences.get(id))) { staleCompletions++; return false; }
        return true;
    }
    private void removeCharge(VectorRoadTile tile) { retainedBytes -= tile.estimatedRetainedBytes(); sourceBytes -= tile.sourceBytes(); }
    public synchronized long generation() { return generation; }
    public synchronized Snapshot snapshot() { return new Snapshot(generation, epoch, retainedBytes, sourceBytes, released, new ArrayList<>(tiles.values())); }
    public synchronized void clear() { tiles.clear(); retainedBytes = sourceBytes = 0; epoch++; generation++; }
    public synchronized void release() { if (released) return; released = true; clear(); }
    public synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "denza-yandex-vector-road-cache-v1"); m.put("generation", generation); m.put("epoch", epoch); m.put("released", released);
        m.put("tiles", tiles.size()); m.put("maxTiles", maxTiles); m.put("maxBytes", maxBytes); m.put("retainedBytes", retainedBytes); m.put("sourceBytes", sourceBytes);
        m.put("accepted", accepted); m.put("duplicates", duplicates); m.put("replacements", replacements); m.put("evictions", evictions); m.put("rejected", rejected);
        m.put("sequencedMode", sequencedMode); m.put("highestSequenceSeen", highestSequenceSeen); m.put("sequenceRetirementFloor", retirementFloor);
        m.put("sequenceTrackingEntries", sequences.size()); m.put("maxSequenceTrackingEntries", maxSequenceEntries);
        m.put("staleSequenceRejected", staleSequenceRejected); m.put("retiredSequenceRejected", retiredSequenceRejected);
        m.put("sequenceTrackingRetirements", sequenceTrackingRetirements); m.put("unsequencedRejected", unsequencedRejected); m.put("staleCompletions", staleCompletions);
        List<String> fingerprints = new ArrayList<>(); for (VectorRoadTile t : tiles.values()) fingerprints.add(t.fingerprint());
        m.put("activeFingerprints", Collections.unmodifiableList(fingerprints));
        m.put("byteAccounting", "conservative retained-object estimate, not measured heap; prior snapshots retain their own objects");
        return Collections.unmodifiableMap(m);
    }
}
