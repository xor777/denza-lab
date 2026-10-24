package dev.denza.adaslink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit functional approximation, not equality between the two vendors' enums.
 * See research/adas-bridge/README.md, "Generic vector runtime and offline prerequisite".
 * Never assigns freeway/urban-expressway, administrative ownership, access or lanes.
 */
public final class YandexRoadClass {
    public static final String VERSION = "yandex-functional-v1";
    public static final class Decision {
        public final Integer roadClass;
        public final String rule, basis;
        private Decision(Integer value, String rule, String basis) {
            roadClass = value; this.rule = rule; this.basis = basis;
        }
        public Map<String,Object> evidence() {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("version", VERSION); out.put("amapRoadClass", roadClass);
            out.put("rule", rule); out.put("basis", basis);
            out.put("quality", roadClass == null ? "unresolved" : "functional_approximation");
            return Collections.unmodifiableMap(out);
        }
    }

    /** Called only after geometric/level association. fc alone never establishes driving mode. */
    public static Decision map(Map<String,Object> attributes) {
        if (attributes == null || !(attributes.get("class") instanceof String))
            return no("class_missing", "No literal driving category");
        String literal = (String) attributes.get("class");
        String base = literal.endsWith("_link") ? literal.substring(0, literal.length() - 5) : literal;
        if (base.length() != 1 || base.charAt(0) < '1' || base.charAt(0) > '9')
            return no("non_driving_or_unknown_category", "A numeric fc does not turn this category into a driving road");
        int category = base.charAt(0) - '0';
        Object raw = attributes.get("fc");
        if (!(raw instanceof Integer) && !(raw instanceof Long))
            return no("fc_type_unresolved", "Expected the captured integral fc type");
        if (((Number) raw).longValue() != category)
            return no("class_fc_conflict", "Literal driving class and functional category disagree");
        switch (category) {
            case 1:
                return no("motorway_or_urban_expressway", "Yandex category 1 cannot choose Amap 0 versus 6");
            case 2: case 3: case 4:
                return new Decision(7, "important_to_main", "Preserve functional importance without inventing administrative road ownership");
            case 5: case 6:
                return new Decision(8, "local_to_secondary", "Approximate district/local function as secondary road");
            case 7: case 8: case 9:
                return new Decision(9, "minor_to_common", "Approximate minor/access/field-road function as ordinary road; access is separate");
            default: throw new AssertionError(category);
        }
    }
    private static Decision no(String rule, String basis) { return new Decision(null, rule, basis); }
    private YandexRoadClass() { }
}
