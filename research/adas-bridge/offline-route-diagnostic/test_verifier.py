"""Host lifecycle checks for a research-only diagnostic; no device operations."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FAKES = {
'android/os/Looper.java': '''package android.os; public class Looper { static final Looper M=new Looper(); public static Looper myLooper(){return M;} }''',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler { public final List<Runnable> timers=new ArrayList<>(); public Looper getLooper(){return Looper.M;} public boolean post(Runnable r){r.run();return true;} public boolean postDelayed(Runnable r,long ms){timers.add(r);return true;} public void removeCallbacks(Runnable r){timers.remove(r);} public void fire(){List<Runnable> copy=new ArrayList<>(timers);timers.clear();for(Runnable r:copy)r.run();} }''',
'com/yandex/mapkit/geometry/Point.java': '''package com.yandex.mapkit.geometry; public class Point { final double a,b; public Point(double a,double b){this.a=a;this.b=b;} public double getLatitude(){return a;} public double getLongitude(){return b;} }''',
'com/yandex/mapkit/RequestPointType.java': '''package com.yandex.mapkit; public enum RequestPointType { WAYPOINT, VIAPOINT }''',
'com/yandex/mapkit/RequestPoint.java': '''package com.yandex.mapkit; public class RequestPoint { public RequestPoint(com.yandex.mapkit.geometry.Point p,RequestPointType t,String c,String i,String d){} }''',
'com/yandex/mapkit/directions/driving/DrivingRouterType.java': '''package com.yandex.mapkit.directions.driving; public enum DrivingRouterType { ONLINE,OFFLINE,COMBINED }''',
'com/yandex/mapkit/directions/driving/DrivingOptions.java': '''package com.yandex.mapkit.directions.driving; public class DrivingOptions {public int count=9;public DrivingOptions setRoutesCount(int n){count=n;return this;} public Object getAnnotationLanguage(){return null;} public Object getAvoidanceFlags(){return null;} public Object getAvoidedZones(){return null;} public Object getDepartureTime(){return null;} public Object getHdRoute(){return null;} public Object getInitialAzimuth(){return null;} }''',
'com/yandex/mapkit/directions/DirectionsFactory.java': '''package com.yandex.mapkit.directions; public class DirectionsFactory { public static Object router; public static DirectionsFactory getInstance(){return new DirectionsFactory();} public Object createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType t){if(t!=com.yandex.mapkit.directions.driving.DrivingRouterType.OFFLINE)throw new AssertionError();return router;} }''',
}
HARNESS = r'''
package dev.denza.hud.probe;
import java.util.*;
import android.os.Handler;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.directions.driving.*;
public class DiagnosticTest {
 public static class Geo {List<Point> p;Geo(double[][] c){p=new ArrayList<>();for(double[] v:c)p.add(new Point(v[0],v[1]));}public List<Point> getPoints(){return p;}}
 public static class Flags {boolean offline;Flags(boolean b){offline=b;}public boolean getBuiltOffline(){return offline;}}
 public static class Metadata {Flags f;Metadata(boolean b){f=new Flags(b);}public Flags getFlags(){return f;}}
 public static class Route implements DrivingRoute {
  Geo g;String id;Metadata m;DrivingOptions options=new DrivingOptions();List<Object> requests;
  Route(String id,boolean b,double[][] c){this.id=id;g=new Geo(c);m=new Metadata(b);requests=Arrays.asList(new com.yandex.mapkit.RequestPoint(new Point(1,2),com.yandex.mapkit.RequestPointType.WAYPOINT,null,null,null),new com.yandex.mapkit.RequestPoint(new Point(3,4),com.yandex.mapkit.RequestPointType.WAYPOINT,null,null,null));}
  public Geo getGeometry(){return g;}public Metadata getMetadata(){return m;}public String getRouteId(){return id;}public Object getVehicleOptions(){return this;}public DrivingOptions getDrivingOptions(){return options;}public List<Object> getRequestPoints(){return requests;}public List<Object> getWayPoints(){return Collections.emptyList();}
 }
 public static class Session {boolean cancelled;public void cancel(){cancelled=true;}}
 public static class Router {DrivingSession.DrivingRouteListener listener;int calls;Session last;List<DrivingRoute> sync;Object points,opts;public Object requestRoutes(List<?> p,Object o,Object v,DrivingSession.DrivingRouteListener l){calls++;points=p;opts=o;listener=l;last=new Session();if(sync!=null)l.onDrivingRoutes(sync);return last;}void emit(Route...r){listener.onDrivingRoutes(Arrays.<DrivingRoute>asList(r));}}
 static final double[][] P={{1,2},{2,3},{3,4}};
 static Route src(){return new Route("source",false,P);}static Route off(){return new Route("offline",true,P);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static class F {Handler h=new Handler();Router r=new Router();OfflineRouteVerifier v=new OfflineRouteVerifier(new Reflect(),h);Route source=src();OfflineRouteVerifier.Result result;boolean valid=true;F(){com.yandex.mapkit.directions.DirectionsFactory.router=r;}void start(){v.verify(source,"source",1,"inventory",(id,e,f)->valid,x->result=x);}}
 public static void main(String[] args){
  F f=new F();f.start();check(f.source.options.count==9);check(((DrivingOptions)f.r.opts).count==3);f.r.emit(off());check(f.result.proven&&f.r.last.cancelled);check(f.result.sourceGeometrySha256.equals(f.v.geometrySha256(f.source)));f.start();check(f.r.calls==1);
  f=new F();f.start();f.r.emit(new Route("different",true,new double[][]{{1,2},{2,3.1},{3,4}}));check("DIFFERENT_PATH".equals(f.result.status)&&!f.result.proven);
  f=new F();f.start();f.r.emit(new Route("online",false,P));check("REQUEST_ERROR".equals(f.result.status));
  f=new F();f.start();f.valid=false;f.r.emit(off());check("STALE".equals(f.result.status));
  f=new F();f.start();f.source.g=new Geo(new double[][]{{1,2},{2,3.2},{3,4}});f.r.emit(off());check("STALE".equals(f.result.status));
  f=new F();f.start();f.h.fire();check("TIMEOUT".equals(f.result.status)&&f.r.last.cancelled);
  f=new F();f.start();f.v.cancel();f.r.emit(off());check("CANCELLED".equals(f.result.status)&&f.r.last.cancelled);
  f=new F();f.r.sync=Arrays.<DrivingRoute>asList(off());f.start();check(f.result.proven&&f.r.last.cancelled);
  f=new F();f.start();f.r.emit(new Route("duplicate",true,new double[][]{{1,2},{1,2},{2,3},{3,4}}));check(f.result.proven);
  f=new F();f.start();f.r.emit(new Route("subdivided",true,new double[][]{{1,2},{1.5,2.5},{2,3},{3,4}}));check("DIFFERENT_PATH".equals(f.result.status));
  f=new F();f.source.requests=null;f.start();check(((List<?>)f.r.points).size()==2);f.r.emit(off());check(f.result.proven);f.v.close();
  System.out.println("PASS: 11 research diagnostic lifecycle scenarios; no production coverage gate");
 }
}
'''

class DiagnosticLifecycleTest(unittest.TestCase):
 def test_typed_listener_lifecycle_exact_geometry_and_no_source_mutation(self):
  java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))/'bin'
  with tempfile.TemporaryDirectory(prefix='denza-offline-diagnostic-') as tmp:
   p=Path(tmp);sources=[]
   for name,data in FAKES.items():
    f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(data);sources.append(f)
   harness=p/'DiagnosticTest.java';harness.write_text(HARNESS)
   subprocess.run([str(java/'javac'),'--release','8','-d',str(p/'classes'),*[str(s) for s in sources],*[str(s) for s in (HERE/'compile_stubs').rglob('*.java')],str(ROOT/'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe/Reflect.java'),str(HERE/'OfflineDrivingRouteListener.java'),str(HERE/'OfflineRouteVerifier.java'),str(harness)],check=True,timeout=30)
   result=subprocess.run([str(java/'java'),'-cp',str(p/'classes'),'dev.denza.hud.probe.DiagnosticTest'],check=True,timeout=15,text=True,stdout=subprocess.PIPE)
   self.assertIn('11 research diagnostic',result.stdout)
   print(result.stdout.strip())

if __name__=='__main__':unittest.main()
