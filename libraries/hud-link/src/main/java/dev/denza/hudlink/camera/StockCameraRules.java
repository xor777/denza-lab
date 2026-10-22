package dev.denza.hudlink.camera;

/** Native new-3D configuration selection, before route geometry and transition evaluation.
 * Defaults were read from an independently constructed original GBL config manager (mask63).
 * A car's cloud override is not inferred from those defaults. Unknown input never becomes zero. */
public final class StockCameraRules {
    private StockCameraRules() { }

    public enum Table { ACTION_ZOOM, END_ZOOM, COMPLEX_ZOOM, BASE_ZOOM, KEYPOINT_PITCH, BASE_PITCH }
    public static final class Input {
        public final float roadClass, formway, curvature, zoomLevel, screenType;
        public Input(float roadClass, float formway, float curvature, float zoomLevel, float screenType) {
            this.roadClass = roadClass; this.formway = formway; this.curvature = curvature;
            this.zoomLevel = zoomLevel; this.screenType = screenType;
        }
        float field(int index) {
            switch (index) {
                case 0: return roadClass;
                case 1: return formway;
                case 2: return curvature;
                case 3: return zoomLevel;
                case 4: return screenType;
                default: throw new IllegalArgumentException("camera condition field");
            }
        }
    }
    public static final class Condition {
        final int field, operation;
        final float[] values;
        Condition(int field, int operation, float... values) { this.field = field; this.operation = operation; this.values = values; }
        boolean matches(Input input) {
            float value = input.field(field);
            if (!Float.isFinite(value)) return false;
            switch (operation) {
                case 1: return value < values[0];
                case 2: return value < values[0] || equal(value, values[0]);
                case 3: return equal(value, values[0]);
                case 5: return value > values[0];
                case 6: return value > values[0] || equal(value, values[0]);
                case 7: case 8:
                    boolean included = false;
                    for (float other : values) included |= equal(value, other);
                    return operation == 7 ? included : !included;
                default: return false;
            }
        }
        private static boolean equal(float a, float b) { return Math.abs(a - b) <= 1e-6f; }
    }
    public static final class Rule {
        public final int index, effectDistance;
        public final float target, lower, upper;
        final Condition[] conditions;
        Rule(int index, float target, float lower, float upper, int effectDistance, Condition... conditions) {
            this.index = index; this.target = target; this.lower = lower; this.upper = upper;
            this.effectDistance = effectDistance; this.conditions = conditions;
        }
        boolean matches(Input input) {
            for (Condition condition : conditions) if (!condition.matches(input)) return false;
            return true;
        }
    }
    public static Rule select(Table table, Input input) {
        for (Rule rule : StockCameraDefaults.TABLES[table.ordinal()]) if (rule.matches(input)) return rule;
        return null;
    }

    public static final class Target {
        public float value;
        public int count;
        public Target(float value, int count) { this.value = value; this.count = count; }
    }

    /** Original SimpleZoom ELF0xf80a7c: an absent optional leaves both outputs unchanged. */
    public static boolean simpleZoom(Rule rule, Target result) {
        if (rule == null || Float.isNaN(rule.target)) return false;
        result.value = rule.target;
        result.count = 10;
        return true;
    }

    /** Original SimplePitch ELF0xf82d78 deliberately differs in its absent-optional case. */
    public static boolean simplePitch(Rule rule, Target result) {
        result.value = rule == null || Float.isNaN(rule.target) ? -9999 : rule.target;
        result.count = 10;
        return true;
    }
}
