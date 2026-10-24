package dev.denza.adaslink;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Real 44/0x8002 selection feedback, separate from the stock-equivalent dry-run retry behavior.
 * All methods run on one worker. This class has no transport, publisher, timers or Android calls.
 *
 * <p>Firmware: BydLaunchermap SomeipNavigationPathMatchStatusService generated table at line608:
 * Notify.f1 -> Struct.f3 uint64 pathIDResp, f4 int32 statusCode. PathSelectedProxy t.java:13
 * explicitly accepts 200. Missing proto3 scalars remain unknown here, never effective defaults.
 * This acknowledges selection only, not PathMatch, lane validity or NOA eligibility.
 */
public final class RealPathAck {
    public static final int MAX_PAYLOAD = 65536;
    private static final int MAX_FIELDS = 4096, MAX_IDS = 4096;
    public interface Sink { void selected(long pathId); }
    public enum Decision {
        ARMED, ACCEPTED, FAILED, DUPLICATE, NOT_READY, NO_REQUEST, WRONG_SESSION, WRONG_TOPIC,
        STALE, FUTURE, EXPIRED, REPLACED, UNRELATED, MISSING_FIELDS, MALFORMED, REUSED_ID,
        ID_CAPACITY, DELIVERY_FAILED
    }
    public static final class Selection {
        /** Null means absent on the wire, even when a generated proto3 getter would return zero. */
        public final Long pathId;
        public final Integer statusCode;
        private Selection(Long path, Integer status) { pathId = path; statusCode = status; }
    }
    public static final class Result {
        public final Decision decision;
        public final Selection selection;
        private Result(Decision decision, Selection selection) { this.decision = decision; this.selection = selection; }
    }
    private static final class Request {
        final long path, sentAt;
        boolean consumed, confirmed;
        Request(long path, long sentAt) { this.path = path; this.sentAt = sentAt; }
    }
    private final LongSupplier currentPath;
    private final Sink sink;
    private final long maxCallbackDelayMs, maxRequestAgeMs;
    private final int idCapacity;
    // Retained across route/connection changes: the wire never echoes a local request epoch.
    private final Set<Long> usedIds = new HashSet<>();
    private long epoch, sessionAt = -1;
    private boolean ready, exhausted;
    private Request request;

    /** Timeouts are explicit caller policy in elapsedRealtime milliseconds, not firmware semantics. */
    public RealPathAck(LongSupplier currentPath, Sink sink, long maxCallbackDelayMs, long maxRequestAgeMs) {
        this(currentPath, sink, maxCallbackDelayMs, maxRequestAgeMs, MAX_IDS);
    }
    RealPathAck(LongSupplier currentPath, Sink sink, long maxCallbackDelayMs, long maxRequestAgeMs, int idCapacity) {
        if (currentPath == null || sink == null || maxCallbackDelayMs < 0 || maxRequestAgeMs < 0
                || idCapacity < 1 || idCapacity > MAX_IDS) throw new IllegalArgumentException("ACK policy");
        this.currentPath = currentPath; this.sink = sink;
        this.maxCallbackDelayMs = maxCallbackDelayMs; this.maxRequestAgeMs = maxRequestAgeMs; this.idCapacity = idCapacity;
    }

    /**
     * Call only after genuine HAL/binder/client/subscription readiness, never an assumed gate.
     * Capture the returned token with each Binder callback and preserve it across worker queues.
     */
    public long sessionReady(long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("Session clock");
        invalidateSession();
        sessionAt = nowMs; ready = !exhausted;
        return epoch;
    }
    /** Call on HAL loss, Binder disconnect, subscription loss or shutdown, before any new egress. */
    public void invalidateSession() {
        ready = false; request = null; sessionAt = -1;
        if (epoch == Long.MAX_VALUE) throw new IllegalStateException("Session epoch exhausted");
        epoch++;
    }
    public void routeChanged() { request = null; }

    /**
     * Commit only after a real selection fire returns success, using its PRE-fire elapsed time.
     * A callback may arrive during the synchronous Binder fire and be consumed after this commit.
     * No ID may be reused, even after reconnect; caller must also use a fresh cross-process ID namespace.
     */
    public Decision selectionSent(long token, long pathId, long sentAtMs, long nowMs) {
        // A delayed completion from an old transport must not clear the current request.
        if (token != epoch) return Decision.WRONG_SESSION;
        if (!ready) return Decision.NOT_READY;
        request = null;
        if (pathId <= 0) return Decision.UNRELATED;
        if (usedIds.contains(pathId)) return Decision.REUSED_ID;
        if (usedIds.size() >= idCapacity) { exhausted = true; ready = false; return Decision.ID_CAPACITY; }
        usedIds.add(pathId); // An actual successful fire consumed this ID even if prerequisites were lost.
        if (sentAtMs < sessionAt) return Decision.STALE;
        if (nowMs < sentAtMs) return Decision.FUTURE;
        if (nowMs - sentAtMs > maxRequestAgeMs) return Decision.EXPIRED;
        if (currentPath.getAsLong() != pathId) return Decision.REPLACED;
        request = new Request(pathId, sentAtMs);
        return Decision.ARMED;
    }

