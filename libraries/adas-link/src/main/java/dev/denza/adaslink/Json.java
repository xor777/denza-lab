package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON text exactly as the stock map's Gson writes it ({@code new GsonBuilder().serializeNulls()
 * .disableHtmlEscaping()}): keys in the order given (the stock beans' fields, which ART returns
 * sorted by name), long and int as decimal, float through {@link Float#toString}, double through
 * {@link Double#toString}, Gson's string escapes. Plus a small reader for fixtures and traces.
 */
public final class Json {
    /** A float field, serialized as Gson serializes a {@code float}. */
    public static final class F {
        public final float value;
        public F(float value) { this.value = value; }
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) string(out, (String) value);
        else if (value instanceof Boolean) out.append(((Boolean) value) ? "true" : "false");
        else if (value instanceof F) {
            float f = ((F) value).value;
            if (Float.isNaN(f) || Float.isInfinite(f)) throw new IllegalArgumentException("non-finite float");
            out.append(Float.toString(f));
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite double");
            out.append(Double.toString(d));
        } else if (value instanceof Number) out.append(((Number) value).longValue());
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, entry.getKey());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else throw new IllegalArgumentException("unsupported JSON value " + value.getClass());
    }

    /** Gson's JsonWriter.string with htmlSafe off. */
    static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\t') out.append("\\t");
            else if (c == '\b') out.append("\\b");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\f') out.append("\\f");
            else if (c < 0x20 || c == ' ' || c == ' ') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    public static Map<String, Object> object() { return new LinkedHashMap<>(); }

    // --- reader: numbers come back as Long when integral text, Double otherwise ---

    public static Object parse(String text) {
        int[] at = {0};
        Object value = value(text, at);
        skip(text, at);
        if (at[0] != text.length()) throw new IllegalArgumentException("trailing JSON at " + at[0]);
        return value;
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length()) {
            char c = s.charAt(at[0]);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') at[0]++;
            else break;
        }
    }

    private static Object value(String s, int[] at) {
        skip(s, at);
        if (at[0] >= s.length()) throw new IllegalArgumentException("truncated JSON");
        char c = s.charAt(at[0]);
        if (c == '{') {
            at[0]++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip(s, at);
            if (s.charAt(at[0]) == '}') { at[0]++; return map; }
            while (true) {
                skip(s, at);
                String key = (String) value(s, at);
                skip(s, at);
                expect(s, at, ':');
                map.put(key, value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, '}');
                return map;
            }
        }
        if (c == '[') {
            at[0]++;
            List<Object> list = new ArrayList<>();
            skip(s, at);
            if (s.charAt(at[0]) == ']') { at[0]++; return list; }
            while (true) {
                list.add(value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, ']');
                return list;
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            at[0]++;
            while (true) {
                char d = s.charAt(at[0]++);
                if (d == '"') return b.toString();
                if (d != '\\') { b.append(d); continue; }
                char e = s.charAt(at[0]++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); at[0] += 4; break;
                    default: b.append(e);
                }
            }
        }
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String number = s.substring(start, at[0]);
        if (number.isEmpty()) throw new IllegalArgumentException("bad JSON at " + start);
        if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
            try { return Long.parseLong(number); } catch (NumberFormatException ignored) { return Double.parseDouble(number); }
        }
        return Double.parseDouble(number);
    }

    private static void expect(String s, int[] at, char c) {
        if (s.charAt(at[0]) != c) throw new IllegalArgumentException("expected " + c + " at " + at[0]);
        at[0]++;
    }

    private Json() {}
}
