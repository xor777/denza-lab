package dev.denza.adaslink.stocknative;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless raw callback decoder. Native views resolve to live getter proxies, never route snapshots. */
public final class NativeTypedValues {
    public interface Resolver { Object reference(Map<String, Object> typed); }

    private final Resolver resolver;
    private final Map<String, Object> objects = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Map<String, Object>> originals = new IdentityHashMap<>();

    public NativeTypedValues(Resolver resolver) {
        if (resolver == null) throw new IllegalArgumentException("native reference resolver required");
        this.resolver = resolver;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("typed object required: " + value);
        return (Map<String, Object>) value;
    }

    public Map<String, Object> original(Object decoded) { return originals.get(decoded); }

    /** Apply the actual dispatch-time DTO snapshot to the same object seen at receipt. */
    public void refresh(Object typed) { decode(typed); }

    /** Bind identities only AFTER the caller verified the computed mutation graph.
     * No field, list item, map entry or scalar is replaced from this assertion. */
    public void bindVerifiedMutation(Object actual, Object typed) {
        Map<String, Object> value = row(typed);
        String kind = (String) value.get("type");
        if (actual == null || !(kind.equals("object") || kind.equals("list") || kind.equals("struct"))) return;
        remember((String) value.get("id"), actual, value);
        if (kind.equals("list")) {
            if (!(actual instanceof List)) throw new IllegalArgumentException("verified mutation list type");
            List<?> current = (List<?>) actual, expected = (List<?>) value.get("items");
            if (current.size() != expected.size()) throw new IllegalArgumentException("verified mutation list length");
            for (int i = 0; i < current.size(); i++) bindVerifiedMutation(current.get(i), expected.get(i));
        } else if (kind.equals("struct")) {
            for (Map.Entry<String, Object> entry : row(value.get("fields")).entrySet()) {
                if (actual instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) actual;
                    if (map.containsKey(entry.getKey())) bindVerifiedMutation(map.get(entry.getKey()), entry.getValue());
                    continue;
                }
                Field field = field(actual.getClass(), entry.getKey());
                if (field == null) continue;
                try { field.setAccessible(true); bindVerifiedMutation(field.get(actual), entry.getValue()); }
                catch (IllegalAccessException error) { throw new IllegalArgumentException(error); }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Object decode(Object typed) {
        Map<String, Object> value = row(typed);
        String type = (String) value.get("type");
        if ("null".equals(type)) return null;
        if ("i32".equals(type)) {
            int number = ((Number) value.get("value")).intValue();
            Object boxed = value.get("boxed_class");
            if (boxed == null || "java.lang.Integer".equals(boxed)) return Integer.valueOf(number);
            if ("java.lang.Short".equals(boxed) && number >= Short.MIN_VALUE && number <= Short.MAX_VALUE)
                return Short.valueOf((short) number);
            if ("java.lang.Byte".equals(boxed) && number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE)
                return Byte.valueOf((byte) number);
            throw new IllegalArgumentException("invalid original boxed i32 class/range: " + boxed);
        }
        if ("i64".equals(type)) return Long.valueOf((String) value.get("decimal"));
        if ("f32".equals(type)) return Float.intBitsToFloat((int) Long.parseLong((String) value.get("bits"), 16));
        if ("f64".equals(type)) return Double.longBitsToDouble(Long.parseUnsignedLong((String) value.get("bits"), 16));
        if ("boolean".equals(type) || "string".equals(type)) return value.get("value");
        if ("bytes".equals(type)) return Base64.getDecoder().decode((String) value.get("base64"));
        String id = (String) value.get("id");
        Object object = id == null ? null : objects.get(id);
        if ("object".equals(type)) {
            if (object == null) object = resolver.reference(value);
            if (object == null) throw new IllegalArgumentException("unresolved original object " + value);
            remember(id, object, value);
            return object;
        }
        String name = (String) value.get("class");
        if ("list".equals(type)) {
            if (object == null) object = new ArrayList<Object>();
            if (!(object instanceof List)) throw new IllegalArgumentException("identity changed to list: " + id);
            remember(id, object, value);
            List<Object> list = (List<Object>) object;
            List<Object> decoded = new ArrayList<>();
            for (Object item : (List<?>) value.get("items")) decoded.add(decode(item));
            list.clear(); list.addAll(decoded);
            return list;
        }
        if (!"struct".equals(type)) throw new IllegalArgumentException("unknown typed value " + type);
        Map<String, Object> fields = row(value.get("fields"));
        if ("java.math.BigInteger".equals(name)) {
            object = new BigInteger((String) decode(fields.get("decimal")));
            remember(id, object, value);
            return object;
        }
        if (virtual(name)) {
            if (object == null) object = resolver.reference(value);
            if (object == null) throw new IllegalArgumentException("unresolved original virtual view " + name);
            remember(id, object, value);
            return object;
        }
        if (object == null) object = newStruct(name);
        remember(id, object, value); // Cyclic DTO references must resolve before reading children.
        if (object instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) object;
            map.clear();
            if (name.startsWith("java.util.") && name.contains("Map") && fields.containsKey("entries")) {
                for (Object pair : (List<?>) decode(fields.get("entries"))) {
                    List<?> entry = (List<?>) pair;
                    if (entry.size() != 2) throw new IllegalArgumentException("raw Java Map entry arity");
                    map.put(entry.get(0), entry.get(1));
                }
            } else {
                for (Map.Entry<String, Object> field : fields.entrySet()) map.put(field.getKey(), decode(field.getValue()));
            }
        } else {
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                Field field = field(object.getClass(), entry.getKey());
                // The raw snapshot retains all SDK members; the finite source port
                // models only consumed fields. No absent member is synthesized.
                if (field == null) continue;
                try {
                    field.setAccessible(true);
                    field.set(object, convert(decode(entry.getValue()), field.getGenericType()));
                } catch (ReflectiveOperationException error) {
                    throw new IllegalArgumentException("cannot refresh " + name + "." + entry.getKey(), error);
                }
            }
        }
        return object;
    }

