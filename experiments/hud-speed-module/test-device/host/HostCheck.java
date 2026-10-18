package dev.denza.hud.speed.check;

import android.app.Instrumentation;
import android.os.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/** Seeds a route only on the emulator, observes the production host's compositor and saves cards. */
public final class HostCheck extends Instrumentation {
    private Object sink,scene,module,radar,maneuver,limit,map;
    private ClassLoader loader;
    private int gaps;
    private boolean previous;
    private final Set<Integer> pictures=new HashSet<>();
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) throw new AssertionError("emulator only");
            loader=getTargetContext().getClassLoader();
            runOnMainSync(() -> { try { setup(); } catch(Exception e) { throw new RuntimeException(e); } });
            Bundle ready=new Bundle(); ready.putString("state","ready"); sendStatus(1,ready);
            long end=SystemClock.uptimeMillis()+18_000;
            while(SystemClock.uptimeMillis()<end) {
                runOnMainSync(() -> { try { observe(); } catch(Exception e) { throw new RuntimeException(e); } });
                SystemClock.sleep(100);
            }
            if(pictures.size()<3 || gaps<3) throw new AssertionError("cards="+pictures.size()+" clears="+gaps);
            result.putString("result","PASS host: cards="+pictures.size()+", clears="+gaps+", navigation and road limit retained");
            finish(-1,result);
        } catch(Throwable e) {
            android.util.Log.e("HudSpeedHostCheck","failed",e);
            result.putString("error",android.util.Log.getStackTraceString(e)); finish(0,result);
        } finally {
            if(sink!=null) runOnMainSync(() -> { try { invoke(sink,"withdraw",module); } catch(Exception ignored) { } });
        }
    }
    private void setup() throws Exception {
        Class<?> host=loader.loadClass("dev.denza.apps.feature.hud.HudHostOutput");
        Method get=host.getDeclaredMethod("get",android.content.Context.class); get.setAccessible(true);
        Object output=get.invoke(null,getTargetContext());
        sink=invoke(output,"sink");
        scene=field(field(field(output,"output"),"compositor"),"scene");
        module=en("HudSource","MODULE"); radar=en("HudSource","RADAR");
        maneuver=en("HudRole","MANEUVER"); limit=en("HudRole","ROAD_LIMIT"); map=en("HudWindow","MAP");
        Object b=loader.loadClass("dev.denza.hudlink.HudFields$Builder").getConstructor().newInstance();
        invoke(b,"number",9,450L); invoke(b,"text",10,"Проверка HUD Speed");
        invoke(sink,"fields",module,maneuver,invoke(b,"build"),30_000L);
        Object l=loader.loadClass("dev.denza.hudlink.HudFields$Builder").getConstructor().newInstance(); invoke(l,"number",11,90L);
        invoke(sink,"fields",module,limit,invoke(l,"build"),30_000L);
        // An ordinary app UID cannot attach even though the service is exported.
        IBinder endpoint=(IBinder)invoke(output,"endpoint"); Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken("dev.denza.hudlink.HudHost"); data.writeInt(1); data.writeStrongBinder(new Binder());
            endpoint.transact(1,data,reply,0); reply.readException();
            if(reply.readInt()!=1) throw new AssertionError("untrusted caller admitted");
        } finally { data.recycle(); reply.recycle(); }
    }
    private void observe() throws Exception {
        Object composition=invoke(scene,"compose",SystemClock.uptimeMillis());
        if(!module.equals(invoke(composition,"winner",maneuver)) || !module.equals(invoke(composition,"winner",limit)))
            throw new AssertionError("radar replaced navigation");
        boolean visible=false;
        for(Object layer:(List<?>)invoke(composition,"layers",map)) if(radar.equals(field(layer,"source"))) {
            visible=true;
            byte[] png=(byte[])invoke(field(layer,"image"),"png"); int hash=Arrays.hashCode(png);
            if(pictures.add(hash)) {
                File dir=new File(getTargetContext().getFilesDir(),"hud-speed-qa"); dir.mkdirs();
                try(FileOutputStream out=new FileOutputStream(new File(dir,"card-"+pictures.size()+".png"))) { out.write(png); }
            }
        }
        if(previous && !visible) gaps++;
        previous=visible;
    }
    private Object en(String type,String value) throws Exception { return Enum.valueOf((Class)loader.loadClass("dev.denza.hudlink."+type),value); }
    private static Object field(Object owner,String name) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static Object invoke(Object owner,String name,Object...args) throws Exception {
        for(Method m:owner.getClass().getDeclaredMethods()) if(m.getName().equals(name) && m.getParameterTypes().length==args.length) {
            m.setAccessible(true); return m.invoke(owner,args);
        }
        throw new NoSuchMethodException(name);
    }
}
