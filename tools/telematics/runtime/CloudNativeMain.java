package dev.denza.tools.runtime;

import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;

/** Resident shell app_process entry. Not included in product assets until native capability closure. */
public final class CloudNativeMain {
    private static FileDescriptor workerStdoutSink;
    public static void main(String[] args){
        try{
            if(args.length==3 && args[0].equals("control")){
                Path marker=Paths.get(args[1]);
                if(!args[2].matches("[0-9a-f]{32}"))throw new IllegalArgumentException("bridge_nonce");
                CloudInstallMarker.read(marker);
                new CloudNativeBridge(null,marker,args[2],false).serve(System.in,System.out);
                System.exit(0);
            }
            if(args.length==3 && args[0].equals("recover")){
                if(!args[2].matches("[0-9a-f]{12}-[0-9a-f]{12}"))
                    throw new IllegalArgumentException("recovery_runtime_id");
                CloudNativeGuardian.runRecovery(Paths.get(args[1]),args[2]);
                System.exit(0);
            }
            if(args.length==4 && args[0].equals("bridge")){
                requireStartPermit();
                Path workdir=workdir(args[1]),marker=Paths.get(args[2]);
                if(!args[3].matches("[0-9a-f]{32}"))throw new IllegalArgumentException("bridge_nonce");
                CloudInstallMarker.read(marker);
                new CloudNativeBridge(workdir,marker,args[3]).serve(System.in,System.out);
                System.exit(0);
            }
            if(args.length==3 && args[0].equals("guardian")){
                requireStartPermit();
                Path workdir=workdir(args[1]),marker=Paths.get(args[2]);
                CloudNativeGuardian.run(workdir,marker);
                System.exit(0);
            }
            if(args.length!=3 || !args[0].equals("worker") || !args[2].matches("[0-9a-f]{32}"))
                throw new IllegalArgumentException("runtime_arguments");
            requireStartPermit();
            Path workdir=workdir(args[1]);
            runWorker(workdir,args[2]);
        }catch(Throwable failure){
            System.err.println("cloud_runtime_start_failed");Runtime.getRuntime().halt(74);
        }
    }
    private static void requireStartPermit()throws Exception{
        Class<?> permit=Class.forName("dev.denza.tools.runtime.CloudStartPermit");
        String token=(String)permit.getDeclaredField("TOKEN").get(null);
        if(!"native-engine-bundle-v2".equals(token))throw new IllegalStateException("start_permit_missing");
    }
    private static Path workdir(String name)throws Exception{
        if(name==null || !name.matches("/data/local/tmp/denza-cloud-native-[0-9a-f]{12}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("runtime_workdir");
        Path directory=Paths.get(name);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("runtime_directory");
        return directory;
    }
    private static PrintStream workerProtocolOutput()throws Exception{
        // Preserve the guardian's control channel before SDK/native code runs.
        // Incidental Java or native writes to stdout must not enter its frames.
        FileDescriptor protocol=Os.dup(FileDescriptor.out);
        try(FileOutputStream sink=new FileOutputStream("/dev/null")){
            workerStdoutSink=Os.dup2(sink.getFD(),1);
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,
            StandardCharsets.UTF_8));
        return new PrintStream(new FileOutputStream(protocol),true,StandardCharsets.UTF_8);
    }
    private static void runWorker(Path workdir,String nonce)throws Exception{
            Path worker=workdir.resolve("cloud-native-worker");
            if(!Files.isRegularFile(worker,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("runtime_worker");
            CloudRuntimeSupervisor.KernelOwnerLock workerLock=new CloudRuntimeSupervisor.KernelOwnerLock(
                CloudLocalControl.STATE.resolve("worker.lock"));
            if(!workerLock.acquire())throw new IllegalStateException("other_java_worker_present");
            PrintStream protocolOutput=workerProtocolOutput();
            Looper.prepareMainLooper();
            CloudPlatform.AndroidBackend.initializeOnMainThread(true);
            CloudRuntimeSupervisor.Clock clock=new CloudRuntimeSupervisor.Clock(){
                public long nowMs(){return SystemClock.elapsedRealtime();}
                public long nativeMonotonicMs(){return SystemClock.uptimeMillis();}
                public long wallMs(){return System.currentTimeMillis();}
            };
            CloudNativeConnection.Registration registration=new CloudNativeConnection.Registration();
            CloudSessionLoop loop=new CloudSessionLoop(clock,Thread::sleep,(pair,epoch,sink)->
                new CloudNativeConnection(worker,pair,epoch,clock,sink,registration));
            CloudRuntimeSupervisor supervisor=new CloudRuntimeSupervisor(clock,
                new CloudRuntimeSupervisor.OwnerLock(){
                    public boolean acquire(){return true;}
                    public void release(){}
                    public void close(){}
                },
                ()->{},loop,
                code->Runtime.getRuntime().halt(code));
            ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(task->{
                Thread t=new Thread(task,"cloud-owner-watchdog");t.setDaemon(true);return t;
            });
            watchdog.scheduleAtFixedRate(()->{
                try{supervisor.watchdogTick();}catch(Throwable failure){Runtime.getRuntime().halt(74);}
            },100,500,TimeUnit.MILLISECONDS);
            Thread control=new Thread(()->{
                try{new CloudRuntimeProtocol(supervisor,android.os.Process.myPid()).serve(System.in,protocolOutput,nonce);}
                catch(Exception failure){supervisor.closeAndAwait();}
                finally{
                    // serve/closeAndAwait can return only after confirmed local teardown;
                    // an uncertain cleanup terminates through the fatal callback instead.
                    supervisor.closeAndAwait();
                    try{workerLock.close();}
                    catch(Exception uncertain){Runtime.getRuntime().halt(74);}
                    protocolOutput.close();
                    watchdog.shutdownNow();System.exit(0);
                }
            },"cloud-owner-control");
            control.setDaemon(false);control.start();
            Looper.loop();
    }
}
