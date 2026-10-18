import java.io.*;import java.util.*;import java.util.zip.*;
import com.android.tools.smali.dexlib2.*;import com.android.tools.smali.dexlib2.iface.*;import com.android.tools.smali.dexlib2.iface.instruction.*;import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;import com.android.tools.smali.dexlib2.immutable.reference.*;import com.android.tools.smali.dexlib2.dexbacked.*;import com.android.tools.smali.dexlib2.builder.*;import com.android.tools.smali.dexlib2.builder.instruction.*;import com.android.tools.smali.dexlib2.writer.pool.*;import com.android.tools.smali.dexlib2.writer.io.*;
public class IdentityPatch{
 static final String H="Llocal/mapscompat/ClientIdentity;";
 static byte[] dex(Collection<ClassDef> cs)throws Exception{MemoryDataStore s=new MemoryDataStore();DexPool.writeTo(s,new ImmutableDexFile(Opcodes.forApi(26),cs));return Arrays.copyOf(s.getBuffer(),s.getSize());}
 public static void main(String[]a)throws Exception{
  List<ClassDef> moved=new ArrayList<>();int count=0,engines=0;int highest=1;
  try(ZipFile src=new ZipFile(a[0]);ZipOutputStream dst=new ZipOutputStream(new FileOutputStream(a[1]))){
   for(ZipEntry z:Collections.list(src.entries())){
    if(z.getName().startsWith("META-INF/"))continue;
    byte[] b=src.getInputStream(z).readAllBytes();
    if(z.getName().matches("classes[0-9]*\\.dex")){
     String digits=z.getName().replaceAll("[^0-9]","");highest=Math.max(highest,digits.isEmpty()?1:Integer.parseInt(digits));
     DexBackedDexFile d=new DexBackedDexFile(Opcodes.forApi(26),java.nio.ByteBuffer.wrap(b));List<ClassDef> keep=new ArrayList<>();
     for(ClassDef c:d.getClasses()){
      if(c.getType().startsWith("Lorg/chromium/")){keep.add(c);continue;}
      boolean changed=false;List<Method> methods=new ArrayList<>();
      for(Method m:c.getMethods()){
       MethodImplementation impl=m.getImplementation();if(impl==null){methods.add(m);continue;}
       MutableMethodImplementation mi=new MutableMethodImplementation(impl);boolean mc=false;
       for(int i=mi.getInstructions().size()-1;i>=0;i--){
        Instruction x=mi.getInstructions().get(i);
        if(x instanceof ReferenceInstruction && ((ReferenceInstruction)x).getReference() instanceof MethodReference){
         MethodReference r=(MethodReference)((ReferenceInstruction)x).getReference();
         if(r.getDefiningClass().startsWith("Lorg/chromium/net/") && r.getName().equals("addHeader") && r.getParameterTypes().toString().equals("[Ljava/lang/String;, Ljava/lang/String;]")) {
          int key,value;BuilderInstruction call;
          ImmutableMethodReference fix=new ImmutableMethodReference(H,"header",List.of("Ljava/lang/String;","Ljava/lang/String;"),"Ljava/lang/String;");
          if(x instanceof FiveRegisterInstruction){FiveRegisterInstruction f=(FiveRegisterInstruction)x;if(f.getRegisterCount()!=3)throw new IllegalStateException();key=f.getRegisterD();value=f.getRegisterE();call=new BuilderInstruction35c(Opcode.INVOKE_STATIC,2,key,value,0,0,0,fix);}
          else if(x instanceof RegisterRangeInstruction){RegisterRangeInstruction f=(RegisterRangeInstruction)x;if(f.getRegisterCount()!=3)throw new IllegalStateException();key=f.getStartRegister()+1;value=key+1;call=new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,key,2,fix);}
          else throw new IllegalStateException("Unsupported header call");
          mi.addInstruction(i,new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,value));
          mi.addInstruction(i,call);mc=true;engines++;System.out.println("header " +m);
         }
        }
       }
       if(mc){changed=true;methods.add(new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),mi));}else methods.add(m);
      }
      if(changed)moved.add(new ImmutableClassDef(c.getType(),c.getAccessFlags(),c.getSuperclass(),c.getInterfaces(),c.getSourceFile(),c.getAnnotations(),c.getFields(),methods));else keep.add(c);
     }
     b=dex(keep);
    }
    ZipEntry out=new ZipEntry(z.getName());if(z.getMethod()==ZipEntry.STORED){out.setMethod(ZipEntry.STORED);out.setSize(b.length);CRC32 crc=new CRC32();crc.update(b);out.setCrc(crc.getValue());}dst.putNextEntry(out);dst.write(b);dst.closeEntry();
   }
   DexBackedDexFile helper=new DexBackedDexFile(Opcodes.forApi(26),java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[2]))));moved.addAll(helper.getClasses());
   dst.putNextEntry(new ZipEntry("classes"+(highest+1)+".dex"));dst.write(dex(moved));dst.closeEntry();
  }
  if(engines!=30){java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(a[1]));throw new IllegalStateException("Expected exactly 30 Maps 25.16 header hooks, found "+engines);}
  System.out.println("header hooks="+engines+" callbacks="+count);
 }
}
