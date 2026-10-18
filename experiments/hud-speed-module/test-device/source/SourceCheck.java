package dev.denza.hud.speed.check;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Emulator-only in-process fixtures against the actual patched DEX. Never shipped in the APK. */
public final class SourceCheck extends Instrumentation {
    public static final class ServiceFixture extends air.StrelkaSD.MainService {
        public void context(Context context) { attachBaseContext(context); }
    }
    private ClassLoader loader;
    private ServiceFixture service;
    private Object radar;
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) throw new AssertionError("emulator only");
            loader = getTargetContext().getClassLoader();
            runOnMainSync(() -> {
                try {
                    service = new ServiceFixture(); service.context(getTargetContext()); service.onCreate();
                    radar = get(service, "c");
                    set(radar,"w",true); set(radar,"y",true); set(radar,"z","GPS_ON");
                    Object position=get(radar,"n"); set(position,"d",55f); set(position,"e",37f);
                    set(position,"i",100d); set(position,"j",10L);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            point(1,1,60); update(); SystemClock.sleep(1200); check(1,1,60,false);
            runOnMainSync(() -> { try { set(radar,"z","GPS_OFF"); } catch(Exception e) { throw new RuntimeException(e); } });
            runOnMainSync(() -> { try {
                Class<?> event=loader.loadClass("f.i");
                service.getClass().getMethod("onRadarGpsStatusUpdateEvent",event)
                        .invoke(service,event.getConstructor(String.class).newInstance("GPS_OFF"));
            } catch(Exception e) { throw new RuntimeException(e); } });
            SystemClock.sleep(800); checkClear();
            runOnMainSync(() -> { try { set(radar,"z","GPS_ON"); } catch(Exception e) { throw new RuntimeException(e); } });
            point(5,2,40); update(); SystemClock.sleep(1200); check(5,2,40,false);
            SystemClock.sleep(3300); checkClear(); // Neither worker nor host may renew an old observation.
            runOnMainSync(() -> {
                try {
                    ((List<?>) get(radar,"p")).clear();
                    set(radar,"G",true); set(radar,"H",camera(41,1,90)); set(radar,"J",650);
                } catch(Exception e) { throw new RuntimeException(e); }
            });
            update(); SystemClock.sleep(1200); check(41,1,90,true);
            runOnMainSync(() -> service.onTaskRemoved(new android.content.Intent()));
            SystemClock.sleep(200);
            if (module() != null) throw new AssertionError("task removal did not disconnect");
            // Start a second real service instance to check destruction independently.
            runOnMainSync(() -> {
                // This fixture never starts upstream trip statistics; skip their shutdown,
                // but run the real onDestroy entry hook, EventBus detach and Service lifecycle.
                try { set(radar,"w",false); } catch(Exception e) { throw new RuntimeException(e); }
                try { set(service,"n",false); } catch(Exception e) { throw new RuntimeException(e); }
                service.onDestroy();
                service = new ServiceFixture(); service.context(getTargetContext()); service.onCreate();
            });
            SystemClock.sleep(200);
            if (module() == null) throw new AssertionError("service restart did not initialize");
            runOnMainSync(() -> service.onDestroy());
            SystemClock.sleep(800);
            if (module() != null) throw new AssertionError("service destruction did not disconnect");
            result.putString("result","PASS actual DEX hooks: point, GPS loss, mph danger, TTL, section, task removal, restart, destroy");
            finish(-1,result);
        } catch(Throwable e) {
            android.util.Log.e("HudSpeedSourceCheck","failed",e);
            result.putString("error",android.util.Log.getStackTraceString(e)); finish(0,result);
        }
    }
    private void point(int type,int unit,int limit) {
        runOnMainSync(() -> { try {
            List<Object> list=(List<Object>)get(radar,"p"); list.clear(); list.add(camera(type,unit,limit));
        } catch(Exception e) { throw new RuntimeException(e); } });
    }
    private Object camera(int type,int unit,int limit) throws Exception {
        Class<?> e=loader.loadClass("i.e"), a=loader.loadClass("i.a"), h=loader.loadClass("i.h");
        Object camera=e.getConstructor().newInstance();
        set(camera,"a",55.001f); set(camera,"b",37.001f); set(camera,"d",123); set(camera,"f",(short)type); set(camera,"l",(byte)unit);
        Object rule=h.getConstructor().newInstance(); h.getMethod("k",int.class).invoke(rule,limit);
        if(type==41) h.getMethod("l",int.class).invoke(rule,2); // Average limit has its own rule kind.
        ((List<Object>)get(camera,"n")).add(rule);
        Object active=a.getConstructor(e,float.class).newInstance(camera,150f); set(active,"q",true);
        return active;
    }
    private void update() { runOnMainSync(() -> { try {
        service.getClass().getMethod("onRadarUpdateEvent",loader.loadClass("f.j")).invoke(service,new Object[]{null});
    } catch(Exception e) { throw new RuntimeException(e); } }); }
    private Object module() throws Exception {
        Field f=loader.loadClass("dev.denza.hud.speed.HudSpeedModule").getDeclaredField("current"); f.setAccessible(true); return f.get(null);
    }
    private Object latest() throws Exception { return get(module(),"latest"); }
    private void checkClear() throws Exception { if ((Boolean)get(latest(),"active")) throw new AssertionError("warning remained active"); }
    private void check(int type,int unit,int limit,boolean section) throws Exception {
        Object value=latest();
        if (!(Boolean)get(value,"active") || (int)get(value,"type")!=type || (int)get(value,"unit")!=unit ||
                (int)get(value,"limit")!=limit || (boolean)get(value,"section")!=section) throw new AssertionError(
                        "wrong snapshot active="+get(value,"active")+" type="+get(value,"type")+" unit="+get(value,"unit")+
                        " limit="+get(value,"limit")+" section="+get(value,"section"));
        if (!(Boolean)get(module(),"attached")) throw new AssertionError("host not attached");
    }
    private static Field field(Object owner,String name) throws Exception {
        for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name); f.setAccessible(true); return f; } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object owner,String name) throws Exception { return field(owner,name).get(owner); }
    private static void set(Object owner,String name,Object value) throws Exception { field(owner,name).set(owner,value); }
}
