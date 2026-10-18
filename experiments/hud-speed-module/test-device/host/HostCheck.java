package dev.denza.hud.speed.check;

import android.app.Instrumentation;
import android.os.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;

/**
 * Seeds a route only on the emulator, observes the production host's compositor and saves cards.
 * The host passes only what the HUD panel lets through (HudHostOutput.admits), and a fresh install
 * has «Повороты» off: the run turns «Повороты» and «Камеры» on, with the warning at the bottom of
 * the map, and puts the driver's values back after.
 */
public final class HostCheck extends Instrumentation {
    private interface Step { void run() throws Exception; }
    private Object sink,scene,module,radar,maneuver,limit,map,settings;
    private Object[] panel;
    private ClassLoader loader;
    private int gaps;
    private boolean previous;
    private final Set<Integer> pictures=new HashSet<>();
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result=new Bundle(); int code=0;
        try {
            if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) throw new AssertionError("emulator only");
            loader=getTargetContext().getClassLoader();
            main(this::setup);
            Bundle ready=new Bundle(); ready.putString("state","ready"); sendStatus(1,ready);
            long end=SystemClock.uptimeMillis()+18_000;
            while(SystemClock.uptimeMillis()<end) {
                main(this::observe);
                SystemClock.sleep(100);
            }
            if(pictures.size()<3 || gaps<3) throw new AssertionError("cards="+pictures.size()+" clears="+gaps);
            result.putString("result","PASS host: cards="+pictures.size()+", clears="+gaps+", navigation and road limit retained");
            code=-1;
        } catch(Throwable e) {
            android.util.Log.e("HudSpeedHostCheck","failed",e);
            result.putString("error",android.util.Log.getStackTraceString(e));
        }
        // Before finish: the instrumentation's end force-stops the host.
        runOnMainSync(() -> {
            try { if(sink!=null) invoke(sink,"withdraw",module); } catch(Exception ignored) { }
            try {
                if(panel!=null) panel(panel);
                getTargetContext().getSharedPreferences("denza_hud_guidance",0).edit().commit(); // waits for the applies before it
            } catch(Exception ignored) { }
        });
        finish(code,result);
    }
    /** On the main thread, as the host runs; a failure fails the run instead of crashing the process. */
    private void main(Step step) throws Throwable {
        Throwable[] failure=new Throwable[1];
        runOnMainSync(() -> { try { step.run(); } catch(Throwable e) { failure[0]=e; } });
        if(failure[0]!=null) throw failure[0];
    }
    private void setup() throws Exception {
        settings=loader.loadClass("dev.denza.apps.feature.hud.HudGuidanceSettings").getField("INSTANCE").get(null);
        android.content.Context context=getTargetContext();
        Object[] before={invoke(settings,"isEnabled",context),invoke(settings,"areCamerasEnabled",context),invoke(settings,"cameraPlace",context)};
        panel(true,true,Enum.valueOf((Class)loader.loadClass("dev.denza.apps.feature.hud.HudCameraPlace"),"MAP"));
        panel=before;
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
        Object seeded=invoke(scene,"compose",SystemClock.uptimeMillis());
        if(!module.equals(invoke(seeded,"winner",maneuver)) || !module.equals(invoke(seeded,"winner",limit)))
            throw new AssertionError("the host held back the seeded route");
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
        // The warning only: HUD Speed's GPS bars stay while its radar runs, between warnings too.
        for(Object layer:(List<?>)invoke(composition,"layers",map)) if(radar.equals(field(layer,"source")) && "warning".equals(field(layer,"id"))) {
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
    /** «Повороты», «Камеры», «Где показывать камеры». */
    private void panel(Object... values) throws Exception {
        android.content.Context context=getTargetContext();
        invoke(settings,"setEnabled",context,values[0]); invoke(settings,"setCamerasEnabled",context,values[1]);
        invoke(settings,"setCameraPlace",context,values[2]);
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
