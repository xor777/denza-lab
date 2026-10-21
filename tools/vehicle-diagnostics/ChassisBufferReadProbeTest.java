package dev.denza.diagnostics.probe;

import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Host-only lifecycle and bound tests; no Android, SDK, vehicle, network or firmware execution. */
public final class ChassisBufferReadProbeTest {
    static int tests;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static void test(String name, Checked body) throws Exception { body.run(); tests++; System.out.println("PASS " + name); }
    static void reject(String... args) {
        try { ChassisBufferReadProbe.Config.parse(args); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("configuration must be refused: " + Arrays.toString(args));
    }
    static final class Clock implements ChassisBufferReadProbe.Clock { volatile long ns; public long nanos() { return ns; } }
    static class Backend implements ChassisBufferReadProbe.Backend {
        final List<String> calls = new ArrayList<>();
        ChassisBufferReadProbe.Receiver receiver;
        ChassisBufferReadProbe.Value initial = new ChassisBufferReadProbe.Value(new byte[12], null);
        boolean registerThrows, unregisterThrows;
        public void open(ChassisBufferReadProbe.Target target, ChassisBufferReadProbe.Receiver receiver) {
            calls.add("open:" + target.name); this.receiver = receiver;
        }
        public ChassisBufferReadProbe.Value snapshot() { calls.add("snapshot"); return initial; }
        public void register() throws Exception { calls.add("register"); if (registerThrows) throw new Exception(); }
        public void unregister() throws Exception { calls.add("unregister"); if (unregisterThrows) throw new Exception(); }
    }
    static final class Rig {
        final Clock clock = new Clock(); final Backend backend;
        final ChassisBufferReadProbe.Session session;
        Rig() { this(new Backend(), 128); }
        Rig(Backend backend, int capacity) {
            this.backend = backend;
            session = new ChassisBufferReadProbe.Session(new ChassisBufferReadProbe.Config(ChassisBufferReadProbe.Target.WHEELS, 20), clock, backend, capacity);
        }
    }
    public static void main(String[] ignored) throws Exception {
        test("exact target and finite duration before SDK loading", () -> {
            for (String target : new String[]{"wheels", "steering"})
                for (String seconds : new String[]{"3", "10", "20"})
                    check(ChassisBufferReadProbe.Config.parse(new String[]{"--target",target,"--seconds",seconds}).seconds == Integer.parseInt(seconds), "valid configuration");
            for (String target : new String[]{"all", "1049", "0x99000059", "panorama", "WHEELS", ""}) reject("--target",target,"--seconds","20");
            for (String seconds : new String[]{"0","1","2","21","020","+3","-3","3.0","999999999999999"}) reject("--target","wheels","--seconds",seconds);
            reject(); reject("--target","wheels"); reject("--target","wheels","--seconds","10","extra");
        });
        test("one initial snapshot and one ID subscription", () -> {
            Rig r = new Rig(); r.session.start(); check(r.session.ready, "ready after register returns");
            check(r.backend.calls.equals(Arrays.asList("open:wheels","snapshot","register")), "single API sequence");
            check(r.session.queue.peek().source.equals("snapshot"), "snapshot source retained");
            r.clock.ns = r.session.softDeadlineNs; r.session.tick();
            check(r.session.closed && r.session.unregisterReturned && r.session.exitCode == 0, "clean deadline");
            check(r.backend.calls.equals(Arrays.asList("open:wheels","snapshot","register","unregister")), "one unregister");
            r.session.finish("again"); check(r.backend.calls.size() == 4, "idempotent finish");
            check(r.session.hardDeadlineNs == 20_000_000_000L && r.session.softDeadlineNs == 18_000_000_000L, "cleanup budget included");
        });
        test("callback copies bytes and retains raw DTO integer", () -> {
            Rig r = new Rig(); r.session.start(); byte[] data = new byte[12]; data[0] = 1;
            r.session.data(0x99000059,data,123); data[0]=2;
            r.session.queue.poll(); ChassisBufferReadProbe.Record record=r.session.queue.poll();
            check(record.bytes[0]==1 && record.sdkInt==123 && record.source.equals("callback"), "owned immutable copy");
            r.session.finish("test"); long received=r.session.received.get(); r.session.data(0x99000059,new byte[12],0);
            check(r.session.received.get()==received, "no data after closing");
        });
        test("MCU offline empty null and SDK status remain unavailable raw observations", () -> {
            for (byte[] b : new byte[][]{null,new byte[0],"MCU_OFFLINE".getBytes("US-ASCII"),new byte[]{(byte)0x80,0,3,(byte)0xe8}}) {
                ChassisBufferReadProbe.Record record=new ChassisBufferReadProbe.Record(1,"snapshot",1,2,new ChassisBufferReadProbe.Value(b,null));
                String quality=record.quality(ChassisBufferReadProbe.Target.WHEELS);
                check(!quality.equals("wheel_shape_and_status_gate_only"), "never measurement");
                check(record.json(ChassisBufferReadProbe.Target.WHEELS).contains("\"physicalUnit\":null"), "no guessed units");
            }
        });
        test("wheel status and producer domains remain distinct", () -> {
            byte[] b=new byte[12]; b[8]=1;
            check(new ChassisBufferReadProbe.Record(1,"callback",0,0,new ChassisBufferReadProbe.Value(b,0)).quality(ChassisBufferReadProbe.Target.WHEELS).equals("wheel_status_gate_blocked"), "nonzero gate");
            b[8]=2;
            check(new ChassisBufferReadProbe.Record(1,"callback",0,0,new ChassisBufferReadProbe.Value(b,0)).quality(ChassisBufferReadProbe.Target.WHEELS).equals("wheel_status_outside_producer_domain"), "not DTC");
            b[8]=0; b[0]=(byte)0xf0;
            check(new ChassisBufferReadProbe.Record(1,"callback",0,0,new ChassisBufferReadProbe.Value(b,0)).quality(ChassisBufferReadProbe.Target.WHEELS).equals("wheel_word_outside_producer_domain"), "do not mask bad high bits");
        });
        test("oversized source is not copied and blocks registration", () -> {
            Rig r=new Rig(); r.backend.initial=new ChassisBufferReadProbe.Value(new byte[65],null); r.session.start(); r.session.tick();
            check(r.session.closed && !r.session.registerAttempted && r.session.exitCode==2, "no subscription after size failure");
            ChassisBufferReadProbe.Record record=r.session.queue.peek();check(record.bytes==null && record.originalLength==65,"bounded copy");
        });
        test("queue overflow visible and unregisters", () -> {
            Rig r=new Rig(new Backend(),2);r.session.start();
            r.session.data(0x99000059,new byte[12],0);r.session.data(0x99000059,new byte[12],0);r.session.tick();
            check(r.session.closed && r.session.exitCode==2 && r.session.received.get()==3 && r.session.dropped.get()==1,"loss visible");
            check(r.session.unregisterReturned,"still cleaned");
        });
        test("record limit bounds total output even when queue drains", () -> {
            Rig r=new Rig();r.session.start();r.session.queue.clear();
            for(int i=0;i<1024;i++){r.session.data(0x99000059,new byte[12],0);r.session.queue.clear();}
            r.session.tick();check(r.session.closed && r.session.failure.get().equals("record_limit") && r.session.exitCode==2,"record cap");
        });
        test("unexpected callback cannot broaden targets", () -> {
            Rig r=new Rig();r.session.start();r.session.data(0x99000198,new byte[6],0);r.session.tick();
            check(r.session.closed && r.session.unexpected.get()==1 && r.session.received.get()==1,"only initial wheel snapshot retained");
        });
        test("registration throwing after mutation still attempts unregister", () -> {
            Rig r=new Rig();r.backend.registerThrows=true;r.session.start();r.session.tick();
            check(r.session.closed && r.session.registerAttempted && r.session.unregisterReturned && r.session.exitCode==2,"partial registration cleanup");
        });
        test("unregister failure cannot claim clean completion", () -> {
            Rig r=new Rig();r.backend.unregisterThrows=true;r.session.start();r.session.finish("test");
            check(r.session.closed && !r.session.unregisterReturned && r.session.exitCode==2,"unregister failure visible");
        });
        test("SDK callback error stops and unregisters", () -> {
            Rig r=new Rig();r.session.start();r.session.error(-2147482644);r.session.tick();
            check(r.session.closed && r.session.callbackErrors.get()==1 && r.session.exitCode==2 && r.session.unregisterReturned,"fail closed");
        });
        test("deadline during open does not register afterward", () -> {
            Clock clock=new Clock();Backend backend=new Backend(){public void open(ChassisBufferReadProbe.Target t,ChassisBufferReadProbe.Receiver r){super.open(t,r);clock.ns=19_000_000_000L;}};
            ChassisBufferReadProbe.Session s=new ChassisBufferReadProbe.Session(new ChassisBufferReadProbe.Config(ChassisBufferReadProbe.Target.WHEELS,20),clock,backend,128);
            s.start();s.tick();check(s.closed && !s.registerAttempted && !s.ready && s.exitCode==2,"no late subscription");
        });
        test("concurrent finish closes callback gate before waiting on register", () -> {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            Backend backend=new Backend(){public void register() throws Exception{calls.add("register");entered.countDown();check(release.await(2,TimeUnit.SECONDS),"release");}};
            Rig r=new Rig(backend,128);Thread start=new Thread(r.session::start);start.start();check(entered.await(2,TimeUnit.SECONDS),"entered");
            Thread close=new Thread(()->r.session.finish("test"));close.start();
            for(int i=0;i<100&&!r.session.closing.get();i++)Thread.sleep(2);
            check(r.session.closing.get(),"callback gate closed without SDK lock");long count=r.session.received.get();r.session.data(0x99000059,new byte[12],0);check(r.session.received.get()==count,"late callback rejected");
            release.countDown();start.join(2000);close.join(2000);check(!start.isAlive()&&!close.isAlive()&&r.session.unregisterReturned&&!r.session.ready,"registration cannot outlive cleanup");
        });
        test("EOF cancels and unregisters instead of extending capture", () -> {
            Rig r=new Rig();r.session.start();ChassisBufferReadProbe.commands(new ByteArrayInputStream(new byte[0]),r.session);
            for(int i=0;i<100&&!r.session.closing.get();i++)Thread.sleep(2);
            r.session.tick();check(r.session.closed && r.session.unregisterReturned && r.session.reason.equals("stdin_eof"),"EOF cleanup");
        });
        test("early closure without READY is not operational success", () -> {
            Rig r=new Rig();r.session.finish("stdin_eof");r.session.start();
            check(r.session.closed && !r.session.ready && !r.session.registerAttempted && r.session.exitCode==2 && r.backend.calls.isEmpty(),"not started fails closed");
        });
        test("shared reader lock conflicts releases and never removes path", () -> {
            Path dir=Files.createTempDirectory("chassis-reader-lock-");Path lock=dir.resolve("reader.lock");
            try {
                try(ChassisBufferReadProbe.ReaderLease first=ChassisBufferReadProbe.ReaderLease.acquire(lock)) {
                    check(first.lock.isValid(),"first lease valid");boolean refused=false;
                    try(ChassisBufferReadProbe.ReaderLease second=ChassisBufferReadProbe.ReaderLease.acquire(lock)) { check(second.lock.isValid(), "unexpected second lock"); }
                    catch(Exception expected){refused=true;}
                    check(refused,"second reader refused");
                }
                check(Files.exists(lock),"lock path preserved");
                try(ChassisBufferReadProbe.ReaderLease next=ChassisBufferReadProbe.ReaderLease.acquire(lock)){check(next.lock.isValid(),"released for next process");}
            } finally {Files.deleteIfExists(lock);Files.deleteIfExists(dir);}
        });
        test("hard watchdog bypasses SDK lock", () -> {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            Backend backend=new Backend(){public void open(ChassisBufferReadProbe.Target t,ChassisBufferReadProbe.Receiver r){calls.add("open");entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}};
            Rig r=new Rig(backend,128);Thread setup=new Thread(r.session::start);setup.start();check(entered.await(1,TimeUnit.SECONDS),"SDK call blocked");
            AtomicBoolean halted=new AtomicBoolean();Thread hard=ChassisBufferReadProbe.startHardDeadline(25_000_000L,()->halted.set(true));hard.join(1000);
            check(halted.get() && !hard.isAlive() && setup.isAlive(),"watchdog independent of blocked setup");
            r.session.closing.set(true);release.countDown();setup.join(1000);r.session.finish("test");check(!setup.isAlive(),"test backend released");
        });
        System.out.println("PASS TOTAL="+tests);
    }
}
