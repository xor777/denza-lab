import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import java.io.File;
import java.util.*;

/**
 * Every obfuscated name the adapter reaches, checked against an APK before anything is patched.
 * Mirrors HudSpeedSource.read and HudSpeedModule.update member for member: a new HUD Speed
 * renames them, and this lists all that moved at once instead of one snapshot failure on the car.
 * Prints JSON: the dex defining MainService, the hook event types, and every missing member
 * with the public members of the same shape as candidates.
 */
public final class Symbols {
    static final String SERVICE = "Lair/StrelkaSD/MainService;";
    static final String HELPERS = "Lg/i;";
    static final String ANY = "*", BOOL = "bool", NUMBER = "number", STRING = "Ljava/lang/String;", LIST = "list";
    final Map<String, ClassDef> classes = new HashMap<>();
    final Map<String, String> dexOf = new HashMap<>();
    final List<String> missing = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Symbols symbols = new Symbols();
        MultiDexContainer<? extends DexFile> apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(23));
        for (String entry : apk.getDexEntryNames())
            for (ClassDef value : apk.getEntry(entry).getDexFile().getClasses()) {
                symbols.classes.put(value.getType(), value);
                symbols.dexOf.put(value.getType(), entry);
            }
        Map<String, String> events = symbols.check();
        StringBuilder json = new StringBuilder("{\"service_dex\": ").append(quote(symbols.dexOf.get(SERVICE)));
        json.append(", \"events\": {");
        String separator = "";
        for (Map.Entry<String, String> event : events.entrySet()) {
            json.append(separator).append(quote(event.getKey())).append(": ").append(quote(event.getValue()));
            separator = ", ";
        }
        json.append("}, \"missing\": [");
        for (int i = 0; i < symbols.missing.size(); i++) json.append(i == 0 ? "" : ", ").append(quote(symbols.missing.get(i)));
        System.out.println(json.append("]}"));
    }

    Map<String, String> check() {
        Map<String, String> events = new TreeMap<>();
        if (!present(SERVICE)) return events;
        // PatchHooks: five entry hooks on MainService, each name exactly once.
        method(SERVICE, "onCreate", List.of(), "V");
        method(SERVICE, "onDestroy", List.of(), "V");
        method(SERVICE, "onTaskRemoved", List.of("Landroid/content/Intent;"), "V");
        for (String name : List.of("onRadarUpdateEvent", "onRadarGpsStatusUpdateEvent")) {
            List<Method> found = declared(SERVICE, name);
            if (found.size() == 1 && found.get(0).getParameterTypes().size() == 1 && found.get(0).getReturnType().equals("V"))
                events.put(name, found.get(0).getParameterTypes().get(0).toString());
            else missing.add(SERVICE + name + "(event)V: " + found.size() + " declared");
        }
        // HudSpeedSource.read.
        String radar = field(SERVICE, "c", ANY);
        field(radar, "w", BOOL);
        method(radar, "h", List.of(), BOOL);
        field(radar, "z", STRING);
        method(radar, "f", List.of(), LIST);
        String position = field(radar, "n", ANY);
        field(radar, "G", BOOL);
        String camera = field(radar, "H", ANY);
        field(radar, "J", NUMBER);
        method(field(SERVICE, "d", ANY), "S", List.of(), NUMBER);
        for (String name : List.of("v", "u", "k", "l", "j")) method(camera, name, List.of(), NUMBER);
        method(camera, "s", List.of("B"), NUMBER);
        field(position, "d", NUMBER);
        field(position, "e", NUMBER);
        method(position, "a", List.of(), NUMBER);
        // HudSpeedModule.update: the app's own titles, distance and drawables.
        if (present(HELPERS)) {
            method(HELPERS, "y", List.of("Landroid/content/Context;", "I"), STRING);
            method(HELPERS, "x", List.of("D", "D", "D", "D"), NUMBER);
            String art = method(HELPERS, "w", List.of("I"), ANY);
            for (String name : List.of("b", "e", "d")) field(art, name, STRING);
        }
        return events;
    }

    boolean present(String type) {
        if (classes.containsKey(type)) return true;
        missing.add(type + " class");
        return false;
    }

    /** Class.getField: public, declared on the class or inherited within the APK. Returns its type. */
    String field(String owner, String name, String shape) {
        if (owner == null) return null;
        for (String type = owner; classes.containsKey(type); type = classes.get(type).getSuperclass())
            for (Field value : classes.get(type).getFields())
                if (value.getName().equals(name) && AccessFlags.PUBLIC.isSet(value.getAccessFlags())) {
                    if (fits(value.getType(), shape)) return value.getType();
                    missing.add(owner + "->" + name + ":" + shape + " is " + value.getType() + candidates(owner, shape, true, null));
                    return null;
                }
        missing.add(owner + "->" + name + ":" + shape + candidates(owner, shape, true, null));
        return null;
    }

    /** Class.getMethod: public, exact parameters, declared or inherited within the APK. Returns its return type. */
    String method(String owner, String name, List<String> parameters, String shape) {
        if (owner == null) return null;
        for (String type = owner; classes.containsKey(type); type = classes.get(type).getSuperclass())
            for (Method value : classes.get(type).getMethods())
                if (value.getName().equals(name) && AccessFlags.PUBLIC.isSet(value.getAccessFlags())
                        && strings(value.getParameterTypes()).equals(parameters)) {
                    if (fits(value.getReturnType(), shape)) return value.getReturnType();
                    missing.add(owner + "->" + name + parameters + ":" + shape + " returns " + value.getReturnType()
                            + candidates(owner, shape, false, parameters));
                    return null;
                }
        missing.add(owner + "->" + name + parameters + ":" + shape + candidates(owner, shape, false, parameters));
        return null;
    }

    List<Method> declared(String owner, String name) {
        List<Method> found = new ArrayList<>();
        for (Method value : classes.get(owner).getMethods()) if (value.getName().equals(name)) found.add(value);
        return found;
    }

    /** Public members of the same shape on the same class: where a renamed one probably went. */
    String candidates(String owner, String shape, boolean field, List<String> parameters) {
        Set<String> names = new TreeSet<>();
        for (String type = owner; classes.containsKey(type); type = classes.get(type).getSuperclass())
            if (field) {
                for (Field value : classes.get(type).getFields())
                    if (AccessFlags.PUBLIC.isSet(value.getAccessFlags()) && fits(value.getType(), shape)) names.add(value.getName());
            } else for (Method value : classes.get(type).getMethods())
                if (AccessFlags.PUBLIC.isSet(value.getAccessFlags()) && strings(value.getParameterTypes()).equals(parameters)
                        && fits(value.getReturnType(), shape) && !value.getName().startsWith("<")) names.add(value.getName());
        return names.isEmpty() ? "" : "; candidates " + names;
    }

    static boolean fits(String type, String shape) {
        switch (shape) {
            case ANY: return type.startsWith("L");
            case BOOL: return type.equals("Z") || type.equals("Ljava/lang/Boolean;");
            case NUMBER: return "BSIJFD".contains(type) && type.length() == 1
                    || List.of("Ljava/lang/Byte;", "Ljava/lang/Short;", "Ljava/lang/Integer;", "Ljava/lang/Long;",
                               "Ljava/lang/Float;", "Ljava/lang/Double;", "Ljava/lang/Number;").contains(type);
            case LIST: return List.of("Ljava/util/List;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;",
                                      "Ljava/util/concurrent/CopyOnWriteArrayList;").contains(type);
            default: return type.equals(shape);
        }
    }

    static List<String> strings(List<? extends CharSequence> values) {
        List<String> result = new ArrayList<>();
        for (CharSequence value : values) result.add(value.toString());
        return result;
    }

    static String quote(String value) {
        return value == null ? "null" : '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
