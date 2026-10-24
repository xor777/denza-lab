package dev.denza.hud.probe;

import dev.denza.adaslink.OfflineMapGate;
import java.util.*;

/** Inventory-only production policy and exact geometry identity; no map IO or coverage assertion. */
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
    static void admission(){
        String fingerprint="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        OfflineMapGate.Subject subject=new OfflineMapGate.Subject("current",1,YandexOfflineMapSource.geometryHash(route(),"current"));
        YandexOfflineInventory.Snapshot maps=new YandexOfflineInventory.Snapshot(1000,1,true,fingerprint,"ready","Карта доступна",
                fingerprint,Collections.emptyList(),1,1);
        OfflineMapGate.Snapshot available=YandexOfflineMapSource.admissionSnapshot(subject,maps,1000);
        check(available.state==OfflineMapGate.State.AVAILABLE&&available.code.equals("offline_inventory_ready")
                &&available.proofFingerprint==null,"production ready path is AVAILABLE inventory, not fabricated route coverage");
        check(OfflineMapGate.check(subject,available,1000).ready,"actual production snapshot admits without a coverage proof");
        OfflineMapGate.Snapshot stale=YandexOfflineMapSource.admissionSnapshot(subject,maps,6000);
        check(stale.state==OfflineMapGate.State.UNAVAILABLE&&stale.code.equals("offline_stale"),"source uses exact gate TTL boundary and explicit stale reason");
        check(YandexOfflineMapSource.admissionSnapshot(subject,maps,999).state==OfflineMapGate.State.UNAVAILABLE,"future inventory cannot admit");
        for(String geometry:new String[]{null,"invalid"}){
            OfflineMapGate.Subject partial=new OfflineMapGate.Subject("current",1,geometry);
            check(YandexOfflineMapSource.admissionSnapshot(partial,maps,1000).state==OfflineMapGate.State.UNAVAILABLE,"valid geometry required before inventory admission");
        }
        check(YandexOfflineMapSource.admissionSnapshot(new OfflineMapGate.Subject(null,2,null),maps,1000).state==OfflineMapGate.State.PENDING,
                "cancelled route remains inactive despite installed maps");
        OfflineMapGate.Subject replacement=new OfflineMapGate.Subject("new",2,subject.geometrySha256);
        check(!OfflineMapGate.check(replacement,available,1000).ready,"previous subject inventory snapshot cannot admit replacement route");
        check(OfflineMapGate.check(replacement,YandexOfflineMapSource.admissionSnapshot(replacement,maps,1000),1000).ready,
                "same fresh installed inventory can be explicitly scoped to new current route");
        YandexOfflineInventory.Snapshot missing=new YandexOfflineInventory.Snapshot(1000,2,false,fingerprint,"no_usable_regions","Карта отсутствует",
                fingerprint,Collections.emptyList(),0,0);
        OfflineMapGate.Snapshot unavailable=YandexOfflineMapSource.admissionSnapshot(subject,missing,1000);
        check(unavailable.state==OfflineMapGate.State.UNAVAILABLE&&unavailable.code.equals("no_usable_regions")
                &&!OfflineMapGate.check(subject,unavailable,1000).ready,"missing usable region blocks actual production path");
    }
    public static void main(String[]args){hash();errors();admission();System.out.println("YandexOfflineMapSourceHashTest: "+checks+" checks passed");}
}
