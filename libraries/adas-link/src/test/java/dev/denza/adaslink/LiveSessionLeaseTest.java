package dev.denza.adaslink;

import java.util.Map;

public final class LiveSessionLeaseTest {
    private static final String BOOT="56e1b912-893e-41bc-9e81-a6c38eb7d28d", CONSUMER="364db69e-b53c-4969-a242-1fb6505af45e",
            READER="58f6842c-6735-4ec6-9e49-b9b56a33eb22", ARM="7de975e4-8992-43f1-b337-f940022425fe";
    private static int checks;
    static Map<String,Object> arm() {
        Map<String,Object> m=Json.object();m.put("schema","denza-adas-live-arm-v1");m.put("bootId",BOOT);m.put("consumerSession",CONSUMER);
        m.put("readerSession",READER);m.put("armId",ARM);m.put("issuedAtElapsedMs",1000L);m.put("expiresAtElapsedMs",10000L);
        m.put("stockPackage",LiveSessionLease.STOCK_PACKAGE);m.put("stockDisabled",true);return m;
    }
    static LiveSessionLease read(Map<String,Object> m,long now){return LiveSessionLease.read(Json.write(m),BOOT,CONSUMER,now);}
    static void check(boolean b){checks++;if(!b)throw new AssertionError("lease check "+checks);}
    static void bad(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("lease must reject "+checks);}
    public static void main(String[] args) {
        LiveSessionLease a=read(arm(),1000);check(a.same(read(arm(),9000)));
        check(a.frameMode==LiveSessionLease.FrameMode.WGS84);
        for(LiveSessionLease.FrameMode mode:LiveSessionLease.FrameMode.values()){
            Map<String,Object> framed=arm();framed.put("coordinateFrame",mode.name());LiveSessionLease lease=read(framed,1000);
            check(lease.frameMode==mode);check(!lease.same(a));check(lease.same(read(framed,1000)));
        }
        for(Object value:new Object[]{"GCJ02","alternate_routes",null,1L}){Map<String,Object> invalid=arm();invalid.put("coordinateFrame",value);bad(()->read(invalid,1000));}
        Map<String,Object> unexpected=arm();unexpected.put("coordinateFrame","WGS84");unexpected.put("frame","WGS84");bad(()->read(unexpected,1000));
        Map<String,Object> nativeFrame=arm();nativeFrame.put("coordinateFrame","NATIVE_GCJ_UNRESTRICTED_CANDIDATE");Map<String,Object> controlFrame=arm();controlFrame.put("coordinateFrame","WGS84");check(!read(nativeFrame,1000).same(read(controlFrame,1000)));
        bad(()->read(arm(),999));bad(()->read(arm(),10000));
        for(String key:new String[]{"schema","bootId","consumerSession","readerSession","armId","stockPackage"}) {
            Map<String,Object> m=arm();m.put(key,"other");bad(()->read(m,1000));
        }
        Map<String,Object> off=arm();off.put("stockDisabled",false);bad(()->read(off,1000));
        Map<String,Object> extra=arm();extra.put("extra",true);bad(()->read(extra,1000));
        Map<String,Object> future=arm();future.put("expiresAtElapsedMs",LiveSessionLease.MAX_LIFETIME_MS+1001);bad(()->read(future,1000));
        Map<String,Object> floatTime=arm();floatTime.put("issuedAtElapsedMs",1000.0);bad(()->read(floatTime,1000));
        Map<String,Object> changed=arm();changed.put("expiresAtElapsedMs",9999L);check(!a.same(read(changed,1000)));
        Map<String,Object> iso=Json.object();iso.put("schema","denza-adas-stock-isolation-v1");iso.put("bootId",BOOT);
        iso.put("consumerSession",CONSUMER);iso.put("readerSession",READER);iso.put("stockAbsent",true);
        iso.put("startedAtElapsedMs",1000L);iso.put("completedAtElapsedMs",1010L);
        check(a.isolationStart(Json.write(iso),3500)==1000);bad(()->a.isolationStart(Json.write(iso),3501));
        bad(()->a.isolationStart(Json.write(iso),1009));iso.put("stockAbsent",false);bad(()->a.isolationStart(Json.write(iso),1010));
        iso.put("stockAbsent",true);iso.put("readerSession",ARM);bad(()->a.isolationStart(Json.write(iso),1010));
        bad(()->LiveSessionLease.object("[[[[[[[[[0]]]]]]]]]"));bad(()->LiveSessionLease.object("null"));
        check(LiveSessionLease.uuid(ARM));check(!LiveSessionLease.uuid("1-1-1-1-1"));
        check("ecc6a015-850f-3817-85e3-5e60cd0d3d03".equals(BootIdentity.fromCount("42")));
        for(String invalid:new String[]{"null","-1","01","2147483648","1.0",""})bad(()->BootIdentity.fromCount(invalid));
        System.out.println("LiveSessionLeaseTest: "+checks+" checks passed");
    }
}
