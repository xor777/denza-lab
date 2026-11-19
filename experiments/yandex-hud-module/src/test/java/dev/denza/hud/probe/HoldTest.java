package dev.denza.hud.probe;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudRole;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a held manoeuvre may put on the glass (docs/hud-output-architecture.md, 3.4). Every field
 * the module publishes, and every field of the hint behind them, is classified below: the shape a
 * hold carries, or the current frame's with its «unknown». A field nobody classified fails here,
 * so a new one cannot ride on a hold unseen. Only {@code ManeuverHints.apply} and
 * {@code ModuleFields.of} are called, the two steps the engine runs.
 */
public final class HoldTest {
    private static final Object ABSENT = new Object() { @Override public String toString() { return "absent"; } };
    /** Published fields a held manoeuvre gives a frame without one: the block already on the glass. */
    private static final Set<String> HELD = new HashSet<>(Arrays.asList("MANEUVER/8", "MANEUVER/9", "MANEUVER/28"));
    /** Every other published field, as it is when the current frame does not know it. */
    private static final Map<String, Object> UNKNOWN = new LinkedHashMap<>();
    static {
        for (String total : new String[]{"MANEUVER/3", "MANEUVER/4", "MANEUVER/26", "MANEUVER/27"}) UNKNOWN.put(total, ABSENT);
        UNKNOWN.put("MANEUVER/10", ABSENT); // a manoeuvre without a toponym, as Navigator gives many
        UNKNOWN.put("LANES/5", 0L); // the explicit «no lanes» of an active route
        UNKNOWN.put("LANES/7", ABSENT);
        UNKNOWN.put("ROAD_LIMIT/11", ABSENT);
        for (int field : new int[]{19, 20, 21, 22, 30, 31, 32, 33}) UNKNOWN.put("AR/" + field, ABSENT);
    }
    /** The hint's own fields: the shape a hold carries, and the rest, which only the current frame fills. */
    private static final Set<String> HINT_HELD = new HashSet<>(Arrays.asList("icon", "exit", "stockId", "distance"));
    private static final Set<String> HINT_CURRENT = new HashSet<>(Arrays.asList("action", "road", "limitText", "position",
            "followingPosition", "remainingMeters", "remainingSeconds", "speedLimit", "at", "limitValue"));

    private static int assertions;

    /** Every test, or the ones named (to run one against older code). */
    public static void main(String[] args) throws Exception {
        String[] all = {"everyHintFieldIsClassified", "aHeldFrameOwnsOnlyItsShape", "aHeldFinishNeverCapsTheNextRoute",
                "aHeldBlockCarriesNoOldTotals", "theCounterKeysAManoeuvreByItsRoute"};
        for (String name : args.length > 0 ? args : all) {
            try {
                HoldTest.class.getDeclaredMethod(name).invoke(null);
            } catch (java.lang.reflect.InvocationTargetException failed) {
                throw new AssertionError(name + ": " + failed.getCause().getMessage(), failed.getCause());
            }
        }
        System.out.println(assertions + " hold assertions passed");
    }

    private static void everyHintFieldIsClassified() {
        for (Field field : NavigatorSource.Hint.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            check(HINT_HELD.contains(field.getName()) != HINT_CURRENT.contains(field.getName()),
                    "hint field '" + field.getName() + "' is held or current, exactly one (docs/hud-output-architecture.md, 3.4)");
        }
    }

    /** A full frame, then frames that know nothing: whatever is not the shape is the current frame's unknown. */
    private static void aHeldFrameOwnsOnlyItsShape() throws Exception {
        String[] cases = {"rerouting onto a new route", "unread hint", "no manoeuvre ahead"};
        for (String what : cases) {
            ManeuverHints hints = new ManeuverHints();
            NavigatorSource.Hint shown = hints.apply(full("a"), 0);
            Map<String, Object> before = published(shown, 3, new byte[]{1, 2, 3}, ar());
            for (String key : before.keySet()) {
                check(HELD.contains(key) != UNKNOWN.containsKey(key), "published field " + key + " is held or current, exactly one");
            }
            NavigatorSource.Frame empty = new NavigatorSource.Frame();
            empty.mode = "guidance";
            empty.routeId = "a";
            if (what.startsWith("rerouting")) { empty.mode = "rerouting"; empty.routeId = "b"; }
            if (what.startsWith("no manoeuvre")) empty.hint = new NavigatorSource.Hint();
            NavigatorSource.Hint held = hints.apply(empty, 1000);
            check(held != null && hints.hold.holding != null, what + ": the block is held");
            // The current frame has no lanes and no AR: the engine passes it none.
            Map<String, Object> after = published(held, 0, null, null);
            for (Map.Entry<String, Object> field : before.entrySet()) {
                String key = field.getKey();
                Object expected = HELD.contains(key) ? field.getValue() : UNKNOWN.get(key);
                Object actual = after.containsKey(key) ? after.get(key) : ABSENT;
                check(same(expected, actual), what + ": field " + key + " is " + show(actual) + ", expected " + show(expected));
            }
            NavigatorSource.Hint nothing = new NavigatorSource.Hint();
            for (Field field : NavigatorSource.Hint.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                field.setAccessible(true);
                Object expected = HINT_HELD.contains(field.getName()) ? field.get(shown) : field.get(nothing);
                check(Objects.equals(expected, field.get(held)), what + ": hint." + field.getName() + " is "
                        + field.get(held) + ", expected " + expected);
            }
        }
    }

