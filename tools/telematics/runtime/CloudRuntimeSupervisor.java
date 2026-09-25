package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process ownership and cancellation only. Vehicle protocol belongs to the native engine. */
public final class CloudRuntimeSupervisor implements AutoCloseable {
    public static final long LEASE_MS = 30_000;
    public static final long STOP_BUDGET_MS = 5_000;
    public static final long WORK_PROGRESS_MS = 20_000;
    public interface Clock {
        /** BOOTTIME: owner deadlines include time spent suspended. */
        long nowMs();
        /** CLOCK_MONOTONIC: native steady-clock and condition deadlines exclude suspend. */
        default long nativeMonotonicMs() { return nowMs(); }
        /** CLOCK_REALTIME: original firmware time() input, never an owner deadline. */
        default long wallMs() { return System.currentTimeMillis(); }
    }
    public interface OwnerLock extends AutoCloseable {
        boolean acquire() throws Exception;
        void release() throws Exception;
        @Override void close() throws Exception;
    }
    public interface Journal {
        /** Durable before any custom-session side effect. STOP must never clear this marker. */
        void customMayRegister() throws Exception;
    }
    public interface FatalExit { void exit(int code); }
    public interface SessionTask {
        /** Run for this owner's entire lifetime; reconnect never replays an actuator command. */
        void run(Scope resources, Identity identity, Sink events) throws Exception;
    }
    public enum Stage { STARTING, REGISTERING, REGISTRATION_WAIT, DISCOVERING, CONNECTING,
        CONNECTED, WAITING_DATA, RETRY_WAIT, STOPPED, FAILED }
    public enum Code { OWNER_ABSENT_CONFIRMED, OWNER_PRESENT, STARTING, REGISTERING,
        REGISTRATION_WAIT, DISCOVERING, CONNECTING, CONNECTED, WAITING_DATA, RETRY_WAIT,
        OWNER_STOPPED, STOP_PENDING, SESSION_ENDED, SESSION_FAILED, START_FAILED,
        CLEANUP_FAILED, CLEANUP_TIMEOUT, LEASE_EXPIRED, OWNER_EOF, IDENTITY_REQUIRED,
        PROBE_REQUIRED, REGISTRATION_JOURNAL_FAILED, WORKER_STALLED,
        UNSUPPORTED_FIRMWARE, UNSUPPORTED_IDENTITY, NATIVE_UNAVAILABLE,
        REGISTRATION_REJECTED, LOGIN_REJECTED, STOCK_OWNER_COMPETED,
        POWER_LOST, POWER_UNAVAILABLE }
    public enum Metric { RX, TX, REPORT, STATUS_REPLY, COMMAND_FORWARDED, COMMAND_COMPLETED,
        RECONNECT, ATTEMPT }
    private enum Phase { IDLE, PROBING, RESERVED, RUNNING, STOPPING, CLOSED }

    public static final class Identity {
        public final String iccid, imsi;
        public Identity(String iccid, String imsi) {
            if (iccid == null || imsi == null || !iccid.matches("[0-9]{20}") ||
                !imsi.matches("[0-9]{15}")) throw new IllegalArgumentException("identity_required");
            this.iccid = iccid; this.imsi = imsi;
        }
        @Override public String toString() { return "Identity[redacted]"; }
    }

