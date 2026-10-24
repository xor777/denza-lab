package dev.denza.hud.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passive trip evidence, enabled on the car too. No sender, subscription, route or cache mutation. */
final class DriveEvidence {
    private static volatile DriveEvidence current;
    private final Reflect api;
    private final Handler worker;
    private final File directory;
    private final GuidanceSnapshot progress;
    private final DriveEvidenceStreams streams;
    private final boolean enabled;
    private long nextSample, nextCache, generation;
    private String routeId;
    private volatile boolean pending;
    DriveEvidence(Context context, Reflect api, Handler worker) {
        this.api=api;this.worker=worker;progress=new GuidanceSnapshot(api);
        enabled="ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE)
                ||new File(context.getFilesDir(),"denza-drive-capture.enabled").isFile();
        directory=new File(context.getFilesDir(),"denza-drive-evidence");
        streams=new DriveEvidenceStreams(new DriveEvidenceLog(new File(directory,"progress.jsonl"),8L<<20),
                new DriveEvidenceLog(new File(directory,"adas-packets.jsonl"),8L<<20));current=enabled?this:null;
    }
    void sample(NavigatorSource.Frame frame,long now) {
        if(!enabled||pending||now<nextSample)return;nextSample=now+1000;pending=true;
        try {
            Map<String,Object> row=progress.read(frame,android.os.SystemClock.elapsedRealtime(),
                    System.currentTimeMillis(),android.os.SystemClock.uptimeMillis());
            row.put("kind","guidance");row.put("sourceSessionId",NavigationEvidence.SOURCE_SESSION_ID);
            row.put("recorder",streams.stats());
            Object id=row.get("routeId");String selected=id instanceof String?(String)id:null;
            Map<String,Object> route=null;
            if(!java.util.Objects.equals(routeId,selected)) {
                if(selected!=null&&frame.route!=null)route=new RouteSnapshot(api).read(frame.route);
                routeId=selected;generation++;
            }
            row.put("generation",generation);
            Map<String,Object> cache=null;
            if(now>=nextCache){nextCache=now+60000;cache=cache();}
            final Map<String,Object> snapshot=route,offline=cache;
            final long capturedGeneration=generation,slot=capturedGeneration%8;
            if(!worker.post(()->{try{
                if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Evidence directory");
                streams.append(row);
                if(snapshot!=null){snapshot.put("sourceSessionId",NavigationEvidence.SOURCE_SESSION_ID);snapshot.put("capturedAtMs",row.get("capturedAtMs"));
                    String json=RouteSnapshot.json(snapshot);
                    if(json.getBytes(StandardCharsets.UTF_8).length<=2*1024*1024)RouteSnapshot.writeAtomic(new File(directory,"route-"+slot+".json"),json);
                    else {Map<String,Object> oversize=new LinkedHashMap<>();oversize.put("kind","route_snapshot_oversize");
                        oversize.put("generation",capturedGeneration);oversize.put("sourceSessionId",NavigationEvidence.SOURCE_SESSION_ID);
                        oversize.put("capturedAtMs",row.get("capturedAtMs"));streams.append(oversize);}}
                if(offline!=null)streams.append(offline);
            }catch(Exception e){Log.w("DenzaYandexHud","trip evidence write: "+e.getClass().getSimpleName());}
            finally{pending=false;}}))pending=false;
        }catch(RuntimeException e){pending=false;Log.w("DenzaYandexHud","trip evidence copy: "+e.getClass().getSimpleName());}
    }
    private Map<String,Object> cache() {
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","offline_cache");row.put("capturedAtMs",System.currentTimeMillis());row.put("sourceSessionId",NavigationEvidence.SOURCE_SESSION_ID);
        try {
            Object manager=api.call(api.call(api.type("com.yandex.mapkit.MapKitFactory"),"getInstance"),"getOfflineCacheManager");
            List<?> catalog=(List<?>)api.call(manager,"regions");List<Object> regions=new ArrayList<>();
            row.put("catalogCount",catalog.size());
            for(int i=0;i<Math.min(catalog.size(),8192);i++) {
                Object region=catalog.get(i);int id=((Number)api.call(region,"getId")).intValue();String state=String.valueOf(api.call(manager,"getState",id));
                if("AVAILABLE".equals(state))continue;
                Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);item.put("name",api.call(region,"getName"));item.put("state",state);
                item.put("progress",api.call(manager,"getProgress",id));item.put("releaseTime",api.call(region,"getReleaseTime"));
                item.put("downloadedReleaseTime",api.call(manager,"getDownloadedReleaseTime",id));regions.add(item);
            }
            row.put("regions",regions);
            // Native requestPath callbacks reject java.lang.reflect.Proxy on the car ART.
            // Region status is sufficient for trip capture; no native listener is registered.
        }catch(RuntimeException e){row.put("error",e.getClass().getSimpleName());}
        return row;
    }
    private void enqueue(Map<String,Object> row) {
        row.put("sourceSessionId",NavigationEvidence.SOURCE_SESSION_ID);if(!row.containsKey("capturedAtMs"))row.put("capturedAtMs",System.currentTimeMillis());
        DriveEvidenceStreams.QueueBudget budget=streams.budget(row);if(!budget.acquire())return;
        try {
            if(!worker.post(()->{try{if(directory.isDirectory()||directory.mkdirs())streams.append(row);}
                catch(Exception ignored){}finally{budget.done();}}))budget.postFailed();
        }catch(RuntimeException failed){budget.postFailed();}
    }
    static void passiveState(String event,String detail) {
        passiveState("overlay",null,event,detail);
    }
    static void passiveState(String receiver,String receiverSession,String event,String detail) {
        DriveEvidence d=current;if(d==null)return;Map<String,Object> row=new LinkedHashMap<>();row.put("kind","adas_passive_state");
        row.put("receiver",receiver);row.put("receiverSession",receiverSession);
        row.put("capturedAtMs",System.currentTimeMillis());row.put("event",event);row.put("detail",detail);d.enqueue(row);
    }
    static void passive(long topic,long stamp,long received,byte[] bytes) {
        passive("overlay",null,topic,stamp,received,bytes);
    }
    static void passive(String receiver,String receiverSession,long topic,long stamp,long received,byte[] bytes) {
        DriveEvidence d=current;if(d==null)return;
        d.enqueue(DriveEvidenceStreams.packet(receiver,receiverSession,NavigationEvidence.SOURCE_SESSION_ID,
                topic,stamp,received,bytes,System.currentTimeMillis()));
    }
}
