package dev.denza.hud.probe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;

/**
 * The 2026-10-24 drive lost its start: the rings held 15 (live) and 30 (packets) minutes and both
 * receivers recorded every service-13 packet. Sizes for two hours, packets once, and a change
 * journal that a drive never rotates out.
 */
public final class DriveJournalTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static long size(Map<String, Object> row) { return (RouteSnapshot.json(row) + "\n").getBytes(StandardCharsets.UTF_8).length; }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("drive-journal");
        try {
            repeatsAndJournal(dir.toFile());
            driveBoundary(dir.toFile());
            twoHourBudget();
            System.out.println("DriveJournalTest: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) { files.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } }); }
        }
    }

    static void repeatsAndJournal(File dir) throws Exception {
        File history = new File(dir, "progress.jsonl"), raw = new File(dir, "adas-packets.jsonl"), log = new File(dir, "adas-journal.jsonl");
        AdasStateJournal journal = new AdasStateJournal(log, 1_000_000);
        DriveEvidenceStreams streams = new DriveEvidenceStreams(new DriveEvidenceLog(history, 1 << 20), new DriveEvidenceLog(raw, 1 << 20, 3), journal);
        byte[] pilot = AdasWire.pilot(0, 2, false), active = AdasWire.pilot(1, 2, false);
        Map<String, Object> first = streams.passive("overlay", null, "p", AdasWire.PILOT, 1, 100, pilot, 1000);
        Map<String, Object> again = streams.passive("overlay", null, "p", AdasWire.PILOT, 2, 133, pilot.clone(), 1033);
        Map<String, Object> other = streams.passive("live", "arm", "p", AdasWire.PILOT, 2, 134, pilot.clone(), 1034);
        check("adas_passive_packet".equals(first.get("kind")) && first.get("base64") != null, "the first payload is kept in full");
        check("adas_passive_repeat".equals(again.get("kind")) && !again.containsKey("base64") && again.get("receivedAtElapsedMs").equals(133L),
                "an identical payload is one compact row with its own receipt time");
        check("adas_passive_packet".equals(other.get("kind")), "another receiver's packet is its own");
        byte[] ack = AdasWire.selected(901, 200);
        streams.passive("overlay", null, "p", AdasWire.SELECTED, 3, 140, ack, 1040);
        check("adas_passive_packet".equals(streams.passive("overlay", null, "p", AdasWire.SELECTED, 4, 150, ack.clone(), 1050).get("kind")),
                "a selection answer always keeps its bytes");
        for (Map<String, Object> row : new Map[]{first, again}) streams.append(row);
        check(read(raw).contains("adas_passive_repeat") && (!history.exists() || !read(history).contains("adas_passive_repeat")), "repeats go to the packet ring only");

        check(journal.observe("overlay", AdasWire.PILOT, pilot, 100, 1000) != null, "the first pilot state is a transition");
        check(journal.observe("overlay", AdasWire.PILOT, pilot, 133, 1033) == null, "a repeated pilot state is not");
        Map<String, Object> on = journal.observe("overlay", AdasWire.PILOT, active, 166, 1066);
        check(on != null && Long.valueOf(1).equals(((Number) on.get("dnp")).longValue()) && on.get("was") instanceof Map, "DNP 0 -> 1 is journalled with what it was");
        check(journal.observe("overlay", AdasWire.MODE, AdasWire.mode(2), 170, 1070) != null, "the NOA mode is journalled");
        check(journal.observe("overlay", AdasWire.SELECTED, ack, 180, 1080) != null && journal.observe("live", AdasWire.SELECTED, ack, 181, 1081) != null,
                "every selection answer is journalled, by each receiver");
        check(journal.observe("live", 0x0001002C002C0002L, null, 190, 1090) != null, "path availability loss is journalled");
        check(journal.observe("overlay", 0x0004000700078001L, new byte[]{10, 0}, 200, 1100) == null, "the IVI's own feed is not ADAS state");
        check(journal.feed("PAUSED", "Нет свежих сигналов машины", "vehicle gates: stale", 210, 1110) != null
                && journal.feed("PAUSED", "Нет свежих сигналов машины", "vehicle gates: stale", 211, 1111) == null, "our feed's state changes are journalled once");
        streams.append(on);
        check(read(log).contains("\"what\":\"pilot\"") && !read(raw).contains("\"what\":\"pilot\""), "journal rows go to the journal only");
        Map<String, Object> effective = new java.util.LinkedHashMap<>(); effective.put("frame", "STOCK_GCJ");
        Map<String, Object> variant = journal.variant("arm-1", effective, 220, 1120);
        check(variant != null && "variant".equals(variant.get("what")) && effective.equals(variant.get("variant")) && "arm-1".equals(variant.get("armId")),
                "the arm's variant is journalled");
        check(journal.variant("arm-1", effective, 230, 1130) == null && journal.variant("arm-2", effective, 240, 1140) != null,
                "once per arm and variant");
        Map<String, Object> read = new java.util.LinkedHashMap<>(); read.put("n", 1L); read.put("op", "read"); read.put("value", 0L);
        Map<String, Object> set = new java.util.LinkedHashMap<>(); set.put("n", 2L); set.put("op", "set"); set.put("value", 2L); set.put("readback", 2L);
        check(journal.naviSt("arm-2", read, 250, 1150) != null && journal.naviSt("arm-2", read, 251, 1151) == null
                && journal.naviSt("arm-2", set, 260, 1160) != null, "each NaviSt read and write is journalled once by its number");
        streams.append(variant);
        check(read(log).contains("\"what\":\"variant\""), "the variant row reaches the journal file");

        AdasStateJournal small = new AdasStateJournal(new File(dir, "small-journal.jsonl"), 1_000_000, 600);
        for (int i = 0; i < 50; i++) small.append("{\"kind\":\"adas_state\",\"n\":" + i + "}");
        check(!new File(dir, "small-journal.jsonl.1").exists() && small.dropped() > 0 && read(new File(dir, "small-journal.jsonl")).contains("adas_state_journal_full"),
                "a full journal counts what it drops and never rotates within the drive");
    }

    static void driveBoundary(File dir) throws Exception {
        File log = new File(dir, "boundary.jsonl");
        Files.write(log.toPath(), "{\"drive\":1}\n".getBytes(StandardCharsets.UTF_8));
        long modified = log.lastModified();
        new AdasStateJournal(log, modified + 60_000).append("{\"drive\":1,\"restart\":true}");
        check(!new File(dir, "boundary.jsonl.1").exists() && read(log).contains("restart"), "a Navigator restart within the drive continues its journal");
        new AdasStateJournal(log, log.lastModified() + AdasStateJournal.DRIVE_GAP_MS + 1).append("{\"drive\":2}");
        check(read(new File(dir, "boundary.jsonl.1")).contains("restart") && read(log).equals("{\"drive\":2}\n"), "a new drive starts its own journal, the last one kept");
        File ring = new File(dir, "ring.jsonl");
        DriveEvidenceLog five = new DriveEvidenceLog(ring, 10, 5);
        for (int i = 0; i < 30; i++) { String d = Integer.toString(i % 10); five.append(d + d + d + d); }
        check(new File(dir, "ring.jsonl.4").isFile() && !new File(dir, "ring.jsonl.5").exists(), "a five-generation ring keeps exactly four predecessors");
        check(read(new File(dir, "ring.jsonl.1")).length() == 10 && read(new File(dir, "ring.jsonl.4")).length() == 10, "each predecessor is a full file");
    }

    /** Today's drive rates (captures/adas-live-drive-20261024, 630 s of overlay packets) against the ring sizes. */
    static void twoHourBudget() {
        DriveEvidenceStreams streams = new DriveEvidenceStreams(null, null);
        byte[] pilot = Base64.getDecoder().decode("CggIi969jQoYAg==");
        long full = size(streams.passive("overlay", null, "process-session-id-0123456789abcdef", AdasWire.PILOT, 1_792_837_000_000L, 80_000_000L, pilot, 1_792_837_000_000L));
        long repeat = size(streams.passive("overlay", null, "process-session-id-0123456789abcdef", AdasWire.PILOT, 1_792_837_000_033L, 80_000_033L, pilot, 1_792_837_000_033L));
        double seconds = 630, scale = DriveEvidenceStreams.DRIVE_MS / 1000.0 / seconds;
        // Per 630 s: ALARM 6170 rows all changing; PILOT 6173 with 147 changes; MODE 6171 with 1 change; 24 service-44 rows.
        double packets = scale * ((6170 + 147 + 1 + 24) * (full + 24) + (6173 - 147 + 6171 - 1) * repeat);
        long kept = (DriveEvidenceStreams.PACKET_GENERATIONS - 1) * DriveEvidenceStreams.RING_BYTES;
        check(packets <= kept, "two hours of packets fit the packet ring: " + (long) packets / (1 << 20) + " MiB of " + kept / (1 << 20));
        check(packets > DriveEvidenceStreams.RING_BYTES, "the old two-file ring (one full file kept) held well under two hours");
        double history = DriveEvidenceStreams.DRIVE_MS / 1000.0 * 1315;      // guidance rows, 1 Hz, 205140 B / 156 rows
        check(history <= (DriveEvidenceStreams.HISTORY_GENERATIONS - 1) * DriveEvidenceStreams.RING_BYTES, "two hours of guidance history fit");
        double live = 120 * 0.314 * (1 << 20), dry = 120 * 0.197 * (1 << 20); // MiB a minute at the stock cadence
        check(live <= (DriveEvidenceStreams.LIVE_GENERATIONS - 1) * DriveEvidenceStreams.RING_BYTES, "two hours of the live journal fit");
        check(dry <= (DriveEvidenceStreams.DRY_RUN_GENERATIONS - 1) * DriveEvidenceStreams.RING_BYTES, "two hours of the dry run fit");
        long budget = DriveEvidenceStreams.RING_BYTES * (DriveEvidenceStreams.PACKET_GENERATIONS + DriveEvidenceStreams.HISTORY_GENERATIONS
                + DriveEvidenceStreams.LIVE_GENERATIONS + DriveEvidenceStreams.DRY_RUN_GENERATIONS) + AdasStateJournal.LIMIT * AdasStateJournal.DRIVES;
        check(budget <= 200L << 20, "the whole evidence budget stays under 200 MiB: " + budget / (1 << 20));
    }
}
