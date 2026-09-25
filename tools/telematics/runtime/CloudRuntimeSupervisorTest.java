package dev.denza.tools.runtime;

import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CloudRuntimeSupervisorTest {
    static int tests;
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    static void await(CountDownLatch latch) throws Exception { check(latch.await(2,TimeUnit.SECONDS)); }
    static void eventually(java.util.function.BooleanSupplier predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!predicate.getAsBoolean() && System.nanoTime()<end) Thread.sleep(1);
        check(predicate.getAsBoolean());
    }
    static final Identity PAIR=new Identity("89860700000000000000","460010000000000");
    static final class Time implements Clock { volatile long value=1000; public long nowMs(){return value;} }
    static class Lock implements OwnerLock {
        volatile boolean held,closed;
        public boolean acquire(){check(!closed);held=true;return true;}
        public void release(){held=false;}
        public void close(){release();closed=true;}
    }
    static CloudRuntimeSupervisor create(Time time,OwnerLock lock,Journal journal,SessionTask task,AtomicInteger kill) {
        return new CloudRuntimeSupervisor(time,lock,journal,task,kill::set);
    }
    static void waitCancelled(Scope scope) throws Exception {
        while(!scope.cancelled()) Thread.sleep(2);
    }
    static void basicStopAndLateSink() throws Exception {
        Time time=new Time(); Lock lock=new Lock(); AtomicInteger kill=new AtomicInteger(),closed=new AtomicInteger();
        CountDownLatch running=new CountDownLatch(1); AtomicReference<CloudRuntimeSupervisor.Sink> sink=new AtomicReference<>();
        CloudRuntimeSupervisor s=create(time,lock,()->{},(scope,pair,events)->{
            scope.own((AutoCloseable)closed::incrementAndGet);sink.set(events);
            events.state(Stage.CONNECTED,Code.CONNECTED);running.countDown();waitCancelled(scope);
        },kill);
        check(s.probe().code==Code.OWNER_ABSENT_CONFIRMED && lock.held);
        s.start(PAIR);await(running);
        check(s.snapshot().sessionLive);
        check(s.stop().stage==Stage.STOPPED && !lock.held && closed.get()==1);
        sink.get().state(Stage.CONNECTED,Code.CONNECTED);sink.get().count(Metric.COMMAND_COMPLETED);
        check(!s.snapshot().sessionLive && s.snapshot().commandsCompleted==0);tests++;
    }
    static void failedJournalCannotStart() throws Exception {
        Time t=new Time();Lock lock=new Lock(); AtomicInteger ran=new AtomicInteger();
        CloudRuntimeSupervisor s=create(t,lock,()->{throw new java.io.IOException();},(a,b,c)->ran.incrementAndGet(),new AtomicInteger());
        s.probe();s.start(PAIR);eventually(()->lock.closed);
        check(ran.get()==0 && s.snapshot().code==Code.REGISTRATION_JOURNAL_FAILED);
        check(s.stop().stage==Stage.STOPPED && !lock.held);tests++;
    }
    static void hungJournalDoesNotBlockWatchdog() throws Exception {
        Time t=new Time();Lock lock=new Lock();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger killed=new AtomicInteger(),ran=new AtomicInteger();
        CloudRuntimeSupervisor s=create(t,lock,()->{entered.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){}},
            (a,b,c)->ran.incrementAndGet(),killed);
        s.probe();s.start(PAIR);await(entered);
        t.value+=LEASE_MS;s.watchdogTick();check(!s.snapshot().sessionLive);
        t.value+=STOP_BUDGET_MS;s.watchdogTick();check(killed.get()==74 && lock.held && ran.get()==0);
        release.countDown();s.awaitCleanup(3000);tests++;
    }
    static void hungLockDoesNotBlockWatchdog() throws Exception {
        Time t=new Time();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Lock lock=new Lock(){public boolean acquire(){entered.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){}return super.acquire();}};
        AtomicInteger killed=new AtomicInteger();CloudRuntimeSupervisor s=create(t,lock,()->{},(a,b,c)->{},killed);
        Thread probe=new Thread(()->{try{s.probe();}catch(Exception expected){}});probe.start();await(entered);
        t.value+=LEASE_MS;s.watchdogTick();t.value+=STOP_BUDGET_MS;s.watchdogTick();check(killed.get()==74);
        release.countDown();probe.join(1000);s.awaitCleanup(2000);check(lock.closed && !lock.held);tests++;
    }
    static void failedCloseNeverAcknowledgesStopped() throws Exception {
        Time t=new Time();Lock lock=new Lock();CountDownLatch running=new CountDownLatch(1);AtomicInteger killed=new AtomicInteger();
        CloudRuntimeSupervisor s=create(t,lock,()->{},(scope,pair,events)->{
            scope.own(()->{throw new java.io.IOException();});running.countDown();waitCancelled(scope);
        },killed);
        s.probe();s.start(PAIR);await(running);
        Snapshot answer=s.stop();check(answer.stage!=Stage.STOPPED && lock.held);
        t.value+=STOP_BUDGET_MS;s.watchdogTick();check(killed.get()==74);tests++;
    }
    static void lateResourceCloseFailureRetainsOwnership() throws Exception {
        Time t=new Time();Lock lock=new Lock();CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicInteger killed=new AtomicInteger();CloudRuntimeSupervisor s=create(t,lock,()->{},(scope,pair,events)->{
            started.countDown();for(;;){if(scope.cancelled())break;try{Thread.sleep(1);}catch(InterruptedException ignored){}}
            try{scope.own(()->{throw new java.io.IOException();});}finally{cancelled.countDown();}
        },killed);
        s.probe();s.start(PAIR);await(started);s.close();await(cancelled);s.awaitCleanup(2000);
        check(lock.held && s.snapshot().stage!=Stage.STOPPED);tests++;
    }
    static void concurrentRetireClosesExactlyOnce() throws Exception {
        Scope scope=new Scope();AtomicInteger closes=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AutoCloseable resource=scope.own(()->{closes.incrementAndGet();entered.countDown();release.await();});
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable r=()->{try{scope.retire(resource);}catch(Throwable e){failure.set(e);}};
        Thread a=new Thread(r),b=new Thread(r);a.start();await(entered);b.start();Thread.sleep(20);release.countDown();a.join();b.join();
        check(closes.get()==1 && failure.get()==null);tests++;
    }
    static void kernelExclusionAndMarker() throws Exception {
        Path dir=Files.createTempDirectory("cloud-owner-test");Path file=dir.resolve("owner.lock");
        try(KernelOwnerLock a=new KernelOwnerLock(file);KernelOwnerLock b=new KernelOwnerLock(file)) {
            check(a.acquire() && !b.acquire());a.close();check(b.acquire());
            CloudRegistrationJournal journal=new CloudRegistrationJournal(dir.resolve("registration.pending"));
            check(!journal.registrationUncertain());journal.customMayRegister();check(journal.registrationUncertain());
            journal.customMayRegister();check(Files.size(dir.resolve("registration.pending"))<100);
        } finally {for(Path p:Files.newDirectoryStream(dir))Files.delete(p);Files.delete(dir);}
        tests++;
    }
    static void malformedIdentityAndDuplicateStart() throws Exception {
        try{new Identity("secret","460010000000000");throw new AssertionError();}catch(IllegalArgumentException expected){check(!expected.getMessage().contains("secret"));}
        Time t=new Time();Lock lock=new Lock();CountDownLatch started=new CountDownLatch(1);
        CloudRuntimeSupervisor s=create(t,lock,()->{},(scope,pair,event)->{started.countDown();waitCancelled(scope);},new AtomicInteger());
        try{s.start(PAIR);throw new AssertionError();}catch(IllegalStateException expected){}
        s.probe();s.start(PAIR);await(started);
        try{s.start(PAIR);throw new AssertionError();}catch(IllegalStateException expected){}
        s.stop();tests++;
    }
    static void ownerStatusCannotHideStalledWorker() throws Exception {
        Time t=new Time();Lock lock=new Lock();CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger killed=new AtomicInteger();
        CloudRuntimeSupervisor s=create(t,lock,()->{},(scope,pair,events)->{
            running.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){}
        },killed);
        s.probe();s.start(PAIR);await(running);t.value+=WORK_PROGRESS_MS;
        s.renewLease(t.value+LEASE_MS);s.watchdogTick();
        check(s.snapshot().stage!=Stage.CONNECTED);t.value+=STOP_BUDGET_MS;s.watchdogTick();
        check(killed.get()==74&&lock.held);release.countDown();s.awaitCleanup(2000);tests++;
    }
    static void reservedPermitExpiresBeforeStart() throws Exception {
        Time time=new Time();Lock lock=new Lock();AtomicInteger ran=new AtomicInteger();
        CloudRuntimeSupervisor supervisor=create(time,lock,()->{},
            (scope,pair,sink)->ran.incrementAndGet(),new AtomicInteger());
        supervisor.probe();supervisor.initialLease(31_000);
        time.value=31_000;
        try{supervisor.start(PAIR);throw new AssertionError("expired START admitted");}
        catch(IllegalStateException expected){}
        check(ran.get()==0);
        supervisor.closeAndAwait();check(!lock.held);tests++;
    }
    static void suspendDoesNotExpireProcessOrProgressLease()throws Exception{
        final class SuspendClock implements Clock{
            volatile long boot=1000,uptime=1000;
            public long nowMs(){return boot;}
            public long nativeMonotonicMs(){return uptime;}
        }
        SuspendClock time=new SuspendClock();Lock lock=new Lock();
        CountDownLatch running=new CountDownLatch(1);AtomicInteger killed=new AtomicInteger();
        CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(time,lock,()->{},(scope,pair,events)->{
            events.state(Stage.CONNECTED,Code.CONNECTED);events.progress();running.countDown();
            waitCancelled(scope);
        },killed::set);
        s.probe();s.start(PAIR);await(running);
        time.boot+=120_000; // deep sleep advances BOOTTIME, not CLOCK_MONOTONIC
        s.watchdogTick();
        check(s.snapshot().sessionLive&&lock.held&&killed.get()==0);
        s.stop();check(!lock.held);tests++;
    }
    public static void main(String[] args) throws Exception {
        basicStopAndLateSink();failedJournalCannotStart();hungJournalDoesNotBlockWatchdog();hungLockDoesNotBlockWatchdog();
        failedCloseNeverAcknowledgesStopped();lateResourceCloseFailureRetainsOwnership();concurrentRetireClosesExactlyOnce();
        kernelExclusionAndMarker();malformedIdentityAndDuplicateStart();ownerStatusCannotHideStalledWorker();
        reservedPermitExpiresBeforeStart();
        suspendDoesNotExpireProcessOrProgressLease();
        System.out.println("PASS supervisor cases="+tests);
    }
}
