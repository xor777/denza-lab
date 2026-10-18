import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.value.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue;
import org.jf.dexlib2.rewriter.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Local Maps 25 package compatibility, before signing. Does not rename extension classes. */
public final class RenamePackage {
    static final String OLD = "app.morphe.android.apps.maps";
    static final String NEW = "app.revanced.android.apps.maps";
    static String replace(String value) { return value.replace(OLD, NEW); }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.apk output.apk");
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters r) {
                return new InstructionRewriter(r) {
                    @Override public Instruction rewrite(Instruction i) {
                        if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof StringReference) {
                            String old = ((StringReference) ((ReferenceInstruction) i).getReference()).getString();
                            String value = replace(old);
                            if (!value.equals(old)) {
                                int register = ((OneRegisterInstruction) i).getRegisterA();
                                if (i.getOpcode() == Opcode.CONST_STRING) return new ImmutableInstruction21c(i.getOpcode(), register, new ImmutableStringReference(value));
                                if (i.getOpcode() == Opcode.CONST_STRING_JUMBO) return new ImmutableInstruction31c(i.getOpcode(), register, new ImmutableStringReference(value));
                                throw new IllegalStateException("Unexpected string instruction");
                            }
                        }
                        return super.rewrite(i);
                    }
                };
            }
            @Override public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters r) {
                return new EncodedValueRewriter(r) {
                    @Override public EncodedValue rewrite(EncodedValue value) {
                        if (value instanceof StringEncodedValue) return new ImmutableStringEncodedValue(replace(((StringEncodedValue) value).getValue()));
                        return super.rewrite(value);
                    }
                };
            }
        });
        try (ZipFile input = new ZipFile(args[0]); ZipOutputStream output = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (ZipEntry entry : Collections.list(input.entries())) {
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] bytes = input.getInputStream(entry).readAllBytes();
                if (entry.getName().matches("classes[0-9]*\\.dex")) {
                    DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), bytes);
                    boolean needed = false;
                    for (StringReference s : dex.getStringReferences()) if (s.getString().contains(OLD)) needed = true;
                    if (needed) {
                        MemoryDataStore store = new MemoryDataStore();
                        DexPool.writeTo(store, rewriter.getDexFileRewriter().rewrite(dex));
                        bytes = store.getData();
                        System.out.println("renamed literals in " + entry.getName());
                    }
                }
                ZipEntry target = new ZipEntry(entry.getName());
                target.setMethod(entry.getMethod()); target.setTime(entry.getTime());
                if (target.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32(); crc.update(bytes); target.setSize(bytes.length); target.setCrc(crc.getValue());
                }
                output.putNextEntry(target); output.write(bytes); output.closeEntry();
            }
        }
    }
}
