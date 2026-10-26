"""Execute the real main coordinator, SDK inventory and typed regional callbacks on the host.

SDK/Android and the asynchronous reader View boundary are explicit doubles. This does not
exercise file decoding or downstream transmission; their independent harnesses own those claims.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build import product_adas_sources

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
JHOME = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')) / 'bin'

STUBS = {
    'com/yandex/mapkit/geometry/Point.java': r'''package com.yandex.mapkit.geometry;
public class Point {final double lat,lon;public Point(double lat,double lon){this.lat=lat;this.lon=lon;}public double getLatitude(){return lat;}public double getLongitude(){return lon;}}
''',
    'com/yandex/mapkit/MapKitFactory.java': r'''package com.yandex.mapkit;
import com.yandex.mapkit.geometry.Point;import com.yandex.mapkit.offline_cache.RegionsAtPointListener;import java.util.*;
public class MapKitFactory {
 public static final Manager manager=new Manager();public static MapKitFactory getInstance(){return new MapKitFactory();}public Manager getOfflineCacheManager(){return manager;}
 public enum State {AVAILABLE,COMPLETED}
 public static class Region {
  public final int id;public String name;public Integer parent;public long release=20;public Long downloaded;public State state=State.AVAILABLE;
  Region(int id,String name,boolean installed){this.id=id;this.name=name;install(installed,10);}
  public void install(boolean installed,long version){state=installed?State.COMPLETED:State.AVAILABLE;downloaded=installed?version:null;}
  public int getId(){return id;}public String getName(){return name;}public Integer getParentId(){return parent;}public long getReleaseTime(){return release;}
 }
 public static class Manager {
  public final Region unrelated=new Region(1,"Москва",true),required=new Region(2,"Тула",false);
  final ArrayDeque<RegionsAtPointListener> pending=new ArrayDeque<>();public int queries,catalogReads;
  public List<Region> regions(){catalogReads++;return Arrays.asList(unrelated,required);}
  Region region(int id){if(id==1)return unrelated;if(id==2)return required;throw new AssertionError("unknown catalog region");}
  public State getState(int id){return region(id).state;}public Long getDownloadedReleaseTime(int id){return region(id).downloaded;}
  public float getProgress(int id){return region(id).state==State.COMPLETED?1:0;}public boolean isLegacyPath(int id){return false;}
  public void requestRegionsAtPoint(Point point,RegionsAtPointListener callback){queries++;pending.add(callback);}
  public void answerAll(){int budget=1100;while(!pending.isEmpty()){if(--budget==0)throw new AssertionError("unbounded discovery");pending.remove().onRegions(Arrays.asList(2,225,10000));android.os.Handler.advance(0);}}
 }
}''',
    'dev/denza/hud/probe/LiveMapBridge.java': r'''package dev.denza.hud.probe;
import java.util.Objects;import dev.denza.adaslink.OfflineMapGate;
/** Explicit sink double: no real sender, transport, ACK or downstream claim. */
class LiveMapBridge {
 OfflineMapGate.Subject subject;OfflineMapGate.Snapshot gate;String key;long generation;int writes;
 void sourceRoute(OfflineMapGate.Subject value){if(!Objects.equals(subject,value)){key=null;gate=null;generation++;}subject=value;}
 void sourcePreparation(OfflineMapGate.Subject value,String next){if(Objects.equals(subject,value)&&!Objects.equals(key,next)){key=next;gate=null;generation++;}}
 void offlineMap(OfflineMapGate.Snapshot value){writes++;gate=value;}
 boolean ready(){return OfflineMapGate.check(subject,gate,android.os.SystemClock.now).ready;}
}''',
    'dev/denza/hud/probe/YandexOfflineAttributes.java': r'''package dev.denza.hud.probe;
import java.util.*;import dev.denza.adaslink.*;
/** Reader scheduling seam. A supplied View is a result-contract sentinel, not decoded map data. */
class YandexOfflineAttributes {
 static class View {
  final boolean settled,readReady;final String sourceKey,preparationKey;final RouteAttributes attributes;final Map<String,Object> status;
  View(String key,boolean ready,String identity){sourceKey=key;preparationKey=key+"|"+identity;settled=true;readReady=ready;attributes=ready?SENTINEL:null;Map<String,Object> m=new LinkedHashMap<>();m.put("sourceKey",key);m.put("preparationKey",preparationKey);m.put("packageIdentity",identity);m.put("categoriesComplete",false);m.put("reason",ready?"read_ready":"reader_failed");m.put("reasonRussian",ready?"Прочитано":"Не удалось прочитать карты");status=Collections.unmodifiableMap(m);}
 }
 private static final RouteAttributes SENTINEL=new RouteAttributes(){public boolean matches(YandexRoute r){throw new AssertionError("reader sentinel cannot prove route matching");}public double[] cuts(){throw new AssertionError("not decoded attributes");}public RoadNetwork.KnownAttributes attributesBetween(double b,double e){throw new AssertionError("not decoded attributes");}public Map<String,Object> summary(){return Collections.emptyMap();}};
 String key,geometry;int samples;
 void sample(String id,long epoch,String geometry,YandexOfflineCachePath.PathSnapshot path,YandexOfflineInventory.Snapshot inventory){samples++;this.geometry=geometry;key=id+"|"+epoch+"|"+geometry+"|"+(path==null?-1:path.generation)+"|"+(inventory==null?"":inventory.fingerprint);}
 String sourceKey(){return key;}
 View result(String identity){return new View(key,true,identity);}
 void deliver(YandexOfflineMapSource source,View view,long delay){new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->source.readerResult(view),delay);}
}''',
    'dev/denza/hud/probe/OfflineMapSourceHost.java': r'''package dev.denza.hud.probe;
import java.util.*;import android.os.*;import com.yandex.mapkit.MapKitFactory;import dev.denza.adaslink.*;
public class OfflineMapSourceHost {
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static Map<String,Object> route(String id,double lon,double lat){Map<String,Object> r=new LinkedHashMap<>();r.put("routeId",id);r.put("coordinateFrame","wgs84");r.put("pointOrder","lon,lat");r.put("points",Arrays.asList(Arrays.asList(lon,lat),Arrays.asList(lon+.001,lat+.001)));return r;}
 static void sample(YandexOfflineMapSource source,Reflect api,String id,long epoch,Map<String,Object> route,YandexOfflineCachePath.PathSnapshot path,YandexOfflineAttributes reader){source.sample(api,id,epoch,route,path,reader,SystemClock.now);}
 static void code(LiveMapBridge live,String code){check(live.gate!=null&&code.equals(live.gate.code)&&!live.ready(),"expected closed "+code+", got "+(live.gate==null?null:live.gate.code));}
 public static void main(String[] args){
  Handler.reset();long started=SystemClock.now;Reflect api=new Reflect();LiveMapBridge live=new LiveMapBridge();YandexOfflineMapSource source=new YandexOfflineMapSource(live);YandexOfflineAttributes reader=new YandexOfflineAttributes();
  YandexOfflineCachePath.PathSnapshot path=new YandexOfflineCachePath.PathSnapshot("/fake-sdk",1,SystemClock.now,true,"ready");
  MapKitFactory.Manager sdk=MapKitFactory.manager;
  sample(source,api,"one",1,route("one",37,55),path,reader);
  check(source.available(SystemClock.now)&&source.inventorySnapshot().usableCount==1,"actual inventory has one unrelated usable map");
  check(!live.ready()&&!source.mayRead(),"inventory presence alone refuses regional read and live admission");code(live,"regions_pending");
  sdk.answerAll();sample(source,api,"one",1,null,path,reader);code(live,"regions_manual_download_required");
  check(live.gate.reason.contains("Тула")&&!source.mayRead(),"actual typed region replies require named manual download");
  check(sdk.queries==2,"two endpoints traversed through actual SDK listener/reflection path");
  int queries=sdk.queries;sdk.required.install(true,10);Handler.advance(2000);sample(source,api,"one",1,null,path,reader);
  code(live,"offline_read_pending");check(source.mayRead(),"manual installation enables read but cannot admit before completed View");
  check(sdk.queries==queries,"manual installation reuses route discovery cache");
  YandexOfflineAttributes.View first=reader.result("package-one");int writes=live.writes;reader.deliver(source,first,0);Handler.advance(0);
  check(live.writes==writes&&!live.ready(),"worker result delivery cannot write or renew the gate itself");
  sample(source,api,"one",1,null,path,reader);check(live.ready()&&live.gate.state==OfflineMapGate.State.PREPARED,"current successful View opens coordinator admission");
  check(Boolean.FALSE.equals(source.status().get("categoriesComplete"))&&Boolean.FALSE.equals(source.status().get("completeRouteCoverageChecked")),"successful read does not claim every category or continuous coverage");
  String geometry=source.geometrySha256(),firstKey=source.preparationKey();long generation=live.generation;
  sample(source,api,"one",1,null,path,reader);check(live.ready()&&live.generation==generation,"unchanged source keeps prepared gate and preparation generation");
  sdk.required.install(true,11);Handler.advance(2000);sample(source,api,"one",1,null,path,reader);
  code(live,"offline_read_pending");check(geometry.equals(source.geometrySha256())&&!firstKey.equals(source.preparationKey())&&live.generation==generation+1,"same geometry package version change closes and changes preparation identity");
  reader.deliver(source,first,0);Handler.advance(0);sample(source,api,"one",1,null,path,reader);code(live,"offline_read_pending");
  check(source.status().get("reader")==null,"late successful old reader result is rejected by exact source key");
  YandexOfflineAttributes.View second=reader.result("package-two");reader.deliver(source,second,1000);
  sdk.required.install(true,12);Handler.advance(2000);sample(source,api,"one",1,null,path,reader);code(live,"offline_read_pending");
  check(!second.sourceKey.equals(source.preparationKey()),"result queued before another manual update cannot authorize latest preparation");
  YandexOfflineAttributes.View third=reader.result("package-three");reader.deliver(source,third,0);Handler.advance(0);sample(source,api,"one",1,null,path,reader);check(live.ready(),"latest source result recovers without route reselection");
  sdk.required.install(false,0);Handler.advance(2000);sample(source,api,"one",1,null,path,reader);code(live,"regions_manual_download_required");
  check(source.available(SystemClock.now)&&!source.mayRead(),"deleting required region closes despite another installed usable map");
  sdk.required.install(true,13);Handler.advance(2000);sample(source,api,"one",1,null,path,reader);code(live,"offline_read_pending");
  YandexOfflineAttributes.View restored=reader.result("restored-package");source.readerResult(restored);sample(source,api,"one",1,null,path,reader);check(live.ready(),"manual reinstall plus matching completed result restores admission");
  Handler.advance(started+YandexOfflineCachePath.TTL_MS-SystemClock.now);sample(source,api,"one",1,null,path,reader);code(live,"offline_path_unavailable");
  check(source.inventorySnapshot().ready(SystemClock.now),"path TTL closes with fresh installed inventory and cached regional readiness");
  path=new YandexOfflineCachePath.PathSnapshot("/fake-sdk",2,SystemClock.now,true,"ready");sample(source,api,"one",1,null,path,reader);code(live,"offline_read_pending");
  source.readerResult(restored);sample(source,api,"one",1,null,path,reader);code(live,"offline_read_pending");
  YandexOfflineAttributes.View newPath=reader.result("same-files-new-path-generation");source.readerResult(newPath);sample(source,api,"one",1,null,path,reader);check(live.ready(),"path generation recovery requires matching reader result");
  Handler.advance(OfflineMapGate.MAX_AGE_MS);check(!live.ready(),"prepared gate itself expires without another coordinator observation");
  sample(source,api,"one",1,null,path,reader);check(live.ready(),"fresh unchanged inventory can renew matching completed preparation");
  source.routeIdentity("two",2);check(!live.ready()&&source.geometrySha256()==null&&!source.mayRead(),"new route identity immediately clears cached geometry, gate and read eligibility");
  source.readerResult(newPath);sample(source,api,"two",2,null,path,reader);code(live,"route_geometry_unavailable");
  check(source.geometrySha256()==null&&reader.geometry==null&&sdk.queries==queries,"new route cannot reuse previous route geometry or region requests");
  sample(source,api,"two",2,route("two",40,56),path,reader);code(live,"regions_pending");sdk.answerAll();sample(source,api,"two",2,null,path,reader);code(live,"offline_read_pending");
  check(!geometry.equals(source.geometrySha256())&&sdk.queries==queries+2,"new chosen geometry gets independent endpoint discovery");
  source.readerResult(reader.result("new-route-package"));sample(source,api,"two",2,null,path,reader);check(live.ready(),"new route opens only after its own discovery and matching completed read");
  System.out.println("OfflineMapSourceHost: "+checks+" checks PASS; real coordinator/inventory/regions, explicit reader scheduling seam; no decoded coverage, device or downstream claim");
 }
}''',
}


class OfflineMapSourceHostTests(unittest.TestCase):
    def test_actual_coordinator_manual_download_and_async_source_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='offline-map-source-host-') as taskdir:
            work = Path(taskdir)
            src, classes = work/'src', work/'classes'
            for name, content in STUBS.items():
                path = src/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            harness = ROOT/'tools/adas_bridge/runtime_harness/android/os'
            sources = list(src.rglob('*.java')) + [harness/(name+'.java') for name in ('Looper', 'Handler', 'SystemClock')]
            sources += [MODULE/(name+'.java') for name in ('Reflect', 'YandexOfflineMapSource', 'YandexOfflineInventory', 'YandexRouteRegions', 'YandexOfflineCachePath')]
            sources += list((ROOT/'tools/yandex_hud_build/compile_only').rglob('*.java')) + product_adas_sources()
            compiled = subprocess.run([str(JHOME/'javac'), '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], capture_output=True, text=True, timeout=45)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            ran = subprocess.run([str(JHOME/'java'), '-cp', str(classes), 'dev.denza.hud.probe.OfflineMapSourceHost'], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout+ran.stderr)
            print(ran.stdout.strip())


if __name__ == '__main__':
    unittest.main()
