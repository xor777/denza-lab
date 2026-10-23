package dev.denza.hud.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SOME/IP events the ADAS overlay listens to, as the SDK hands them over (protobuf in
 * {@code SomeIpData}), and nothing it could send. Plain Java: decoded on the host in AdasOverlayTest.
 *
 * <p>ADAS's answers (services 13 and 44) are ported from experiments/adas-status-probe
 * (StatusProtocol), field for field. The stock map's own feed (services 7 and 0x8202) is what the
 * idle stock map keeps firing once a second - NavigationStatus 100, TrafficInfo roadClass -1 and a
 * vehicle location with pathId -1 (research/adas-bridge/README.md, "What the idle stock map still
 * sends"). Field numbers and types are the stock map's generated protobuf
 * (BydLaunchermap someip/navigation/status_/link/info/service/SomeipNavigationStatusLinkInfoService
 * and someip/sd/map/inform/service/SomeipSdMapInformService, 34.1.33): navigationStatus is uint32
 * field 4, roadClass uint32 field 19 (-1 arrives as 0xFFFFFFFF), curSDRouteID a double, field 7.
 */
final class AdasWire {
    // ADAS -> IVI: the answers.
    static final long PILOT = 0x0004000D000D8002L;
    static final long ALARM = 0x0004000D000D8003L;
    static final long MODE = 0x0004000D000D8005L;
    static final long SELECTED = 0x0004002C002C8002L;
    static final long PATH_MATCH = 0x0004002C002C8001L;
    // IVI -> ADAS: what the stock map (and, once live, our bridge) fires; read, never fired here.
    static final long NAV_STATUS = 0x0004000700078001L;
    static final long TRAFFIC = 0x0004000700078003L;
    static final long LOCATION = 0x0004820282028008L;
    static final long[] ANSWERS = {PILOT, ALARM, MODE, SELECTED, PATH_MATCH};
    static final long[] STOCK = {NAV_STATUS, TRAFFIC, LOCATION};
    /** Client services started for the subscriptions: 13, 44, 7, 0x8202. */
    static final long[] SERVICES = {0x000B000D000D0000L, 0x000B002C002C0000L, 0x000B000700070000L, 0x000B820282020000L};
    static final int MAX_PAYLOAD = 65536;
    /** NavigationStatus the stock map asserts when it is not navigating. */
    static final int STATUS_IDLE = 100;
    static final int SUCCESS = 200;
    /** The ADS dictionary's «Please keep your hands on the steering wheel». */
    static final int TOR_HANDS = 1302;

    static boolean allowed(long topic) {
        for (long value : ANSWERS) if (topic == value) return true;
        for (long value : STOCK) if (topic == value) return true;
        return false;
    }

    enum Kind { PILOT, ALARM, MODE, SELECTED, MATCH, NAV_STATUS, TRAFFIC, LOCATION }

    /** One decoded event; only the fields the overlay reads. */
    static final class Event {
        final Kind kind;
        int dnp, icc, acc, noaMode, alarmReason, alarmStage, notice, statusCode, navigationStatus, roadClass;
        boolean takeover;
        long pathId;
        double routeId;
        long[] matchedPaths = new long[0];
        long[] matchedCodes = new long[0];
        Event(Kind kind) { this.kind = kind; }
        /** The stock map's "no route" in this event: status 100, road class -1, pathId -1. */
        boolean stockEmpty() {
            if (kind == Kind.NAV_STATUS) return navigationStatus == STATUS_IDLE;
            if (kind == Kind.TRAFFIC) return roadClass == -1;
            if (kind == Kind.LOCATION) return routeId == -1.0;
            return false;
        }
    }

    static Event decode(long topic, byte[] payload) {
        if (!allowed(topic)) throw new IllegalArgumentException("Unsubscribed topic");
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Payload cap");
        Fields outer = new Fields(payload);
        if (outer.all(1).size() != 1 || outer.kind(1) != 2 || !(outer.first(1) instanceof byte[]))
            throw new IllegalArgumentException("Expected notification struct field 1");
        Fields f = new Fields((byte[]) outer.first(1));
        Event e;
        if (topic == PILOT) {
            e = new Event(Kind.PILOT);
            e.acc = (int) f.number(3); e.icc = (int) f.number(4); e.dnp = (int) f.number(5);
            e.takeover = f.number(6) != 0;
        } else if (topic == MODE) {
            e = new Event(Kind.MODE); e.noaMode = (int) f.number(3);
        } else if (topic == ALARM) {
            e = new Event(Kind.ALARM);
            e.alarmReason = (int) f.number(3); e.alarmStage = (int) f.number(5);
            f.real(6); e.notice = (int) f.number(7); f.real(9);
        } else if (topic == SELECTED) {
            e = new Event(Kind.SELECTED); e.pathId = f.number(3); e.statusCode = (int) f.number(4); f.text(5);
        } else if (topic == PATH_MATCH) {
            e = new Event(Kind.MATCH); e.statusCode = (int) f.number(3); f.text(5);
            for (int kind : f.kinds(4)) if (kind != 2) throw new IllegalArgumentException("RouteInfo wire kind");
            List<Object> items = f.all(4);
            e.matchedPaths = new long[items.size()]; e.matchedCodes = new long[items.size()];
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof byte[])) throw new IllegalArgumentException("RouteInfo wire type");
                Fields item = new Fields((byte[]) items.get(i));
                e.matchedPaths[i] = item.number(1); e.matchedCodes[i] = item.number(5);
            }
        } else if (topic == NAV_STATUS) {
            e = new Event(Kind.NAV_STATUS); e.navigationStatus = (int) f.number(4); f.real(3);
        } else if (topic == TRAFFIC) {
            e = new Event(Kind.TRAFFIC); e.roadClass = (int) f.number(19);
        } else {
            e = new Event(Kind.LOCATION); e.routeId = f.real(7);
        }
        return e;
    }

    /* ------------------------------------------------------- encoding (fixtures and tests only) */

    static byte[] pilot(int dnp, int icc, boolean takeover) { return wrap(new Out().varint(4, icc).varint(5, dnp).varint(6, takeover ? 1 : 0)); }
    static byte[] mode(int noaMode) { return wrap(new Out().varint(3, noaMode)); }
    static byte[] alarm(int reason, int notice) { return wrap(new Out().varint(3, reason).varint(7, notice)); }
    static byte[] selected(long pathId, int code) { return wrap(new Out().varint(3, pathId).varint(4, code)); }
    static byte[] match(int code, long... paths) {
        Out out = new Out().varint(3, code);
        for (long path : paths) out.bytes(4, new Out().varint(1, path).varint(5, SUCCESS).toBytes());
        return wrap(out);
    }
    static byte[] navStatus(int status) { return wrap(new Out().varint(4, status)); }
    static byte[] traffic(int roadClass) { return wrap(new Out().varint(19, roadClass & 0xFFFFFFFFL)); }
    static byte[] location(double routeId) { return wrap(new Out().fixed64(7, routeId)); }
    private static byte[] wrap(Out inner) { return new Out().bytes(1, inner.toBytes()).toBytes(); }

    private static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Out raw(long value) {
            while ((value & ~0x7FL) != 0) { bytes.write((int) ((value & 0x7F) | 0x80)); value >>>= 7; }
            bytes.write((int) value); return this;
        }
        Out varint(int field, long value) { if (value == 0) return this; raw((long) field << 3); return raw(value); }
        Out bytes(int field, byte[] value) { raw(((long) field << 3) | 2); raw(value.length); bytes.write(value, 0, value.length); return this; }
        Out fixed64(int field, double value) {
            raw(((long) field << 3) | 1);
            byte[] b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
            bytes.write(b, 0, 8); return this;
        }
        byte[] toBytes() { return bytes.toByteArray(); }
    }

    /* ------------------------------------------------------------- the protobuf reader (probe's) */

    private static final class Fields {
        final Map<Integer, List<Object>> values = new LinkedHashMap<>();
        final Map<Integer, List<Integer>> wireKinds = new LinkedHashMap<>();
        int offset;
        final byte[] bytes;
        Fields(byte[] bytes) {
            this.bytes = bytes;
            while (offset < bytes.length) {
                long tag = varint(); int field = (int) (tag >>> 3), kind = (int) tag & 7;
                if (field < 1) throw new IllegalArgumentException("Zero protobuf field");
                Object value;
                if (kind == 0) value = varint();
                else if (kind == 2) {
                    long size = varint();
                    if (size < 0 || size > MAX_PAYLOAD || size > bytes.length - offset)
                        throw new IllegalArgumentException("Truncated protobuf bytes");
                    byte[] copy = new byte[(int) size]; System.arraycopy(bytes, offset, copy, 0, copy.length);
                    offset += copy.length; value = copy;
                } else if (kind == 1 || kind == 5) {
                    int size = kind == 1 ? 8 : 4;
                    if (offset + size > bytes.length) throw new IllegalArgumentException("Truncated fixed protobuf");
                    byte[] copy = new byte[size]; System.arraycopy(bytes, offset, copy, 0, size); offset += size; value = copy;
                } else throw new IllegalArgumentException("Unsupported protobuf wire type");
                List<Object> list = values.get(field);
                if (list == null) { list = new ArrayList<>(); values.put(field, list); }
                list.add(value);
                List<Integer> kinds = wireKinds.get(field);
                if (kinds == null) { kinds = new ArrayList<>(); wireKinds.put(field, kinds); }
                kinds.add(kind);
            }
        }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                if (offset == bytes.length) throw new IllegalArgumentException("Truncated varint");
                int next = bytes[offset++] & 255;
                if (shift == 63 && next > 1) throw new IllegalArgumentException("Varint overflow");
                value |= (long) (next & 127) << shift;
                if (next < 128) return value;
            }
            throw new IllegalArgumentException("Oversized varint");
        }
        Object first(int field) { List<Object> list = values.get(field); return list == null ? null : list.get(list.size() - 1); }
        List<Object> all(int field) { List<Object> list = values.get(field); return list == null ? new ArrayList<Object>() : list; }
        List<Integer> kinds(int field) { List<Integer> list = wireKinds.get(field); return list == null ? new ArrayList<Integer>() : list; }
        int kind(int field) { List<Integer> list = wireKinds.get(field); return list == null ? -1 : list.get(list.size() - 1); }
        long number(int field) {
            Object value = first(field);
            if (value == null) return 0; // Generated proto3 primitive default.
            if (!(value instanceof Long)) throw new IllegalArgumentException("Expected integer field " + field);
            return (Long) value;
        }
        double real(int field) {
            Object value = first(field);
            if (value == null) return 0.0; // Generated protobuf DOUBLE default.
            if (kind(field) != 1 || !(value instanceof byte[]) || ((byte[]) value).length != 8)
                throw new IllegalArgumentException("Expected double field " + field);
            double result = ByteBuffer.wrap((byte[]) value).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            if (Double.isNaN(result) || Double.isInfinite(result)) throw new IllegalArgumentException("Nonfinite double field " + field);
            return result;
        }
        String text(int field) {
            Object value = first(field);
            if (value == null) return "";
            if (kind(field) != 2 || !(value instanceof byte[])) throw new IllegalArgumentException("Expected string field " + field);
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
    }
}
