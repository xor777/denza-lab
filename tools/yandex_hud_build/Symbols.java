import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Where the two hooks live in an APK, and whether the MapKit names the module reaches exist.
 * Arguments: APK, then a names file from symbols.py ("class <java name>" / "method <name>" lines).
 * Prints JSON: the dex defining each hook class, and every missing class, method or hook.
 * A method name counts when some public com.yandex class declares it public: the module calls
 * MapKit by name on objects it receives, so this catches a rename, not a changed meaning.
 */
public final class Symbols {
    /** PatchHooks's two targets: owner, method, its one parameter. */
    static final String[][] HOOKS = {
        {"context", "Lcom/yandex/mapkit/MapKitFactory;", "initialize", "Landroid/content/Context;"},
        {"guidance", "Lcom/yandex/navikit/guidance/internal/GuidanceBinding;", "<init>", "Lcom/yandex/runtime/NativeObject;"},
    };

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        Map<String, String> dexOf = new HashMap<>();
        MultiDexContainer<? extends DexFile> apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(26));
        for (String entry : apk.getDexEntryNames())
            for (ClassDef value : apk.getEntry(entry).getDexFile().getClasses()) {
                classes.put(value.getType(), value);
                dexOf.put(value.getType(), entry);
            }
        List<String> missing = new ArrayList<>();
        StringBuilder json = new StringBuilder("{\"hooks\": {");
        for (int i = 0; i < HOOKS.length; i++) {
            String[] hook = HOOKS[i];
            int found = 0;
            if (classes.containsKey(hook[1]))
                for (Method value : classes.get(hook[1]).getMethods())
                    if (value.getName().equals(hook[2]) && value.getParameterTypes().size() == 1
                            && value.getParameterTypes().get(0).toString().equals(hook[3])) found++;
            if (found != 1) missing.add(hook[1] + "->" + hook[2] + "(" + hook[3] + "): " + found + " declared");
            json.append(i == 0 ? "" : ", ").append(quote(hook[0])).append(": ").append(quote(found == 1 ? dexOf.get(hook[1]) : null));
        }
        Set<String> methods = new HashSet<>();
        for (ClassDef value : classes.values())
            if (value.getType().startsWith("Lcom/yandex/") && AccessFlags.PUBLIC.isSet(value.getAccessFlags()))
                for (Method method : value.getMethods())
                    if (AccessFlags.PUBLIC.isSet(method.getAccessFlags())) methods.add(method.getName());
        for (String line : Files.readAllLines(new File(args[1]).toPath())) {
            String[] parts = line.split(" ");
            if (parts[0].equals("class")) {
                ClassDef value = classes.get("L" + parts[1].replace('.', '/') + ";");
                if (value == null || !AccessFlags.PUBLIC.isSet(value.getAccessFlags())) missing.add(parts[1] + " class");
            } else if (!methods.contains(parts[1])) missing.add(parts[1] + " method");
        }
        json.append("}, \"missing\": [");
        for (int i = 0; i < missing.size(); i++) json.append(i == 0 ? "" : ", ").append(quote(missing.get(i)));
        System.out.println(json.append("]}"));
    }

    static String quote(String value) {
        return value == null ? "null" : '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
