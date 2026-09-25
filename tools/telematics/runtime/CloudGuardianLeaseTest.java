package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Offline reproduction of a guardian STATUS blocked at the service lease deadline. */
public final class CloudGuardianLeaseTest {
    private static final String INSTALL="0123456789abcdef0123456789abcdef";
    private static final String SERVICE="11111111111111111111111111111111";
    private static void need(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private static void await(CountDownLatch latch)throws Exception{
        need(latch.await(2,TimeUnit.SECONDS),"latch timeout");
    }
    private static void marker(Path path)throws Exception{
        Files.createDirectories(path.getParent());
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+INSTALL+
            "\",\"generation\":1,\"desired\":\"custom\"}\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static void blockedStatusStillExpiresWorker()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-lease-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);
        CountDownLatch enteredStatus=new CountDownLatch(1),releaseStatus=new CountDownLatch(1),running=new CountDownLatch(1);
        AtomicInteger started=new AtomicInteger();
        final class Worker implements CloudGuardianState.Worker {
            final CloudRuntimeSupervisor inner;
            final CloudRuntimeProtocol wire;
            long id;boolean closed;
            Worker()throws Exception{
                CloudRuntimeSupervisor.Clock clock=()->time.get();
                inner=new CloudRuntimeSupervisor(clock,new CloudRuntimeSupervisor.OwnerLock(){
                    public boolean acquire(){return true;}
                    public void release(){}
                    public void close(){}
                },()->{},(scope,pair,sink)->{
                    started.incrementAndGet();running.countDown();
                    while(!scope.cancelled())Thread.sleep(5);
                },code->{throw new AssertionError("unexpected inner fatal");});
                wire=new CloudRuntimeProtocol(inner,4242);
                wire.handle("{\"id\":1,\"op\":\"PROBE\"}");id=1;
            }
            public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                return request(op,pair,0);
            }
            public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair,long ceiling)throws Exception{
                if(op.equals("STATUS")){enteredStatus.countDown();releaseStatus.await();}
                JSONObject command=new JSONObject().put("id",++id).put("op",op);
                if(pair!=null)command.put("iccid",pair.iccid).put("imsi",pair.imsi);
                if(op.equals("START")||op.equals("STATUS")||op.equals("PERMIT"))
                    command.put("lease_until_uptime_ms",ceiling);
                return new JSONObject(wire.handle(command.toString()));
            }
            public boolean isAlive(){return !closed;}
            public void close(){closed=true;inner.closeAndAwait();}
        }
        Worker worker=new Worker();
        CloudGuardianState owner=new CloudGuardianState(path,CloudInstallMarker.read(path),
            "abc123abc123-abc123abc123",new CloudGateJournal(base.resolve("gate.pending")),
            new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},()->worker,
            ()->44,time::get,time::get,pair->{},false);
        try{
            JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
                .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
                .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
            JSONObject accepted=owner.execute(CloudControlRequest.parse(start.toString()),CloudInstallMarker.read(path));
            need(accepted.getBoolean("ok")&&accepted.getLong("lease_until_uptime_ms")==31_000,
                "guardian START ceiling");
            await(running);need(started.get()==1,"inner worker not started");
            time.set(30_999);
            Thread status=new Thread(owner::tick,"blocked-guardian-status");status.start();await(enteredStatus);
            time.set(31_000);
            worker.inner.watchdogTick();worker.inner.awaitCleanup(1000);
            need(worker.inner.snapshot().code==CloudRuntimeSupervisor.Code.LEASE_EXPIRED &&
                !worker.inner.snapshot().sessionLive,"inner watchdog depended on guardian STATUS");
            releaseStatus.countDown();status.join(2000);need(!status.isAlive(),"guardian STATUS did not finish");
        }finally{releaseStatus.countDown();owner.emergencyShutdown();}
    }
    private static void schedulerErrorTriggersBoundedFatal()throws Exception{
        AtomicInteger teardown=new AtomicInteger(),fatal=new AtomicInteger();
        try{CloudNativeGuardian.watchdogGuard(()->{throw new AssertionError("watchdog_error");},
            teardown::incrementAndGet,fatal::incrementAndGet,50);
            throw new AssertionError("returned from fatal guard");
        }catch(AssertionError expected){}
        need(teardown.get()==1&&fatal.get()==1,"watchdog Error escaped without teardown/fatal");

        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        fatal.set(0);
        Thread blocked=new Thread(()->{
            try{CloudNativeGuardian.watchdogGuard(()->{throw new AssertionError("watchdog_error");},
                ()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}},
                fatal::incrementAndGet,50);
            }catch(AssertionError expected){}
        },"blocked-guardian-teardown");
        blocked.start();await(entered);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(fatal.get()==0&&System.nanoTime()<end)Thread.sleep(5);
        need(fatal.get()==1,"bounded fatal fallback did not fire");
        release.countDown();blocked.join(2000);
        need(!blocked.isAlive()&&fatal.get()==1,"fatal callback repeated after teardown");
    }
    private static void cleanLaunchFailureReleasesOwnerForRetry()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-launch-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger attempts=new AtomicInteger();
        CloudGuardianState.WorkerFactory factory=()->{
            if(attempts.getAndIncrement()==0)throw new IOException("temporary_spawn_failure");
            return new CloudGuardianState.Worker(){
                public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                    return new JSONObject().put("ok",true).put("stage","connected").put("code","connected")
                        .put("session_live",true);
                }
                public boolean isAlive(){return true;}
                public void close(){}
            };
        };
        CloudInstallMarker install=CloudInstallMarker.read(path);
        CloudGuardianState first=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},factory,()->44,time::get,time::get,pair->{},false);
        JSONObject command=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        JSONObject refused=first.execute(CloudControlRequest.parse(command.toString()),install);
        need(!refused.getBoolean("ok")&&refused.getBoolean("retryable")&&
            refused.getString("code").equals("network_retry")&&first.shutdownRequested(),
            "clean spawn failure poisoned START generation");
        first.emergencyShutdown();
        CloudGuardianState second=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},factory,()->45,time::get,time::get,pair->{},false);
        try{need(second.execute(CloudControlRequest.parse(command.toString()),install).getBoolean("ok")&&
            attempts.get()==2,"new guardian could not retry clean spawn failure");}
        finally{second.emergencyShutdown();}
    }
    private static void offPreemptsBlockedStatus()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-off-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1);
        AtomicBoolean aborted=new AtomicBoolean();
        CloudGuardianState.Worker worker=new CloudGuardianState.Worker(){
            public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                if(op.equals("STATUS")){entered.countDown();release.await();}
                return new JSONObject().put("ok",true).put("stage","connected").put("code","connected")
                    .put("session_live",true);
            }
            public void abort(){aborted.set(true);release.countDown();}
            public boolean isAlive(){return true;}
            public void close(){}
        };
        CloudInstallMarker install=CloudInstallMarker.read(path);
        CloudGuardianState owner=new CloudGuardianState(path,install,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},()->worker,
            ()->44,time::get,time::get,pair->{},false);
        JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        need(owner.execute(CloudControlRequest.parse(start.toString()),install).getBoolean("ok"),"off fixture START");
        Thread status=new Thread(owner::tick,"off-blocked-status");status.start();await(entered);
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+INSTALL+
            "\",\"generation\":2,\"desired\":\"off\"}\n").getBytes(StandardCharsets.US_ASCII));
        JSONObject stop=new JSONObject().put("id",2).put("op","STOP").put("protocol",3)
            .put("owner_id","").put("service_instance","");
        Thread task=new Thread(()->{
            try{owner.execute(CloudControlRequest.parse(stop.toString()),CloudInstallMarker.read(path));}
            catch(Exception failure){throw new AssertionError(failure);}
            finally{stopped.countDown();}
        },"off-preemptive-stop");
        task.start();
        try{need(stopped.await(2,TimeUnit.SECONDS)&&aborted.get(),
            "OFF waited for blocked STATUS instead of aborting child");}
        finally{release.countDown();status.join(2000);task.join(2000);owner.emergencyShutdown();}
    }
    private static void ambiguousStartCannotReplayGeneration()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-ambiguous-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger starts=new AtomicInteger();
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(path,CloudInstallMarker.read(path),
            "abc123abc123-abc123abc123",new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},()->new CloudGuardianState.Worker(){
                public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                    if(op.equals("START")){starts.incrementAndGet();throw new IOException("start_reply_lost");}
                    return new JSONObject().put("ok",true).put("stage","stopped");
                }
                public boolean isAlive(){return true;}
                public void close(){}
            },()->44,time::get,time::get,pair->{},false);
        JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        JSONObject first=owner.execute(CloudControlRequest.parse(start.toString()),install);
        need(!first.getBoolean("ok")&&!first.getBoolean("retryable")&&
            first.getString("code").equals("operation_rejected")&&fence.blocked(INSTALL,1),
            "ambiguous START was replayable");
        start.put("id",2);
        JSONObject again=owner.execute(CloudControlRequest.parse(start.toString()),install);
        need(!again.getBoolean("ok")&&again.getString("code").equals("config_changed")&&
            !again.getBoolean("retryable")&&starts.get()==1,"same generation repeated ambiguous START");
        owner.emergencyShutdown();
    }
    private static void delayedStartReplyRecoversByAttach()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-slow-start-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger starts=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CloudGuardianState owner=new CloudGuardianState(path,CloudInstallMarker.read(path),
            "abc123abc123-abc123abc123",new CloudGateJournal(base.resolve("gate.pending")),
            new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},()->new CloudGuardianState.Worker(){
                public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                    if(op.equals("START")){starts.incrementAndGet();entered.countDown();release.await();}
                    return new JSONObject().put("ok",true).put("stage","connected").put("code","connected")
                        .put("session_live",true);
                }
                public boolean isAlive(){return true;}
                public void close(){}
            },()->44,time::get,time::get,pair->{},false);
        CloudInstallMarker install=CloudInstallMarker.read(path);
        JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        Thread pending=new Thread(()->{
            try{owner.execute(CloudControlRequest.parse(start.toString()),install);}
            catch(Exception failure){throw new AssertionError(failure);}
        },"delayed-start-reply");
        pending.start();await(entered);
        // Simulate the old ten-second client timeout while START is still
        // running; a new bridge probes and attaches, never sends START again.
        need(pending.isAlive(),"START did not remain pending for timeout simulation");
        release.countDown();pending.join(2000);need(!pending.isAlive(),"START did not finish");
        JSONObject probe=owner.execute(CloudControlRequest.parse(
            new JSONObject().put("id",2).put("op","PROBE").put("protocol",3).toString()),install);
        JSONObject attach=owner.execute(CloudControlRequest.parse(
            new JSONObject().put("id",3).put("op","ATTACH").put("protocol",3)
                .put("owner_id",probe.getString("owner_id"))
                .put("service_instance",SERVICE).toString()),install);
        need(probe.getString("code").equals("owner_present")&&attach.getBoolean("ok")&&
            starts.get()==1,"lost START reply caused duplicate child START");
        owner.emergencyShutdown();
    }
    private static void slowLaunchCannotExtendServiceLease()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-slow-lease-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);AtomicInteger childStarts=new AtomicInteger();
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState owner=new CloudGuardianState(path,CloudInstallMarker.read(path),
            "abc123abc123-abc123abc123",new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}
                public String profile(){return "double_apn";}},()->{
                time.set(31_000); // worker startup consumed the original 30-second lease
                return new CloudGuardianState.Worker(){
                    public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                        if(op.equals("START"))childStarts.incrementAndGet();
                        return new JSONObject().put("ok",true).put("stage","stopped");
                    }
                    public boolean isAlive(){return true;}
                    public void close(){}
                };
            },()->44,time::get,time::get,pair->{},false);
        JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        JSONObject result=owner.execute(CloudControlRequest.parse(start.toString()),CloudInstallMarker.read(path));
        need(!result.getBoolean("ok")&&result.getString("code").equals("lease_expired")&&
            !result.getBoolean("retryable")&&childStarts.get()==0&&fence.blocked(INSTALL,1),
            "slow worker startup extended or replayed service lease");
        owner.emergencyShutdown();
    }
    private static void expiredGenerationRequiresNewStartEpoch()throws Exception{
        Path base=Files.createTempDirectory("cloud-guardian-generation-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");marker(path);
        AtomicLong time=new AtomicLong(1000);
        CloudStopFence fence=new CloudStopFence(base.resolve("stop.fence"));
        CloudGuardianState.WorkerFactory workers=()->new CloudGuardianState.Worker(){
            public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                return new JSONObject().put("ok",true).put("stage","connected").put("code","connected")
                    .put("session_live",true);
            }
            public boolean isAlive(){return true;}
            public void close(){}
        };
        CloudStockGate.BinderAccess stock=new CloudStockGate.BinderAccess(){
            public int tcp(){return 0;}public String profile(){return "double_apn";}
        };
        CloudInstallMarker firstMarker=CloudInstallMarker.read(path);
        CloudGuardianState first=new CloudGuardianState(path,firstMarker,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,workers,
            ()->44,time::get,time::get,pair->{},false);
        JSONObject start=new JSONObject().put("id",1).put("op","START").put("protocol",3)
            .put("profile","awake-alpha-v1").put("iccid","89010000000000000001")
            .put("imsi","001010123456789").put("service_instance",SERVICE).put("renew_seq",1);
        need(first.execute(CloudControlRequest.parse(start.toString()),firstMarker).getBoolean("ok"),
            "first generation START");
        time.set(31_000);first.tick();need(fence.blocked(INSTALL,1),"expired generation not fenced");
        first.emergencyShutdown();
        CloudGuardianState same=new CloudGuardianState(path,firstMarker,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,workers,
            ()->45,time::get,time::get,pair->{},false);
        JSONObject stale=same.execute(CloudControlRequest.parse(start.toString()),firstMarker);
        need(!stale.getBoolean("ok")&&stale.getString("code").equals("config_changed")&&
            !stale.getBoolean("retryable"),"fenced generation advertised endless retry");
        same.emergencyShutdown();
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+INSTALL+
            "\",\"generation\":2,\"desired\":\"custom\"}\n").getBytes(StandardCharsets.US_ASCII));
        CloudInstallMarker nextMarker=CloudInstallMarker.read(path);
        CloudGuardianState next=new CloudGuardianState(path,nextMarker,"abc123abc123-abc123abc123",
            new CloudGateJournal(base.resolve("gate.pending")),fence,
            new CloudRegistrationJournal(base.resolve("registration.pending")),stock,workers,
            ()->46,time::get,time::get,pair->{},false);
        try{need(next.execute(CloudControlRequest.parse(start.toString()),nextMarker).getBoolean("ok"),
            "new explicit generation remained fenced");}
        finally{next.emergencyShutdown();}
    }
    public static void main(String[] args)throws Exception{
        blockedStatusStillExpiresWorker();schedulerErrorTriggersBoundedFatal();
        cleanLaunchFailureReleasesOwnerForRetry();ambiguousStartCannotReplayGeneration();
        delayedStartReplyRecoversByAttach();slowLaunchCannotExtendServiceLease();offPreemptsBlockedStatus();
        expiredGenerationRequiresNewStartEpoch();
        System.out.println("PASS guardian lease, clean retry, ambiguous/delayed START, slow launch, preemptive OFF, terminal generation and Error cases=8");
    }
}
