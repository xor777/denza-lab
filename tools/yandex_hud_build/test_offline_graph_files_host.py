"""Execute production discovery on HOST Java with explicit filesystem/platform doubles.

No Android runtime, SDK JNI, APK build or device connection. Sparse large-file tests
exercise header discovery/scoped mapping only, not a full tile-container decode.
"""
from pathlib import Path
import hashlib
import mmap
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JHOME = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
SOURCE = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe/YandexOfflineGraphFiles.java'
CORPUS = ROOT / 'captures/yandex-offline-region-20261024/region-43/vmap3fb.fb'
MOSCOW_HEADER = ROOT / 'captures/drive-kit-20261025/install-check/moscow-vmap-header.bin'
MOSCOW_BLOCK = ROOT / 'captures/drive-kit-20261025/install-check/moscow-vmap-offset-3620864.bin'
STUBS = {
'android/os/Looper.java': '''package android.os; public class Looper { private static final Looper MAIN=new Looper(); public static Looper myLooper(){return null;} public static Looper getMainLooper(){return MAIN;} }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }''',
'android/system/StructStat.java': '''package android.system; public class StructStat { public long st_dev,st_ino,st_size,st_mtime,st_ctime; public int st_mode; }''',
'android/system/ErrnoException.java': '''package android.system; public class ErrnoException extends Exception { public final int errno; public ErrnoException(String message,int code){super(message);errno=code;} }''',
'android/system/OsConstants.java': '''package android.system; public class OsConstants { public static final int ENOENT=2; public static boolean S_ISREG(int mode){return (mode&0170000)==0100000;} }''',
'android/system/Os.java': '''package android.system;
import java.io.*; import java.nio.file.*; import java.nio.file.attribute.*; import java.util.*;
public class Os {
 private static StructStat read(String path,boolean follow)throws ErrnoException {try {
 Map<String,Object> m=Files.readAttributes(Paths.get(path),"unix:*",follow?new LinkOption[0]:new LinkOption[]{LinkOption.NOFOLLOW_LINKS});
 StructStat s=new StructStat();s.st_dev=((Number)m.get("dev")).longValue();s.st_ino=((Number)m.get("ino")).longValue();s.st_size=((Number)m.get("size")).longValue();s.st_mode=((Number)m.get("mode")).intValue();s.st_mtime=((FileTime)m.get("lastModifiedTime")).toMillis()/1000;s.st_ctime=((FileTime)m.get("ctime")).toMillis()/1000;return s;
 }catch(NoSuchFileException e){throw new ErrnoException("absent",2);}catch(Exception e){throw new ErrnoException(e.toString(),13);}}
 public static StructStat lstat(String path)throws ErrnoException{return read(path,false);}
 public static StructStat fstat(FileDescriptor fd)throws ErrnoException{try{
 java.lang.reflect.Field f=FileDescriptor.class.getDeclaredField("fd");f.setAccessible(true);Class<?> c=Class.forName("sun.nio.fs.UnixFileAttributes");java.lang.reflect.Method get=c.getDeclaredMethod("get",int.class);get.setAccessible(true);Object a=get.invoke(null,f.getInt(fd));StructStat s=new StructStat();
 for(String name:new String[]{"dev","ino","size","mtime","ctime","mode"}){String nativeName="st_"+name+((name.equals("mtime")||name.equals("ctime"))?"_sec":"");java.lang.reflect.Field from=c.getDeclaredField(nativeName);from.setAccessible(true);java.lang.reflect.Field to=StructStat.class.getDeclaredField("st_"+name);if(name.equals("mode"))to.setInt(s,from.getInt(a));else to.setLong(s,from.getLong(a));}return s;
 }catch(Exception e){throw new ErrnoException(e.toString(),13);}}
}''',
'dev/denza/hud/probe/YandexOfflineCachePath.java': '''package dev.denza.hud.probe; class YandexOfflineCachePath { static class PathSnapshot { final String root; final long generation=1; PathSnapshot(String p){root=p;} boolean ready(long now){return true;} } }''',
'dev/denza/hud/probe/YandexOfflineInventory.java': '''package dev.denza.hud.probe; import java.util.*; class YandexOfflineInventory { static final int MAX_REGIONS=128; static class Snapshot { final java.util.List<java.util.Map<String,Object>> regions; final String fingerprint="sdk-release-fixture"; Snapshot(){Map<String,Object> r=new HashMap<>();r.put("id",1);r.put("downloadedReleaseTime",1234L);r.put("usable",true);r.put("isLegacyPath",false);regions=Collections.singletonList(r);} boolean ready(long now){return true;} } }''',
'dev/denza/hud/probe/OfflineFilesHost.java': '''package dev.denza.hud.probe;
import java.io.*; import java.nio.file.*; import java.nio.file.attribute.*; import java.util.*;
public class OfflineFilesHost {
 static int checks; static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static YandexOfflineCachePath.PathSnapshot path; static YandexOfflineInventory.Snapshot inv=new YandexOfflineInventory.Snapshot();
 static YandexOfflineGraphFiles.Package discover(){YandexOfflineGraphFiles.Snapshot s=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check(s.valid&&s.files.size()==1,"discovery "+s.reasonCode);return s.files.get(0);}
 static void rejected(YandexOfflineGraphFiles.Package p,String label)throws Exception{boolean failed=false;try{YandexOfflineGraphFiles.mapStable(p,b->b.capacity());}catch(IOException e){failed=true;}check(failed,label);}
 public static void main(String[] args)throws Exception{
 File root=new File(args[0]).getCanonicalFile();path=new YandexOfflineCachePath.PathSnapshot(root.getPath());Path folder=root.toPath().resolve("offline_caches/1"),file=folder.resolve("vmap3fb.fb"),meta=folder.resolve("region.pb");
 YandexOfflineGraphFiles.Package absent=discover();check(absent.file.length()==826573656L,"large sparse file");check("26.10.07-1".equals(absent.version),"real corpus version");check(absent.regionIds().length==0,"no fabricated region IDs");check(absent.downloadedRelease==1234L,"SDK release retained without version join");
 check(YandexOfflineGraphFiles.mapStable(absent,b->b.capacity())==826573656,"large scoped mapping");
 Files.write(meta,"release0".getBytes("UTF-8"));rejected(absent,"sidecar appearance");YandexOfflineGraphFiles.Package present=discover();check(!present.identity.equals(absent.identity),"presence in identity");check(YandexOfflineGraphFiles.mapStable(present,b->b.capacity())==826573656,"present sidecar mapping");
 FileTime oldTime=Files.getLastModifiedTime(meta);Files.write(meta,"release1".getBytes("UTF-8"));Files.setLastModifiedTime(meta,oldTime);rejected(present,"same-size restored-mtime sidecar content mutation");present=discover();Files.delete(meta);rejected(present,"sidecar disappearance");
 absent=discover();Files.write(meta,"release2".getBytes("UTF-8"));present=discover();Path metaNew=folder.resolve("region.pb.new");Files.write(metaNew,Files.readAllBytes(meta));Files.setLastModifiedTime(metaNew,Files.getLastModifiedTime(meta));Files.move(metaNew,meta,StandardCopyOption.REPLACE_EXISTING);rejected(present,"sidecar inode replacement");Files.delete(meta);
 absent=discover();final YandexOfflineGraphFiles.Package during=absent;boolean callbackFailed=false;try{YandexOfflineGraphFiles.mapStable(during,b->{Files.write(meta,"release3".getBytes("UTF-8"));return b.capacity();});}catch(IOException e){callbackFailed=true;}check(callbackFailed,"post-mapping appearance fence");Files.delete(meta);
 absent=discover();FileTime headerTime=Files.getLastModifiedTime(file);try(RandomAccessFile f=new RandomAccessFile(file.toFile(),"rw")){f.seek(3622360);f.write('X');}Files.setLastModifiedTime(file,headerTime);rejected(absent,"version-header mutation");try(RandomAccessFile f=new RandomAccessFile(file.toFile(),"rw")){f.seek(3622360);f.write('2');}
 absent=discover();Path replacement=folder.resolve("vmap3fb.fb.unused");try(RandomAccessFile f=new RandomAccessFile(replacement.toFile(),"rw")){f.setLength(826573656L);}Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(file));Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);rejected(absent,"package atomic replacement equal size and mtime");

 YandexOfflineGraphFiles.Snapshot bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check(!bad.valid&&bad.rejectedCount==1,"corrupt header rejected");Map<String,Object> diagnosis=bad.packageDiagnostics.get(0);check("header".equals(diagnosis.get("stage"))&&"package_root_invalid".equals(diagnosis.get("reasonCode")),"header rejection diagnostic");check(((Number)diagnosis.get("fileBytes")).longValue()==826573656L&&diagnosis.get("packagePath").equals(file.toString()),"rejection retains exact path and bytes");
 inv.regions.get(0).put("isLegacyPath",true);bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check("legacy_package_layout".equals(bad.packageDiagnostics.get(0).get("reasonCode")),"legacy layout diagnostic");inv.regions.get(0).put("isLegacyPath",null);bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check("package_layout_unavailable".equals(bad.packageDiagnostics.get(0).get("reasonCode")),"missing layout diagnostic");inv.regions.get(0).put("isLegacyPath",false);
 Files.write(file,new byte[4]);bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check("file_too_small".equals(bad.packageDiagnostics.get(0).get("reasonCode")),"size diagnostic");Files.delete(file);bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check("package_stat".equals(bad.packageDiagnostics.get(0).get("stage"))&&Boolean.FALSE.equals(bad.packageDiagnostics.get(0).get("fileExists")),"missing package diagnostic");
 Path outside=root.toPath().resolve("outside.fb");Files.write(outside,new byte[16]);Files.createSymbolicLink(file,outside);bad=YandexOfflineGraphFiles.discoverVectors(path,inv,1000);check("package_path_invalid".equals(bad.packageDiagnostics.get(0).get("reasonCode")),"symlink confinement diagnostic");
 System.out.println("OfflineFilesHost: "+checks+" checks PASS; Android/JNI/device not exercised");
 }
}'''
}

