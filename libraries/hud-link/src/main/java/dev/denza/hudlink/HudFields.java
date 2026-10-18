package dev.denza.hudlink;

import java.util.Map;
import java.util.TreeMap;

/**
 * Values of road-packet fields, one role's worth, as a source publishes them. What would not go
 * on the wire is not kept at all - empty text or bytes, a double that is not finite - so
 * {@link #has} answers whether the field reaches the HUD, the way {@link HudMessage} writes it.
 */
public final class HudFields {
    private final TreeMap<Integer, Object> values;

    private HudFields(TreeMap<Integer, Object> values) {
        this.values = values;
    }

    public boolean has(int field) {
        return values.containsKey(field);
    }

    /** A {@code Long}, {@code Double}, {@code String} or {@code byte[]}; null when absent. */
    public Object value(int field) {
        return values.get(field);
    }

    /** Field numbers, low first. */
    public int[] fields() {
        int[] out = new int[values.size()];
        int index = 0;
        for (Integer field : values.keySet()) {
            out[index++] = field;
        }
        return out;
    }

    /** Writes {@code field} into {@code message} as its kind says; nothing when absent. */
    void writeTo(HudMessage message, int field) {
        Object value = values.get(field);
        if (value instanceof Long) {
            message.number(field, (Long) value);
        } else if (value instanceof Double) {
            message.decimal(field, (Double) value);
        } else if (value instanceof String) {
            message.text(field, (String) value);
        } else if (value instanceof byte[]) {
            message.bytes(field, (byte[]) value);
        }
    }

    int byteSize() {
        int size = 0;
        for (Map.Entry<Integer, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            size += value instanceof byte[] ? ((byte[]) value).length : 16;
        }
        return size;
    }

    public static final class Builder {
        private final TreeMap<Integer, Object> values = new TreeMap<>();

        /** A varint, always written. */
        public Builder number(int field, long value) {
            values.put(field, value);
            return this;
        }

        /** A double, left out when not finite. */
        public Builder decimal(int field, double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                values.remove(field);
            } else {
                values.put(field, value);
            }
            return this;
        }

        /** UTF-8 text, left out when null or empty. */
        public Builder text(int field, String value) {
            if (value == null || value.isEmpty()) {
                values.remove(field);
            } else {
                values.put(field, value);
            }
            return this;
        }

        /** Bytes - a PNG - left out when null or empty; not copied. */
        public Builder bytes(int field, byte[] value) {
            if (value == null || value.length == 0) {
                values.remove(field);
            } else {
                values.put(field, value);
            }
            return this;
        }

        public HudFields build() {
            return new HudFields(new TreeMap<>(values));
        }
    }
}
