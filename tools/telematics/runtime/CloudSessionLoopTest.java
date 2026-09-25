package dev.denza.tools.runtime;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

public final class CloudSessionLoopTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void await(CountDownLatch latch)throws Exception{check(latch.await(2,TimeUnit.SECONDS));}
    static void awaitTerminal(CloudRuntimeSupervisor s)throws Exception{
        for(int i=0;i<200&&s.snapshot().stage!=Stage.FAILED;i++)Thread.sleep(10);
        s.awaitCleanup(1000);
    }
    static final class Time implements Clock { volatile long value=1000;public long nowMs(){return value;} }
    static final class Lock implements OwnerLock {
        volatile boolean held;public boolean acquire(){held=true;return true;}public void release(){held=false;}public void close(){release();}
    }
    static void reconnectRetiresBeforeReplacement()throws Exception{
        Time clock=new Time();Lock lock=new Lock();List<String> order=Collections.synchronizedList(new ArrayList<>());
        AtomicInteger live=new AtomicInteger();CountDownLatch second=new CountDownLatch(1);
        AtomicReference<Identity> pair=new AtomicReference<>();
        CloudSessionLoop loop=new CloudSessionLoop(clock,ms->clock.value+=ms,(identity,epoch,sink)->{
            if(epoch==1)pair.set(identity);else check(pair.get()==identity);
            check(live.get()==0);order.add("create"+epoch);
            return new CloudSessionLoop.Connection(){
                public void establish(CloudSessionLoop.Progress p){check(live.incrementAndGet()==1);order.add("open"+epoch);}
                public void pump(long ms,CloudSessionLoop.Progress p)throws Exception{
                    if(epoch==1)throw new IOException("synthetic lost connection");
                    second.countDown();Thread.sleep(10);
                }
                public void close(){order.add("close"+epoch);live.decrementAndGet();}
            };
        });
        CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(clock,lock,()->{},loop,code->{throw new AssertionError();});
        s.probe();s.start(new Identity("89860700000000000000","460010000000000"));await(second);
        check(order.indexOf("close1")<order.indexOf("create2"));check(s.snapshot().reconnects==1);
        check(s.stop().stage==Stage.STOPPED && live.get()==0 && !lock.held);
    }
    static void cleanupFailureNeverCreatesReplacement()throws Exception{
        Time clock=new Time();Lock lock=new Lock();AtomicInteger attempts=new AtomicInteger();CountDownLatch failed=new CountDownLatch(1);
        CloudSessionLoop loop=new CloudSessionLoop(clock,ms->clock.value+=ms,(identity,epoch,sink)->{
            attempts.incrementAndGet();return new CloudSessionLoop.Connection(){
                public void establish(CloudSessionLoop.Progress p)throws Exception{throw new IOException();}
                public void pump(long ms,CloudSessionLoop.Progress p){}
                public void close()throws Exception{failed.countDown();throw new IOException("uncertain native process");}
            };
        });
        AtomicInteger killed=new AtomicInteger();CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(clock,lock,()->{},loop,killed::set);
        s.probe();s.start(new Identity("89860700000000000000","460010000000000"));await(failed);
        // close() counts down before it throws. Wait for the cleanup thread to
        // record the debt before advancing the synthetic watchdog clock.
        for(int i=0;i<200 && s.snapshot().code!=Code.CLEANUP_FAILED;i++)Thread.sleep(10);
        s.awaitCleanup(1000);
        check(attempts.get()==1 && lock.held && s.snapshot().code==Code.CLEANUP_FAILED);
        clock.value+=STOP_BUDGET_MS;s.watchdogTick();check(killed.get()==74);
    }
    static void deterministicMismatchNeverRetries()throws Exception{
        Time clock=new Time();Lock lock=new Lock();AtomicInteger attempts=new AtomicInteger();
        CloudSessionLoop loop=new CloudSessionLoop(clock,ms->clock.value+=ms,(identity,epoch,sink)->{
            attempts.incrementAndGet();return new CloudSessionLoop.Connection(){
                public void establish(CloudSessionLoop.Progress p)throws Exception{throw new CloudNativePipe.ProtocolFailure();}
                public void pump(long ms,CloudSessionLoop.Progress p){}
                public void close(){}
            };
        });
        CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(clock,lock,()->{},loop,code->{throw new AssertionError();});
        s.probe();s.start(new Identity("89860700000000000000","460010000000000"));awaitTerminal(s);
        check(attempts.get()==1&&s.snapshot().code==Code.NATIVE_UNAVAILABLE&&!lock.held);
    }
    static void nativeChildRecoveryIsBounded()throws Exception{
        Time clock=new Time();Lock lock=new Lock();AtomicInteger attempts=new AtomicInteger();
        CloudSessionLoop loop=new CloudSessionLoop(clock,ms->clock.value+=ms,(identity,epoch,sink)->{
            attempts.incrementAndGet();return new CloudSessionLoop.Connection(){
                public void establish(CloudSessionLoop.Progress p)throws Exception{throw new CloudNativePipe.NativeFailure();}
                public void pump(long ms,CloudSessionLoop.Progress p){}
                public void close(){}
            };
        });
        CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(clock,lock,()->{},loop,code->{throw new AssertionError();});
        s.probe();s.start(new Identity("89860700000000000000","460010000000000"));awaitTerminal(s);
        check(attempts.get()==3&&s.snapshot().code==Code.NATIVE_UNAVAILABLE&&!lock.held);
    }
    static void sleepBetweenAttemptsIsTerminal()throws Exception{
        AtomicLong elapsed=new AtomicLong(1000),uptime=new AtomicLong(1000);
        Clock clock=new Clock(){public long nowMs(){return elapsed.get();}
            public long nativeMonotonicMs(){return uptime.get();}};
        Lock lock=new Lock();AtomicInteger attempts=new AtomicInteger();
        CloudSessionLoop loop=new CloudSessionLoop(clock,ms->{
            elapsed.addAndGet(1_800_000);uptime.addAndGet(10);
        },(identity,epoch,sink)->{
            attempts.incrementAndGet();return new CloudSessionLoop.Connection(){
                public void establish(CloudSessionLoop.Progress p)throws Exception{throw new IOException();}
                public void pump(long ms,CloudSessionLoop.Progress p){}
                public void close(){}
            };
        });
        CloudRuntimeSupervisor s=new CloudRuntimeSupervisor(clock,lock,()->{},loop,code->{throw new AssertionError();});
        s.probe();s.start(new Identity("89860700000000000000","460010000000000"));awaitTerminal(s);
        check(attempts.get()==1&&s.snapshot().code==Code.POWER_LOST&&!lock.held);
    }
    public static void main(String[] args)throws Exception{
        reconnectRetiresBeforeReplacement();cleanupFailureNeverCreatesReplacement();
        deterministicMismatchNeverRetries();nativeChildRecoveryIsBounded();
        sleepBetweenAttemptsIsTerminal();
        System.out.println("PASS reconnect lifecycle cases=5");
    }
}