    /** Binder vendor timestamp is deliberately unused: its clock/ordering contract is not established. */
    public Result event(long token, long topic, long receivedAtMs, byte[] payload, long nowMs) {
        if (topic != Topics.PATH_SELECT_CONFIRM) return result(Decision.WRONG_TOPIC, null);
        if (!ready) return result(Decision.NOT_READY, null);
        if (token != epoch) return result(Decision.WRONG_SESSION, null);
        if (request == null) return result(Decision.NO_REQUEST, null);
        if (currentPath.getAsLong() != request.path) { request = null; return result(Decision.REPLACED, null); }
        if (receivedAtMs < sessionAt || receivedAtMs < request.sentAt) return result(Decision.STALE, null);
        if (nowMs < receivedAtMs) return result(Decision.FUTURE, null);
        if (nowMs - receivedAtMs > maxCallbackDelayMs) return result(Decision.STALE, null);
        if (!request.consumed && nowMs - request.sentAt > maxRequestAgeMs) {
            request.consumed = true; return result(Decision.EXPIRED, null);
        }
        Selection parsed;
        try { parsed = decode(payload); }
        catch (IllegalArgumentException error) { return result(Decision.MALFORMED, null); }
        if (parsed.pathId == null || parsed.statusCode == null) return result(Decision.MISSING_FIELDS, parsed);
        if (parsed.pathId != request.path) return result(Decision.UNRELATED, parsed);
        if (request.consumed) return result(Decision.DUPLICATE, parsed);
        request.consumed = true;
        if (parsed.statusCode != 200) return result(Decision.FAILED, parsed); // Diagnostic + disarm; no resend.
        Request accepted = request;
        try { sink.selected(accepted.path); }
        catch (RuntimeException failure) { return result(Decision.DELIVERY_FAILED, parsed); }
        if (!ready || request != accepted || currentPath.getAsLong() != accepted.path)
            return result(Decision.REPLACED, parsed);
        accepted.confirmed = true;
        return result(Decision.ACCEPTED, parsed);
    }
    /** Selection acknowledgement is connection/route scoped; never an availability or map-match claim. */
    public long confirmedPathId() {
        return ready && request != null && request.confirmed && currentPath.getAsLong() == request.path ? request.path : -1;
    }
    private static Result result(Decision decision, Selection selection) { return new Result(decision, selection); }

    /** Strict known-field wire types/uniqueness, bounded unknown-field skipping for forward compatibility. */
    public static Selection decode(byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("ACK payload cap");
        Reader outer = new Reader(payload, 0, payload.length), body = null;
        while (outer.more()) {
            int tag = outer.tag();
            if ((tag >>> 3) == 1) {
                if ((tag & 7) != 2 || body != null) throw new IllegalArgumentException("ACK wrapper");
                body = outer.message();
            } else outer.skip(tag & 7);
        }
        if (body == null) throw new IllegalArgumentException("Missing ACK wrapper");
        boolean[] seen = new boolean[9];
        Long path = null; Integer status = null;
        while (body.more()) {
            int tag = body.tag(), field = tag >>> 3, wire = tag & 7;
            if (field < seen.length) {
                if (seen[field]) throw new IllegalArgumentException("Duplicate ACK field");
                seen[field] = true;
                int expectedWire = field == 5 ? 2 : field == 8 ? 5 : 0;
                if (wire != expectedWire) throw new IllegalArgumentException("ACK field wire type");
                if (wire == 0) {
                    long value = body.varint();
                    if (field == 3) path = value;
                    else if (field == 4) {
                        if (!(value >= 0 && value <= 0xFFFFFFFFL) && value != (long) (int) value)
                            throw new IllegalArgumentException("ACK int32 range");
                        status = (int) value;
                    } else if (field != 6 && (value < 0 || value > 0xFFFFFFFFL))
                        throw new IllegalArgumentException("ACK uint32 range");
                } else body.skip(wire);
            } else body.skip(wire);
        }
        return new Selection(path, status);
    }
    private static final class Reader {
        final byte[] bytes;
        final int end;
        int at, fields;
        Reader(byte[] bytes, int at, int end) { this.bytes = bytes; this.at = at; this.end = end; }
        boolean more() { return at < end; }
        int tag() {
            long tag = varint();
            if (++fields > MAX_FIELDS || (tag >>> 3) < 1 || (tag >>> 3) > 0x1FFFFFFFL)
                throw new IllegalArgumentException("ACK field cap/tag");
            return (int) tag;
        }
        long varint() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (at >= end) throw new IllegalArgumentException("Truncated ACK varint");
                int b = bytes[at++] & 255;
                if (i == 9 && b > 1) throw new IllegalArgumentException("ACK varint overflow");
                value |= (long) (b & 127) << (i * 7);
                if ((b & 128) == 0) return value;
            }
            throw new IllegalArgumentException("ACK varint overflow");
        }
        int length() {
            long size = varint();
            if (size < 0 || size > end - at) throw new IllegalArgumentException("Truncated ACK bytes");
            return (int) size;
        }
        Reader message() { int size = length(); Reader child = new Reader(bytes, at, at + size); at += size; return child; }
        void skip(int wire) {
            int size;
            if (wire == 0) { varint(); return; }
            if (wire == 2) size = length();
            else if (wire == 1) size = 8;
            else if (wire == 5) size = 4;
            else throw new IllegalArgumentException("Unsupported ACK wire type");
            if (size > end - at) throw new IllegalArgumentException("Truncated ACK fixed");
            at += size;
        }
    }
}
