package dev.denza.tools.runtime;

import android.os.Process;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** The detached owner's policy. No cloud or vehicle packet is decoded here. */
final class CloudGuardianState {
    static final long LEASE_TTL_MS=30_000;
    interface PowerProof { void verify(CloudRuntimeSupervisor.Identity pair)throws Exception; }
    interface Worker extends AutoCloseable {
        JSONObject request(String operation,CloudRuntimeSupervisor.Identity pair)throws Exception;
        /** Legacy host fixtures may ignore the ceiling; the production worker enforces it. */
        default JSONObject request(String operation,CloudRuntimeSupervisor.Identity pair,long ceiling)throws Exception{
            return request(operation,pair);
        }
        /** OFF may kill the child without taking the serialized request/close lock. */
        default void abort(){}
        boolean isAlive();
        @Override void close()throws Exception;
    }
    /** A thrown start must mean no child effects remain and local cleanup succeeded. */
    interface WorkerFactory { Worker start()throws Exception; }
    private static final class CleanLaunchFailure extends IOException {
        CleanLaunchFailure(Exception cause){super("worker_launch_failed",cause);}
    }
    private final Path markerPath;
    private final CloudInstallMarker installation;
    private final CloudGateJournal gateJournal;
    private final CloudSharedPropertyJournal sharedJournal;
    private final CloudStopFence stopFence;
    private final CloudRegistrationJournal registrationJournal;
    private final CloudStockGate.BinderAccess stock;
    private final WorkerFactory workers;
    private final String ownerId,runtimeId;
    private final boolean recoveryOnly;
    private final ArrayDeque<JSONObject> eventHistory=new ArrayDeque<>();
    private long nextEventSequence,lastWorkerEventSequence;
    private final IntSupplier pid;
    private final LongSupplier elapsedMs,uptimeMs;
    private final PowerProof powerProof;
    private final long bornAt;
    private volatile Worker worker;
    private volatile boolean offRequested;
    private CloudRuntimeSupervisor.Identity savedPair;
    private long retryAt;
    private long leaseUntilUptimeMs,renewSeq;
    private String serviceInstance;
    private String lastOwnedProfile;
    private boolean leaseActive;
    private CloudPowerGuard.Continuity continuity;
    private int retries;
    private JSONObject last;
    private boolean cleanupUncertain,shutdown,stockCompeted,cleanLaunchRetry,terminalOutcome;

