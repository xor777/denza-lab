package dev.denza.tools.runtime;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Detached shell process: owns the kernel lock and survives app/bridge EOF. */
final class CloudNativeGuardian {
    static void run(Path workdir,Path markerPath)throws Exception{
        runOwner(workdir,markerPath,workdir.getFileName().toString().replace("denza-cloud-native-",""));
    }
    static void runRecovery(Path markerPath,String runtimeId)throws Exception{
        runOwner(null,markerPath,runtimeId);
    }
    private static void runOwner(Path workdir,Path markerPath,String runtimeId)throws Exception{
        try{android.system.Os.setsid();}catch(android.system.ErrnoException ignored){
            // Parent launch still redirects every descriptor; QuickBoot survival
            // remains a separate on-car qualification gate.
        }
        Looper.prepareMainLooper();
        // Recovery runs without the SDK worker, but still needs the same
        // hidden SystemProperties/ServiceManager Binder access as that worker.
        Class<?> runtime=Class.forName("dalvik.system.VMRuntime");
        Object vm=runtime.getMethod("getRuntime").invoke(null);
        runtime.getMethod("setHiddenApiExemptions",String[].class).invoke(vm,(Object)new String[]{"L"});
        Files.createDirectories(CloudLocalControl.STATE);
        if(!Files.isDirectory(CloudLocalControl.STATE,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("control_state_directory");
        Files.setPosixFilePermissions(CloudLocalControl.STATE,
            PosixFilePermissions.fromString("rwx------"));
        CloudInstallMarker marker=CloudInstallMarker.read(markerPath);
        CloudRuntimeSupervisor.KernelOwnerLock lock=new CloudRuntimeSupervisor.KernelOwnerLock(
            CloudLocalControl.STATE.resolve("owner.lock"));
        if(!lock.acquire())throw new IOException("global_owner_present");
        CloudRuntimeSupervisor.KernelOwnerLock orphan=new CloudRuntimeSupervisor.KernelOwnerLock(
            CloudLocalControl.STATE.resolve("worker.lock"));
        try{if(!orphan.acquire())throw new IOException("orphan_worker_present");}
        finally{orphan.close();}
        CloudGuardianState state=new CloudGuardianState(markerPath,marker,runtimeId,
            new CloudGateJournal(CloudLocalControl.STATE.resolve("gate.pending")),
            new CloudStopFence(CloudLocalControl.STATE.resolve("stop.fence")),
            new CloudRegistrationJournal(CloudLocalControl.STATE.resolve("registration.pending")),
            CloudStockGate.android(),()->{
                if(workdir==null)throw new IOException("recovery_only_no_worker");
                return new CloudInternalWorker(workdir);
            },android.os.Process::myPid,android.os.SystemClock::elapsedRealtime,workdir==null);
        byte[] secret=CloudLocalControl.secret(true);
        try(LocalServerSocket server=new LocalServerSocket(CloudLocalControl.SOCKET)){
            Semaphore clients=new Semaphore(4);
            ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{
                Thread thread=new Thread(r,"cloud-guardian-watchdog");thread.setDaemon(true);return thread;
            });
            timer.scheduleAtFixedRate(()->watchdogGuard(()->{
                state.tick();
                if(state.shutdownRequested())try{server.close();}catch(IOException ignored){}
            },()->{
                try{state.emergencyShutdown();}catch(Throwable ignored){}
            },()->Runtime.getRuntime().halt(74),5_000),500,500,TimeUnit.MILLISECONDS);
            try{
                while(!state.shutdownRequested()){
                    LocalSocket client;
                    try{client=server.accept();}
                    catch(IOException stopped){if(state.shutdownRequested())break;throw stopped;}
                    if(!clients.tryAcquire()){client.close();continue;}
                    Thread handler=new Thread(()->{
                        try(LocalSocket incoming=client){serve(incoming,secret,markerPath,state);}
                        catch(Exception failure){/* One malformed client cannot take the owner down. */}
                        finally{clients.release();}
                    },"cloud-guardian-client");
                    handler.setDaemon(true);handler.start();
                }
            }finally{timer.shutdownNow();}
        }finally{
            // An accept/setup failure must not release the global lock while
            // the worker still has access to the vehicle or cloud. If cleanup
            // is uncertain, the worker's independent lock remains the orphan
            // exclusion gate even after this process terminates.
            try{state.emergencyShutdown();}finally{lock.close();}
        }
    }
    /** An Error must not silently cancel the only guardian lease watcher. */
    static void watchdogGuard(Runnable tick,Runnable teardown,Runnable fatal,long budgetMs){
        try{tick.run();return;}
        catch(Throwable failure){
            AtomicBoolean exited=new AtomicBoolean();
            Runnable exitOnce=()->{if(exited.compareAndSet(false,true))fatal.run();};
            Thread fallback=new Thread(()->{
                try{Thread.sleep(budgetMs);}catch(InterruptedException cancelled){return;}
                exitOnce.run();
            },"cloud-guardian-fatal-fallback");
            fallback.setDaemon(true);
            try{fallback.start();}
            catch(Throwable unavailable){exitOnce.run();throw new AssertionError("fatal callback returned",failure);}
            try{teardown.run();}catch(Throwable ignored){}
            finally{exitOnce.run();fallback.interrupt();}
            throw new AssertionError("fatal callback returned",failure);
        }
    }
    private static void serve(LocalSocket client,byte[] secret,Path markerPath,
            CloudGuardianState state)throws Exception{
        if(client.getPeerCredentials().getUid()!=2000)throw new IOException("control_peer_uid");
        client.setSoTimeout(12000);
        String challenge=CloudLocalControl.nonce();
        CloudLocalControl.writeLine(client.getOutputStream(),"CHALLENGE "+challenge);
        String hello=CloudLocalControl.readLine(client.getInputStream(),160);
        if(hello==null)throw new IOException("control_hello_eof");
        String[] words=hello.split(" ",-1);
        if(words.length!=4||!words[0].equals("HELLO")||!words[1].matches("[0-9a-f]{32}")||
           !words[2].matches("[1-9][0-9]{0,18}"))throw new IOException("control_hello");
        long generation=Long.parseLong(words[2]);
        CloudInstallMarker current=CloudInstallMarker.read(markerPath);
        if(!words[1].equals(current.installId)||generation!=current.generation||
           !CloudLocalControl.matches(CloudLocalControl.mac(secret,challenge,words[1],generation),words[3]))
            throw new IOException("control_authentication");
        long lastId=0;
        for(;;){
            String line=CloudLocalControl.readLine(client.getInputStream(),4096);
            if(line==null)return;
            CloudControlRequest request=CloudControlRequest.parse(line);
            if(request.id<=lastId)throw new IOException("control_id_reused");
            lastId=request.id;
            CloudInstallMarker latest;
            try{latest=CloudInstallMarker.read(markerPath);}
            catch(IOException unavailable){
                if(!request.op.equals("STOP"))throw unavailable;
                latest=current; // Previously authenticated connection may still stop locally.
            }
            JSONObject answer=state.execute(request,latest);
            CloudLocalControl.writeLine(client.getOutputStream(),answer.toString());
            if(state.shutdownRequested())return;
        }
    }
}
