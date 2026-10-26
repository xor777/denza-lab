package dev.denza.hud.probe;

import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Read-only discovery of native nonlegacy root/offline_caches/<SDK region id>/<layer>.fb.
 * Pinned native path constructors: 0x1af6dbc, 0x1af1ea8, 0x1aefa3c; no recursive scan.
 * These packages supply graph attributes. Neither presence nor absence proves route coverage.
 * SDK release and file identities are contemporaneous fences, not a decoded region.pb release join.
 */
final class YandexOfflineGraphFiles {
    static final int MAX_PACKAGES = 128, MAX_FILE_BYTES = 32 * 1024 * 1024;
    // Mapping is address space, not a full-file allocation. Decoded RAM has a separate budget.
    static final int MAX_VECTOR_FILE_BYTES = Integer.MAX_VALUE;
    static final int MAX_METADATA_BYTES = 65536, MAX_REGION_IDS = 1024, MAX_VERSION_BYTES = 128;
    static final long MAX_DISCOVERY_MS = 2000, TTL_MS = 5000;

    static final class Package {
        final File file;
        final int regionId;
        final long downloadedRelease;
        final String identity, version, layer;
        private final int maxBytes;
        private final int[] ids;
        private final File metadata;
        private final Identity fileStat, metadataStat;
        private final String metadataSha;
        private Package(File file, int id, long release, Header header, Identity stat,
                        File metadata, Identity metadataStat, String metadataSha, boolean vector) {
            this.file = file; regionId = id; downloadedRelease = release;
            layer = vector ? "vmap3fb" : "driving";
            maxBytes = vector ? MAX_VECTOR_FILE_BYTES : MAX_FILE_BYTES;
            version = header.version; ids = header.ids.clone(); fileStat = stat;
            this.metadata = metadata; this.metadataStat = metadataStat; this.metadataSha = metadataSha;
            identity = digest(file.getPath() + "|" + stat.key() + "|" + metadataKey(metadataStat) + "|"
                    + metadataSha + "|" + id + "|" + release + "|" + version);
        }
        /** IDs embedded in driving.fb; empty for vmap3fb, whose binding is SDK/native folder. */
        int[] regionIds() { return ids.clone(); }
    }
    static final class Snapshot {
        final List<Package> files;
        final long observedAt, pathGeneration;
        final String inventoryFingerprint, reasonCode, identity;
        final boolean valid;
        final int rejectedCount;
        final List<Map<String,Object>> packageDiagnostics;
        private Snapshot(List<Package> files, long at, long generation, String inventory, String code,
                         boolean valid, List<Map<String,Object>> diagnostics) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files)); observedAt = at;
            pathGeneration = generation; inventoryFingerprint = inventory; reasonCode = code;
            this.valid = valid;
            List<Map<String,Object>> rows = new ArrayList<>(); int rejected = 0;
            for (Map<String,Object> row : diagnostics) {
                rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
                if (Boolean.TRUE.equals(row.get("rejected"))) rejected++;
            }
            packageDiagnostics = Collections.unmodifiableList(rows); rejectedCount = rejected;
            StringBuilder s = new StringBuilder(inventory).append('|').append(generation);
            for (Package p : files) s.append('|').append(p.identity);
            identity = digest(s.toString());
        }
        Map<String,Object> status() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("schema", "denza-yandex-offline-graph-files-v1"); m.put("valid", valid);
            m.put("reasonCode", reasonCode); m.put("count", files.size());
            m.put("rejectedCount", rejectedCount); m.put("observedAt", observedAt);
            m.put("pathGeneration", pathGeneration); m.put("identity", identity);
            m.put("inventoryFingerprint", inventoryFingerprint);
            m.put("packageDiagnostics", packageDiagnostics);
            m.put("routeCoverageProved", false);
            return Collections.unmodifiableMap(m);
        }
    }

    static Snapshot discover(YandexOfflineCachePath.PathSnapshot path,
                             YandexOfflineInventory.Snapshot inventory, long now) {
        return discover(path, inventory, now, false);
    }
    static Snapshot discoverVectors(YandexOfflineCachePath.PathSnapshot path,
                                    YandexOfflineInventory.Snapshot inventory, long now) {
        return discover(path, inventory, now, true);
    }
    private static Snapshot discover(YandexOfflineCachePath.PathSnapshot path,
                                     YandexOfflineInventory.Snapshot inventory, long now, boolean vector) {
        background();
        List<Package> result = new ArrayList<>();
        List<Map<String,Object>> diagnostics = new ArrayList<>();
        long generation = path == null ? -1 : path.generation;
        String fingerprint = inventory == null ? "" : inventory.fingerprint;
        if (path == null || !path.ready(now)) return empty(now, generation, fingerprint, "path_unready", diagnostics);
        if (inventory == null || !inventory.ready(now)) return empty(now, generation, fingerprint, "inventory_unready", diagnostics);
        if (inventory.regions.size() > YandexOfflineInventory.MAX_REGIONS)
            return empty(now, generation, fingerprint, "region_budget", diagnostics);
        long started = SystemClock.elapsedRealtime();
        try {
            File root = new File(path.root).getCanonicalFile();
            if (!root.isDirectory()) return empty(now, generation, fingerprint, "root_missing", diagnostics);
            TreeSet<Integer> seen = new TreeSet<>();
            for (Map<String,Object> row : inventory.regions) {
                if (SystemClock.elapsedRealtime() - started > MAX_DISCOVERY_MS)
                    return empty(now, generation, fingerprint, "discovery_budget", diagnostics);
                if (!Boolean.TRUE.equals(row.get("usable"))) continue;
                Object idValue = row.get("id"), releaseValue = row.get("downloadedReleaseTime");
                if (!(idValue instanceof Integer) || !(releaseValue instanceof Long))
                    return empty(now, generation, fingerprint, "inventory_row_invalid", diagnostics);
                int id = (Integer) idValue; long release = (Long) releaseValue;
                if (id < 0 || release <= 0 || !seen.add(id))
                    return empty(now, generation, fingerprint, "inventory_row_invalid", diagnostics);
                if (seen.size() > MAX_PACKAGES) return empty(now, generation, fingerprint, "package_budget", diagnostics);
                File folder = new File(new File(root, "offline_caches"), Integer.toString(id));
                File file = new File(folder, vector ? "vmap3fb.fb" : "driving.fb");
                File metadata = new File(folder, "region.pb");
                Map<String,Object> detail = new LinkedHashMap<>();
                detail.put("regionId", id); detail.put("downloadedRelease", release);
                detail.put("layer", vector ? "vmap3fb" : "driving");
                detail.put("packagePath", file.getPath()); detail.put("metadataPath", metadata.getPath());
                detail.put("isLegacyPath", row.get("isLegacyPath"));
                String stage = "layout";
                try {
                    // Legacy has two other layouts. A missing flag is unsupported, never guessed.
                    if (!Boolean.FALSE.equals(row.get("isLegacyPath")))
                        throw bad(row.get("isLegacyPath") == null ? "package_layout_unavailable" : "legacy_package_layout");
                    stage = "path"; file = confined(root, file); metadata = confined(root, metadata);
                    int maxBytes = vector ? MAX_VECTOR_FILE_BYTES : MAX_FILE_BYTES;
                    stage = "package_stat";
                    detail.put("fileExists", file.exists()); detail.put("fileBytes", file.length());
                    detail.put("maximumFileBytes", maxBytes);
                    Identity stat = stat(file, maxBytes);
                    stage = "metadata_stat";
                    detail.put("metadataExists", metadata.exists()); detail.put("metadataBytes", metadata.length());
                    Identity metaStat = optionalMetadataStat(metadata);
                    detail.put("metadataPresent", metaStat != null);
                    stage = "metadata_read";
                    String metaSha = metaStat == null ? "" : digest(read(metadata, metaStat, MAX_METADATA_BYTES));
                    stage = "header";
                    Header header;
                    try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                        header = header(input, stat.size, vector);
                    }
                    // vmap3fb has no embedded region IDs; don't manufacture them from the folder.
                    boolean belongs = vector;
                    for (int member : header.ids) belongs |= member == id;
                    if (!belongs) throw bad("package_region_mismatch");
                    stage = "identity_fence";
                    if (!stat.equals(stat(file, maxBytes))
                            || !metadataMatches(metadata, metaStat, metaSha)) throw bad("package_changed");
                    result.add(new Package(file, id, release, header, stat, metadata, metaStat, metaSha, vector));
                    detail.put("version", header.version); detail.put("rejected", false); detail.put("reasonCode", "ready");
                } catch (IOException | ErrnoException | IllegalArgumentException | SecurityException e) {
                    detail.put("rejected", true); detail.put("stage", stage);
                    detail.put("reasonCode", e instanceof ErrnoException ? "package_stat_error" :
                            e instanceof SecurityException ? "package_access_denied" : e.getMessage());
                    detail.put("errorType", e.getClass().getSimpleName()); detail.put("error", e.getMessage());
                }
                diagnostics.add(detail);
            }
            if (SystemClock.elapsedRealtime() - started > MAX_DISCOVERY_MS)
                return empty(now, generation, fingerprint, "discovery_budget", diagnostics);
            boolean rejected = false;
            for (Map<String,Object> detail : diagnostics) rejected |= Boolean.TRUE.equals(detail.get("rejected"));
            return new Snapshot(result, now, generation, fingerprint,
                    result.isEmpty() ? "no_supported_package" : rejected ? "ready_partial" : "ready",
                    !result.isEmpty(), diagnostics);
        } catch (IOException | SecurityException error) {
            return empty(now, generation, fingerprint, "discovery_error", diagnostics);
        }
    }

    /** Cheap owner-side fence, without filesystem access. Re-run after worker completion. */
    static boolean stillCurrent(Snapshot files, YandexOfflineCachePath.PathSnapshot path,
                                YandexOfflineInventory.Snapshot inventory, long now) {
        return files != null && files.valid && path != null && path.ready(now)
                && inventory != null && inventory.ready(now) && files.pathGeneration == path.generation
                && files.inventoryFingerprint.equals(inventory.fingerprint)
                && now >= files.observedAt && now - files.observedAt < TTL_MS;
    }

    /**
     * Immutable bounded bytes, checked against the exact discovered inode and metadata.
     * Two complete reads must hash identically; an opened old inode after rename is rejected.
     * Caller must re-check current path generation and SDK fingerprint before publishing results.
     */
    static byte[] readStable(Package source) throws IOException {
        background(); if (source == null) throw bad("package_missing");
        if (!source.layer.equals("driving")) throw bad("vector_requires_scoped_mapping");
        try {
            check(source);
            byte[] result = read(source.file, source.fileStat, MAX_FILE_BYTES);
            String sha = digest(result);
            if (!sha.equals(hashFile(source.file, source.fileStat, MAX_FILE_BYTES))) throw bad("package_changed");
            Header header;
            try (RandomAccessFile input = new RandomAccessFile(source.file, "r")) {
                header = header(input, source.fileStat.size, false);
            }
            if (!header.version.equals(source.version) || !java.util.Arrays.equals(header.ids, source.ids))
                throw bad("package_changed");
            check(source); return result;
        } catch (ErrnoException error) { throw new IOException("package_stat_error", error); }
    }

    interface Reader<T> { T read(ByteBuffer bytes) throws IOException; }
    /**
     * Scoped read-only mapping, with descriptor/path/metadata identities checked before and after.
     * Reader MUST finish all access inside this call. It must not retain or return the buffer, its
     * views, a package reader referencing it, or any other mapping-backed object. Return copies.
     * Closing the channel releases its descriptor; Android unmaps later when the buffer is GC'd.
     * A failed post-check discards the callback result. Re-check stillCurrent before publication.
     */
    static <T> T mapStable(Package source, Reader<T> reader) throws IOException {
        background(); if (source == null || reader == null) throw bad("mapping_arguments");
        try {
            check(source); T result;
            try (FileInputStream input = new FileInputStream(source.file)) {
                opened(input, source.fileStat);
                ByteBuffer bytes = input.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, source.fileStat.size)
                        .asReadOnlyBuffer();
                result = reader.read(bytes);
                if (result instanceof ByteBuffer) throw bad("mapping_escape");
                opened(input, source.fileStat);
            }
            check(source); return result;
        } catch (ErrnoException error) { throw new IOException("package_stat_error", error); }
    }

    private static void check(Package source) throws IOException, ErrnoException {
        if (!source.fileStat.equals(stat(source.file, source.maxBytes))
                || !metadataMatches(source.metadata, source.metadataStat, source.metadataSha))
            throw bad("package_changed");
        // A sidecar-free package still keeps its discovered native format/version identity.
        try (RandomAccessFile input = new RandomAccessFile(source.file, "r")) {
            if (!source.fileStat.equals(new Identity(Os.fstat(input.getFD())))) throw bad("package_changed");
            Header current = header(input, source.fileStat.size, source.layer.equals("vmap3fb"));
            if (!current.version.equals(source.version) || !java.util.Arrays.equals(current.ids, source.ids))
                throw bad("package_changed");
        }
        if (!source.fileStat.equals(stat(source.file, source.maxBytes))) throw bad("package_changed");
    }
    /** Native packages may omit region.pb. Its absence is fenced just like a present sidecar:
     * appearance, disappearance or replacement during a read invalidates the attempt. */
    private static Identity optionalMetadataStat(File metadata) throws IOException, ErrnoException {
        try { return stat(metadata, MAX_METADATA_BYTES); }
        catch (ErrnoException error) { if (error.errno == OsConstants.ENOENT) return null; throw error; }
    }
    private static String metadataKey(Identity identity) { return identity == null ? "absent" : identity.key(); }
    private static boolean metadataMatches(File file, Identity expected, String sha) throws IOException, ErrnoException {
        Identity actual = optionalMetadataStat(file);
        return expected == null ? actual == null : expected.equals(actual) && sha.equals(hashFile(file, expected, MAX_METADATA_BYTES));
    }
    private static File confined(File root, File child) throws IOException {
        File absolute = child.getAbsoluteFile(), canonical = child.getCanonicalFile();
        if (!absolute.equals(canonical) || !canonical.getPath().startsWith(root.getPath() + File.separator))
            throw bad("package_path_invalid");
        return canonical;
    }
    private static final class Identity {
        final long dev, inode, size, mtime, ctime;
        Identity(StructStat s) { dev = s.st_dev; inode = s.st_ino; size = s.st_size; mtime = s.st_mtime; ctime = s.st_ctime; }
        String key() { return dev + ":" + inode + ":" + size + ":" + mtime + ":" + ctime; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false; Identity s = (Identity) other;
            return dev == s.dev && inode == s.inode && size == s.size && mtime == s.mtime && ctime == s.ctime;
        }
        @Override public int hashCode() { return key().hashCode(); }
    }
    private static Identity stat(File file, int max) throws ErrnoException, IOException {
        StructStat s = Os.lstat(file.getPath());
        if (!OsConstants.S_ISREG(s.st_mode)) throw bad("file_not_regular");
        if (s.st_size < 8) throw bad("file_too_small");
        if (s.st_size > max) throw bad("file_byte_budget");
        return new Identity(s);
    }
    private static void opened(FileInputStream input, Identity expected) throws ErrnoException, IOException {
        if (!expected.equals(new Identity(Os.fstat(input.getFD())))) throw bad("package_changed");
    }
    private static byte[] read(File file, Identity expected, int max) throws IOException, ErrnoException {
        if (expected.size > max || !expected.equals(stat(file, max))) throw bad("package_changed");
        byte[] bytes = new byte[(int)expected.size];
        try (FileInputStream input = new FileInputStream(file)) {
            opened(input, expected); int at = 0;
            while (at < bytes.length) {
                int count = input.read(bytes, at, bytes.length - at);
                if (count <= 0) throw bad("package_short_read"); at += count;
            }
            if (input.read() != -1) throw bad("package_changed"); opened(input, expected);
        }
        if (!expected.equals(stat(file, max))) throw bad("package_changed"); return bytes;
    }
    private static String hashFile(File file, Identity expected, int max) throws IOException, ErrnoException {
        if (!expected.equals(stat(file, max))) throw bad("package_changed");
        MessageDigest sha = sha(); long count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            opened(input, expected); byte[] buffer = new byte[16384]; int size;
            while ((size = input.read(buffer)) != -1) {
                count += size; if (count > max || count > expected.size) throw bad("file_budget");
                sha.update(buffer, 0, size);
            }
            opened(input, expected);
        }
        if (count != expected.size || !expected.equals(stat(file, max))) throw bad("package_changed");
        return hex(sha.digest());
    }
    private static final class Header {
        final String version; final int[] ids;
        Header(String version, int[] ids) { this.version = version; this.ids = ids; }
    }
    private static Header header(RandomAccessFile f, long size, boolean vectorPackage) throws IOException {
        long root = u32(f, 0, size), relative = i32(f, root, size), vtable = root - relative;
        if (root < 4 || vtable < 4 || u16(f, vtable, size) < 20 || u16(f, vtable, size) > 256)
            throw bad("package_root_invalid");
        int objectSize = u16(f, vtable + 2, size);
        if (objectSize < 4 || objectSize > 256 || root + objectSize > size) throw bad("package_root_invalid");
        if (vectorPackage) {
            String format = text(f, reference(f, root, vtable, objectSize, 2, size), size);
            String version = text(f, reference(f, root, vtable, objectSize, 3, size), size);
            if (!format.equals("vmap3")) throw bad("vector_format_invalid");
            return new Header(version, new int[0]);
        }
        long version = reference(f, root, vtable, objectSize, 6, size);
        long vector = reference(f, root, vtable, objectSize, 7, size);
        long count = u32(f, vector, size);
        if (count < 1 || count > MAX_REGION_IDS || vector + 4 + count * 4 > size)
            throw bad("package_identity_invalid");
        int[] ids = new int[(int)count]; TreeSet<Integer> unique = new TreeSet<>();
        for (int i = 0; i < ids.length; i++) {
            long id = u32(f, vector + 4 + i * 4L, size);
            if (id > Integer.MAX_VALUE || !unique.add((int)id)) throw bad("package_region_invalid"); ids[i] = (int)id;
        }
        return new Header(text(f, version, size), ids);
    }
    private static String text(RandomAccessFile f, long at, long size) throws IOException {
        long length = u32(f, at, size);
        if (length < 1 || length > MAX_VERSION_BYTES || at + 5 + length > size) throw bad("package_text_invalid");
        byte[] text = new byte[(int)length]; f.seek(at + 4); f.readFully(text);
        if (f.read() != 0) throw bad("package_text_invalid");
        for (byte b : text) if (b < 0x21 || b > 0x7e) throw bad("package_text_invalid");
        return new String(text, StandardCharsets.US_ASCII);
    }
    private static long reference(RandomAccessFile f, long root, long vtable, int objectSize, int field, long size) throws IOException {
        int offset = u16(f, vtable + 4 + field * 2, size);
        if (offset < 4 || offset > objectSize - 4) throw bad("package_field_invalid");
        long at = root + offset, delta = u32(f, at, size);
        if (delta < 4 || at + delta > size - 4) throw bad("package_field_invalid"); return at + delta;
    }
    private static long u32(RandomAccessFile f, long at, long size) throws IOException { return i32(f, at, size) & 0xffffffffL; }
    private static int i32(RandomAccessFile f, long at, long size) throws IOException {
        if (at < 0 || at > size - 4) throw bad("package_bounds"); f.seek(at); return Integer.reverseBytes(f.readInt());
    }
    private static int u16(RandomAccessFile f, long at, long size) throws IOException {
        if (at < 0 || at > size - 2) throw bad("package_bounds"); f.seek(at); return Short.reverseBytes(f.readShort()) & 65535;
    }
    private static Snapshot empty(long now, long generation, String fingerprint, String code) {
        return empty(now, generation, fingerprint, code, Collections.emptyList());
    }
    private static Snapshot empty(long now, long generation, String fingerprint, String code, List<Map<String,Object>> diagnostics) {
        return new Snapshot(Collections.emptyList(), now, generation, fingerprint, code, false, diagnostics);
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String digest(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] bytes) { return hex(sha().digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }
    private static IOException bad(String code) { return new IOException(code); }
    private static void background() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("offline files worker required");
    }
}
