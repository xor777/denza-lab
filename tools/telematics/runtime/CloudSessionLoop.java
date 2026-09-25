package dev.denza.tools.runtime;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** One serial connection loop. Neither network requests nor vehicle commands are replayed. */
public final class CloudSessionLoop implements SessionTask {
    public interface Progress {
        void pulse();
        void stage(Stage stage, Code code);
    }
    public interface Connection extends AutoCloseable {
        /** No external work in construction; Scope owns this resource before establish(). */
        void establish(Progress progress) throws Exception;
        /** Process fresh events once, without retaining commands across connections. */
        void pump(long maxWaitMs, Progress progress) throws Exception;
        @Override void close() throws Exception;
    }
    public interface Factory {
        /** A new instance and epoch for every attempt, always with the same immutable pair. */
        Connection create(Identity identity,long epoch,CloudRuntimeSupervisor.Sink sink) throws Exception;
    }
    public interface Waiter { void waitMs(long durationMs) throws InterruptedException; }
    /** Deterministic failure: changing sockets cannot repair this configuration. */
    public static class PermanentFailure extends IOException {
        public final Code code;
        public PermanentFailure(Code code){super("permanent_native_failure");this.code=code;}
    }
    public static final class Unavailable extends PermanentFailure {
        public Unavailable(){super(Code.NATIVE_UNAVAILABLE);}
    }
    /** DNS/network transport failed; a fresh connection may succeed. */
    public static final class NetworkFailure extends IOException {
        public NetworkFailure(){super("network_unavailable");}
    }
    private final Clock clock;
    private final Waiter waiter;
    private final Factory factory;
    public CloudSessionLoop(Clock clock,Waiter waiter,Factory factory) {
        this.clock=clock;this.waiter=waiter;this.factory=factory;
    }
    @Override public void run(Scope scope,Identity identity,CloudRuntimeSupervisor.Sink sink) throws Exception {
        final Progress progress=new Progress(){
            public void pulse(){scope.check();sink.progress();}
            public void stage(Stage stage,Code code){scope.check();sink.state(stage,code);sink.progress();}
        };
        long epoch=0;int consecutiveFailures=0,nativeFailures=0;
        CloudPowerGuard.Continuity continuity=new CloudPowerGuard.Continuity();
        for(;;){
            scope.check();progress.pulse();
            long uptime=clock.nativeMonotonicMs();continuity.check(clock.nowMs(),uptime);
            if(++epoch>0xffffffffL)throw new Unavailable();
            sink.count(Metric.ATTEMPT);
            // No factory or resource failure is retried before its exact cleanup succeeds.
            Connection connection=scope.own(factory.create(identity,epoch,sink));
            long connectedAt=-1;
            Exception failed=null;
            try{
                progress.stage(Stage.CONNECTING,Code.CONNECTING);
                connection.establish(progress);progress.pulse();
                connectedAt=clock.nowMs();
                progress.stage(Stage.CONNECTED,Code.CONNECTED);
                for(;;){
                    scope.check();connection.pump(250,progress);progress.pulse();
                }
            }catch(LinkageError mismatch){
                // A missing Android API or incompatible component cannot be
                // repaired by opening another session. Retire this owner once.
                diagnostic("platform_linkage",mismatch);failed=new Unavailable();
            }catch(Exception failure){diagnostic("connection",failure);failed=failure;}
            finally{
                // Any close failure exits the loop. A replacement must never coexist
                // with an old socket, listener, native operation or unjoined thread.
                scope.retire(connection);
            }
            scope.check();
            if(failed instanceof InterruptedException){Thread.currentThread().interrupt();throw failed;}
            if(failed instanceof PermanentFailure || failed instanceof CancellationException)throw failed;
            if(failed instanceof CloudNativePipe.NativeFailure && ++nativeFailures>=3)
                throw new Unavailable();
            if(connectedAt>=0 && clock.nowMs()-connectedAt>=60_000)consecutiveFailures=0;
            consecutiveFailures=Math.min(6,consecutiveFailures+1);
            long delay=Math.min(60_000,1000L << consecutiveFailures);
            long until=clock.nowMs()+delay;
            sink.count(Metric.RECONNECT);sink.retryAt(until);
            progress.stage(Stage.RETRY_WAIT,Code.RETRY_WAIT);
            while(clock.nowMs()<until){
                scope.check();uptime=clock.nativeMonotonicMs();continuity.check(clock.nowMs(),uptime);
                progress.pulse();waiter.waitMs(Math.min(250,until-clock.nowMs()));
            }
        }
    }
    /** Bounded code locations only: exception messages may contain private native input. */
    static synchronized void diagnostic(String stage,Throwable failure) {
        StringBuilder safe=new StringBuilder(stage);
        for(int depth=0;failure!=null&&depth<3;depth++,failure=failure.getCause()) {
            safe.append(" ").append(failure.getClass().getSimpleName());
            StackTraceElement[] frames=failure.getStackTrace();
            for(int n=0;n<Math.min(4,frames.length);n++) {
                StackTraceElement frame=frames[n];
                safe.append(" at ").append(frame.getClassName()).append(".")
                    .append(frame.getMethodName()).append(":").append(frame.getLineNumber());
            }
        }
        try{android.util.Log.w("DenzaCloudRuntime",safe.toString());}
        catch(RuntimeException hostWithoutAndroid){/* Host fixtures have no Android logger. */}
        try {
            java.nio.file.Path file=CloudLocalControl.STATE.resolve("last-failure.txt");
            java.util.List<String> recent=java.nio.file.Files.exists(file)
                &&java.nio.file.Files.size(file)<32768
                ?java.nio.file.Files.readAllLines(file):new java.util.ArrayList<>();
            recent.add(safe.toString());
            java.nio.file.Files.write(file,recent.subList(Math.max(0,recent.size()-8),recent.size()));
            java.nio.file.Files.setPosixFilePermissions(file,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch(Exception unavailable){/* Failure capture cannot change session cleanup. */}
    }
}
