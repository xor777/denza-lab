package dev.denza.adasprobe;

import dev.denza.adaslink.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The integer-get reply must never reinterpret an exception, float or parcel offset as a gate; the
 * NaviSt side channel reads its original at arm time, writes 2 only on guidance on a confirmed path
 * under its own unexpired arm, and restores on every way out; a failed or unexpected read writes nothing.
 */
public final class AdasGateReaderTest {
    private static int checks;
    private static final String BOOT = "11111111-1111-4111-8111-111111111111", CONSUMER = "22222222-2222-4222-8222-222222222222",
            READER = "33333333-3333-4333-8333-333333333333", ARM = "44444444-4444-4444-8444-444444444444";
    private static final String READ = "service call autoservice 5 i32 1023 i32 1285554251";
    private static String write(int value) { return "service call autoservice 6 i32 1023 i32 1285554251 i32 " + value + " null"; }

    public static void main(String[] args) throws Exception {
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001   '........')\n") == 1);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000000   '........')") == 0);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 ffffd8e5   '........')") == -10011);
        check(AdasGateReader.parseInteger("Result: Parcel(NULL)") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(ffffffff 00000001 '........')") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(\n0x00000000: 00000000 00000001 '........'\n)") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001 00000002 '............')") == null);
        check(AdasGateReader.parseInteger("Result: Parcel(00000000 00000001 '........')\nResult: Parcel(00000000 00000002 '........')") == null);
        check(AdasGateReader.parseInteger("permission denied") == null);
        check(AdasGateReader.stockAbsent("NAME\ninit\nru.yandex.yandexnavi\n"));
        check(!AdasGateReader.stockAbsent("NAME\ncom.byd.launchermap\n"));
        check(!AdasGateReader.stockAbsent("NAME\ncom.byd.launchermap:service\n"));
        check(!AdasGateReader.stockAbsent("PID NAME\n123 init\n"));
        check(!AdasGateReader.stockAbsent("NAME\n"));
        check(!AdasGateReader.stockAbsent("permission denied"));
        check(!AdasGateReader.stockAbsent("NAME\nru.yandex.yandex\n"));
        check(!AdasGateReader.stockAbsent("NAME\ninit\n"));
        String heartbeat = "{\"schema\":\"denza-adas-consumer-v1\",\"consumerSession\":\"consumer\",\"bootId\":\"boot\",\"elapsedMs\":1001}";
        long[] clock = {1000};
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> { clock[0]=1002; return heartbeat; }, () -> clock[0]));
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 13001)); // a 12 s restart keeps the reader
        check(AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 61001));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 61002));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> heartbeat, () -> 1000));
        check(!AdasGateReader.consumerAlive("other", "boot", () -> heartbeat, () -> 1002));
        check(!AdasGateReader.consumerAlive("consumer", "other", () -> heartbeat, () -> 1002));
        check(!AdasGateReader.consumerAlive("consumer", "boot", () -> "{}", () -> 1002));
        naviStCommands();
        naviStGuidanceCycle();
        naviStExpiryAndExit();
        naviStFailedReadsWriteNothing();
        naviStUnverifiedWriteRestores();
        naviStTrigger();
        instrumentLifecycle();
        instrumentTrigger();
        System.out.println("AdasGateReaderTest: " + checks + " checks passed");
    }

    /** The car's NaviSt property as autoservice answers it, with every command it was sent. */
    static final class Car implements AdasGateReader.NaviSt.Shell {
        int value; String readReply; boolean failRead, ignoreWrites, failWrite;
        final boolean instrument;
        final List<String> sent = new ArrayList<>();
        Car(int value) { this(value, false); }
        Car(int value, boolean instrument) { this.value = value; this.instrument = instrument; }
        @Override public String run(byte[] input, String... args) throws Exception {
            if (input != null || !args[0].equals("/system/bin/service")) throw new AssertionError("only the fixed service commands");
            String line = String.join(" ", args).substring("/system/bin/".length());
            sent.add(line);
            if (line.equals(instrument ? INSTRUMENT_READ : READ)) {
                if (failRead) throw new IllegalStateException("read timeout");
                return readReply != null ? readReply : String.format("Result: Parcel(00000000 %08x   '........')%n", value);
            }
            if (!line.startsWith(instrument ? "service call autoservice 6 i32 1007 i32 1138753594 i32 " : "service call autoservice 6 i32 1023 i32 1285554251 i32 ") || !line.endsWith(" null"))
                throw new AssertionError("unexpected command " + line);
            if (failWrite) throw new IllegalStateException("write failed");
            if (!ignoreWrites) value = Integer.parseInt(line.split(" ")[9]);
            return "Result: Parcel(00000000 00000000   '........')\n";
        }
    }
    static final class Rig {
        final Car car; final List<Map<String, Object>> ledgers = new ArrayList<>(); final List<String> log = new ArrayList<>();
        long now = 5000; final AdasGateReader.NaviSt channel;
        Rig(int value) { car = new Car(value); channel = new AdasGateReader.NaviSt(car, () -> now, log::add, ledgers::add); }
        Map<String, Object> ledger() { return ledgers.get(ledgers.size() - 1); }
    }

    private static final String INSTRUMENT_READ = "service call autoservice 5 i32 1007 i32 1138753594";
    static final class InstrumentRig {
        final Car car; final List<Map<String, Object>> ledgers = new ArrayList<>();
        final AdasGateReader.InstrumentNaviStatus channel;
        InstrumentRig(int value) {
            car = new Car(value, true);
            channel = new AdasGateReader.InstrumentNaviStatus(car, () -> 5000, line -> { }, ledgers::add);
        }
    }
    @SuppressWarnings("unchecked")
    static void instrumentLifecycle() {
        for (int original : new int[] {0, 1, 2, 3, 4}) {
            InstrumentRig r = new InstrumentRig(original);
            r.channel.start(); r.channel.step("path not confirmed");
            check(r.car.sent.size() == 1 && r.car.value == original, "original read, then wait for confirmed guidance");
            r.channel.step(null);
            check(r.car.value == 2 && r.channel.dirty() && r.car.sent.size() == 4, "instrument writes 2 and verifies");
            for (String stop : new String[] {"guidance ended", "arm expired", "live state stale", "session STOPPED"}) {
                r.channel.step(stop);
                check(r.car.value == original && !r.channel.dirty(), "instrument restores on " + stop);
                r.channel.step(null);
                check(r.car.value == 2, "instrument resumes only under confirmed guidance");
            }
            r.channel.close("reader exit");
            check(r.car.value == original && !r.channel.dirty(), "instrument exit restores exact original");
            int sent = r.car.sent.size(); r.channel.close("shutdown hook"); r.channel.step(null);
            check(r.car.sent.size() == sent, "instrument close is idempotent");
            check(r.car.sent.stream().allMatch(x -> x.contains("1007 i32 1138753594")), "instrument never writes MPC");
            Map<String, Object> ledger = r.ledgers.get(r.ledgers.size() - 1);
            check(Long.valueOf(original).equals(ledger.get("original")) && "CLOSED".equals(ledger.get("state")), "independent original ledger");
            List<Object> events = (List<Object>) ledger.get("events");
            check(events.size() > 1 && ((Map<?, ?>) events.get(0)).get("device").equals(1007L), "instrument events name the device");
        }
        for (int unknown : new int[] {-1, 5, 65535}) {
            InstrumentRig r = new InstrumentRig(unknown); r.channel.start(); r.channel.step(null); r.channel.close("stop");
            check(r.car.sent.equals(list(INSTRUMENT_READ)) && !r.channel.dirty(), "unknown original writes nothing: " + unknown);
        }
        InstrumentRig failed = new InstrumentRig(1); failed.car.failRead = true; failed.channel.start(); failed.channel.step(null);
        check(failed.car.sent.equals(list(INSTRUMENT_READ)), "instrument failed original read writes nothing");
        InstrumentRig changed = new InstrumentRig(1); changed.channel.start(); changed.car.value = 3; changed.channel.step(null);
        check(changed.car.sent.size() == 2 && changed.car.value == 3, "external change since arm disables instrument write");
        InstrumentRig writeFailed = new InstrumentRig(1); writeFailed.channel.start(); writeFailed.car.failWrite = true;
        writeFailed.channel.step(null);
        check(writeFailed.channel.state() == AdasGateReader.NaviSt.State.DISABLED && writeFailed.channel.dirty(), "uncertain write keeps restoration pending");
        writeFailed.car.failWrite = false; writeFailed.channel.close("failure exit");
        check(writeFailed.car.value == 1 && !writeFailed.channel.dirty(), "failure exit retries instrument original");
        InstrumentRig readback = new InstrumentRig(1); readback.channel.start(); readback.car.ignoreWrites = true; readback.channel.step(null);
        check(readback.channel.state() == AdasGateReader.NaviSt.State.DISABLED && !readback.channel.dirty(), "mismatching instrument readback restores and disables");
        Rig mpc = new Rig(1); InstrumentRig instrument = new InstrumentRig(4);
        mpc.channel.start(); instrument.channel.start(); mpc.channel.step(null); instrument.channel.step(null);
        instrument.channel.step("arm expired");
        check(mpc.car.value == 2 && instrument.car.value == 4, "instrument restore is independent of MPC lifecycle");
        mpc.channel.close("stop");
    }
    static void instrumentTrigger() {
        long now = 100000;
        Map<String, Object> arm = map("schema", "denza-adas-live-arm-v1", "bootId", BOOT, "consumerSession", CONSUMER,
                "readerSession", READER, "armId", ARM, "issuedAtElapsedMs", 1000L, "expiresAtElapsedMs", 200000L,
                "variant", map("instrumentNaviStatus", true));
        Map<String, Object> state = map("schema", "denza-adas-live-state-v1", "bootId", BOOT, "consumerSession", CONSUMER,
                "armId", ARM, "elapsedMs", now - 900, "state", "LIVE", "guidanceActive", true, "confirmedPathId", 7L);
        check(AdasGateReader.notGuiding(arm, state, BOOT, CONSUMER, READER, ARM, now, "instrumentNaviStatus") == null, "instrument owns live guidance");
        check("arm without naviSt".equals(AdasGateReader.notGuiding(arm, state, BOOT, CONSUMER, READER, ARM, now)), "instrument arm does not enable MPC");
        check("arm without instrumentNaviStatus".equals(AdasGateReader.notGuiding(with(arm, "variant", map("naviSt", true)), state,
                BOOT, CONSUMER, READER, ARM, now, "instrumentNaviStatus")), "MPC arm does not enable instrument");
        check("live state stale".equals(AdasGateReader.notGuiding(arm, with(state, "elapsedMs", now - 5001), BOOT, CONSUMER, READER, ARM, now,
                "instrumentNaviStatus")), "instrument stale state restores");
        check("another arm".equals(AdasGateReader.notGuiding(arm, state, "other-boot", CONSUMER, READER, ARM, now,
                "instrumentNaviStatus")), "instrument cannot cross boot");
    }

    static void naviStCommands() {
        check(String.join(" ", AdasGateReader.NaviSt.readCommand()).equals("/system/bin/" + READ), "the parked session's read command");
        check(String.join(" ", AdasGateReader.NaviSt.writeCommand(2)).equals("/system/bin/" + write(2)), "the parked session's write command");
    }

    @SuppressWarnings("unchecked")
    static void naviStGuidanceCycle() {
        Rig r = new Rig(1);
        r.channel.step(null);
        check(r.car.sent.isEmpty(), "nothing before the arm-time read");
        r.channel.start();
        check(r.car.sent.equals(list(READ)) && r.channel.state() == AdasGateReader.NaviSt.State.IDLE, "arm time: one read, no write");
        check(Long.valueOf(1).equals(r.ledger().get("original")), "the original is in the ledger: " + r.ledger());
        r.channel.step("path not confirmed");
        check(r.car.sent.size() == 1, "no write before guidance on a confirmed path");
        r.channel.step(null);
        check(r.car.sent.equals(list(READ, READ, write(2), READ)) && r.car.value == 2, "guidance: read, write 2, read back: " + r.car.sent);
        check(r.channel.state() == AdasGateReader.NaviSt.State.WRITTEN && r.channel.dirty(), "written");
        r.channel.step(null);
        check(r.car.sent.size() == 4, "one write per guidance");
        r.channel.step("guidance ended");
        check(r.car.sent.subList(4, 6).equals(list(write(1), READ)) && r.car.value == 1 && !r.channel.dirty(),
                "guidance end restores the original and reads it back");
        check(r.channel.state() == AdasGateReader.NaviSt.State.IDLE, "and the next guidance may write again");
        r.channel.step(null);
        check(r.car.value == 2, "the next guidance writes 2 again");
        r.channel.step("session STOPPED");
        check(r.car.value == 1, "the session's end restores");
        Map<String, Object> view = r.channel.snapshot();
        check(Long.valueOf(11).equals(view.get("n")) && "IDLE".equals(view.get("state")) && !view.containsKey("events")
                && !Json.write(view).contains("["), "the gates file's summary is flat and names the last event: " + view);
        List<Object> events = (List<Object>) r.ledger().get("events");
        Map<String, Object> last = (Map<String, Object>) events.get(events.size() - 1);
        check(Long.valueOf(11).equals(last.get("n")) && "read".equals(last.get("op")) && Long.valueOf(1).equals(last.get("value")),
                "each event has its number, op and value: " + last);
        check(((List<Object>) r.ledger().get("events")).size() == 11, "the ledger keeps them all");
        check(gatesAccept(view), "the summary passes the hardened gates parser beside all ten values");
        int before = r.car.sent.size();
        r.channel.close("stop requested");
        check(r.car.sent.size() == before && r.channel.state() == AdasGateReader.NaviSt.State.CLOSED, "nothing written: nothing to restore");
        r.channel.step(null);
        check(r.car.sent.size() == before, "closed: no write");
    }

    static void naviStExpiryAndExit() {
        Rig r = new Rig(0); r.channel.start(); r.channel.step(null);
        check(r.car.value == 2, "written");
        r.channel.step("arm expired");
        check(r.car.value == 0 && !r.channel.dirty(), "arm expiry restores the original 0");
        Rig exit = new Rig(1); exit.channel.start(); exit.channel.step(null);
        exit.channel.close("reader exit (shutdown hook)");
        check(exit.car.value == 1 && !exit.channel.dirty() && exit.channel.state() == AdasGateReader.NaviSt.State.CLOSED,
                "the reader's exit restores");
        int sent = exit.car.sent.size();
        exit.channel.close("lifetime over");
        check(exit.car.sent.size() == sent, "close is idempotent (finally and the shutdown hook)");
        check(String.valueOf(exit.ledger().get("state")).equals("CLOSED") && Boolean.FALSE.equals(exit.ledger().get("dirty")),
                "the ledger says closed and clean: " + exit.ledger());
        Rig stuck = new Rig(1); stuck.channel.start(); stuck.channel.step(null);
        stuck.car.ignoreWrites = true;
        stuck.channel.close("consumer gone");
        check(stuck.car.value == 2 && stuck.channel.dirty() && String.valueOf(stuck.ledger().get("reason")).contains("NOT verified"),
                "a restore that does not read back stays dirty in the ledger for the host: " + stuck.ledger());
        check(stuck.car.sent.subList(4, stuck.car.sent.size()).equals(list(write(1), READ, write(1), READ)), "and was retried once");
    }

    static void naviStFailedReadsWriteNothing() {
        Rig failed = new Rig(1); failed.car.failRead = true;
        failed.channel.start(); failed.channel.step(null); failed.channel.close("lifetime over");
        check(failed.car.sent.equals(list(READ)) && failed.channel.state() == AdasGateReader.NaviSt.State.CLOSED,
                "a failed arm-time read: never a write: " + failed.car.sent);
        check(failed.ledger().get("original") == null, "and no original recorded");
        for (String reply : new String[] {"Result: Parcel(ffffd8e5 00000001 '....')", "Result: Parcel(NULL)", "permission denied"}) {
            Rig bad = new Rig(1); bad.car.readReply = reply;
            bad.channel.start(); bad.channel.step(null);
            check(bad.car.sent.equals(list(READ)), "an unproven reply writes nothing: " + reply);
        }
        Rig odd = new Rig(7); odd.channel.start(); odd.channel.step(null);
        check(odd.car.sent.equals(list(READ)) && odd.channel.state() == AdasGateReader.NaviSt.State.DISABLED, "an unexpected value writes nothing");
        Rig changed = new Rig(1); changed.channel.start(); changed.car.value = 0; changed.channel.step(null);
        check(changed.car.sent.equals(list(READ, READ)) && changed.car.value == 0, "a value changed since arm time writes nothing");
        Rig late = new Rig(1); late.channel.start(); late.car.failRead = true; late.channel.step(null);
        check(late.car.sent.equals(list(READ, READ)) && late.channel.state() == AdasGateReader.NaviSt.State.DISABLED,
                "a failed read before the write writes nothing");
    }

    static void naviStUnverifiedWriteRestores() {
        Rig r = new Rig(1); r.channel.start();
        r.car.readReply = null;
        // The write is accepted but the readback disagrees: the original goes back and the channel stops.
        r.car.ignoreWrites = true;
        r.channel.step(null);
        check(r.car.sent.equals(list(READ, READ, write(2), READ, write(1), READ)), "an unverified write is restored: " + r.car.sent);
        check(r.channel.state() == AdasGateReader.NaviSt.State.DISABLED && !r.channel.dirty(), "and the channel is disabled");
        r.car.ignoreWrites = false; r.channel.step("guidance ended"); r.channel.step(null);
        check(r.car.sent.size() == 6, "a disabled channel never writes again");
    }

    @SuppressWarnings("unchecked")
    static void naviStTrigger() {
        long now = 100_000;
        Map<String, Object> arm = map("schema", "denza-adas-live-arm-v1", "bootId", BOOT, "consumerSession", CONSUMER,
                "readerSession", READER, "armId", ARM, "issuedAtElapsedMs", 1000L, "expiresAtElapsedMs", 200_000L,
                "stockPackage", "com.byd.launchermap", "stockDisabled", true, "variant", map("id", "H6", "naviSt", true));
        Map<String, Object> state = map("schema", "denza-adas-live-state-v1", "bootId", BOOT, "consumerSession", CONSUMER,
                "armId", ARM, "elapsedMs", now - 900, "state", "LIVE", "guidanceActive", true, "confirmedPathId", 7L);
        check(AdasGateReader.notGuiding(arm, state, BOOT, CONSUMER, READER, ARM, now) == null, "guidance on a confirmed path under the arm");
        check("arm expired".equals(AdasGateReader.notGuiding(arm, state, BOOT, CONSUMER, READER, ARM, 200_000)), "expiry");
        check("no arm".equals(AdasGateReader.notGuiding(null, state, BOOT, CONSUMER, READER, ARM, now)), "a removed arm");
        check("another arm".equals(AdasGateReader.notGuiding(with(arm, "armId", READER), state, BOOT, CONSUMER, READER, ARM, now)), "another arm");
        check("another arm".equals(AdasGateReader.notGuiding(with(arm, "readerSession", ARM), state, BOOT, CONSUMER, READER, ARM, now)), "another reader");
        check("arm without naviSt".equals(AdasGateReader.notGuiding(with(arm, "variant", map("naviSt", false)), state, BOOT, CONSUMER, READER, ARM, now)), "switch off");
        check("arm without naviSt".equals(AdasGateReader.notGuiding(with(arm, "variant", null), state, BOOT, CONSUMER, READER, ARM, now)), "no variant");
        check("no live state".equals(AdasGateReader.notGuiding(arm, null, BOOT, CONSUMER, READER, ARM, now)), "no state");
        check("live state stale".equals(AdasGateReader.notGuiding(arm, with(state, "elapsedMs", now - 5001), BOOT, CONSUMER, READER, ARM, now)), "stale");
        check("live state stale".equals(AdasGateReader.notGuiding(arm, with(state, "elapsedMs", now + 1), BOOT, CONSUMER, READER, ARM, now)), "future");
        check("session WAITING".equals(AdasGateReader.notGuiding(arm, with(state, "state", "WAITING"), BOOT, CONSUMER, READER, ARM, now)), "not LIVE");
        check("guidance ended".equals(AdasGateReader.notGuiding(arm, with(state, "guidanceActive", false), BOOT, CONSUMER, READER, ARM, now)), "no guidance");
        check("path not confirmed".equals(AdasGateReader.notGuiding(arm, with(state, "confirmedPathId", -1L), BOOT, CONSUMER, READER, ARM, now)), "no ACK");
        check("live state of another session".equals(AdasGateReader.notGuiding(arm, with(state, "armId", READER), BOOT, CONSUMER, READER, ARM, now)), "other arm's state");
    }

    /** A gates row as the reader writes it (ten values, the worst-case entries) with the summary, through Navigator's parser. */
    private static boolean gatesAccept(Map<String, Object> summary) {
        Map<String, Object> row = Json.object(), values = Json.object();
        row.put("schema", dev.denza.adaslink.AdasGateSnapshot.SCHEMA); row.put("bootId", BOOT); row.put("consumerSession", CONSUMER);
        row.put("readerSession", READER); row.put("sequence", 1L); row.put("startedAtElapsedMs", 1000L); row.put("startedAtUptimeMs", 900L);
        for (int fid : dev.denza.adaslink.AdasGateSnapshot.ALL_FIDS) {
            long v = fid == dev.denza.adaslink.AdasGateSnapshot.HNP_CONFIG ? 3 : 0;
            values.put(Integer.toString(fid), map("status", "ok", "transact", 5L, "value", v));
        }
        row.put("values", values); row.put("completedAtUptimeMs", 950L); row.put("completedAtElapsedMs", 1050L);
        row.put("naviSt", summary);
        dev.denza.adaslink.AdasGateSnapshot.Snapshot s =
                new dev.denza.adaslink.AdasGateSnapshot.Tracker(BOOT, CONSUMER, 5000).read(Json.write(row), 1100);
        return s.valid && summary.equals(s.naviSt);
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = Json.object();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static Map<String, Object> with(Map<String, Object> m, String key, Object value) {
        Map<String, Object> copy = Json.object(); copy.putAll(m); copy.put(key, value); return copy;
    }
    private static List<String> list(String... values) { return java.util.Arrays.asList(values); }
    private static void check(boolean value) { check(value, "parcel integer decode"); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
