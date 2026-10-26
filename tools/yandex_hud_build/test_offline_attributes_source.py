"""Host execution of the production offline reader with Android scheduling/filesystem doubles.

The Kazan replay supplies retained decompressed frames, never private JNI. It checks whole-route
read admission and lifecycle; these results do not establish Android/device execution.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

from tools.yandex_hud_build.test_offline_graph_files_host import STUBS, JHOME
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build import product_adas_sources

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
PACKAGE = ROOT / 'captures/yandex-offline-region-20261024/region-43/vmap3fb.fb'
ROUTE = ROOT / 'captures/yandex-offline-region-20261024/runtime/online-short.json'
TILES = ROOT / 'captures/yandex-fullroute-attributes-20261024/offline-replay'

HARNESS = r'''package dev.denza.hud.probe;
import android.os.Handler;
import dev.denza.adaslink.*;
import java.io.*; import java.nio.*; import java.nio.channels.*; import java.nio.file.*;
import java.security.*; import java.util.*;
public class OfflineAttributesHost {
 static int checks; static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static String hash(byte[] b)throws Exception {byte[] h=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte v:h)s.append(String.format("%02x",v&255));return s.toString();}
 static YandexOfflineInventory.Snapshot inventory(String key,boolean extra){return new YandexOfflineInventory.Snapshot(key,true,extra);}
 static YandexRoute route(String id,double[] lon,double[] lat){return new YandexRoute(id,lon,lat,Collections.emptyList(),null,null,null,null,null,null);}
 static void sample(YandexOfflineAttributes a,YandexRoute r,YandexOfflineCachePath.PathSnapshot p,YandexOfflineInventory.Snapshot i){a.sample(r.routeId,1,RouteVectorAttributes.geometryFingerprint(r),p,i);}
 static YandexOfflineAttributes.View finish(YandexOfflineAttributes a,YandexRoute r){YandexOfflineAttributes.View pending=a.prepare(r);check(!pending.readReady,"pending is never ready");Handler.runAll();return a.prepare(r);}
 static void blocked(YandexOfflineAttributes.View v,String reason){check(v.settled&&!v.readReady&&v.attributes==null,"settled blocked discards accumulated attributes: "+v.status);check(reason.equals(v.status.get("reason")),"reason "+reason+": "+v.status);}
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
 Path root=Paths.get(args[0]),pkg=root.resolve("offline_caches/43/vmap3fb.fb"),tiles=Paths.get(args[1]);
 List<String> points=Files.readAllLines(Paths.get(args[2]));double[] lon=new double[points.size()],lat=new double[points.size()];for(int n=0;n<points.size();n++){String[] p=points.get(n).split(",");lon[n]=Double.parseDouble(p[0]);lat[n]=Double.parseDouble(p[1]);}
 YandexRoute route=route("retained-kazan",lon,lat);Map<String,byte[]> frames=new HashMap<>();
 try(FileInputStream f=new FileInputStream(pkg.toFile())){OfflineVectorPackage catalog=new OfflineVectorPackage(f.getChannel().map(FileChannel.MapMode.READ_ONLY,0,f.getChannel().size()));for(Path p:Files.newDirectoryStream(tiles,"tile-*.bin")){String[] v=p.getFileName().toString().replace(".bin","").split("-");VectorRoadTile.TileId id=new VectorRoadTile.TileId(Integer.parseInt(v[2]),Integer.parseInt(v[3]),Integer.parseInt(v[1]));frames.put(hash(catalog.compressed(catalog.find(id))),Files.readAllBytes(p));}}
 final int[] decodes={0};OfflineVectorPackage.Decoder decoder=(b,d,max)->{try{decodes[0]++;byte[] raw=frames.get(hash(b));if(raw==null)throw new IllegalArgumentException("unpinned test frame");return raw.clone();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException(e);}};
 Handler worker=new Handler();YandexOfflineAttributes reader=new YandexOfflineAttributes(worker,decoder);
 check(reader.sourceKey()==null,"source key absent before sample");YandexOfflineAttributes.View initial=reader.prepare(route);check(!initial.settled&&!initial.readReady,"route pending");
 YandexOfflineCachePath.PathSnapshot path=new YandexOfflineCachePath.PathSnapshot(root.toString(),1,true,"ready");
 sample(reader,route,path,inventory("one",false));YandexOfflineAttributes.View ready=finish(reader,route);
 check(ready.sourceKey.equals(reader.sourceKey()),"source accessor agrees with sampled preparation");check(ready.readReady&&ready.settled&&ready.attributes!=null,"real Kazan complete read "+ready.status);
 check(((Number)ready.status.get("tilesRequested")).intValue()==21&&((Number)ready.status.get("tilesMissing")).intValue()==0,"all 21 route/corridor tiles read");
 check(Boolean.FALSE.equals(ready.status.get("categoriesComplete")),"ambiguous/missing raw fields do not claim complete categories");
 check(Boolean.FALSE.equals(ready.status.get("fullRouteCoverageProved")),"tile corridor coverage does not prove road categories or regional semantics");
 check(Boolean.TRUE.equals(ready.status.get("routeTileCoverageChecked"))&&"conservative_segment_tile_corridor".equals(ready.status.get("coverageScope")),"conservative whole-route corridor validated before admission");int count=decodes[0];check(reader.prepare(route)==ready&&decodes[0]==count,"same source reuses immutable attempt");String old=ready.preparationKey;
 sample(reader,route,path,inventory("two",false));YandexOfflineAttributes.View updated=finish(reader,route);check(updated.readReady&&!old.equals(updated.preparationKey)&&decodes[0]>count,"manual update retries same route ID");
 sample(reader,route,path,inventory("old-queued",false));reader.prepare(route);sample(reader,route,path,inventory("latest-queued",false));updated=finish(reader,route);check(updated.readReady&&updated.sourceKey.contains("latest-queued"),"coalesced same-route update rejects old source");
 YandexOfflineInventory.Snapshot expired=inventory("latest-queued",false);expired.fresh=false;sample(reader,route,path,expired);check(!reader.prepare(route).readReady,"stale inventory cannot expose old ready snapshot");
 YandexOfflineCachePath.PathSnapshot failedPath=new YandexOfflineCachePath.PathSnapshot(root.toString(),2,false,"path_timeout");sample(reader,route,failedPath,inventory("three",false));blocked(reader.prepare(route),"path_timeout");
 sample(reader,route,new YandexOfflineCachePath.PathSnapshot(root.toString(),3,true,"ready"),inventory("three",false));check(finish(reader,route).readReady,"new path recovers terminal empty attempt on same route ID");
 sample(reader,route,path,inventory("unsupported",true));YandexOfflineAttributes.View rejected=finish(reader,route);blocked(rejected,"offline_package_unreadable_or_unsupported");Map<String,Object> discovery=(Map<String,Object>)rejected.status.get("packageDiscovery");check(((List<?>)discovery.get("packageDiagnostics")).size()==2,"both accepted and rejected package details retained");
 YandexRoute missing=route("outside-region",new double[]{37.60,37.61},new double[]{55.75,55.75});sample(reader,missing,path,inventory("one",false));YandexOfflineAttributes.View absent=finish(reader,missing);blocked(absent,"offline_route_tiles_missing");check(Boolean.FALSE.equals(absent.status.get("routeTileCoverageChecked")),"missing tiles never claim validated route coverage");check(((Number)absent.status.get("tilesMissing")).intValue()>0&&!((List<?>)absent.status.get("missingTiles")).isEmpty(),"missing route tiles carry geographic diagnostics");
 final int[] errorsAfter={0};YandexOfflineAttributes errors=new YandexOfflineAttributes(new Handler(),(b,d,m)->{if(errorsAfter[0]++==0)return decoder.decode(b,d,m);throw new IllegalArgumentException("test_decode_error");});sample(errors,route,path,inventory("one",false));blocked(finish(errors,route),"offline_attributes_rejected");
 YandexRoute huge=route("over-budget",new double[]{-160,150},new double[]{0,0});sample(reader,huge,path,inventory("one",false));blocked(finish(reader,huge),"route_tile_budget");
 YandexOfflineInventory.Snapshot none=new YandexOfflineInventory.Snapshot("none",false,false);sample(reader,route,path,none);blocked(reader.prepare(route),"no_usable_regions");
 System.out.println("OfflineAttributesHost: "+checks+" checks PASS; real Kazan route, 21 retained decoded frames; Android/JNI/device not exercised");
 }
}'''


class OfflineAttributesHostTests(unittest.TestCase):
    @unittest.skipUnless(PACKAGE.is_file() and ROUTE.is_file() and (JHOME/'javac').is_file(), 'local corpus/JDK17 absent')
    def test_real_kazan_whole_route_admission_and_source_lifecycle(self):
        self.assertEqual(hashlib.sha256(PACKAGE.read_bytes()).hexdigest(), '053bb300f26fc699b8f6e5d8d972480179ef78872426946caad2f43e8ec35a1e')
        self.assertEqual(hashlib.sha256(ROUTE.read_bytes()).hexdigest(), '72c3ca28467c89229a699b9d13947d7241db3935b8c1f7e71d399351e59c472c')
        with tempfile.TemporaryDirectory(prefix='offline-attributes-host-') as taskdir:
            work=Path(taskdir); src=work/'src'; classes=work/'classes'; package=work/'sdk/offline_caches/43/vmap3fb.fb'; package.parent.mkdir(parents=True)
            os.link(PACKAGE, package)
            points=json.loads(ROUTE.read_text())['routes'][0]['getGeometry']['getPoints']['items']
            route=work/'route.csv'; route.write_text('\n'.join(str(p['getLongitude'])+','+str(p['getLatitude']) for p in points))
            stubs={k:v for k,v in STUBS.items() if not k.endswith('OfflineFilesHost.java')}
            stubs.update({
                'android/os/Handler.java': 'package android.os; public class Handler { static final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>(); public Handler(){} public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;} public static void runAll(){while(!queue.isEmpty())queue.remove().run();} }',
                'android/os/HandlerThread.java': 'package android.os; public class HandlerThread { public HandlerThread(String n,int p){} public void start(){} public Looper getLooper(){return null;} }',
                'android/os/Process.java': 'package android.os; public class Process { public static final int THREAD_PRIORITY_BACKGROUND=10; }',
                'dev/denza/hud/probe/OfflineTileDecoder.java': 'package dev.denza.hud.probe; class OfflineTileDecoder implements dev.denza.adaslink.OfflineVectorPackage.Decoder { public byte[] decode(byte[] b,byte[] d,int m){throw new AssertionError("test decoder must be supplied");} }',
                'dev/denza/hud/probe/YandexOfflineCachePath.java': 'package dev.denza.hud.probe; class YandexOfflineCachePath { static class PathSnapshot { final String root,reasonCode; final long generation; final boolean valid; PathSnapshot(String p,long g,boolean v,String r){root=p;generation=g;valid=v;reasonCode=r;} boolean ready(long n){return valid;} } }',
                'dev/denza/hud/probe/YandexOfflineInventory.java': '''package dev.denza.hud.probe; import java.util.*; class YandexOfflineInventory {static final int MAX_REGIONS=8192; static class Snapshot { final String fingerprint,reasonCode;final boolean valid;boolean fresh=true;final List<Map<String,Object>> regions=new ArrayList<>();Snapshot(String k,boolean v,boolean extra){fingerprint=k;valid=v;reasonCode=v?"ready":"no_usable_regions";if(v){row(43,false);if(extra)row(999,true);}}void row(int id,boolean legacy){Map<String,Object> r=new HashMap<>();r.put("id",id);r.put("downloadedReleaseTime",1234L);r.put("usable",true);r.put("isLegacyPath",legacy);regions.add(r);}boolean ready(long n){return valid&&fresh;}}}''',
                'dev/denza/hud/probe/OfflineAttributesHost.java': HARNESS,
            })
            for name,content in stubs.items():
                path=src/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(content)
            sources=list(src.rglob('*.java'))+[MODULE/'YandexOfflineAttributes.java',MODULE/'YandexOfflineGraphFiles.java']+product_adas_sources()
            compiled=subprocess.run([str(JHOME/'javac'),'--release','8','-encoding','UTF-8','-d',str(classes)]+list(map(str,sources)),capture_output=True,text=True,timeout=45)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            ran=subprocess.run([str(JHOME/'java'),'--add-opens','java.base/java.io=ALL-UNNAMED','--add-opens','java.base/sun.nio.fs=ALL-UNNAMED','-Xmx512m','-cp',str(classes),'dev.denza.hud.probe.OfflineAttributesHost',str(work/'sdk'),str(TILES),str(route)],capture_output=True,text=True,timeout=60)
            self.assertEqual(ran.returncode,0,ran.stdout+ran.stderr)
            print(ran.stdout.strip())


if __name__=='__main__': unittest.main()