    /** Review round 2, finding 2: a held finish at 0 m capped the new route's finish at the same place. */
    private static void aHeldFinishNeverCapsTheNextRoute() {
        ManeuverHints hints = new ManeuverHints();
        check(hints.apply(finish("a", 20), 0).distance == 20, "the old route's finish 20 m away");
        NavigatorSource.Frame rebuild = new NavigatorSource.Frame();
        rebuild.mode = "rerouting";
        rebuild.routeId = "b";
        check(hints.apply(rebuild, 100).distance == 20, "the rebuild holds the 20 on the glass");
        check(hints.apply(finish("b", 350), 200).distance == 400, "the new route's finish shows its own 400 m, not the old 20");
        check(hints.apply(finish("b", 330), 300).distance == 300, "and counts down from there");
    }

    /** Review round 2, finding 8: a rebuild republished the old route's totals for up to 30 s. */
    private static void aHeldBlockCarriesNoOldTotals() {
        ManeuverHints hints = new ManeuverHints();
        hints.apply(full("a"), 0);
        NavigatorSource.Frame rebuild = new NavigatorSource.Frame();
        rebuild.mode = "rerouting";
        rebuild.routeId = "b";
        NavigatorSource.Hint held = hints.apply(rebuild, 1000);
        HudFields block = ModuleFields.of(held, new byte[]{7}, 0, null, null).get(HudRole.MANEUVER);
        for (int total : new int[]{3, 4, 26, 27}) check(!block.has(total), "a rebuild publishes no old total, field " + total);
        check(Arrays.equals(block.fields(), new int[]{8, 9, 28}), "a held block is arrow, metres and id: " + Arrays.toString(block.fields()));
    }

    /** A manoeuvre is its route and its place: a new route's at the same place starts over. */
    private static void theCounterKeysAManoeuvreByItsRoute() {
        ManeuverHints hints = new ManeuverHints();
        check(hints.apply(at("a", 400, 250), 0).distance == 250, "250 m");
        NavigatorSource.Frame gap = new NavigatorSource.Frame();
        gap.mode = "guidance";
        gap.routeId = "a";
        gap.hint = new NavigatorSource.Hint();
        check(hints.apply(gap, 100).distance == 250, "a blink holds it");
        check(hints.apply(at("a", 400, 262), 200).distance == 250, "the same manoeuvre never counts back up");
        check(hints.apply(at("b", 400, 280), 300).distance == 300, "another route's manoeuvre at the same place starts from its own");
    }

    private static NavigatorSource.Frame full(String route) {
        NavigatorSource.Frame frame = at(route, 400, 250);
        NavigatorSource.Hint hint = frame.hint;
        hint.action = "RIGHT";
        hint.road = "Тверская";
        hint.remainingMeters = 4200;
        hint.remainingSeconds = 660;
        hint.speedLimit = 60;
        hint.limitValue = 16.67;
        hint.limitText = "60";
        hint.position = new Object();
        hint.followingPosition = new Object();
        return frame;
    }

    private static NavigatorSource.Frame at(String route, int at, int distance) {
        NavigatorSource.Frame frame = new NavigatorSource.Frame();
        frame.mode = "guidance";
        frame.routeId = route;
        frame.hint = new NavigatorSource.Hint();
        frame.hint.icon = "right";
        frame.hint.stockId = 2;
        frame.hint.at = at;
        frame.hint.distance = distance;
        return frame;
    }

    private static NavigatorSource.Frame finish(String route, int distance) {
        NavigatorSource.Frame frame = at(route, 0, distance);
        frame.hint.icon = "finish";
        frame.hint.stockId = 48;
        return frame;
    }

    /** What ModuleFields publishes for {@code hint} as the engine calls it, by "ROLE/field". */
    private static Map<String, Object> published(NavigatorSource.Hint hint, int laneCount, byte[] lanes, HudAr.Frame ar) {
        byte[] icon = hint != null && hint.icon != null ? new byte[]{7, 7} : null;
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<HudRole, HudFields> role : ModuleFields.of(hint, icon, laneCount, lanes, ar).entrySet()) {
            for (int field : role.getValue().fields()) out.put(role.getKey() + "/" + field, role.getValue().value(field));
        }
        return out;
    }

    private static HudAr.Frame ar() {
        HudAr.Pose pose = HudAr.Pose.read(new HudAr.Point(37.5, 55.7), 90d, 10d, 4d, 150d, 1000L, 1000L, 100L);
        HudAr.Point[] line = {new HudAr.Point(37.5, 55.7), new HudAr.Point(37.501, 55.701)};
        return new HudAr.Frame(pose, line[1], line, .25);
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        return Objects.equals(a, b);
    }

    private static String show(Object value) {
        return value instanceof byte[] ? Arrays.toString((byte[]) value) : String.valueOf(value);
    }

    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
}
