package dev.denza.hud.probe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.yandex.mapkit.offline_cache.RegionsAtPointListener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only regional discovery for the selected route. Sampling is never a tile coverage proof. */
final class YandexRouteRegions {
    static final double SAMPLE_METERS=5000, MAX_ROUTE_METERS=5000000;
    static final int MAX_SAMPLES=1024, MAX_POINTS=200000, MAX_ANSWER_IDS=64, MAX_ATTEMPTS=2;
    static final long REQUEST_TIMEOUT_MS=5000, RETRY_MS=1000, MAX_LOOKUP_MS=180000;

    static final class Snapshot {
        final String routeId, geometrySha256, inventoryFingerprint, fingerprint, code, reason, reasonRussian;
        final long epoch, observedAt, inventoryObservedAt, generation;
        final boolean complete, discovered, pending, ready;
        final int sampleCount, answeredCount;
        final double routeMeters;
        /** Each row is an OR of catalog regions; rows themselves are AND requirements. */
        final List<Map<String,Object>> requirements, missingRegions;
        Snapshot(String route, long epoch, String geometry, YandexOfflineInventory.Snapshot inventory,
                 long now,long generation,String fingerprint,String code,String reason,boolean complete,boolean ready,
                 int samples,int answered,double meters,List<Map<String,Object>> requirements,List<Map<String,Object>> missing) {
            routeId=route;this.epoch=epoch;geometrySha256=geometry;
            inventoryFingerprint=inventory==null?null:inventory.fingerprint;
            inventoryObservedAt=inventory==null?-1:inventory.observedAt;
            observedAt=now;this.generation=generation;this.fingerprint=fingerprint;this.code=code;
            this.reason=reason;reasonRussian=reason;this.complete=complete;discovered=complete;
            pending="regions_pending".equals(code);this.ready=ready;
            sampleCount=samples;answeredCount=answered;routeMeters=meters;
            this.requirements=Collections.unmodifiableList(new ArrayList<>(requirements));
            missingRegions=Collections.unmodifiableList(new ArrayList<>(missing));
        }
        boolean ready(long now) { return ready && now>=inventoryObservedAt && now-inventoryObservedAt<YandexOfflineInventory.TTL_MS; }
        Map<String,Object> status() {
            Map<String,Object> m=new LinkedHashMap<>();m.put("schema","denza-yandex-route-regions-v1");
            m.put("routeId",routeId);m.put("routeEpoch",epoch);m.put("geometrySha256",geometrySha256);
            m.put("generation",generation);m.put("fingerprint",fingerprint);m.put("inventoryFingerprint",inventoryFingerprint);
            m.put("observedAt",observedAt);m.put("inventoryObservedAt",inventoryObservedAt);
            m.put("complete",complete);m.put("ready",ready);m.put("pending",pending);m.put("code",code);m.put("reason",reason);
            m.put("sampleCount",sampleCount);m.put("answeredCount",answeredCount);m.put("routeMeters",routeMeters);
            m.put("sampleSpacingMeters",SAMPLE_METERS);m.put("maxSamples",MAX_SAMPLES);m.put("maxRouteMeters",MAX_ROUTE_METERS);
            m.put("requirements",requirements);m.put("missingRegions",missingRegions);m.put("automaticDownload",false);
            m.put("coverageMeaning","sampled region discovery only; actual full-route tile coverage remains required");
            m.put("completeRouteCoverageChecked",false);return Collections.unmodifiableMap(m);
        }
    }
    interface Callback { void regions(List<Integer> ids); void error(); }
    interface Access { void request(double lon,double lat,Callback callback); }
    interface Runtime {
        long now(); void post(Runnable task,long delay); void remove(Runnable task); void assertOwner();
    }
    private static final class MainRuntime implements Runtime {
        final Handler main=new Handler(Looper.getMainLooper());
        @Override public long now(){return SystemClock.elapsedRealtime();}
        @Override public void post(Runnable r,long delay){main.postDelayed(r,delay);}
        @Override public void remove(Runnable r){main.removeCallbacks(r);}
        @Override public void assertOwner(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("region lookup main thread required");}
    }
    /** The typed object is held by Request until callback/deadline; JNI must see the pinned SDK interface. */
    private static final class SdkAccess implements Access {
        final Reflect api;
        SdkAccess(Reflect api){this.api=api;}
        @Override public void request(double lon,double lat,Callback callback) {
            if(api==null||api.type("com.yandex.mapkit.offline_cache.RegionsAtPointListener")!=RegionsAtPointListener.class)
                throw new IllegalStateException("region listener classloader mismatch");
            Object manager=api.call(api.call(api.type("com.yandex.mapkit.MapKitFactory"),"getInstance"),"getOfflineCacheManager");
            Object point=api.make("com.yandex.mapkit.geometry.Point",lat,lon);
            // Keep a real listener alive through callback, not a Proxy consumed by the native SDK.
            RegionsAtPointListener listener=new RegionsAtPointListener(){
                @Override public void onRegions(List<Integer> ids){callback.regions(ids);}
                @Override public void onError(com.yandex.runtime.Error error){callback.error();}
            };
            if(callback instanceof Request)((Request)callback).sdkListener=listener;
            api.call(manager,"requestRegionsAtPoint",point,listener);
        }
    }
    private final Thread owner=Thread.currentThread();
    private final Runtime runtime;
    private Access access;
    private YandexOfflineInventory.Snapshot inventory;
    private Snapshot last;
    private String routeId, geometry;
    private long epoch, ticket, startedAt, lastNow=-1;
    private int next, attempts;
    private double routeMeters;
    private List<double[]> points=Collections.emptyList();
    private final List<List<Integer>> answers=new ArrayList<>();
    private Request pending;
    private Runnable scheduled,deadline;
    private String failure;
    private boolean closed;