class HostChecks(unittest.TestCase):
    @unittest.skipUnless(CORPUS.is_file() and (JHOME/'javac').is_file(), 'local corpus/JDK17 absent')
    def test_optional_metadata_and_large_mapping(self):
        self.run_host(False)

    @unittest.skipUnless(MOSCOW_HEADER.is_file() and MOSCOW_BLOCK.is_file() and (JHOME/'javac').is_file(), 'saved Moscow header/JDK17 absent')
    def test_actual_moscow_large_header_mapping(self):
        self.assertEqual(hashlib.sha256(MOSCOW_HEADER.read_bytes()).hexdigest(), '7800c7f9d0cfa0e0e383d48481de45daeae7cd6b7a810a826d506afe86a7a196')
        self.assertEqual(hashlib.sha256(MOSCOW_BLOCK.read_bytes()).hexdigest(), '1228be548e42301aadc20ddbb2dc41568dde7cfaa8be4ae993fe59b9ee94de8b')
        self.run_host(True)

    def run_host(self, moscow):
        with tempfile.TemporaryDirectory(prefix='offline-files-host-') as td:
            work=Path(td); src=work/'src'; classes=work/'classes'; package=work/'sdk/offline_caches/1/vmap3fb.fb';package.parent.mkdir(parents=True)
            # Preserve actual native root/vtable and field2/3 string locations. All other
            # extents are sparse: this fixture proves header+size acceptance, not tile data.
            with CORPUS.open('rb') as source, package.open('wb') as output:
                with mmap.mmap(source.fileno(),0,access=mmap.ACCESS_READ) as b:
                    root=struct.unpack_from('<I',b,0)[0];vt=root-struct.unpack_from('<i',b,root)[0]
                    output.write(b[:256])
                    for index in (2,3):
                        at=root+struct.unpack_from('<H',b,vt+4+index*2)[0];target=at+struct.unpack_from('<I',b,at)[0];length=struct.unpack_from('<I',b,target)[0]
                        output.seek(target);output.write(b[target:target+length+5])
                output.truncate(826573656)
            if moscow:
                with package.open('r+b') as output:
                    output.write(MOSCOW_HEADER.read_bytes());output.seek(3620864);output.write(MOSCOW_BLOCK.read_bytes())
            for name,text in STUBS.items():
                p=src/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
            target=src/'dev/denza/hud/probe/YandexOfflineGraphFiles.java';target.write_text(SOURCE.read_text())
            compiled=subprocess.run([str(JHOME/'javac'),'--release','8','-d',str(classes)]+[str(p) for p in src.rglob('*.java')],capture_output=True,text=True,timeout=45)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            ran=subprocess.run([str(JHOME/'java'),'--add-opens','java.base/java.io=ALL-UNNAMED','--add-opens','java.base/sun.nio.fs=ALL-UNNAMED','-Xmx64m','-cp',str(classes),'dev.denza.hud.probe.OfflineFilesHost',str(work/'sdk')],capture_output=True,text=True,timeout=30)
            self.assertEqual(ran.returncode,0,ran.stdout+ran.stderr)
            print(('Moscow' if moscow else 'Kazan')+': '+ran.stdout.strip())

if __name__=='__main__':unittest.main()