    CloudGuardianState(Path markerPath,CloudInstallMarker installation,String runtimeId,
            CloudGateJournal gateJournal,CloudStopFence stopFence,CloudRegistrationJournal registrationJournal,
            CloudStockGate.BinderAccess stock,WorkerFactory workers) {
        this(markerPath,installation,runtimeId,gateJournal,stopFence,registrationJournal,
            stock,workers,Process::myPid,android.os.SystemClock::elapsedRealtime,false);
    }
    CloudGuardianState(Path markerPath,CloudInstallMarker installation,String runtimeId,
            CloudGateJournal gateJournal,CloudStopFence stopFence,CloudRegistrationJournal registrationJournal,
            CloudStockGate.BinderAccess stock,WorkerFactory workers,IntSupplier pid) {
        this(markerPath,installation,runtimeId,gateJournal,stopFence,registrationJournal,
            stock,workers,pid,android.os.SystemClock::elapsedRealtime,false);
    }
    CloudGuardianState(Path markerPath,CloudInstallMarker installation,String runtimeId,
            CloudGateJournal gateJournal,CloudStopFence stopFence,CloudRegistrationJournal registrationJournal,
            CloudStockGate.BinderAccess stock,WorkerFactory workers,IntSupplier pid,LongSupplier elapsedMs) {
        this(markerPath,installation,runtimeId,gateJournal,stopFence,registrationJournal,
            stock,workers,pid,elapsedMs,false);
    }
    CloudGuardianState(Path markerPath,CloudInstallMarker installation,String runtimeId,
            CloudGateJournal gateJournal,CloudStopFence stopFence,CloudRegistrationJournal registrationJournal,
            CloudStockGate.BinderAccess stock,WorkerFactory workers,IntSupplier pid,LongSupplier elapsedMs,
            boolean recoveryOnly) {
        this(markerPath,installation,runtimeId,gateJournal,stopFence,registrationJournal,
            stock,workers,pid,elapsedMs,android.os.SystemClock::uptimeMillis,
            CloudPowerGuard::verifyAndroid,recoveryOnly);
    }
    CloudGuardianState(Path markerPath,CloudInstallMarker installation,String runtimeId,
            CloudGateJournal gateJournal,CloudStopFence stopFence,CloudRegistrationJournal registrationJournal,
            CloudStockGate.BinderAccess stock,WorkerFactory workers,IntSupplier pid,LongSupplier elapsedMs,
            LongSupplier uptimeMs,PowerProof powerProof,boolean recoveryOnly) {
        if(runtimeId==null||!runtimeId.matches("[0-9a-f]{12}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("runtime_id_shape");
        this.markerPath=markerPath;this.installation=installation;this.runtimeId=runtimeId;
        this.gateJournal=gateJournal;
        this.sharedJournal=new CloudSharedPropertyJournal(gateJournalPathSibling(gateJournal));
        this.stopFence=stopFence;this.registrationJournal=registrationJournal;
        this.stock=stock;this.workers=workers;this.pid=pid;this.elapsedMs=elapsedMs;
        this.uptimeMs=uptimeMs;this.powerProof=powerProof;
        this.recoveryOnly=recoveryOnly;
        this.bornAt=elapsedMs.getAsLong();this.ownerId=randomId();
        this.last=empty("stopped","owner_present");
    }
    private static String randomId(){
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static Path gateJournalPathSibling(CloudGateJournal journal){
        return journal.path().resolveSibling("property.pending");
    }
    String ownerId(){return ownerId;}
    String installId(){return installation.installId;}
    long generation(){return installation.generation;}
    boolean shutdownRequested(){return shutdown;}
    synchronized void emergencyShutdown()throws Exception{
        if(!recoveryOnly&&!cleanLaunchRetry)stopFence.block(installation.installId,installation.generation);
        savedPair=null;retryAt=0;
        leaseActive=false;serviceInstance=null;
        stopWorker();
        shutdown=true;
    }
    private boolean expireLeaseIfNeeded()throws Exception{
        if(serviceInstance==null||uptimeMs.getAsLong()<leaseUntilUptimeMs)return false;
        leaseActive=false;savedPair=null;retryAt=0;serviceInstance=null;
        stopFence.block(installation.installId,installation.generation);
        stopWorker();
        if(!terminalOutcome)last=empty("failed","lease_expired");
        shutdown=!cleanupUncertain;return true;
    }
    private void checkContinuity()throws CloudSessionLoop.PermanentFailure{
        if(!terminalOutcome&&continuity!=null&&serviceInstance!=null){
            long uptime=uptimeMs.getAsLong();continuity.check(elapsedMs.getAsLong(),uptime);
        }
    }
    synchronized void tick(){
        try{
            if(shutdown)return;
            if(offRequested){
                stopFence.block(installation.installId,installation.generation);
                savedPair=null;retryAt=0;leaseActive=false;serviceInstance=null;
                stopWorker();last=empty("stopped","owner_stopped");shutdown=!cleanupUncertain;return;
            }
            checkContinuity();
            CloudInstallMarker current=CloudInstallMarker.read(markerPath);
            if(!current.installId.equals(installation.installId) ||
               current.generation!=installation.generation || current.desired.equals("off")){
                if(!recoveryOnly)stopFence.block(installation.installId,installation.generation);
                stopWorker();shutdown=!cleanupUncertain;return;
            }
            if(expireLeaseIfNeeded())return;
            if(worker==null && gateJournal.pending())recoverGate();
            if(worker==null && sharedJournal.pending())recoverShared();
            if(worker==null && savedPair==null && !terminalOutcome &&
               elapsedMs.getAsLong()-bornAt>=10_000){
                // START may have been lost after guardian launch. An idle lock
                // with no effects cannot become a permanent phantom owner.
                shutdown=true;return;
            }
            if(worker==null && savedPair!=null && leaseActive && retryAt>0 && elapsedMs.getAsLong()>=retryAt &&
               !cleanupUncertain && !stopFence.blocked(installation.installId,installation.generation)){
                verifyResumeStock();if(serviceInstance!=null)powerProof.verify(savedPair);
                retryAt=0;startWorker(savedPair);
            }
            if(worker!=null){
                if(!worker.isAlive()){
                    try{
                        worker.close();worker=null;
                        recoverGate();recoverShared();
                        cleanupUncertain=false;
                        if(savedPair!=null&&leaseActive&&!stockCompeted&&retries<3){
                            retryAt=elapsedMs.getAsLong()+Math.min(30_000,2000L<<retries++);
                            last=empty("retry_wait","network_retry");
                            last.put("next_retry_elapsed_ms",retryAt);
                        }else{
                            stopFence.block(installation.installId,installation.generation);
                            last=empty("failed","session_failed");
                        }
                    }catch(Exception uncertain){
                        CloudSessionLoop.diagnostic("guardian_reap",uncertain);
                        cleanupUncertain=true;last=empty("failed","cleanup_uncertain");
                    }
                }else{
                    JSONObject status=worker.request("STATUS",null,leaseUntilUptimeMs);
                    if(offRequested)throw new IOException("off_requested");
                    if(!status.getBoolean("ok"))throw new IOException("worker_status_rejected");
                    absorbEvents(status);
                    last=status;
                    String stage=status.optString("stage","");
                    if(stage.equals("failed")||stage.equals("stopped")){
                        String code=status.optString("code","session_failed");
                        stopWorker();
                        if(stage.equals("failed") && retryableWorkerFailure(code) && leaseActive && !stockCompeted && retries<3){
                            retryAt=elapsedMs.getAsLong()+Math.min(30_000,2000L<<retries++);
                            last=empty("retry_wait","network_retry");
                            last.put("next_retry_elapsed_ms",retryAt);
                        }else{
                            // Terminal results require a new app generation.
                            stopFence.block(installation.installId,installation.generation);
                            savedPair=null;retryAt=0;leaseActive=false;
                            terminalOutcome=true;
                            last=empty("failed",code);
                            // Keep the diagnostic readable until the original
                            // lease deadline; no renewal or worker restart is allowed.
                            shutdown=serviceInstance==null&&!cleanupUncertain;
                        }
                    }
                }
            }
        }catch(Exception failure){
            // A missing marker or broken worker is an OFF trigger, never a
            // reason to keep vehicle effects running without an owner.
            CloudSessionLoop.diagnostic("guardian_tick",failure);
            if(!recoveryOnly)try{stopFence.block(installation.installId,installation.generation);}catch(Exception ignored){cleanupUncertain=true;}
            try{stopWorker();}catch(Exception ignored){cleanupUncertain=true;}
            leaseActive=false;savedPair=null;retryAt=0;
            serviceInstance=null;
            last=empty("failed",cleanupUncertain?"cleanup_uncertain":
                failure instanceof CloudSessionLoop.PermanentFailure
                    ? ((CloudSessionLoop.PermanentFailure)failure).code.name().toLowerCase(Locale.ROOT)
                    : "session_failed");
            shutdown=!cleanupUncertain;
        }
    }
    JSONObject execute(CloudControlRequest request,CloudInstallMarker caller){
        revokeForOff(request.op,caller);
        synchronized(this){return executeInternal(request.id,request.op,caller,request.pair,request.protocol,
            request.ownerId,request.serviceInstance,request.renewSeq);}
    }
    /** Host fixture seam. Production requests always pass through CloudControlRequest. */
    JSONObject execute(long id,String op,CloudInstallMarker caller,
            CloudRuntimeSupervisor.Identity pair){
        revokeForOff(op,caller);
        synchronized(this){return executeInternal(id,op,caller,pair,2,null,null,0);}
    }
    private void revokeForOff(String op,CloudInstallMarker caller){
        if(!op.equals("STOP")||!caller.installId.equals(installation.installId)||
           !caller.desired.equals("off")||caller.generation<installation.generation)return;
        offRequested=true;
        Worker current=worker;
        if(current!=null)current.abort();
    }
    private JSONObject executeInternal(long id,String op,CloudInstallMarker caller,
            CloudRuntimeSupervisor.Identity pair,int protocol,String requestedOwner,
            String requestedService,long requestedSeq){
        boolean ok=true,retryable=false;String reject=null;
        try{
            checkContinuity();
            // A client can arrive just before the next watchdog tick. Expired
            // permission is terminal even if ATTACH carries a new FGS token.
            expireLeaseIfNeeded();
            if(op.equals("PROBE")){
                // Global lock is held by this guardian; never claim absence.
            }else if(!caller.installId.equals(installation.installId)){
                reject="owner_changed";
            }else if(op.equals("STOP")){
                boolean protectedLease=!terminalOutcome && serviceInstance!=null &&
                    uptimeMs.getAsLong()<leaseUntilUptimeMs && caller.desired.equals("custom");
                if(protocol==2 && protectedLease)reject="owner_changed";
                else if(protocol==3 && protectedLease &&
                    !(ownerId.equals(requestedOwner)&&serviceInstance.equals(requestedService)))
                    reject="owner_changed";
                else if(protocol==3 && !protectedLease &&
                    !("".equals(requestedOwner)||ownerId.equals(requestedOwner)))
                    reject="owner_changed";
                else {
                // Fence only this owner's generation. A later app generation must
                // remain independently eligible after this owner has drained.
                if(!recoveryOnly)stopFence.block(installation.installId,installation.generation);
                retryAt=0;savedPair=null;leaseActive=false;serviceInstance=null;
                stopWorker();shutdown=!cleanupUncertain;last=empty("stopped","owner_stopped");
                }
            }else if(caller.generation!=installation.generation){
                reject="config_changed";
            }else if((op.equals("ATTACH")&&protocol==2)||op.equals("STATUS")){
                // Watchdog owns worker polling/recovery. Reads return its last
                // snapshot (normally <=500 ms old), never stop a dead worker
                // behind its back and strand an active lease without retryAt.
            }else if(shutdown){
                reject=last.optString("code","owner_stopped");
            }else if(op.equals("ATTACH")&&protocol==3){
                if(!ownerId.equals(requestedOwner))reject="owner_changed";
                else if(terminalOutcome)reject=last.optString("code","session_failed");
                else if(savedPair==null || recoveryOnly)reject="identity_required";
                else if(serviceInstance==null)reject="lease_expired";
                else if(serviceInstance.equals(requestedService)){
                    // Reconnect of the same FGS preserves worker, deadline and sequence.
                }else{
                    stopWorker();powerProof.verify(savedPair);
                    serviceInstance=requestedService;renewSeq=0;
                    leaseUntilUptimeMs=uptimeMs.getAsLong()+LEASE_TTL_MS;
                    leaseActive=false;retryAt=0;last=empty("starting","starting");
                }
            }else if(op.equals("RENEW")&&protocol==3){
                if(!ownerId.equals(requestedOwner))reject="owner_changed";
                else if(terminalOutcome)reject=last.optString("code","session_failed");
                else if(serviceInstance==null||!serviceInstance.equals(requestedService))reject="service_changed";
                else if(requestedSeq<=renewSeq)reject="lease_sequence";
                else if(uptimeMs.getAsLong()>=leaseUntilUptimeMs)reject="lease_expired";
                else if(savedPair==null || stopFence.blocked(caller.installId,caller.generation))reject="config_changed";
                else if(cleanupUncertain || worker==null&&(gateJournal.pending()||sharedJournal.pending()))
                    reject="cleanup_uncertain";
                else{
                    // Only ATTACH's inactive lease may resume here. Crash recovery
                    // belongs to tick(), including its backoff and cleanup proof.
                    long nextCeiling=uptimeMs.getAsLong()+LEASE_TTL_MS;
                    if(worker==null&&!leaseActive&&retryAt==0){
                        verifyResumeStock();powerProof.verify(savedPair);
                        leaseUntilUptimeMs=nextCeiling;startWorker(savedPair);
                    }else if(worker!=null){
                        JSONObject permitted=worker.request("PERMIT",null,nextCeiling);
                        if(!permitted.getBoolean("ok"))throw new IOException("worker_renew_rejected");
                        leaseUntilUptimeMs=nextCeiling;
                    }else leaseUntilUptimeMs=nextCeiling;
                    if(uptimeMs.getAsLong()>=nextCeiling)
                        throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.LEASE_EXPIRED);
                    renewSeq=requestedSeq;
                    leaseActive=true;
                }
            }else if(op.equals("START")){
                if(recoveryOnly)reject="operation_rejected";
                else if(stockCompeted)reject="stock_owner_competed";
                else if(stopFence.blocked(caller.installId,caller.generation))reject="config_changed";
                else if(!caller.desired.equals("custom") || pair==null || worker!=null ||
                        savedPair!=null || serviceInstance!=null || cleanupUncertain)
                    reject="operation_rejected";
                else{
                    if(gateJournal.pending())recoverGate();
                    if(sharedJournal.pending())recoverShared();
                    if(protocol==3){
                        powerProof.verify(pair);continuity=new CloudPowerGuard.Continuity();
                        long uptime=uptimeMs.getAsLong();continuity.check(elapsedMs.getAsLong(),uptime);
                    }
                    savedPair=pair;
                    if(protocol==3){serviceInstance=requestedService;renewSeq=requestedSeq;
                        leaseUntilUptimeMs=uptimeMs.getAsLong()+LEASE_TTL_MS;leaseActive=true;}
                    else leaseActive=true; // Legacy host-fixture seam; wire v2 START is rejected.
                    startWorker(pair);
                    if(protocol==3&&uptimeMs.getAsLong()>=leaseUntilUptimeMs)
                        throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.LEASE_EXPIRED);
                }
            }else reject="operation_rejected";
        }catch(Exception failure){
            if(!(failure instanceof CloudSessionLoop.PermanentFailure)
                && !(failure instanceof CleanLaunchFailure))
                CloudSessionLoop.diagnostic("guardian_request",failure);
            reject=cleanupUncertain?"cleanup_uncertain":
                failure instanceof CloudSessionLoop.PermanentFailure
                    ? ((CloudSessionLoop.PermanentFailure)failure).code.name().toLowerCase(Locale.ROOT)
                    : failure instanceof CleanLaunchFailure ? "network_retry"
                    : "operation_rejected";
            try{stopWorker();}catch(Exception cleanup){cleanupUncertain=true;reject="cleanup_uncertain";}
            if(op.equals("START")){savedPair=null;retryAt=0;leaseActive=false;serviceInstance=null;leaseUntilUptimeMs=0;}
            if(op.equals("START")&&failure instanceof CleanLaunchFailure&&!cleanupUncertain)
            {cleanLaunchRetry=true;shutdown=true;} // No child/effects: permit a fresh owner to retry.
            else if(op.equals("START")&&!recoveryOnly){
                // Once START may have reached a child, a lost reply cannot
                // authorize another registration in the same generation.
                try{stopFence.block(installation.installId,installation.generation);}
                catch(Exception debt){cleanupUncertain=true;reject="cleanup_uncertain";}
            }
            if(failure instanceof CloudSessionLoop.PermanentFailure&&
               (((CloudSessionLoop.PermanentFailure)failure).code==CloudRuntimeSupervisor.Code.POWER_LOST||
                ((CloudSessionLoop.PermanentFailure)failure).code==CloudRuntimeSupervisor.Code.POWER_UNAVAILABLE||
                ((CloudSessionLoop.PermanentFailure)failure).code==CloudRuntimeSupervisor.Code.LEASE_EXPIRED)){
                try{stopFence.block(installation.installId,installation.generation);}
                catch(Exception debt){cleanupUncertain=true;reject="cleanup_uncertain";}
                leaseActive=false;savedPair=null;retryAt=0;serviceInstance=null;
                last=empty("failed",reject);shutdown=!cleanupUncertain;
            }
        }
        if(reject!=null)ok=false;
        JSONObject base=reject==null?last:empty("failed",reject);
        String stage=base.optString("stage","");
        String code=base.optString("code","");
        retryable=op.equals("PROBE") || stage.equals("retry_wait") || code.equals("network_retry") ||
            code.equals("owner_present") ||
            code.equals("owner_changed");
        JSONObject answer;
        try{answer=new JSONObject(base.toString());if(op.equals("PROBE"))answer.put("code","owner_present");}
        catch(Exception impossible){answer=empty("failed","operation_rejected");ok=false;}
        try{
            answer.put("id",id).put("op",op).put("protocol",protocol).put("ok",ok)
                .put("pid",pid.getAsInt()).put("owner_id",ownerId).put("runtime_id",runtimeId)
                .put("config_generation",installation.generation).put("retryable",retryable)
                .put("registration_uncertain",registrationJournal.registrationUncertain())
                .put("capabilities",capabilities(answer)).put("native_events",eventHistory());
            if(protocol==3)answer.put("profile","awake-alpha-v1")
                .put("lease_until_uptime_ms",leaseUntilUptimeMs)
                .put("lease_active",leaseActive&&serviceInstance!=null&&uptimeMs.getAsLong()<leaseUntilUptimeMs);
        }catch(Exception impossible){throw new IllegalStateException("guardian_response");}
        return answer;
    }
    private static JSONArray capabilities(JSONObject status){
        JSONArray result=new JSONArray();
        if(status.optBoolean("session_live",false))for(String name:new String[]{
            "reg","data","control_awake","wake_ack_awake","timers_awake",
            "post_login_awake","heartbeat","power_guard"})result.put(name);
        return result;
    }
    private static boolean retryableWorkerFailure(String code){
        return code.equals("session_failed") || code.equals("session_ended") ||
            code.equals("worker_stalled");
    }
    private void absorbEvents(JSONObject status)throws Exception{
        JSONArray events=status.optJSONArray("native_events");
        if(events==null)return;
        if(events.length()>128)throw new IOException("worker_event_bound");
        for(int i=0;i<events.length();i++){
            JSONObject event=events.getJSONObject(i);
            long sequence=event.getLong("seq");
            if(sequence<=lastWorkerEventSequence)continue;
            String name=event.getString("event");
            if(!name.matches("[a-z_]{1,40}"))throw new IOException("worker_event_name");
            JSONObject rebased=new JSONObject().put("seq",++nextEventSequence)
                .put("t_ms",event.getLong("t_ms")).put("event",name)
                .put("value",event.getInt("value"));
            eventHistory.addLast(rebased);
            if(eventHistory.size()>128)eventHistory.removeFirst();
            lastWorkerEventSequence=sequence;
        }
    }
    private JSONArray eventHistory(){
        JSONArray result=new JSONArray();for(JSONObject event:eventHistory)result.put(event);return result;
    }
    private void startWorker(CloudRuntimeSupervisor.Identity pair)throws Exception{
        Worker fresh;
        try{fresh=workers.start();}
        catch(Exception launch){
            if(launch.getSuppressed().length!=0){cleanupUncertain=true;throw launch;}
            throw new CleanLaunchFailure(launch);
        }
        worker=fresh;
        if(offRequested){fresh.abort();throw new IOException("off_requested");}
        if(continuity!=null){long uptime=uptimeMs.getAsLong();continuity.check(elapsedMs.getAsLong(),uptime);}
        if(continuity!=null&&uptimeMs.getAsLong()>=leaseUntilUptimeMs)
            throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.LEASE_EXPIRED);
        lastWorkerEventSequence=0;
        last=fresh.request("START",pair,leaseUntilUptimeMs);
        if(continuity!=null){long uptime=uptimeMs.getAsLong();continuity.check(elapsedMs.getAsLong(),uptime);}
        if(continuity!=null&&uptimeMs.getAsLong()>=leaseUntilUptimeMs)
            throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.LEASE_EXPIRED);
        absorbEvents(last);
        if(!last.getBoolean("ok"))throw new IOException("java_worker_start_rejected");
    }
    private void verifyResumeStock()throws Exception{
        // Cleanup may already have discharged the pause journal. A later stock
        // reconnection still owns the link; a replacement must not pause it again.
        int tcp=stock.tcp();
        if(tcp!=0&&tcp!=1)throw new IOException("stock_tcp_unknown");
        String profile=stock.profile();
        if(profile==null)throw new IOException("stock_profile_unavailable");
        if(tcp==1||stockCompeted||lastOwnedProfile!=null&&!lastOwnedProfile.equals(profile)){
            stockCompeted=true;savedPair=null;retryAt=0;leaseActive=false;
            last=empty("failed","stock_owner_competed");
            stopFence.block(installation.installId,installation.generation);
            throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.STOCK_OWNER_COMPETED);
        }
        // Preserve the resume intent across worker launch. The worker checks
        // a pending journal against stock TCP again before issuing gone.
        gateJournal.beforePause(profile);
    }
    private void recoverGate()throws Exception{
        if(!gateJournal.pending())return;
        String ownedProfile=gateJournal.profile();
        String currentProfile=stock.profile();
        if(currentProfile==null)throw new IOException("stock_profile_unavailable");
        if(!ownedProfile.equals(currentProfile)){
            // This profile is no longer ours. Never apply a gone/ready event
            // to an unknown or foreign branch after local worker exit.
            stockCompeted=true;gateJournal.resolved();return;
        }
        int tcp=stock.tcp();
        if(tcp!=0&&tcp!=1)throw new IOException("stock_tcp_unknown");
        // The worker has exited. A changed profile or reconnected stock owner
        // belongs to the system now; local OFF never sends ready or gone into
        // that foreign state. The journal records ownership, not TCP baseline.
        if(tcp==1)stockCompeted=true;
        else lastOwnedProfile=ownedProfile;
        gateJournal.resolved();
    }
    private void recoverShared()throws Exception{
        if(!sharedJournal.pending())return;
        String observed=(String)Class.forName("android.os.SystemProperties")
            .getMethod("get",String.class).invoke(null,"sys.cloud.remote_controling");
        sharedJournal.resolveAfterExit(observed);
    }
    private void stopWorker()throws Exception{
        if(worker!=null){
            Worker ending=worker;
            try{
                JSONObject stopped=ending.request("STOP",null);
                if(!stopped.optBoolean("ok") || !"stopped".equals(stopped.optString("stage")))
                    throw new IOException("java_worker_stop_unconfirmed");
            }catch(Exception lostAck){
                // The process-exit proof below is authoritative for local
                // cleanup; a lost control reply alone is not a lasting debt.
            }
            try{ending.close();worker=null;}
            catch(Exception childMayLive){cleanupUncertain=true;throw childMayLive;}
            try{
                recoverGate();recoverShared();cleanupUncertain=false;
            }catch(Exception cleanup){
                cleanupUncertain=true;
                throw cleanup;
            }
            return;
        }
        recoverGate();
        recoverShared();
        cleanupUncertain=false;
    }
    private JSONObject empty(String stage,String code){
        JSONObject value=new JSONObject();
        try{
            value.put("id",0).put("op","STATUS").put("protocol",1).put("ok",true)
                .put("pid",pid.getAsInt()).put("session_live",false)
                .put("stage",stage).put("code",code)
                .put("updated_elapsed_ms",elapsedMs.getAsLong()).put("connected_elapsed_ms",0)
                .put("last_rx_elapsed_ms",0).put("last_tx_elapsed_ms",0)
                .put("last_report_elapsed_ms",0).put("next_retry_elapsed_ms",0)
                .put("attempts",0).put("reports_sent",0).put("status_replies",0)
                .put("commands_forwarded",0).put("commands_completed",0)
                .put("reconnects",0).put("callback_age_ms",-1).put("native_events",new JSONArray());
        }catch(Exception impossible){throw new IllegalStateException("guardian_empty");}
        return value;
    }
}
