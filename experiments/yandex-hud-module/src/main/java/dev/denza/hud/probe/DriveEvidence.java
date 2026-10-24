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

/**
 * Passive trip evidence, enabled on the car too. No sender, subscription, route or cache mutation.
 * Sized for a two-hour drive: packets 7 × 8 MiB (2026-10-24: ~0.35 MiB a minute once repeats are
 * compact and service 13 is recorded by one receiver), history 3 × 8 MiB (~1.3 KiB a second), and
 * the change journal of ADAS state, which does not rotate while a drive lasts.
 */
final class DriveEvidence {
    static final long RING_BYTES = DriveEvidenceStreams.RING_BYTES;
    static final int PACKET_GENERATIONS = DriveEvidenceStreams.PACKET_GENERATIONS, HISTORY_GENERATIONS = DriveEvidenceStreams.HISTORY_GENERATIONS;
    private static volatile DriveEvidence current;
    private final Reflect api;
    private final Handler worker;
    private final File directory;
    private final GuidanceSnapshot progress;
    private final DriveEvidenceStreams streams;
    private final AdasStateJournal journal;
    private final boolean enabled;
    private long nextSample, nextCache, generation;
    private String routeId;
    private volatile boolean pending;
    DriveEvidence(Context context, Reflect api, Handler worker) {
        this.api=api;this.worker=worker;progress=new GuidanceSnapshot(api);
        enabled="ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE)
                ||new File(context.getFilesDir(),"denza-drive-capture.enabled").isFile();
        directory=new File(context.getFilesDir(),"denza-drive-evidence");
        AdasStateJournal opened=null;
        if(enabled)try{opened=new AdasStateJournal(new File(directory,"adas-journal.jsonl"),System.currentTimeMillis());}
        catch(java.io.IOException|RuntimeException failed){Log.w("DenzaYandexHud","no ADAS change journal: "+failed.getClass().getSimpleName());}
        journal=opened;
        streams=new DriveEvidenceStreams(new DriveEvidenceLog(new File(directory,"progress.jsonl"),RING_BYTES,HISTORY_GENERATIONS),
                new DriveEvidenceLog(new File(directory,"adas-packets.jsonl"),RING_BYTES,PACKET_GENERATIONS),journal);current=enabled?this:null;
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
        long wall=System.currentTimeMillis();
        d.enqueue(d.streams.passive(receiver,receiverSession,NavigationEvidence.SOURCE_SESSION_ID,topic,stamp,received,bytes,wall));
        if(d.journal!=null){Map<String,Object> change=d.journal.observe(receiver,topic,bytes,received,wall);if(change!=null)d.enqueue(change);}
    }
    /** Our live feed's state, into the change journal when it changes. */
    static void feedState(String state,String reason,String detail) {
        DriveEvidence d=current;if(d==null||d.journal==null)return;
        Map<String,Object> change=d.journal.feed(state,reason,detail,android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis());
        if(change!=null)d.enqueue(change);
    }
}