    YandexRouteRegions(){this(new MainRuntime());}
    YandexRouteRegions(Runtime runtime){this.runtime=runtime;owner();}
    Snapshot sample(Reflect api,String routeId,long epoch,String geometrySha256,Map<String,Object> routeCopy,
                    YandexOfflineInventory.Snapshot inventory,long now) {
        return sample(new SdkAccess(api),routeId,epoch,geometrySha256,routeCopy,inventory,now);
    }
    Snapshot sample(Access access,String routeId,long epoch,String geometrySha256,Map<String,Object> routeCopy,
                    YandexOfflineInventory.Snapshot inventory,long now) {
        owner();this.inventory=inventory;this.access=access;
        if(closed){publish(now,"regions_closed");return last;}
        boolean changed=last==null||!Objects.equals(this.routeId,routeId)||this.epoch!=epoch||!Objects.equals(geometry,geometrySha256);
        if(changed) {
            cancel();this.routeId=routeId;this.epoch=epoch;geometry=geometrySha256;failure=null;next=0;attempts=0;
            points=Collections.emptyList();answers.clear();routeMeters=0;startedAt=now;
            if(routeId==null)failure="route_not_started";
            else if(geometrySha256==null||!geometrySha256.matches("[0-9a-f]{64}"))failure="route_geometry_unavailable";
            else try { points=plan(routeCopy,routeId); } catch(Limit e){failure=e.code;}
            catch(RuntimeException e){failure="route_geometry_unavailable";}
            if(failure==null){final long token=ticket;deadline=()->{owner();if(ticket==token){fail("regions_lookup_timeout");}};runtime.post(deadline,MAX_LOOKUP_MS);}
        }
        if(now<0||lastNow>=0&&now<lastNow){cancel();failure="regions_clock_invalid";}
        lastNow=now;
        if(failure==null&&next<points.size()&&now-startedAt>=MAX_LOOKUP_MS)fail("regions_lookup_timeout");
        if(failure==null&&pending==null&&scheduled==null&&next<points.size())request();
        publish(runtime.now(),null);return last;
    }
    Snapshot snapshot(){owner();return last;}
    void close(){owner();closed=true;cancel();publish(runtime.now(),"regions_closed");}
    private final class Request implements Callback {
        final long token,at; final int index; Object sdkListener;
        final AtomicBoolean delivered=new AtomicBoolean();
        Runnable timeout;
        Request(long token,long at,int index){this.token=token;this.at=at;this.index=index;}
        @Override public void regions(List<Integer> ids){
            if(!delivered.compareAndSet(false,true))return;
            // Copy before queueing: SDK callback storage is not owned by this adapter.
            List<Integer> copy=null;
            if(ids!=null&&ids.size()<=MAX_ANSWER_IDS)try{copy=new ArrayList<>(ids);}catch(RuntimeException ignored){}
            final List<Integer> owned=copy;deliver(()->finish(this,owned,null));
        }
        @Override public void error(){if(delivered.compareAndSet(false,true))deliver(()->finish(this,null,"regions_request_error"));}
        private void deliver(Runnable task){if(Thread.currentThread()==owner)task.run();else runtime.post(task,0);}
    }
    private void request(){
        owner();if(closed||failure!=null||pending!=null||next>=points.size())return;
        long now=runtime.now();if(now<startedAt||now-startedAt>=MAX_LOOKUP_MS){fail("regions_lookup_timeout");return;}
        final Request request=new Request(ticket,now,next);pending=request;attempts++;
        request.timeout=()->finish(request,null,"regions_request_timeout");runtime.post(request.timeout,REQUEST_TIMEOUT_MS);
        double[] point=points.get(next);
        try{access.request(point[0],point[1],request);}catch(RuntimeException|LinkageError e){finish(request,null,"regions_api_unsupported");}
    }
    private void finish(Request request,List<Integer> ids,String error){
        owner();if(closed||request.token!=ticket||pending!=request||request.index!=next)return;
        long now=runtime.now();runtime.remove(request.timeout);pending=null;request.sdkListener=null;
        if(now<request.at){fail("regions_clock_invalid");return;}
        if(now-startedAt>=MAX_LOOKUP_MS){fail("regions_lookup_timeout");return;}
        if(now-request.at>=REQUEST_TIMEOUT_MS)error="regions_request_timeout";
        TreeSet<Integer> unique=new TreeSet<>();
        if(error==null){
            if(ids==null||ids.isEmpty()||ids.size()>MAX_ANSWER_IDS)error="regions_answer_invalid";
            else for(Object value:ids){if(!(value instanceof Integer)||(Integer)value<0){error="regions_answer_invalid";break;}unique.add((Integer)value);}
        }
        if(error!=null){
            if(attempts<MAX_ATTEMPTS&&!"regions_api_unsupported".equals(error)&&!"regions_answer_invalid".equals(error))schedule(RETRY_MS);
            else fail(error);
        }else{
            answers.add(Collections.unmodifiableList(new ArrayList<>(unique)));next++;attempts=0;
            if(next<points.size())schedule(0);else if(deadline!=null){runtime.remove(deadline);deadline=null;}
        }
        publish(now,null);
    }
    private void schedule(long delay){final long token=ticket;scheduled=()->{owner();if(token!=ticket)return;scheduled=null;request();};runtime.post(scheduled,delay);}
    private void fail(String code){cancel();failure=code;publish(runtime.now(),code);}
    private void cancel(){ticket++;if(pending!=null)runtime.remove(pending.timeout);pending=null;
        if(scheduled!=null)runtime.remove(scheduled);scheduled=null;if(deadline!=null)runtime.remove(deadline);deadline=null;}

