package dev.denza.diagnostics.probe;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only lifecycle tests. No Android, ADB, network or vehicle access. */
public final class OtaRouteObserverTest {
    static final String NONCE = "0123456789abcdef0123456789abcdef";
    static final String OTHER = "abcdef0123456789abcdef0123456789";
    static int tests;
    interface Checked { void run() throws Exception; }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void test(String name, Checked body) throws Exception { body.run(); tests++; System.out.println("PASS " + name); }
    static void rejects(Checked body) throws Exception {
        try { body.run(); } catch (Exception expected) { return; }
        throw new AssertionError("expected rejection");
    }
    static final class FakeClock implements OtaRouteObserver.Clock {
        volatile long now = 100;
        public long millis() { return now; }
    }
    static class FakeProps implements OtaRouteObserver.Properties {
        final Map<String, String> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean throwSecondEnable, denyRestore, corruptSecondRead;
        FakeProps() {
            values.put(OtaRouteObserver.KEYS[0], "");
            values.put(OtaRouteObserver.KEYS[1], "W");
            values.put("log.tag", "M");
        }
        public String get(String key) {
            if (corruptSecondRead && key.equals(OtaRouteObserver.KEYS[1]) && "D".equals(values.get(key))) return "W";
            return values.get(key);
        }
        public void set(String key, String value) throws Exception {
            check(Arrays.asList(OtaRouteObserver.KEYS).contains(key), "only two properties");
            writes.add(key + "=" + value);
            if (denyRestore && !"D".equals(value)) throw new Exception();
            values.put(key, value);
            if (throwSecondEnable && "D".equals(value) && key.equals(OtaRouteObserver.KEYS[1])) throw new Exception();
        }
    }
    static final class FakeChild implements OtaRouteObserver.Child {
        boolean dead, stopResult = true;
        int stops;
        String failure;
        public boolean exited() { return dead; }
        public boolean stop() { stops++; dead = stopResult; return stopResult; }
        public String streamFailure() { return failure; }
    }
    static final class FakeSink implements OtaRouteObserver.StateSink {
        final List<OtaRouteObserver.State> states = new ArrayList<>();
        String failStatus;
        public void save(OtaRouteObserver.State state) throws Exception {
            if (state.status.equals(failStatus)) throw new Exception();
            states.add(state);
        }
        OtaRouteObserver.State last() { return states.get(states.size() - 1); }
    }
    static final class Rig {
        final FakeClock clock = new FakeClock();
        final FakeProps props;
        final FakeChild child = new FakeChild();
        final FakeSink sink = new FakeSink();
        final AtomicBoolean stop = new AtomicBoolean();
        OtaRouteObserver.Session session;
        int starts;
        boolean throwStart;
        Rig() { this(new FakeProps()); }
        Rig(FakeProps props) {
            this.props = props;
            session = new OtaRouteObserver.Session(props, clock, () -> {
                starts++; if (throwStart) throw new Exception(); return child;
            }, sink, stop::get, NONCE, 123, 2);
        }
        void restored() {
            check("".equals(props.values.get(OtaRouteObserver.KEYS[0])), "empty restored exactly");
            check("W".equals(props.values.get(OtaRouteObserver.KEYS[1])), "W restored exactly");
            check("M".equals(props.values.get("log.tag")), "global unchanged");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--child")) {
            System.out.println("synthetic host-only output"); System.out.flush(); Thread.sleep(15000); return;
        }
        test("CLI exact range and nonce", () -> {
            check(OtaRouteObserver.duration(new String[]{"--duration-seconds", "350", "--nonce", NONCE}) == 350, "350 accepted");
            for (String value : new String[]{"0", "351", "-1", "01", "1.0", "999999"})
                rejects(() -> OtaRouteObserver.duration(new String[]{"--duration-seconds", value, "--nonce", NONCE}));
            rejects(() -> OtaRouteObserver.duration(new String[]{"--duration-seconds", "1", "--nonce", "oops"}));
        });
        test("deadline restores originals and reaps child once", () -> {
            Rig r = new Rig(); r.session.start();
            check(r.sink.last().status.equals("OBSERVING"), "ready status");
            check(r.sink.states.get(0).originals.size() == 2 && r.sink.states.get(0).status.equals("STARTING"), "original snapshot first");
            r.clock.now = 2100; r.session.tick(); r.session.finish("shutdown", true); r.session.start();
            r.restored(); check(r.session.result() == 0 && r.child.stops == 1 && r.starts == 1, "closed idempotently");
            check(r.sink.last().restoredVerified && r.sink.last().childExited, "terminal proof");
        });
        test("partial mutation throwing setter restores both", () -> {
            Rig r = new Rig(); r.props.throwSecondEnable = true; r.session.start();
            r.restored(); check(r.starts == 0 && r.session.result() == 2, "no child and setup failed");
        });
        test("enable readback mismatch restores", () -> {
            Rig r = new Rig(); r.props.corruptSecondRead = true; r.session.start();
            r.restored(); check(r.starts == 0 && r.session.result() == 2, "readback required");
        });
        test("original snapshot must persist before any setter", () -> {
            Rig r = new Rig(); r.sink.failStatus = "STARTING"; r.session.start();
            check(r.props.writes.isEmpty() && r.starts == 0, "no mutation"); r.restored();
        });
        test("ready state persistence failure cleans up", () -> {
            Rig r = new Rig(); r.sink.failStatus = "OBSERVING"; r.session.start();
            r.restored(); check(r.child.stops == 1 && r.session.result() == 2, "ready failure cleanup");
        });
        test("source startup failure restores", () -> {
            Rig r = new Rig(); r.throwStart = true; r.session.start();
            r.restored(); check(r.session.result() == 2, "source failure reported");
        });
        test("stop before enable changes nothing", () -> {
            Rig r = new Rig(); r.stop.set(true); r.session.start();
            check(r.props.writes.isEmpty() && r.starts == 0 && r.session.result() == 0, "early stop");
        });
        test("stop file signal and shutdown are idempotent", () -> {
            Rig r = new Rig(); r.session.start(); r.stop.set(true); r.session.tick(); r.session.finish("shutdown", true);
            r.restored(); check(r.child.stops == 1 && r.session.result() == 0, "one stop");
        });
        test("deadline checked again after slow setter", () -> {
            FakeClock clock = new FakeClock();
            FakeProps props = new FakeProps() {
                public void set(String key, String value) throws Exception {
                    super.set(key, value); if ("D".equals(value)) clock.now = 1100;
                }
            };
            FakeSink sink = new FakeSink();
            OtaRouteObserver.Session session = new OtaRouteObserver.Session(props, clock,
                () -> { throw new AssertionError("must not start after deadline"); }, sink, () -> false, NONCE, 123, 1);
            session.start(); check(session.result() == 0 && sink.last().restoredVerified, "deadline restoration");
            check(props.writes.size() == 3, "one enable and two restores");
        });
        test("concurrent shutdown cannot allow a later enable", () -> {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            FakeProps props = new FakeProps() {
                public void set(String key, String value) throws Exception {
                    super.set(key, value);
                    if ("D".equals(value)) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS), "release setter"); }
                }
            };
            Rig r = new Rig(props);
            Thread start = new Thread(r.session::start), shutdown = new Thread(() -> r.session.finish("shutdown", true));
            start.start(); check(entered.await(2, TimeUnit.SECONDS), "setter entered");
            r.stop.set(true); shutdown.start(); release.countDown(); start.join(2000); shutdown.join(2000);
            check(!start.isAlive() && !shutdown.isAlive(), "threads finish"); r.restored();
            int writes = props.writes.size(); r.session.start(); check(props.writes.size() == writes && r.starts == 0, "no enable after close");
        });
        test("stdout failure or logcat EOF restores", () -> {
            for (String cause : new String[]{"stream_failed", "logcat_eof", "output_limit"}) {
                Rig r = new Rig(); r.session.start(); r.child.failure = cause; r.session.tick();
                r.restored(); check(r.session.result() == 2 && r.child.stops == 1, "stream failure cleanup");
            }
        });
        test("restore failure cannot claim STOPPED", () -> {
            Rig r = new Rig(); r.session.start(); r.props.denyRestore = true; r.session.finish("shutdown", true);
            check(r.session.result() == 3 && r.sink.last().status.equals("RESTORE_FAILED") && !r.sink.last().restoredVerified, "restore failure visible");
            check(r.child.stops == 1, "child still cleaned");
        });
        test("child cleanup failure cannot claim STOPPED", () -> {
            Rig r = new Rig(); r.session.start(); r.child.stopResult = false; r.session.finish("shutdown", true);
            r.restored(); check(r.session.result() == 3 && !r.sink.last().childExited, "child failure visible");
        });
        test("final state write failure changes exit result", () -> {
            Rig r = new Rig(); r.session.start(); r.sink.failStatus = "STOPPED"; r.session.finish("shutdown", true);
            r.restored(); check(r.session.result() == 3 && r.sink.last().status.equals("RESTORING"), "incomplete state visible");
        });
        test("file lease nonce stop and stale restoration gate", () -> {
            Path parent = Files.createTempDirectory("ota-observer-test-"); Path base = parent.resolve("state");
            try (OtaRouteObserver.FilesState files = new OtaRouteObserver.FilesState(base, NONCE)) {
                rejects(() -> new OtaRouteObserver.FilesState(base, OTHER));
                Files.write(base.resolve("stop.request"), (OTHER + "\n").getBytes(StandardCharsets.US_ASCII));
                check(!files.requested(), "wrong nonce ignored");
                Files.write(base.resolve("stop.request"), (NONCE + "\n").getBytes(StandardCharsets.US_ASCII));
                check(files.requested(), "matching nonce accepted");
                files.save(new OtaRouteObserver.State(NONCE, 123, 1, "OBSERVING", "observing", 1, new HashMap<>(), false, false));
                check(Files.getPosixFilePermissions(base.resolve("state.json")).equals(PosixFilePermissions.fromString("rw-------")), "state0600");
            }
            rejects(() -> new OtaRouteObserver.FilesState(base, OTHER));
            // Explicit test-only recovery of state; the real CLI has no override/recovery flag.
            Files.delete(base.resolve("state.json"));
            try (OtaRouteObserver.FilesState files = new OtaRouteObserver.FilesState(base, NONCE)) {
                files.save(new OtaRouteObserver.State(NONCE, 123, 1, "STOPPED", "deadline", 1001, new HashMap<>(), true, true));
            }
            rejects(() -> new OtaRouteObserver.FilesState(base, NONCE));
            try (OtaRouteObserver.FilesState next = new OtaRouteObserver.FilesState(base, OTHER)) { check(!next.requested(), "old stop ignored"); }
            for (String name : new String[]{"state.json", "stop.request", "lease.lock"}) Files.deleteIfExists(base.resolve(name));
            Files.delete(base); Files.delete(parent);
        });
        test("symlinks and permissive directory rejected", () -> {
            Path parent = Files.createTempDirectory("ota-observer-links-"); Path target = parent.resolve("real");
            Files.createDirectory(target); Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
            Path link = parent.resolve("link"); Files.createSymbolicLink(link, target);
            rejects(() -> new OtaRouteObserver.FilesState(link, NONCE));
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            rejects(() -> new OtaRouteObserver.FilesState(target, NONCE));
            Files.delete(link); Files.delete(target); Files.delete(parent);
        });
        test("blocked stdout does not block owned process cleanup", () -> {
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
            OutputStream blocked = new OutputStream() {
                public void write(int value) throws java.io.IOException {
                    writing.countDown(); try { release.await(); } catch (InterruptedException e) { throw new java.io.IOException(); }
                }
            };
            Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), OtaRouteObserverTest.class.getName(), "--child").start();
            OtaRouteObserver.LogcatChild child = new OtaRouteObserver.LogcatChild(process, blocked);
            try {
                check(writing.await(3, TimeUnit.SECONDS), "stdout reached");
                long start = System.nanoTime(); check(child.stop(), "owned child exited");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500, "cleanup bounded independently");
            } finally { release.countDown(); process.destroyForcibly(); }
        });
        System.out.println("PASS " + tests + " host-only tests");
    }
}
