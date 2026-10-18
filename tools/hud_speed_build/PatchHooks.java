import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.*;

/** Five entry hooks in exactly one class. Move it to a new dex to avoid reference overflow. */
public final class PatchHooks {
    static final String OWNER = "Lair/StrelkaSD/MainService;";
    public static void main(String[] args) throws Exception {
        Map<String, String> hooks = Map.of("onCreate", "start", "onDestroy", "stop",
                "onTaskRemoved", "stop", "onRadarUpdateEvent", "update", "onRadarGpsStatusUpdateEvent", "update");
        Map<String, List<String>> parameters = Map.of("onCreate", List.of(), "onDestroy", List.of(),
                "onTaskRemoved", List.of("Landroid/content/Intent;"),
                "onRadarUpdateEvent", List.of("Lf/j;"), "onRadarGpsStatusUpdateEvent", List.of("Lf/i;"));
        Set<String> seen = new HashSet<>();
        DexFile input = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forApi(23));
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            public Rewriter<Method> getMethodRewriter(Rewriters unused) {
                return method -> {
                    if (!OWNER.equals(method.getDefiningClass()) || !hooks.containsKey(method.getName())) return method;
                    if (!method.getParameterTypes().equals(parameters.get(method.getName())) ||
                            !method.getReturnType().equals("V") || !seen.add(method.getName()))
                        throw new IllegalStateException("Unexpected hook signature " + method);
                    MutableMethodImplementation code = new MutableMethodImplementation(method.getImplementation());
                    int self = code.getRegisterCount() - 1 - method.getParameterTypes().size();
                    code.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, self, 1,
                            new ImmutableMethodReference("Ldev/denza/hud/speed/HudSpeedModule;", hooks.get(method.getName()),
                                    List.of("Landroid/content/Context;"), "V")));
                    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                            method.getHiddenApiRestrictions(), code);
                };
            }
        });
        ImmutableDexFile output = ImmutableDexFile.of(rewriter.getDexFileRewriter().rewrite(input));
        if (!seen.equals(hooks.keySet())) throw new IllegalStateException("Missing hooks " + seen);
        List<ClassDef> kept = new ArrayList<>(), moved = new ArrayList<>();
        for (ClassDef value : output.getClasses()) (value.getType().equals(OWNER) ? moved : kept).add(value);
        if (moved.size() != 1) throw new IllegalStateException("Missing service");
        DexPool.writeTo(args[1], new ImmutableDexFile(input.getOpcodes(), kept));
        DexPool.writeTo(args[2], new ImmutableDexFile(input.getOpcodes(), moved));
        System.out.println("MainService: five entry hooks, one relocated class");
    }
}