    private void publish(long now,String override){
        String code=override!=null?override:failure;boolean complete=failure==null&&!points.isEmpty()&&answers.size()==points.size();
        boolean fresh=inventory!=null&&now>=inventory.observedAt&&now-inventory.observedAt<YandexOfflineInventory.TTL_MS;
        boolean catalog=inventory!=null&&!inventory.catalogDigest.isEmpty()&&inventory.catalogRows.size()==inventory.catalogCount;
        Map<Integer,Map<String,Object>> rows=new HashMap<>();if(catalog)for(Map<String,Object> row:inventory.catalogRows)rows.put(((Number)row.get("id")).intValue(),row);
        List<Map<String,Object>> requirements=new ArrayList<>(),missing=new ArrayList<>();Set<String> seen=new HashSet<>();boolean unknown=false;
        for(List<Integer> ids:answers){
            List<Map<String,Object>> alternatives=new ArrayList<>();boolean usable=false;StringBuilder key=new StringBuilder();
            for(Integer id:ids){Map<String,Object> row=rows.get(id);if(row!=null){alternatives.add(row);key.append(id).append(',');usable|=Boolean.TRUE.equals(row.get("usable"));}}
            if(alternatives.isEmpty()){unknown=true;continue;}
            if(!seen.add(key.toString()))continue;
            Map<String,Object> requirement=new LinkedHashMap<>();requirement.put("alternatives",Collections.unmodifiableList(alternatives));requirement.put("satisfied",usable);
            requirement=Collections.unmodifiableMap(requirement);requirements.add(requirement);if(!usable)missing.add(requirement);
        }
        if(code==null)code=!fresh?"regions_inventory_stale":!catalog?"regions_inventory_unavailable":!complete?"regions_pending":unknown?"regions_catalog_gap":!missing.isEmpty()?"regions_manual_download_required":"regions_ready";
        String reason=reason(code,missing);String fingerprint=hash(routeId+"|"+epoch+"|"+geometry+"|"+(inventory==null?null:inventory.fingerprint)+"|"+answers+"|"+code);
        long generation=last==null?1:last.generation+(last.fingerprint.equals(fingerprint)?0:1);
        last=new Snapshot(routeId,epoch,geometry,inventory,now,generation,fingerprint,code,reason,complete,"regions_ready".equals(code),points.size(),answers.size(),routeMeters,requirements,missing);
    }
    private List<double[]> plan(Map<String,Object> route,String id){
        if(route==null||!id.equals(route.get("routeId"))||!"wgs84".equals(route.get("coordinateFrame"))||!"lon,lat".equals(route.get("pointOrder"))||!(route.get("points") instanceof List))throw new IllegalArgumentException();
        List<?> raw=(List<?>)route.get("points");if(raw.size()<2||raw.size()>MAX_POINTS)throw new Limit("regions_geometry_limit");
        List<double[]> out=new ArrayList<>();double[] previous=null;double distance=0,nextDistance=SAMPLE_METERS;
        for(Object value:raw){
            if(!(value instanceof List)||((List<?>)value).size()!=2)throw new IllegalArgumentException();List<?> pair=(List<?>)value;
            if(!(pair.get(0) instanceof Number)||!(pair.get(1) instanceof Number))throw new IllegalArgumentException();
            double lon=((Number)pair.get(0)).doubleValue(),lat=((Number)pair.get(1)).doubleValue();
            if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90)throw new IllegalArgumentException();
            double[] point={lon,lat};
            if(previous==null)out.add(point);
            else {
                double length=meters(previous,point),end=distance+length;if(end>MAX_ROUTE_METERS)throw new Limit("regions_distance_limit");
                while(nextDistance<=end&&length>0){double f=(nextDistance-distance)/length,dLon=wrap(lon-previous[0]);
                    out.add(new double[]{wrap(previous[0]+f*dLon),previous[1]+f*(lat-previous[1])});nextDistance+=SAMPLE_METERS;
                    if(out.size()>=MAX_SAMPLES)throw new Limit("regions_sample_limit");}
                distance=end;
            }
            previous=point;
        }
        if(distance<=0)throw new IllegalArgumentException();
        double[] tail=out.get(out.size()-1);if(tail[0]!=previous[0]||tail[1]!=previous[1])out.add(previous);
        if(out.size()>MAX_SAMPLES)throw new Limit("regions_sample_limit");routeMeters=distance;return out;
    }
    private static double meters(double[] a,double[] b){double lat=Math.toRadians(b[1]-a[1]),lon=Math.toRadians(wrap(b[0]-a[0]));double s=Math.sin(lat/2),t=Math.sin(lon/2);double h=s*s+Math.cos(Math.toRadians(a[1]))*Math.cos(Math.toRadians(b[1]))*t*t;return 12742000*Math.asin(Math.sqrt(Math.min(1,h)));}
    private static double wrap(double lon){while(lon>180)lon-=360;while(lon< -180)lon+=360;return lon;}
    private static final class Limit extends RuntimeException {final String code;Limit(String code){this.code=code;}}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("region lookup owner thread required");runtime.assertOwner();}
    private static String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes){s.append(Character.forDigit((b>>>4)&15,16));s.append(Character.forDigit(b&15,16));}return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String reason(String code,List<Map<String,Object>> missing){
        switch(code){
            case "regions_ready":return "Регионы маршрута загружены; проверяем покрытие офлайн-карт";
            case "regions_pending":return "Определяем офлайн-карты вдоль выбранного маршрута";
            case "route_not_started":return "Ожидаем выбранный маршрут";
            case "regions_manual_download_required":
                List<String> names=new ArrayList<>();for(Map<String,Object> group:missing){List<String> alternatives=new ArrayList<>();for(Object row:(List<?>)group.get("alternatives"))alternatives.add(String.valueOf(((Map<?,?>)row).get("name")));String name=String.join(" или ",alternatives);if(!names.contains(name))names.add(name);if(names.size()==3)break;}
                return "Скачайте карты вручную в Яндексе: "+String.join("; ",names)+(missing.size()>3?"; и другие регионы":"");
            case "regions_inventory_stale":return "Ожидаем свежий список загруженных карт Яндекса";
            case "regions_inventory_unavailable":return "Не удалось прочитать каталог офлайн-карт Яндекса";
            case "regions_catalog_gap":return "Для части маршрута Яндекс не сообщил доступную офлайн-карту";
            case "regions_api_unsupported":return "Эта версия Яндекса не позволяет определить карты маршрута";
            case "regions_distance_limit":case "regions_geometry_limit":case "regions_sample_limit":return "Маршрут превышает предел проверки офлайн-регионов";
            case "regions_request_timeout":case "regions_lookup_timeout":return "Яндекс не успел определить офлайн-карты маршрута; выберите маршрут заново";
            case "regions_closed":return "Проверка офлайн-карт остановлена";
            case "route_geometry_unavailable":return "Не удалось прочитать геометрию маршрута для проверки карт";
            default:return "Не удалось достоверно определить офлайн-карты маршрута; выберите маршрут заново";
        }
    }
}
