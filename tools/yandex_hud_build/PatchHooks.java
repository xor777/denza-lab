import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

/** Adds context entry and completed-guidance-constructor hooks to SHA-pinned code. */
public final class PatchHooks {
    public static void main(String[] args) throws Exception {
        String kind = args[2];
        String owner = kind.equals("context") ? "Lcom/yandex/mapkit/MapKitFactory;"
                : "Lcom/yandex/navikit/guidance/internal/GuidanceBinding;";
        String name = kind.equals("context") ? "initialize" : "<init>";
        String parameter = kind.equals("context") ? "Landroid/content/Context;" : "Lcom/yandex/runtime/NativeObject;";
        int[] matches = {0}, calls = {0};
        DexFile input = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forApi(26));
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters unused) {
                return method -> {
                    if (!method.getDefiningClass().equals(owner) || !method.getName().equals(name)
                            || !method.getParameterTypes().equals(List.of(parameter))) return method;
                    matches[0]++;
                    MutableMethodImplementation code = new MutableMethodImplementation(method.getImplementation());
                    int register = code.getRegisterCount() - (kind.equals("context") ? 1 : 2);
                    ImmutableMethodReference hook = new ImmutableMethodReference("Ldev/denza/hud/probe/HudModule;",
                            kind.equals("context") ? "initialize" : "attach",
                            List.of(kind.equals("context") ? "Landroid/content/Context;" : "Ljava/lang/Object;"), "V");
                    if (kind.equals("context")) {
                        // R8 reuses the Context parameter register later in this method.
                        // Capture it at entry; the module defers all work to the main queue.
                        code.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, hook));
                        calls[0]++;
                    } else for (int i = code.getInstructions().size() - 1; i >= 0; i--) {
                        if (code.getInstructions().get(i).getOpcode() == Opcode.RETURN_VOID) {
                            code.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, hook));
                            calls[0]++;
                        }
                    }
                    return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                            method.getHiddenApiRestrictions(), code);
                };
            }
        });
        // Materialize once: lazy rewriters may otherwise invoke the transformation repeatedly.
        ImmutableDexFile output = ImmutableDexFile.of(rewriter.getDexFileRewriter().rewrite(input));
        if (matches[0] != 1 || calls[0] != 1) throw new IllegalStateException("Unexpected hook shape: " + matches[0] + "/" + calls[0]);
        if (kind.equals("guidance")) {
            // classes12.dex already has 65,536 method references. Relocate the one
            // patched class rather than overflow the original dex or rewrite unrelated code.
            List<ClassDef> kept = new ArrayList<>(), moved = new ArrayList<>();
            for (ClassDef value : output.getClasses()) (value.getType().equals(owner) ? moved : kept).add(value);
            if (moved.size() != 1) throw new IllegalStateException("Relocation mismatch");
            DexPool.writeTo(args[1], new ImmutableDexFile(input.getOpcodes(), kept));
            DexPool.writeTo(args[3], new ImmutableDexFile(input.getOpcodes(), moved));
        } else DexPool.writeTo(args[1], output);
        System.out.println(kind + ": one method, one hook; classes=" + output.getClasses().size());
    }
}
