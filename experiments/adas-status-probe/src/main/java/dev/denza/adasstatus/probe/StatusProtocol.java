package dev.denza.adasstatus.probe;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact retained stock protobuf fields, no vehicle functions or Android dependency. */
final class StatusProtocol {
    static final long PILOT = 0x0004000D000D8002L;
    static final long ALARM = 0x0004000D000D8003L;
    static final long MODE = 0x0004000D000D8005L;
    static final long SELECTED = 0x0004002C002C8002L;
    static final long PATH_MATCH = 0x0004002C002C8001L;
    static final long[] TOPICS = {PILOT, ALARM, MODE, SELECTED, PATH_MATCH};
    static final int MAX_PAYLOAD = 65536;

    static boolean allowed(long topic) {
        for (long value : TOPICS) if (topic == value) return true;
        return false;
    }
    static String hex(long value) { return String.format("0x%013X", value); }
    static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { int value = bytes[i] & 255; out[i*2] = digits[value >>> 4]; out[i*2+1] = digits[value & 15]; }
        return new String(out);
    }

    static Map<String, Object> decode(long topic, byte[] payload) {
        if (!allowed(topic)) throw new IllegalArgumentException("Unsubscribed topic");
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Payload cap");
        Fields outer = new Fields(payload);
        if (outer.all(1).size() != 1 || outer.kind(1) != 2 || !(outer.first(1) instanceof byte[]))
            throw new IllegalArgumentException("Expected notification struct field 1");
        Fields f = new Fields((byte[]) outer.first(1));
        Map<String, Object> out = new LinkedHashMap<>();
        if (topic == PILOT) {
            out.put("event", "pilotStatus");
            out.put("acc", (int) f.number(3)); out.put("icc", (int) f.number(4));
            out.put("dnp", (int) f.number(5)); out.put("takeoverStatus", f.number(6) != 0);
            out.put("drivingTime", f.number(7));
        } else if (topic == MODE) {
            out.put("event", "noaMode"); out.put("noaMode", (int) f.number(3));
            out.put("notice", (int) f.number(4));
        } else if (topic == ALARM) {
            out.put("event", "alarm"); out.put("pilotAlarmReason", (int) f.number(3));
            out.put("alarmDistance", (int) f.number(4)); out.put("alarmStage", (int) f.number(5));
            out.put("alarmTimestamp", f.real(6)); out.put("pilotNotice", (int) f.number(7));
            out.put("noticeDistance", (int) f.number(8)); out.put("noticeTimestamp", f.real(9));
        } else if (topic == SELECTED) {
            out.put("event", "pathSelected"); out.put("pathIDResp", f.number(3));
            out.put("statusCode", (int) f.number(4)); out.put("errorMsg", f.text(5));
            out.put("selectionConfirmed", f.number(4) == 200);
        } else {
            out.put("event", "pathMatch"); out.put("code", f.number(3));
            out.put("msg", f.text(5));
            List<Map<String, Object>> routes = new ArrayList<>();
            for (int kind : f.kinds(4)) if (kind != 2) throw new IllegalArgumentException("RouteInfo wire kind");
            for (Object value : f.all(4)) {
                if (!(value instanceof byte[])) throw new IllegalArgumentException("RouteInfo wire type");
                Fields item = new Fields((byte[]) value);
                Map<String, Object> route = new LinkedHashMap<>();
                route.put("pathID", item.number(1)); route.put("totalNOADistance", item.number(2));
                route.put("routeBeginIdx", item.number(3)); route.put("routeEndIdx", item.number(4));
                route.put("statusCode", item.number(5)); routes.add(route);
            }
            out.put("routeInfoArray", routes);
        }
        return out;
    }

    static String availability(Integer value) {
        if (value == null) return "UNKNOWN";
        if (value == 0) return "NOT OFFERED / OFF (0)";
        if (value == 1) return "ACTIVE (1)";
        if (value == 2) return "AVAILABLE (2)";
        return "UNKNOWN (" + value + ")";
    }
    static String mode(Integer value) {
        if (value == null) return "UNKNOWN";
        if (value == 1) return "HNOA / HIGHWAY (1)";
        if (value == 2) return "CNOA / CITY (2)";
        return "UNKNOWN (" + value + ")";
    }

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
        Object first(int field) { List<Object> list = values.get(field); return list == null ? null : list.get(list.size()-1); }
        List<Object> all(int field) { List<Object> list = values.get(field); return list == null ? new ArrayList<>() : list; }
        List<Integer> kinds(int field) { List<Integer> list = wireKinds.get(field); return list == null ? new ArrayList<>() : list; }
        int kind(int field) { List<Integer> list = wireKinds.get(field); return list == null ? -1 : list.get(list.size()-1); }
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
            if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite double field " + field);
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
