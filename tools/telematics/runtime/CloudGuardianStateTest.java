package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Host-only ownership and crash-debt fixtures; no Android service is reached. */
public final class CloudGuardianStateTest {
    private static final String INSTALL="0123456789abcdef0123456789abcdef";
    private static final String RUNTIME="abc123abc123-abc123abc123";
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void publish(Path path,long generation,String desired)throws Exception{
        Files.createDirectories(path.getParent());
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+INSTALL+"\",\"generation\":"+
            generation+",\"desired\":\""+desired+"\"}").getBytes(StandardCharsets.UTF_8));
    }
    private static final class Stock implements CloudStockGate.BinderAccess {
        final AtomicInteger probes=new AtomicInteger();
        String activeProfile="triple_apn";
        public int tcp(){probes.incrementAndGet();return 0;}
        public String profile(){return activeProfile;}
    }
    private static final class Worker implements CloudGuardianState.Worker {
        boolean closed,failStop,failStatus,emitEvent;
        String statusFailureCode="session_failed";
        public JSONObject request(String op,CloudRuntimeSupervisor.Identity ignored)throws Exception{
            if(op.equals("STOP")&&failStop)throw new IOException("ack_lost");
            if(op.equals("STATUS")&&failStatus)return new JSONObject().put("ok",true)
                .put("stage","failed").put("code",statusFailureCode).put("session_live",false)
                .put("native_events",events());
            return new JSONObject().put("ok",true).put("stage",op.equals("STOP")?"stopped":"connected")
                .put("code",op.equals("STOP")?"owner_stopped":"connected")
                .put("session_live",!op.equals("STOP")).put("native_events",events());
        }
        private org.json.JSONArray events()throws Exception{
            org.json.JSONArray result=new org.json.JSONArray();
            if(emitEvent)result.put(new JSONObject().put("seq",1).put("t_ms",1000)
                .put("event","connected").put("value",0));
            return result;
        }
        public boolean isAlive(){return !closed;}
        public void close(){closed=true;}
    }
    private static void idleAndRetryAreBounded()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,1,"custom");CloudInstallMarker custom=CloudInstallMarker.read(marker);
        AtomicLong time=new AtomicLong(1000);Stock stock=new Stock();
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        AtomicInteger starts=new AtomicInteger();Worker first=new Worker();first.failStatus=true;first.emitEvent=true;
        CloudGuardianState.WorkerFactory factory=()->{
            gate.beforePause("triple_apn");starts.incrementAndGet();
            if(starts.get()==1)return first;
            Worker next=new Worker();next.emitEvent=true;return next;
        };
        CloudGuardianState idle=new CloudGuardianState(marker,custom,RUNTIME,gate,
            new CloudStopFence(base.resolve("idle.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,factory,()->44,time::get);
        time.set(11000);idle.tick();check(idle.shutdownRequested()&&starts.get()==0);
        time.set(12000);
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,gate,
            new CloudStopFence(base.resolve("retry.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,factory,()->45,time::get);
        owner.execute(1,"START",custom,new CloudRuntimeSupervisor.Identity(
            "89860700000000000000","460010000000000"));
        owner.tick();check(first.closed && starts.get()==1 && stock.probes.get()>=1);
        check(owner.execute(2,"STATUS",custom,null).getBoolean("retryable"));
        time.set(14001);owner.tick();check(starts.get()==2 && !owner.shutdownRequested());
        org.json.JSONArray events=owner.execute(3,"STATUS",custom,null).getJSONArray("native_events");
        check(events.length()==2 && events.getJSONObject(0).getLong("seq")==1 &&
            events.getJSONObject(1).getLong("seq")==2);
    }
    private static void stopFencesOldGenerationAndRecoversGate(boolean lostAck)throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,2,"custom");
        CloudInstallMarker custom=CloudInstallMarker.read(marker);
        Stock stock=new Stock();Worker worker=new Worker();worker.failStop=lostAck;
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,gate,fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,
            ()->{gate.beforePause("triple_apn");return worker;},()->42,()->1000);
        CloudRuntimeSupervisor.Identity pair=new CloudRuntimeSupervisor.Identity(
            "89860700000000000000","460010000000000");
        check(owner.execute(1,"START",custom,pair).getBoolean("ok"));
        check(gate.pending());
        publish(marker,3,"off");
        JSONObject stopped=owner.execute(2,"STOP",CloudInstallMarker.read(marker),null);
        check(worker.closed && stock.probes.get()>=1 && !gate.pending());
        check(fence.blocked(INSTALL,2) && !fence.blocked(INSTALL,3));
        check(stopped.getBoolean("ok") && "stopped".equals(stopped.getString("stage")));
        check(owner.shutdownRequested());
    }
    private static void missingMarkerStopsWithoutClient()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,1,"custom");CloudInstallMarker custom=CloudInstallMarker.read(marker);
        Stock stock=new Stock();Worker worker=new Worker();
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,gate,fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,
            ()->{gate.beforePause("triple_apn");return worker;},()->43,()->1000);
        owner.execute(1,"START",custom,new CloudRuntimeSupervisor.Identity(
            "89860700000000000000","460010000000000"));
        Files.delete(marker);owner.tick();
        check(worker.closed && stock.probes.get()>=1 && fence.blocked(INSTALL,1));
        check(owner.shutdownRequested());
    }
    private static void sharedPropertyDebtNeverGuessesRestoration()throws Exception{
        Path base=Files.createTempDirectory("cloud-property-host-");
        CloudSharedPropertyJournal debt=new CloudSharedPropertyJournal(base.resolve("property.pending"));
        try{debt.beforeWrite("1");throw new AssertionError();}catch(IOException expected){}
        check(!debt.pending());
        debt.beforeWrite("0");check(debt.pending());
        try{debt.resolveAfterExit("");throw new AssertionError();}catch(IOException expected){}
        check(debt.pending());debt.resolveAfterExit("1");check(!debt.pending());
        debt.beforeWrite("0");debt.resolveAfterExit("0");check(!debt.pending());
    }
    private static void foreignProfileDriftDoesNotBlockLocalOff()throws Exception{
        Path base=Files.createTempDirectory("cloud-profile-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,1,"custom");CloudInstallMarker custom=CloudInstallMarker.read(marker);
        Stock stock=new Stock();Worker worker=new Worker();
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,gate,
            new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,
            ()->{gate.beforePause("triple_apn");return worker;},()->46,()->1000);
        owner.execute(1,"START",custom,new CloudRuntimeSupervisor.Identity(
            "89860700000000000000","460010000000000"));
        stock.activeProfile="unrecognized_new_profile";
        publish(marker,2,"off");
        JSONObject result=owner.execute(2,"STOP",CloudInstallMarker.read(marker),null);
        check(result.getBoolean("ok")&&worker.closed&&!gate.pending()&&owner.shutdownRequested());
    }
    private static void recoveryOnlyStopDoesNotFenceSavedCustomIntent()throws Exception{
        Path base=Files.createTempDirectory("cloud-recovery-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,5,"custom");CloudInstallMarker custom=CloudInstallMarker.read(marker);
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        gate.beforePause("triple_apn");
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,gate,fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),new Stock(),
            ()->{throw new AssertionError("recovery launched worker");},()->49,()->1000,true);
        JSONObject stopped=owner.execute(1,"STOP",custom,null);
        check(stopped.getBoolean("ok")&&stopped.getString("code").equals("owner_stopped"));
        check(!gate.pending()&&!fence.blocked(INSTALL,5)&&owner.shutdownRequested());
    }
    private static void reconnectPreservesPauseProfile()throws Exception{
        Path base=Files.createTempDirectory("cloud-gate-host-");
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        gate.beforePause("triple_apn");check(gate.profile().equals("triple_apn"));
        gate.beforePause("triple_apn");check(gate.profile().equals("triple_apn"));
        try{gate.beforePause("double_apn");throw new AssertionError();}catch(IOException expected){}
        check(gate.profile().equals("triple_apn"));
        gate.resolved();gate.beforePause("double_apn");check(gate.profile().equals("double_apn"));
        check(CloudGateJournal.goneValue("triple_apn")==-2&&CloudGateJournal.goneValue("double_apn")==-5);
    }
    private static CloudControlRequest protocol3(long id,String op,JSONObject fields)throws Exception{
        JSONObject request=new JSONObject().put("protocol",3).put("id",id).put("op",op);
        if(fields!=null)for(java.util.Iterator<String> keys=fields.keys();keys.hasNext();){
            String key=keys.next();request.put(key,fields.get(key));
        }
        return CloudControlRequest.parse(request.toString());
    }
    private static void terminalWorkerFailurePreservesCauseAndReleasesOwner(boolean stopEarly)throws Exception{
        Path base=Files.createTempDirectory("cloud-terminal-host-");
        Path marker=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        publish(marker,17,"custom");CloudInstallMarker custom=CloudInstallMarker.read(marker);
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        Worker worker=new Worker();worker.failStatus=true;worker.statusFailureCode="native_unavailable";
        AtomicInteger starts=new AtomicInteger();AtomicLong elapsed=new AtomicLong(1000),uptime=new AtomicLong(900);
        CloudGuardianState owner=new CloudGuardianState(marker,custom,RUNTIME,
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),new Stock(),
            ()->{starts.incrementAndGet();return worker;},()->50,elapsed::get,uptime::get,pair->{},false);
        String service="11111111111111111111111111111111";
        JSONObject started=owner.execute(protocol3(1,"START",new JSONObject()
            .put("profile","awake-alpha-v1").put("iccid","89860700000000000000")
            .put("imsi","460010000000000").put("service_instance",service)
            .put("renew_seq",1)),custom);
        check(started.getBoolean("ok")&&started.getBoolean("lease_active"));
        owner.tick();
        check(worker.closed&&starts.get()==1&&fence.blocked(INSTALL,17)&&!owner.shutdownRequested());
        elapsed.set(12000);uptime.set(11900);owner.tick();
        check(!owner.shutdownRequested()&&starts.get()==1);
        JSONObject status=owner.execute(protocol3(2,"STATUS",null),custom);
        check(status.getBoolean("ok")&&status.getString("code").equals("native_unavailable")&&
            status.getString("stage").equals("failed")&&!status.getBoolean("lease_active"));
        JSONObject identity=new JSONObject().put("owner_id",owner.ownerId())
            .put("service_instance",service);
        JSONObject attach=owner.execute(protocol3(3,"ATTACH",identity),custom);
        check(!attach.getBoolean("ok")&&attach.getString("code").equals("native_unavailable"));
        JSONObject renew=owner.execute(protocol3(4,"RENEW",new JSONObject(identity.toString())
            .put("renew_seq",2)),custom);
        check(!renew.getBoolean("ok")&&renew.getString("code").equals("native_unavailable")&&
            !renew.getBoolean("retryable")&&!renew.getBoolean("lease_active"));
        if(stopEarly){
            JSONObject stopped=owner.execute(protocol3(5,"STOP",new JSONObject()
                .put("owner_id","").put("service_instance","")),custom);
            check(stopped.getBoolean("ok")&&stopped.getString("code").equals("owner_stopped")&&
                owner.shutdownRequested());
        }else{
            elapsed.set(31000);uptime.set(30900);owner.tick();
            check(owner.shutdownRequested());
            JSONObject expired=owner.execute(protocol3(5,"STATUS",null),custom);
            check(expired.getString("code").equals("native_unavailable")&&
                !expired.getBoolean("lease_active"));
        }
        check(starts.get()==1);
    }
    public static void main(String[]args)throws Exception{
        stopFencesOldGenerationAndRecoversGate(false);
        stopFencesOldGenerationAndRecoversGate(true);
        missingMarkerStopsWithoutClient();idleAndRetryAreBounded();
        sharedPropertyDebtNeverGuessesRestoration();reconnectPreservesPauseProfile();
        foreignProfileDriftDoesNotBlockLocalOff();recoveryOnlyStopDoesNotFenceSavedCustomIntent();
        terminalWorkerFailurePreservesCauseAndReleasesOwner(true);
        terminalWorkerFailurePreservesCauseAndReleasesOwner(false);
        System.out.println("PASS guardian STOP fence, retry, terminal failure, and cleanup debt cases=10");
    }
}
