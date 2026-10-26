package dev.denza.hud.probe;

import dev.denza.adaslink.OfflineMapGate;
import java.util.*;

/** Product admission requires regional readiness and a completed current-source read. */
public final class YandexOfflineMapSourceHashTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void fails(Runnable r){try{r.run();}catch(RuntimeException e){checks++;return;}throw new AssertionError("malformed geometry accepted");}
    static Map<String,Object> route(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("routeId","current");m.put("coordinateFrame","wgs84");m.put("pointOrder","lon,lat");
        m.put("points",Arrays.asList(Arrays.asList(0d,0d),Arrays.asList(.001d,.002d)));return m;
    }
    static void hash(){
        Map<String,Object> r=route();String h=YandexOfflineMapSource.geometryHash(r,"current");
        check(h.equals("1b1774a093e87792d6cea98d47040bd79e4f918554790f35963b87e6e63caad5"),"independent Python sha256 big-endian double-pair oracle");
        r.put("metadata","changed traffic");check(YandexOfflineMapSource.geometryHash(r,"current").equals(h),"conditions metadata does not alter geometry identity");
        r.put("points",Arrays.asList(Arrays.asList(0L,0L),Arrays.asList(.001d,.002d)));
        check(YandexOfflineMapSource.geometryHash(r,"current").equals(h),"finite numeric geometry normalised to doubles");
        r.put("points",Arrays.asList(Arrays.asList(.001d,.002d),Arrays.asList(0d,0d)));
        check(!YandexOfflineMapSource.geometryHash(r,"current").equals(h),"orientation changes geometry identity");
        r.put("points",Arrays.asList(Arrays.asList(0d,0d),Arrays.asList(.001d,.002d),Arrays.asList(.001d,.002d)));
        check(!YandexOfflineMapSource.geometryHash(r,"current").equals(h),"point count and duplicate vertices retained");
        r.put("points",Arrays.asList(Arrays.asList(0d,0d),Arrays.asList(.001d,.002001d)));
        check(!YandexOfflineMapSource.geometryHash(r,"current").equals(h),"changed chosen polyline cannot reuse proof");
        r.put("routeId","new");check(YandexOfflineMapSource.geometryHash(r,"new").length()==64,"route ID is separately checked before hashing");
        fails(()->YandexOfflineMapSource.geometryHash(r,"old"));
    }
    static void errors(){
        fails(()->YandexOfflineMapSource.geometryHash(null,"current"));fails(()->YandexOfflineMapSource.geometryHash(route(),null));
        for(String key:new String[]{"coordinateFrame","pointOrder","routeId"}){
            Map<String,Object> r=route();r.put(key,"wrong");fails(()->YandexOfflineMapSource.geometryHash(r,"current"));
        }
        for(Object points:new Object[]{null,"opaque",Collections.emptyList(),Arrays.asList(Arrays.asList(0,0)),
                Arrays.asList(Arrays.asList(0,0,0),Arrays.asList(1,1)),Arrays.asList("opaque",Arrays.asList(1,1)),
                Arrays.asList(Arrays.asList("zero",0),Arrays.asList(1,1))}){
            Map<String,Object> r=route();r.put("points",points);fails(()->YandexOfflineMapSource.geometryHash(r,"current"));
        }
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,180.0001,-180.0001}){
            Map<String,Object> r=route();r.put("points",Arrays.asList(Arrays.asList(value,0),Arrays.asList(0,0)));fails(()->YandexOfflineMapSource.geometryHash(r,"current"));
        }
        for(double lat:new double[]{90.0001,-90.0001}){
            Map<String,Object> r=route();r.put("points",Arrays.asList(Arrays.asList(0,lat),Arrays.asList(0,0)));fails(()->YandexOfflineMapSource.geometryHash(r,"current"));
        }
        Map<String,Object> tooLarge=route();tooLarge.put("points",new AbstractList<Object>(){
            @Override public int size(){return 200001;}@Override public Object get(int index){throw new AssertionError("must reject size before reading");}
        });fails(()->YandexOfflineMapSource.geometryHash(tooLarge,"current"));
        Map<String,Object> boundary=route();boundary.put("points",Arrays.asList(Arrays.asList(-180,-90),Arrays.asList(180,90)));
        check(YandexOfflineMapSource.geometryHash(boundary,"current").length()==64,"inclusive WGS84 boundaries");
    }
    static YandexRouteRegions.Snapshot regions(OfflineMapGate.Subject s,YandexOfflineInventory.Snapshot maps,boolean ready){
        return new YandexRouteRegions.Snapshot(s.routeId,s.epoch,s.geometrySha256,maps,maps.observedAt,1,maps.fingerprint,
                ready?"regions_ready":"regions_missing",ready?"Регион загружен":"Загрузите карты Яндекса: Москва",true,ready,
                2,2,1000,Collections.emptyList(),Collections.emptyList());
    }
    static YandexOfflineAttributes.View read(String key,boolean ready){
        Map<String,Object> status=new LinkedHashMap<>();status.put("sourceKey",key);status.put("preparationKey",key);
        status.put("reason","offline_route_tiles_missing");status.put("reasonRussian","Загрузите карты всего маршрута");
        dev.denza.adaslink.YandexRoute r=new dev.denza.adaslink.YandexRoute("current",new double[]{0,.001},new double[]{0,.002},
                Collections.emptyList(),null,null,null,null,null,null);
        return new YandexOfflineAttributes.View(true,ready,ready?new dev.denza.adaslink.RouteVectorAttributes.Builder(r).finish():null,status);
    }
    static void admission(){
        String fp="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        OfflineMapGate.Subject subject=new OfflineMapGate.Subject("current",1,YandexOfflineMapSource.geometryHash(route(),"current"));
        YandexOfflineInventory.Snapshot maps=new YandexOfflineInventory.Snapshot(1000,1,true,fp,"ready","Карта доступна",
                fp,Collections.emptyList(),1,1);
        YandexRouteRegions.Snapshot required=regions(subject,maps,true);
        YandexOfflineAttributes.View read=read("current-source",true);
        OfflineMapGate.Snapshot prepared=YandexOfflineMapSource.admissionSnapshot(subject,maps,required,read,"current-source",true,1000);
        check(prepared.state==OfflineMapGate.State.PREPARED&&prepared.proofFingerprint.matches("[0-9a-f]{64}"),"prepared state binds source evidence");
        check(OfflineMapGate.check(subject,prepared,1000).ready,"complete regional read permits current source");
        check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,maps,null,null,"current-source",true,1000),1000).ready,
                "installed region alone cannot admit product");
        OfflineMapGate.Snapshot missing=YandexOfflineMapSource.admissionSnapshot(subject,maps,regions(subject,maps,false),read,"current-source",true,1000);
        check(!OfflineMapGate.check(subject,missing,1000).ready&&missing.reason.contains("Москва"),"missing required region prompts named manual download");
        for(YandexOfflineAttributes.View value:new YandexOfflineAttributes.View[]{null,read("current-source",false),read("old-source",true)})
            check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,maps,required,value,"current-source",true,1000),1000).ready,
                    "pending, partial and stale-source results never admit");
        check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,maps,required,read,"current-source",false,1000),1000).ready,
                "path readiness required even after a successful read");
        for(long now:new long[]{999,6000})
            check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,maps,required,read,"current-source",true,now),now).ready,
                    "future/stale inventory blocks");
        for(String geometry:new String[]{null,"invalid"}){
            OfflineMapGate.Subject partial=new OfflineMapGate.Subject("current",1,geometry);
            check(YandexOfflineMapSource.admissionSnapshot(partial,maps,required,read,"current-source",true,1000).state==OfflineMapGate.State.UNAVAILABLE,"geometry required");
        }
        check(YandexOfflineMapSource.admissionSnapshot(new OfflineMapGate.Subject(null,2,null),maps,required,read,"current-source",true,1000).state==OfflineMapGate.State.PENDING,
                "route cancellation cannot inherit completed read");
        OfflineMapGate.Subject replacement=new OfflineMapGate.Subject("new",2,subject.geometrySha256);
        check(!OfflineMapGate.check(replacement,YandexOfflineMapSource.admissionSnapshot(replacement,maps,required,read,"current-source",true,1000),1000).ready,
                "old region discovery cannot admit replacement");
        check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,maps,required,read,null,true,1000),1000).ready,"source key mandatory");
        YandexOfflineInventory.Snapshot changed=new YandexOfflineInventory.Snapshot(1000,2,true,subject.geometrySha256,"ready","Карта доступна",
                subject.geometrySha256,Collections.emptyList(),1,1);
        check(!OfflineMapGate.check(subject,YandexOfflineMapSource.admissionSnapshot(subject,changed,required,read,"current-source",true,1000),1000).ready,
                "catalog change invalidates regional result before new read");
    }
    public static void main(String[]args){hash();errors();admission();System.out.println("YandexOfflineMapSourceHashTest: "+checks+" checks passed");}
}
