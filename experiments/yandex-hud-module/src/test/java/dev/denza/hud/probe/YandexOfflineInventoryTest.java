package dev.denza.hud.probe;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Fake SDK getters only: completeness, installed-state policy, deletion, TTL and canonical provenance. */
public final class YandexOfflineInventoryTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    enum State {AVAILABLE,COMPLETED,OUTDATED,DOWNLOADING,PAUSED,NEED_UPDATE,UNSUPPORTED,FUTURE}
    static final class Region {
        final int id; Object state,release=20L,downloaded; Object progress,legacyPath=Boolean.FALSE;
        Region(int id,State state,Long downloaded,float progress){this.id=id;this.state=state;this.downloaded=downloaded;this.progress=progress;}
    }
    static final class Fake implements YandexOfflineInventory.Access {
        List<Region> regions=new ArrayList<>(); final Set<String> calls=new HashSet<>(); int reads; String fail;
        final Object manager=new Object();
        Fake(Region...r){regions.addAll(Arrays.asList(r));}
        @Override public Object manager(){reads++;calls.add("getOfflineCacheManager");if("manager".equals(fail))throw new IllegalStateException("native unavailable");return manager;}
        @Override public Object call(Object o,String method,Object...args){
            reads++;calls.add(method);if(method.equals(fail))throw new IllegalStateException("Missing method test" );
            if(o==manager){
                if(method.equals("regions"))return regions;
                int id=((Number)args[0]).intValue();Region r=null;for(Region q:regions)if(q.id==id){r=q;break;}
                if(r==null)throw new AssertionError("unknown region id");
                if(method.equals("getState"))return r.state;
                if(method.equals("getProgress"))return r.progress;
                if(method.equals("getDownloadedReleaseTime"))return r.downloaded;
                if(method.equals("isLegacyPath"))return r.legacyPath;
            }else{
                Region r=(Region)o;if(method.equals("getId"))return r.id;if(method.equals("getReleaseTime"))return r.release;
            }
            throw new AssertionError("unexpected SDK operation "+method);
        }
    }
    static Region good(int id){return new Region(id,State.COMPLETED,10L,1f);}
    static YandexOfflineInventory.Snapshot read(Region...regions){return new YandexOfflineInventory().poll(new Fake(regions),1000);}
    static void basic(){
        Fake f=new Fake(good(43),new Region(1,State.AVAILABLE,null,0));YandexOfflineInventory inventory=new YandexOfflineInventory();
        check(inventory.status().get("reasonCode").equals("not_polled"),"status before poll doesn't fake observation");
        check(!inventory.ready(0),"availability before first inventory observation is false");
        YandexOfflineInventory.Snapshot first=inventory.poll(f,0);
        check(first.valid&&first.ready(0)&&first.usableCount==1&&first.catalogCount==2,"one genuine installed usable release");
        check(inventory.ready(0),"cached inventory availability needs no route or coverage proof");
        check(first.observedAt==0&&first.regions.size()==1&&first.reasonCode.equals("ready"),"snapshot API exact fields");
        int reads=f.reads;check(inventory.poll(f,1999)==first&&f.reads==reads,"poll cached below two seconds");
        YandexOfflineInventory.Snapshot again=inventory.poll(f,2000);
        check(again!=first&&again.fingerprint.equals(first.fingerprint)&&again.generation==first.generation&&f.reads>reads,"fresh observation keeps canonical fingerprint");
        check(first.ready(4999)&&!first.ready(5000)&&!again.ready(1999),"exact five-second TTL and future snapshot fail closed");
        check(inventory.ready(6999)&&!inventory.ready(7000),"availability getter expires relative to latest observation");
        check(f.calls.equals(new HashSet<>(Arrays.asList("getOfflineCacheManager","regions","getId","getReleaseTime","getState","getProgress","getDownloadedReleaseTime","isLegacyPath"))),"strict getter-only surface");
        try{first.regions.clear();throw new AssertionError("mutable inventory");}catch(UnsupportedOperationException ok){checks++;}
        try{first.regions.get(0).put("state","AVAILABLE");throw new AssertionError("mutable row");}catch(UnsupportedOperationException ok){checks++;}
        try{first.status().clear();throw new AssertionError("mutable status");}catch(UnsupportedOperationException ok){checks++;}
    }
    static void policy(){
        YandexOfflineInventory.Snapshot old=read(new Region(43,State.OUTDATED,9L,1));
        check(old.valid&&old.reasonCode.equals("ready_outdated"),"outdated installed map usable by explicit policy");
        for(State state:new State[]{State.AVAILABLE,State.DOWNLOADING,State.PAUSED,State.NEED_UPDATE,State.UNSUPPORTED}){
            check(!read(new Region(43,state,null,0)).valid,"no installed release not sufficient: "+state);
            check(read(good(1),new Region(43,state,null,0)).valid,"unrelated not-installed region does not block good map: "+state);
            YandexOfflineInventory.Snapshot mixed=read(good(1),new Region(43,state,5L,.5f));
            check(mixed.valid&&mixed.ready(1000)&&mixed.usableCount==1&&mixed.regions.size()==2,
                    "unrelated installed unusable region does not block any-region admission: "+state);
            check(Boolean.FALSE.equals(mixed.regions.get(1).get("usable")),"unusable installed row remains explicit: "+state);
            check(!read(new Region(43,state,5L,.5f)).valid,"unusable installed region alone cannot admit: "+state);
        }
        check(!read(new Region(43,State.COMPLETED,null,1)).valid,"claimed complete but downloaded release missing");
        check(!read(new Region(43,State.OUTDATED,0L,1)).valid,"zero release isn't installed proof");
        check(read(new Region(43,State.COMPLETED,10L,.9999f)).reasonCode.equals("installed_progress_incomplete"),"complete state with partial progress blocked");
        YandexOfflineInventory.Snapshot legacy=read(good(1),new Region(43,State.NEED_UPDATE,9L,1));
        check(legacy.valid&&legacy.regions.get(1).get("reasonCode").equals("installed_legacy_update_required"),"legacy format reason retained without blocking unrelated good map");
        YandexOfflineInventory.Snapshot unsupported=read(good(1),new Region(43,State.UNSUPPORTED,9L,1));
        check(unsupported.valid&&unsupported.regions.get(1).get("reasonCode").equals("installed_unsupported"),"unsupported installed reason retained without blocking unrelated good map");
        check(read(good(1),new Region(43,State.COMPLETED,null,1)).valid,"unrelated missing release does not block any-region policy");
        check(read(good(1),new Region(43,State.COMPLETED,10L,.5f)).valid,"unrelated partial installed map does not block any-region policy");
        check(!read().valid,"empty catalog not ready");
    }
    static void identity(){
        Region a=good(43),b=new Region(1,State.AVAILABLE,null,0);Fake f=new Fake(a,b);YandexOfflineInventory inventory=new YandexOfflineInventory();
        YandexOfflineInventory.Snapshot first=inventory.poll(f,1000);
        Collections.reverse(f.regions);YandexOfflineInventory.Snapshot reordered=inventory.poll(f,3000);
        check(first.fingerprint.equals(reordered.fingerprint)&&first.catalogDigest.equals(reordered.catalogDigest),"catalog order does not alter fingerprint");
        a.release=21L;YandexOfflineInventory.Snapshot changed=inventory.poll(f,5000);
        check(!changed.fingerprint.equals(first.fingerprint)&&changed.generation>first.generation,"catalog current release changes fingerprint");
        a.downloaded=11L;YandexOfflineInventory.Snapshot release=inventory.poll(f,7000);
        check(!release.fingerprint.equals(changed.fingerprint),"downloaded release changes fingerprint");
        a.state=State.OUTDATED;YandexOfflineInventory.Snapshot state=inventory.poll(f,9000);
        check(!state.fingerprint.equals(release.fingerprint)&&state.valid,"state transition captured");
        f.regions.remove(a);YandexOfflineInventory.Snapshot deleted=inventory.poll(f,11000);
        check(!deleted.valid&&!deleted.fingerprint.equals(state.fingerprint)&&deleted.usableCount==0,"deleted installed region invalidates proof");
        check(!inventory.ready(11000),"availability getter revokes immediately when last usable region disappears");
        a.state=State.COMPLETED;f.regions.add(a);YandexOfflineInventory.Snapshot restored=inventory.poll(f,13000);
        check(restored.valid&&!deleted.ready(13000),"new inventory can recover; old invalid snapshot remains invalid");
        check(inventory.poll(f,12000).reasonCode.equals("clock_invalid"),"clock rollback invalidates poll");
    }
    static void layout(){
        Region region=good(43);Fake fake=new Fake(region);YandexOfflineInventory inventory=new YandexOfflineInventory();
        YandexOfflineInventory.Snapshot current=inventory.poll(fake,1000);
        check(Boolean.FALSE.equals(current.regions.get(0).get("isLegacyPath")),"explicit current layout retained");
        region.legacyPath=Boolean.TRUE;YandexOfflineInventory.Snapshot legacy=inventory.poll(fake,3000);
        check(legacy.valid&&Boolean.TRUE.equals(legacy.regions.get(0).get("isLegacyPath")),"layout does not strengthen installed-region admission");
        check(!current.fingerprint.equals(legacy.fingerprint),"layout transition invalidates package observation identity");
        fake.fail="isLegacyPath";YandexOfflineInventory.Snapshot unavailable=inventory.poll(fake,5000);
        check(unavailable.valid&&unavailable.regions.get(0).get("isLegacyPath")==null,"optional layout failure remains explicit without rejecting inventory");
        check(!unavailable.fingerprint.equals(legacy.fingerprint),"unavailable layout changes identity");
        fake.fail=null;region.legacyPath="false";
        check(inventory.poll(fake,7000).regions.get(0).get("isLegacyPath")==null,"nonboolean layout is not silently converted");
        Fake absent=new Fake(new Region(1,State.AVAILABLE,null,0));new YandexOfflineInventory().poll(absent,1000);
        check(!absent.calls.contains("isLegacyPath"),"layout query restricted to usable installed regions");
    }
    static void malformed(){
        Region unknown=good(43);unknown.state=State.FUTURE;
        check(read(unknown).reasonCode.equals("state_unknown"),"unknown enum value fails closed");
        check(!read(good(1),unknown).valid,"unknown enum still invalidates catalog even when another region is usable");
        Region string=good(43);string.state="COMPLETED";
        check(read(string).reasonCode.equals("state_invalid"),"string impersonating SDK enum rejected");
        for(Object progress:new Object[]{Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.1f,1L,null}){
            Region r=good(43);r.progress=progress;check(read(r).reasonCode.equals("progress_invalid"),"invalid progress scalar rejected");
        }
        Region release=good(43);release.release=null;check(read(release).reasonCode.equals("release_invalid"),"missing catalog release fails closed");
        release=good(43);release.downloaded=-1L;check(read(release).reasonCode.equals("release_invalid"),"negative downloaded release rejected");
        check(read(good(43),good(43)).reasonCode.equals("duplicate_region"),"duplicate region identity rejected");
        Fake unsupported=new Fake(good(43));unsupported.fail="getDownloadedReleaseTime";
        check(new YandexOfflineInventory().poll(unsupported,1000).reasonCode.equals("api_unsupported"),"unsupported getter explicit");
        Fake error=new Fake(good(43));error.fail="manager";
        check(new YandexOfflineInventory().poll(error,1000).reasonCode.equals("sdk_read_error"),"SDK failure invalidates whole inventory");
        Fake large=new Fake();for(int i=0;i<=YandexOfflineInventory.MAX_REGIONS;i++)large.regions.add(good(i));
        YandexOfflineInventory.Snapshot tooLarge=new YandexOfflineInventory().poll(large,1000);
        check(!tooLarge.valid&&tooLarge.reasonCode.equals("catalog_truncated")&&large.reads==2,"oversized catalog rejected before region JNI reads");
        check(new YandexOfflineInventory().poll(new Fake(),-1).reasonCode.equals("clock_invalid"),"negative observation clock rejected");
    }
    static void thread()throws Exception{
        YandexOfflineInventory inventory=new YandexOfflineInventory();Fake f=new Fake(good(1));AtomicReference<Throwable> result=new AtomicReference<>();
        Thread t=new Thread(()->{try{inventory.poll(f,0);}catch(Throwable e){result.set(e);}});t.start();t.join(5000);
        check(result.get()instanceof IllegalStateException&&f.reads==0,"wrong-thread poll never enters SDK");
    }
    public static void main(String[]args)throws Exception{basic();policy();identity();layout();malformed();thread();System.out.println("YandexOfflineInventoryTest: "+checks+" checks passed; no route coverage assertion");}
}
