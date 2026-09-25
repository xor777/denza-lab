package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** Protocol-3 lease/FGS handoff checks with injected worker and power proof. */
public final class CloudAwakeProtocolTest {
    private static final String INSTALL="0123456789abcdef0123456789abcdef";
    private static final String FIRST="11111111111111111111111111111111";
    private static final String SECOND="22222222222222222222222222222222";
    private static void need(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private static JSONObject request(long id,String op)throws Exception{
        return new JSONObject().put("protocol",3).put("id",id).put("op",op);
    }
    private static CloudControlRequest parse(JSONObject value)throws Exception{return CloudControlRequest.parse(value.toString());}
    private static void reject(JSONObject value)throws Exception{
        try{parse(value);throw new AssertionError("invalid request admitted");}catch(IOException expected){}
    }
    private static Path marker(Path base)throws Exception{
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path,"{\"protocol\":2,\"install_id\":\""+INSTALL+"\",\"generation\":1,\"desired\":\"custom\"}");
        return path;
    }
    private static final class Worker implements CloudGuardianState.Worker {
        boolean closed;
        public JSONObject request(String op,Identity pair)throws Exception{
            return new JSONObject().put("ok",true).put("stage",op.equals("STOP")?"stopped":"connected")
                .put("code",op.equals("STOP")?"owner_stopped":"connected")
                .put("session_live",!op.equals("STOP"));
        }
        public boolean isAlive(){return !closed;}
        public void close(){closed=true;}
    }
    private static void leaseAndHandoff()throws Exception{
        Path base=Files.createTempDirectory("cloud-awake-wire-");Path path=marker(base);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong elapsed=new AtomicLong(1000),uptime=new AtomicLong(900);
        AtomicInteger starts=new AtomicInteger(),proofs=new AtomicInteger();
        Worker[] workers=new Worker[3];
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}public String profile(){return "double_apn";}},
            ()->{Worker worker=new Worker();workers[starts.getAndIncrement()]=worker;return worker;},
            ()->44,elapsed::get,uptime::get,pair->proofs.incrementAndGet(),false);
        JSONObject probe=owner.execute(parse(request(1,"PROBE")),install);
        String ownerId=probe.getString("owner_id");
        need(probe.getInt("protocol")==3&&probe.getString("profile").equals("awake-alpha-v1")&&
            !probe.getBoolean("lease_active"),"v3 probe envelope");
        JSONObject start=request(2,"START").put("profile","awake-alpha-v1")
            .put("iccid","89860700000000000000").put("imsi","460010000000000")
            .put("service_instance",FIRST).put("renew_seq",1);
        JSONObject started=owner.execute(parse(start),install);
        long firstDeadline=started.getLong("lease_until_uptime_ms");
        need(started.getBoolean("ok")&&started.getBoolean("lease_active")&&starts.get()==1&&proofs.get()==1,
            "FGS start lease");
        uptime.set(5000);elapsed.set(5100);
        JSONObject same=owner.execute(parse(request(3,"ATTACH").put("owner_id",ownerId)
            .put("service_instance",FIRST)),install);
        need(same.getBoolean("ok")&&same.getLong("lease_until_uptime_ms")==firstDeadline&&
            !workers[0].closed,"same FGS attach changed worker or lease");
        JSONObject status=owner.execute(parse(request(4,"STATUS")),install);
        need(status.getLong("lease_until_uptime_ms")==firstDeadline,"STATUS renewed lease");
        JSONObject renewed=owner.execute(parse(request(5,"RENEW").put("owner_id",ownerId)
            .put("service_instance",FIRST).put("renew_seq",2)),install);
        need(renewed.getLong("lease_until_uptime_ms")==35000,"renewal deadline");
        JSONObject replacement=owner.execute(parse(request(6,"ATTACH").put("owner_id",ownerId)
            .put("service_instance",SECOND)),install);
        need(replacement.getBoolean("ok")&&!replacement.getBoolean("lease_active")&&workers[0].closed&&
            starts.get()==1&&proofs.get()==2,"different FGS did not reap and power-check first");
        JSONObject replacementStart=new JSONObject(start.toString()).put("id",70)
            .put("iccid","89860700000000000001").put("service_instance",FIRST);
        JSONObject forbidden=owner.execute(parse(replacementStart),install);
        need(!forbidden.getBoolean("ok")&&starts.get()==1,
            "START replaced immutable pair during owner handoff gap");
        JSONObject staleStop=owner.execute(parse(request(7,"STOP").put("owner_id",ownerId)
            .put("service_instance",FIRST)),install);
        need(!staleStop.getBoolean("ok")&&!owner.shutdownRequested(),"old FGS stopped successor");
        JSONObject adopted=owner.execute(parse(request(8,"RENEW").put("owner_id",ownerId)
            .put("service_instance",SECOND).put("renew_seq",1)),install);
        need(adopted.getBoolean("ok")&&adopted.getBoolean("lease_active")&&starts.get()==2&&proofs.get()==3,
            "first new-FGS renew did not resume saved pair");
        JSONObject staleRenew=owner.execute(parse(request(9,"RENEW").put("owner_id",ownerId)
            .put("service_instance",FIRST).put("renew_seq",3)),install);
        need(!staleRenew.getBoolean("ok"),"old FGS renewed successor");
        uptime.set(adopted.getLong("lease_until_uptime_ms"));elapsed.set(uptime.get()+100);
        owner.tick();need(workers[1].closed&&owner.shutdownRequested()&&fence.blocked(INSTALL,1),
            "lease expiry left detached worker");
    }
    private static void parserRejectsLegacyMutation()throws Exception{
        reject(new JSONObject().put("id",1).put("op","START")
            .put("iccid","89860700000000000000").put("imsi","460010000000000"));
        reject(new JSONObject().put("id",1).put("op","ATTACH"));
        reject(request(1,"START").put("profile","awake-alpha-v1")
            .put("iccid","89860700000000000000").put("imsi","460010000000000")
            .put("service_instance",FIRST).put("renew_seq",2));
        reject(request(1,"STOP").put("owner_id",FIRST)); // STOP always names its FGS token
        need(parse(new JSONObject().put("id",1).put("op","STOP")).protocol==2,"legacy OFF removed");
        need(parse(request(1,"STOP").put("owner_id","").put("service_instance","")).protocol==3,
            "debt-only OFF removed");
    }
    private static void renewCannotBypassRecovery(boolean debt)throws Exception{
        Path base=Files.createTempDirectory("cloud-awake-recovery-");Path path=marker(base);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger starts=new AtomicInteger();
        AtomicReference<Worker> current=new AtomicReference<>();
        AtomicReference<String> profile=new AtomicReference<>("double_apn");
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        CloudGuardianState owner=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            gate,new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}public String profile(){return profile.get();}},
            ()->{if(debt&&starts.get()==0)gate.beforePause("double_apn");
                starts.incrementAndGet();Worker w=new Worker();current.set(w);return w;},
            ()->44,time::get,time::get,pair->{},false);
        JSONObject first=owner.execute(parse(request(1,"START").put("profile","awake-alpha-v1")
            .put("iccid","89860700000000000000").put("imsi","460010000000000")
            .put("service_instance",FIRST).put("renew_seq",1)),install);
        need(first.getBoolean("ok"),"recovery test start");
        current.get().closed=true;if(debt)profile.set(null);
        owner.tick();
        JSONObject waiting=owner.execute(parse(request(2,"STATUS")),install);
        need(waiting.getString("stage").equals(debt?"failed":"retry_wait"),"recovery not entered");
        JSONObject renewed=owner.execute(parse(request(3,"RENEW").put("owner_id",first.getString("owner_id"))
            .put("service_instance",FIRST).put("renew_seq",2)),install);
        need(starts.get()==1,"RENEW bypassed cleanup or backoff");
        if(debt){
            need(!renewed.getBoolean("ok")&&renewed.getString("code").equals("cleanup_uncertain")&&gate.pending(),
                "unresolved cleanup admitted renewal");
        }else{
            need(renewed.getBoolean("ok")&&renewed.getString("stage").equals("retry_wait"),
                "permission renewal disrupted retry");
            long retry=waiting.getLong("next_retry_elapsed_ms");
            time.set(retry-1);owner.tick();need(starts.get()==1,"retry before deadline");
            time.set(retry);owner.tick();need(starts.get()==2,"scheduled retry did not resume");
        }
        profile.set("double_apn");owner.emergencyShutdown();
    }
    private static void stockReturnOwnsRecoveryGap(boolean handoff)throws Exception{
        Path base=Files.createTempDirectory("cloud-awake-stock-return-");Path path=marker(base);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger starts=new AtomicInteger(),tcp=new AtomicInteger();
        AtomicReference<Worker> current=new AtomicReference<>();
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        CloudGuardianState owner=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            gate,new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return tcp.get();}public String profile(){return "double_apn";}},
            ()->{gate.beforePause("double_apn");starts.incrementAndGet();Worker w=new Worker();current.set(w);return w;},
            ()->44,time::get,time::get,pair->{},false);
        JSONObject first=owner.execute(parse(request(1,"START").put("profile","awake-alpha-v1")
            .put("iccid","89860700000000000000").put("imsi","460010000000000")
            .put("service_instance",FIRST).put("renew_seq",1)),install);
        String ownerId=first.getString("owner_id");
        if(handoff)owner.execute(parse(request(2,"ATTACH").put("owner_id",ownerId)
            .put("service_instance",SECOND)),install);
        else{current.get().closed=true;owner.tick();}
        need(starts.get()==1&&!gate.pending(),"cleanup did not precede recovery gap");
        tcp.set(1);time.set(5000);
        if(handoff)owner.execute(parse(request(3,"RENEW").put("owner_id",ownerId)
            .put("service_instance",SECOND).put("renew_seq",1)),install);
        else owner.tick();
        JSONObject status=owner.execute(parse(request(4,"STATUS")),install);
        need(starts.get()==1&&status.getString("code").equals("stock_owner_competed"),
            "returned stock client was reclaimed by custom retry");
        owner.emergencyShutdown();
    }
    private static void requestRecoveryEdges(int scenario)throws Exception{
        Path base=Files.createTempDirectory("cloud-awake-request-edge-");Path path=marker(base);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong elapsed=new AtomicLong(1000),uptime=new AtomicLong(1000);
        AtomicInteger starts=new AtomicInteger();AtomicReference<Worker> current=new AtomicReference<>();
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}public String profile(){return "double_apn";}},
            ()->{starts.incrementAndGet();Worker w=new Worker();current.set(w);
                if(scenario==4){elapsed.addAndGet(1_800_000);uptime.addAndGet(10);}
                return w;},
            ()->44,elapsed::get,uptime::get,pair->{},false);
        try{
            JSONObject started=owner.execute(parse(request(1,"START").put("profile","awake-alpha-v1")
                .put("iccid","89860700000000000000").put("imsi","460010000000000")
                .put("service_instance",FIRST).put("renew_seq",1)),install);
            if(scenario==4){
                need(!started.getBoolean("ok")&&started.getString("code").equals("power_lost")&&
                    current.get().closed&&!started.getBoolean("lease_active"),"sleeping worker launch received permission");
                return;
            }
            String ownerId=started.getString("owner_id");need(started.getBoolean("ok"),"edge START failed");
            if(scenario==0){
                uptime.set(started.getLong("lease_until_uptime_ms"));elapsed.set(uptime.get());
                JSONObject attached=owner.execute(parse(request(2,"ATTACH").put("owner_id",ownerId)
                    .put("service_instance",SECOND)),install);
                need(!attached.getBoolean("ok")&&attached.getString("code").equals("lease_expired")&&
                    current.get().closed&&fence.blocked(INSTALL,1),"expired ATTACH resurrected lease");
                JSONObject renewed=owner.execute(parse(request(3,"RENEW").put("owner_id",ownerId)
                    .put("service_instance",SECOND).put("renew_seq",1)),install);
                need(!renewed.getBoolean("ok")&&starts.get()==1,"expired owner launched replacement");
            }else{
                current.get().closed=true;
                // A read immediately before watchdog must not steal its dead
                // worker transition, leaving no worker and no retry deadline.
                JSONObject read=request(2,scenario==1?"STATUS":"ATTACH");
                if(scenario!=1)read.put("owner_id",ownerId).put("service_instance",FIRST);
                owner.execute(parse(read),install);
                owner.tick();
                JSONObject waiting=owner.execute(parse(request(3,"STATUS")),install);
                need(waiting.getString("stage").equals("retry_wait"),"read stranded dead worker");
                long retry=waiting.getLong("next_retry_elapsed_ms");
                elapsed.set(scenario==3?elapsed.get()+1_800_000:retry);
                uptime.set(scenario==3?uptime.get()+500:retry);
                owner.tick();
                JSONObject result=owner.execute(parse(request(4,"STATUS")),install);
                if(scenario==3)need(starts.get()==1&&result.getString("code").equals("power_lost")&&
                    fence.blocked(INSTALL,1),"sleep resumed old retry lease");
                else need(starts.get()==2,"watchdog did not recover after read");
            }
        }finally{owner.emergencyShutdown();}
    }
    public static void main(String[]args)throws Exception{
        parserRejectsLegacyMutation();leaseAndHandoff();renewCannotBypassRecovery(false);
        renewCannotBypassRecovery(true);
        stockReturnOwnsRecoveryGap(false);stockReturnOwnsRecoveryGap(true);
        for(int scenario=0;scenario<5;scenario++)requestRecoveryEdges(scenario);
        System.out.println("PASS awake protocol3 strict requests, FGS handoff and lease cases=11");
    }
}
