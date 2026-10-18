import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;
public class AdaptPatch {
 public static void main(String[] a)throws Exception {
  Map<String,Integer> hits=new HashMap<>();
  for(String n:Arrays.asList("suppressMisleadingPlayServicesUpdateNotification","patchBydNavigationAudio"))hits.put(n,0);
  try(ZipFile src=new ZipFile(a[0]);ZipOutputStream dst=new ZipOutputStream(new FileOutputStream(a[1]))) {
   for(ZipEntry e:Collections.list(src.entries())) {
    byte[] bytes=src.getInputStream(e).readAllBytes();
    if(e.getName().equals("app/morphe/patches/maps/GoogleMapsMicroGPatchKt.class")) {
     ClassReader r=new ClassReader(bytes);ClassWriter w=new ClassWriter(r,0);
     r.accept(new ClassVisitor(Opcodes.ASM8,w) {
      public MethodVisitor visitMethod(int ac,String name,String desc,String sig,String[] ex) {
       return new MethodVisitor(Opcodes.ASM8,super.visitMethod(ac,name,desc,sig,ex)) {
        public void visitMethodInsn(int op,String owner,String n,String d,boolean itf) {
         if(op==Opcodes.INVOKESTATIC && owner.equals("app/morphe/patches/maps/GoogleMapsMicroGPatchKt") && d.equals("(Lapp/morphe/patcher/patch/BytecodePatchContext;)V") && hits.containsKey(n)) {
          hits.put(n,hits.get(n)+1);
          super.visitInsn(Opcodes.POP);
         }
         else super.visitMethodInsn(op,owner,n,d,itf);
        }
       };
      }
     },0);bytes=w.toByteArray();
    }
    dst.putNextEntry(new ZipEntry(e.getName()));dst.write(bytes);dst.closeEntry();
   }
  }
  if(hits.values().stream().anyMatch(n->n!=1)){Files.deleteIfExists(Paths.get(a[1]));throw new IllegalStateException(hits.toString());}
  System.out.println(hits);
 }
}