    public static final class Event {
        public final long sequence, atMs;
        public final Code code;
        Event(long sequence, long atMs, Code code) { this.sequence=sequence; this.atMs=atMs; this.code=code; }
    }
    public static final class Snapshot {
        public final Stage stage;
        public final Code code;
        public final boolean sessionLive;
        public final long generation, updatedMs, connectedMs, lastRxMs, lastTxMs, lastReportMs,
            nextRetryMs, attempts, reports, statusReplies, commandsForwarded, commandsCompleted,
            reconnects, callbackAgeMs;
        public final List<Event> events;
        Snapshot(CloudRuntimeSupervisor s) {
            stage=s.stage; code=s.code; sessionLive=s.live; generation=s.generation;
            updatedMs=s.updatedMs; connectedMs=s.connectedMs; lastRxMs=s.lastRxMs;
            lastTxMs=s.lastTxMs; lastReportMs=s.lastReportMs; nextRetryMs=s.nextRetryMs;
            attempts=s.attempts; reports=s.reports; statusReplies=s.statusReplies;
            commandsForwarded=s.commandsForwarded; commandsCompleted=s.commandsCompleted;
            reconnects=s.reconnects; callbackAgeMs=s.callbackAtMs < 0 ? -1 : Math.max(0,s.clock.nowMs()-s.callbackAtMs);
            events=Collections.unmodifiableList(new ArrayList<>(s.events));
        }
    }

    /** Register each resource before starting its potentially blocking external operation. */
    public static final class Scope implements AutoCloseable {
        private static final class Entry {
            final AutoCloseable resource;
            boolean closing, closed;
            Exception failure;
            Entry(AutoCloseable resource) { this.resource=resource; }
        }
        private final List<Entry> resources = new ArrayList<>();
        private final Map<AutoCloseable,Entry> owned = new IdentityHashMap<>();
        private boolean cancelled;
        public <T extends AutoCloseable> T own(T resource) throws Exception {
            if (resource == null) throw new NullPointerException("resource");
            Entry entry;
            boolean rejected;
            synchronized (this) {
                if (owned.containsKey(resource)) throw new IllegalStateException("duplicate_resource");
                entry=new Entry(resource); resources.add(entry); owned.put(resource,entry);
                if (resources.size()>64) cancelled=true;
                rejected=cancelled;
            }
            // Track even a late resource until its close is confirmed. A close failure
            // must not disappear just because cancellation won the allocation race.
            if (rejected) { closeEntry(entry); throw new CancellationException("owner_stopped"); }
            return resource;
        }
        public synchronized boolean cancelled() { return cancelled; }
        public void check() { if (cancelled()) throw new CancellationException("owner_stopped"); }
        private synchronized void cancel() { cancelled=true; }
        public void retire(AutoCloseable resource) throws Exception {
            Entry entry;
            synchronized (this) { entry=owned.get(resource); }
            if (entry==null) throw new IllegalStateException("resource_not_owned");
            closeEntry(entry);
        }
        private void closeEntry(Entry entry) throws Exception {
            synchronized (this) {
                while(entry.closing) wait();
                if(entry.closed) return;
                if(entry.failure!=null) throw new IOException("resource_cleanup_failed");
                entry.closing=true;
            }
            Exception failure=null;
            boolean succeeded=false;
            try { entry.resource.close(); succeeded=true; }
            catch(Exception e) { failure=e; throw e; }
            finally {
                synchronized(this) {
                    entry.closing=false;
                    // If an Error escaped, retain the ownership debt as well.
                    if(succeeded) {
                        entry.closed=true; owned.remove(entry.resource); resources.remove(entry);
                    } else entry.failure=failure!=null?failure:new IOException("resource_cleanup_failed");
                    notifyAll();
                }
            }
        }
        @Override public void close() throws Exception { closeAll(); }
        private void closeAll() throws Exception {
            List<Entry> pending;
            synchronized(this) { cancelled=true; pending=new ArrayList<>(resources); }
            Collections.reverse(pending);
            Exception failure=null;
            for(Entry entry:pending) {
                try { closeEntry(entry); }
                catch(Exception e) { if(failure==null) failure=e; else if(e!=failure)failure.addSuppressed(e); }
            }
            if(failure!=null) throw failure;
        }
    }