    private void remember(String id, Object object, Map<String, Object> value) {
        if (id != null) {
            Object old = objects.get(id);
            if (old != null && old != object) throw new IllegalArgumentException("original identity rebound: " + id);
            objects.put(id, object);
        }
        originals.put(object, value);
    }

    private static boolean virtual(String name) {
        return name.endsWith(".PathInfo") || name.endsWith(".SegmentInfo") || name.endsWith(".LinkInfo")
            || name.endsWith(".RouteCarResultData") || name.endsWith(".RouteOption") || name.endsWith(".POI")
            || name.endsWith(".AlongWaySearchPoi") || name.endsWith(".AlongWayPoiDeepInfo");
    }

    private static Object newStruct(String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (simple.equals("Coord2DDouble")) return new NativePath.Point();
        if (simple.equals("Coord2DInt32")) return new NativePath.IntPoint();
        if (simple.equals("GroupSegment")) return new NativePath.Group();
        if (simple.equals("ViaRoadInfo")) return new NativePath.ViaRoad();
        if (name.equals("com.byd.map.signal.someip.pilot.bean.TrafficLightState")) return new TrafficLightState();
        try {
            Class<?> type = Class.forName(NativeModels.class.getName() + "$" + simple);
            if (!type.isInterface()) return type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException absent) {
            // Unknown SDK structs remain visible in the raw registry and as a field map.
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("cannot construct original DTO " + name, error);
        }
        return new LinkedHashMap<String, Object>();
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { return owner.getDeclaredField(name); } catch (NoSuchFieldException absent) { /* inherited */ }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object value, Type target) {
        if (value == null) return null;
        if (target instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) target;
            if (value instanceof List && List.class.isAssignableFrom((Class<?>) parameterized.getRawType())) {
                List<Object> list = (List<Object>) value;
                Type itemType = parameterized.getActualTypeArguments()[0];
                for (int index = 0; index < list.size(); index++) list.set(index, convert(list.get(index), itemType));
            }
            return value;
        }
        if (target instanceof Class && value instanceof Number) {
            Number number = (Number) value;
            if (target == byte.class || target == Byte.class) return number.byteValue();
            if (target == short.class || target == Short.class) return number.shortValue();
            if (target == int.class || target == Integer.class) return number.intValue();
            if (target == long.class || target == Long.class) return number.longValue();
            if (target == float.class || target == Float.class) return number.floatValue();
            if (target == double.class || target == Double.class) return number.doubleValue();
        }
        return value;
    }
}