    public final class Sink {
        private final long epoch;
        Sink(long epoch) { this.epoch=epoch; }
        private boolean current() { return generation==epoch && phase==Phase.RUNNING; }
        public void state(Stage value, Code reason) {
            synchronized (mutex) {
                if (!current()) return;
                if (value==Stage.STOPPED) throw new IllegalArgumentException("session_cannot_release_owner");
                boolean connected=value==Stage.CONNECTED || value==Stage.WAITING_DATA;
                if (connected && !live) connectedMs=clock.nowMs();
                live=connected; stage=value; event(reason);
            }
        }
        /** Only the session event loop calls this; owner STATUS and SDK callbacks cannot mask a stalled loop. */
        public void progress() {
            synchronized(mutex) { if(current()) { progressAt=clock.nativeMonotonicMs(); updatedMs=clock.nowMs(); } }
        }
        public void retryAt(long atMs) {
            synchronized (mutex) { if (current()) { nextRetryMs=Math.max(0,atMs); updatedMs=clock.nowMs(); } }
        }
        public void callbackObserved() {
            synchronized (mutex) { if (current()) callbackAtMs=clock.nowMs(); }
        }
        public void count(Metric value) {
            synchronized (mutex) {
                if (!current()) return;
                long now=clock.nowMs(); updatedMs=now;
                switch(value) {
                    case RX: lastRxMs=now; break;
                    case TX: lastTxMs=now; break;
                    case REPORT: reports++; lastReportMs=now; break;
                    case STATUS_REPLY: statusReplies++; break;
                    case COMMAND_FORWARDED: commandsForwarded++; break;
                    case COMMAND_COMPLETED: commandsCompleted++; break;
                    case RECONNECT: reconnects++; break;
                    case ATTEMPT: attempts++; break;
                }
            }
        }
    }

    private final Object mutex=new Object(), ownerIo=new Object();
    private final Clock clock;
    private final OwnerLock owner;
    private final Journal journal;
    private final SessionTask task;
    private final FatalExit fatal;
    private final AtomicBoolean exitSent=new AtomicBoolean();
    private final List<Event> events=new ArrayList<>();
    private Phase phase=Phase.IDLE;
    private Stage stage=Stage.STOPPED;
    private Code code=Code.OWNER_ABSENT_CONFIRMED;
    private boolean held,live;
    private long generation,leaseUntil,stoppingAt,progressAt,sequence,updatedMs,connectedMs,lastRxMs,lastTxMs,
        lastReportMs,nextRetryMs,attempts,reports,statusReplies,commandsForwarded,commandsCompleted,
        reconnects,callbackAtMs=-1;
    private Scope scope;
    private Thread worker,cleanup;

    public CloudRuntimeSupervisor(Clock clock,OwnerLock owner,Journal journal,SessionTask task,FatalExit fatal) {
        this.clock=clock; this.owner=owner; this.journal=journal; this.task=task; this.fatal=fatal;
        leaseUntil=clock.nativeMonotonicMs()+LEASE_MS; updatedMs=clock.nowMs();
    }
    private void event(Code next) {
        code=next; updatedMs=clock.nowMs(); events.add(new Event(++sequence,updatedMs,next));
        if (events.size()>128) events.remove(0);
    }
    /** The guardian computes this ceiling before launching the worker. */
    public void initialLease(long ceiling) {
        synchronized(mutex) {
            long now=clock.nativeMonotonicMs();
            if(phase!=Phase.RESERVED || !held || ceiling<=now || ceiling-now>LEASE_MS)
                throw new IllegalStateException("initial_lease_invalid");
            leaseUntil=ceiling;
        }
    }
    /** Only an explicit guardian renewal may extend the worker's independent deadline. */
    public void renewLease(long ceiling) {
        synchronized(mutex) {
            long now=clock.nativeMonotonicMs();
            if(phase!=Phase.RUNNING || now>=leaseUntil || ceiling<=leaseUntil ||
                ceiling<=now || ceiling-now>LEASE_MS)
                throw new IllegalStateException("renew_lease_invalid");
            leaseUntil=ceiling;
        }
    }
    /** STATUS carries the existing deadline; it never adds time to it. */
    public void observeLease(long ceiling) {
        synchronized(mutex) {
            if(ceiling!=leaseUntil || clock.nativeMonotonicMs()>=leaseUntil)
                throw new IllegalStateException("status_lease_invalid");
        }
    }
    public Snapshot snapshot() { synchronized(mutex) { return new Snapshot(this); } }
    public Snapshot probe() throws Exception {
        synchronized(mutex) {
            if(phase==Phase.RESERVED) return new Snapshot(this);
            if(phase!=Phase.IDLE) throw new IllegalStateException("probe_requires_idle_owner");
            phase=Phase.PROBING;
        }
        // Kernel/file operations never hold the status/watchdog mutex. Cleanup uses
        // the same I/O lock so an in-flight acquire cannot reappear after STOP.
        synchronized(ownerIo) {
            boolean acquired;
            try { acquired=owner.acquire(); }
            catch(Exception e) {
                synchronized(mutex) {
                    if(phase==Phase.PROBING) { phase=Phase.IDLE; stage=Stage.FAILED; event(Code.OWNER_PRESENT); }
                }
                throw e;
            }
            synchronized(mutex) {
                held=acquired;
                if(phase!=Phase.PROBING) throw new CancellationException("owner_stopped");
                phase=acquired?Phase.RESERVED:Phase.IDLE;
                stage=acquired?Stage.STOPPED:Stage.FAILED;
                event(acquired?Code.OWNER_ABSENT_CONFIRMED:Code.OWNER_PRESENT);
                return new Snapshot(this);
            }
        }
    }
    public Snapshot start(Identity identity) throws Exception {
        synchronized(mutex) {
            if(identity==null) throw new IllegalArgumentException("identity_required");
            if(phase!=Phase.RESERVED || !held) throw new IllegalStateException("probe_required");
            if(clock.nativeMonotonicMs()>=leaseUntil) throw new IllegalStateException("start_lease_expired");
            generation++; scope=new Scope(); phase=Phase.RUNNING; stage=Stage.STARTING; progressAt=clock.nativeMonotonicMs();
            event(Code.STARTING); final long epoch=generation; final Scope current=scope;
            worker=new Thread(()->{
                Code end=Code.SESSION_ENDED;
                try {
                    try { journal.customMayRegister(); }
                    catch(Exception failure) { end=Code.REGISTRATION_JOURNAL_FAILED; return; }
                    current.check();
                    task.run(current,identity,new Sink(epoch));
                } catch(Exception failure) {
                    end=failure instanceof CloudSessionLoop.PermanentFailure
                        ? ((CloudSessionLoop.PermanentFailure)failure).code : Code.SESSION_FAILED;
                }
                finally { beginStop(epoch,end); }
            },"cloud-session");
            worker.setDaemon(true);
            try { worker.start(); }
            catch(RuntimeException|Error e) { beginStop(epoch,Code.START_FAILED); throw e; }
            return new Snapshot(this);
        }
    }
    /** Bounded acknowledgement: stopped means owned resources and the worker have really exited. */
    public Snapshot stop() throws InterruptedException {
        beginStop(-1,Code.OWNER_STOPPED);
        awaitCleanup(3_000);
        synchronized(mutex) {
            // A session may have already failed and completed cleanup before OFF.
            // Its failure remains in history; confirmed local OFF is still possible.
            if(phase==Phase.CLOSED) { stage=Stage.STOPPED;event(Code.OWNER_STOPPED); }
            return new Snapshot(this);
        }
    }
    @Override public void close() { beginStop(-1,Code.OWNER_EOF); }
    /** EOF must retain process ownership until teardown succeeds or this process terminates. */
    public void closeAndAwait() {
        close(); boolean interrupted=false;
        try { awaitCleanup(4_000); } catch(InterruptedException e) { interrupted=true; }
        boolean mustExit;
        synchronized(mutex) { mustExit=phase!=Phase.CLOSED && exitSent.compareAndSet(false,true); }
        if(mustExit)fatal.exit(74);
        if(interrupted)Thread.currentThread().interrupt();
    }
    public void awaitCleanup(long timeoutMs) throws InterruptedException {
        Thread closing;
        synchronized(mutex) { closing=cleanup; }
        if(closing!=null) closing.join(Math.min(4_000,Math.max(1,timeoutMs)));
    }
    private void beginStop(long epoch,Code finalCode) {
        synchronized(mutex) {
            if (epoch>=0 && generation!=epoch) return;
            if(phase==Phase.CLOSED || phase==Phase.STOPPING) return;
            phase=Phase.STOPPING; live=false; stoppingAt=clock.nativeMonotonicMs(); stage=Stage.FAILED;
            event(Code.STOP_PENDING);
            final Scope current=scope; final Thread running=worker;
            if(current!=null) current.cancel();
            if(running!=null) running.interrupt();
            cleanup=new Thread(()->finishStop(current,running,finalCode),"cloud-cleanup");
            cleanup.setDaemon(true); cleanup.start();
        }
    }
    private void finishStop(Scope current,Thread running,Code finalCode) {
        try {
            if(current!=null) current.closeAll();
            if(running!=null) { running.join(1_000); if(running.isAlive()) throw new IOException("session_did_not_exit"); }
            // A late own() closes before the worker returns. Recheck any failure
            // recorded by that late close before releasing the process lock.
            if(current!=null) current.closeAll();
            synchronized(ownerIo) { owner.close(); }
            synchronized(mutex) {
                held=false; phase=Phase.CLOSED; scope=null; worker=null;
                stage=(finalCode==Code.OWNER_STOPPED || finalCode==Code.OWNER_EOF || finalCode==Code.LEASE_EXPIRED)
                    ? Stage.STOPPED : Stage.FAILED;
                event(finalCode);
            }
        } catch (Exception e) {
            synchronized(mutex) { stage=Stage.FAILED; event(Code.CLEANUP_FAILED); }
            // Keep the kernel lock; the watchdog must terminate the process if cleanup is uncertain.
        }
    }
    /** Must run independently of command/session/cleanup threads, at least once per second. */
    public void watchdogTick() {
        boolean expired=false,stalled=false,kill=false;
        synchronized(mutex) {
            if(phase==Phase.CLOSED) return;
            long now=clock.nativeMonotonicMs();
            if(phase==Phase.STOPPING && now-stoppingAt>=STOP_BUDGET_MS) {
                stage=Stage.FAILED; live=false; event(Code.CLEANUP_TIMEOUT); kill=exitSent.compareAndSet(false,true);
            } else if(phase!=Phase.STOPPING && now>=leaseUntil) expired=true;
            else if(phase==Phase.RUNNING && now-progressAt>=WORK_PROGRESS_MS) stalled=true;
        }
        if(expired) beginStop(-1,Code.LEASE_EXPIRED);
        if(stalled) beginStop(-1,Code.WORKER_STALLED);
        if(kill) fatal.exit(74); // Terminate this supervisor only; kernel then releases its lock.
    }

    /** File name is shared across asset versions; never delete/unlink a live lock file. */
    public static final class KernelOwnerLock implements OwnerLock {
        private final Path path;
        private FileChannel channel;
        private FileLock lock;
        public KernelOwnerLock(Path path) { this.path=path; }
        @Override public boolean acquire() throws IOException {
            if(lock!=null && lock.isValid()) return true;
            if(channel==null) channel=FileChannel.open(path,StandardOpenOption.CREATE,
                StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try { lock=channel.tryLock(); } catch(OverlappingFileLockException busy) { return false; }
            return lock!=null;
        }
        @Override public void release() throws IOException { if(lock!=null) { lock.release(); lock=null; } }
        @Override public void close() throws IOException {
            release(); if(channel!=null) { channel.close(); channel=null; }
        }
    }
}
