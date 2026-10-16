package dev.denza.vehiclecontrol.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Why «Привет, Денза» was missed on the car, and the rules that keep it from being missed again. */
public class WakeReliabilityTest {
    /** words: "word:conf:start:end" */
    private static JSONObject result(String... words) throws Exception {
        JSONArray list = new JSONArray();
        List<String> text = new ArrayList<>();
        for (String w : words) {
            String[] p = w.split(":");
            list.put(new JSONObject().put("word", p[0]).put("conf", Double.parseDouble(p[1]))
                    .put("start", Double.parseDouble(p[2])).put("end", Double.parseDouble(p[3])));
            text.add(p[0]);
        }
        return new JSONObject().put("text", String.join(" ", text)).put("result", list);
    }

    private static final String[] CALL = {"привет:1:0.00:0.57", "дэн:1:0.57:0.84", "за:1:0.84:1.05"};

    @Test public void theUnrestrictedPassNoLongerNeedsTheExactSpelling() throws Exception {
        // Real owner calls recorded on the car (remote-040): the first two were refused before.
        {WakePhrase.Detection d=WakePhrase.detect(result(CALL), result("привет:1:0.33:0.78", "дэнс:0.35:0.78:1.20"));assertTrue(d.reason(), d.accepted());}
        {WakePhrase.Detection d=WakePhrase.detect(result(CALL), result("привет:1:0.00:0.63", "за:0.39:0.96:1.14"));assertTrue(d.reason(), d.accepted());}
        {WakePhrase.Detection d=WakePhrase.detect(result(CALL), result("привет:1:0.18:0.69", "бензо:1:0.69:1.11"));assertTrue(d.reason(), d.accepted());}
        {WakePhrase.Detection d=WakePhrase.detect(result(CALL), result("привет:1:0.00:0.57", "ден:1:0.57:0.84", "за:1:0.84:1.05"));assertTrue(d.reason(), d.accepted());}
        {WakePhrase.Detection d=WakePhrase.detect(result(CALL), result("привет:1:0.00:0.57", "пенза:1:0.57:1.05"));assertTrue(d.reason(), d.accepted());}
    }

    @Test public void aConfidentOtherNameOrAMissingHelloStillVetoes() throws Exception {
        assertFalse(WakePhrase.matches(result(CALL), result("привет:1:0.00:0.57", "денис:1:0.57:1.05")));
        assertFalse(WakePhrase.matches(result(CALL), result("привет:1:0.00:0.57", "где:1:0.57:0.84", "заправка:1:0.84:1.47")));
        assertFalse(WakePhrase.matches(result(CALL), result("пока:1:0.00:0.57", "дэнс:0.4:0.57:1.05")));
        assertFalse(WakePhrase.matches(result(CALL), new JSONObject().put("text", "").put("result", new JSONArray())));
        assertTrue(WakePhrase.detect(result(CALL), result("привет:1:0.00:0.57", "денис:1:0.57:1.05")).reason().contains("денис"));
    }

    @Test public void oneBreathCallLeavesTheCommandAfterTheName() throws Exception {
        WakePhrase.Detection d = WakePhrase.detect(result("привет:1:0.00:0.57", "дэн:1:0.57:0.84", "за:1:0.84:1.05", "[unk]:1:1.20:1.80"),
                result("привет:1:0.00:0.57", "дэнс:0.5:0.57:1.05", "открой:1:1.20:1.50", "окно:1:1.50:1.80"));
        assertTrue(d.reason(), d.accepted());
        assertEquals(1.05, d.nameEnd(), 1e-9);
    }

    @Test public void aPauseAfterHelloUpToNineTenthsIsStillACall() throws Exception {
        assertTrue(WakePhrase.accepts(result("привет:1:0.00:0.50", "дэн:1:1.30:1.55", "за:1:1.55:1.75")));
        assertFalse(WakePhrase.accepts(result("привет:1:0.00:0.50", "дэн:1:1.45:1.70", "за:1:1.70:1.90")));
        assertTrue(WakePhrase.narrow(result("привет:1:0.00:0.50")).hello());
        assertFalse(WakePhrase.narrow(result("привет:0.6:0.00:0.50")).hello());
        assertFalse(WakePhrase.accepts(result("привет:1:0.00:0.50", "дэн:0.7:0.60:0.80", "за:1:0.80:1.00")));
    }

    @Test public void soundKeysMergeVoicingAndUnstressedVowels() {
        assertEquals(WakePhrase.key("денза"), "тенса");
        assertTrue(WakePhrase.distance(WakePhrase.key("бензо"), WakePhrase.key("денза")) <= WakePhrase.VETO_DISTANCE);
        assertTrue(WakePhrase.distance(WakePhrase.key("дэнс"), WakePhrase.key("денза")) <= WakePhrase.VETO_DISTANCE);
        assertTrue(WakePhrase.distance(WakePhrase.key("ден за"), WakePhrase.key("денза")) <= WakePhrase.VETO_DISTANCE);
        assertTrue(WakePhrase.distance(WakePhrase.key("денис"), WakePhrase.key("денза")) > WakePhrase.VETO_DISTANCE);
        assertTrue(WakePhrase.distance(WakePhrase.key("где"), WakePhrase.key("денза")) > WakePhrase.VETO_DISTANCE);
    }

    /** 10 interleaved channels; value(frame, channel). */
    private interface Signal { int value(int frame, int channel); }

    private static List<CabinSpeech.Utterance> feed(CabinSpeech gate, int frames, Signal signal) {
        List<CabinSpeech.Utterance> events = new ArrayList<>();
        short[] all = new short[frames * 10];
        for (int f = 0; f < frames; f++) for (int c = 0; c < 10; c++) all[f * 10 + c] = (short) signal.value(f, c);
        for (int i = 0; i < all.length; i += 3170) {
            int n = Math.min(3170, all.length - i);
            gate.add(Arrays.copyOfRange(all, i, i + n), n, events::add);
        }
        return events;
    }

    @Test public void theMicrophoneWithMostVoicedEnergyWinsNotTheFirstLoudFrame() {
        CabinSpeech gate = new CabinSpeech();
        // A short click on mic 0 opens the utterance; the speaker is on mic 4 for 1.2 s.
        List<CabinSpeech.Utterance> events = feed(gate, 16000 * 4, (f, c) -> {
            double t = f / 16000.0;
            if (c == 0 && t >= 1.20 && t < 1.24) return (int) (4000 * Math.sin(f * 0.3));
            if (c == 4 && t >= 1.22 && t < 2.4) return (int) (1500 * Math.sin(f * 0.16));
            return 0;
        });
        assertEquals(1, events.size());
        assertEquals(4, events.get(0).microphone());
        // The 0.4 s pre-roll is kept, and sample positions frame the utterance.
        CabinSpeech.Utterance u = events.get(0);
        assertEquals(u.endSample() - u.startSample(), u.pcm().length);
        assertTrue(u.startSample() <= (long) (1.20 * 16000) - 6000);
    }

    @Test public void theRingReturnsWhatWasSaidSinceAnySample() {
        CabinSpeech gate = new CabinSpeech();
        feed(gate, 16000, (f, c) -> c == 8 ? f % 1000 : 0);
        assertEquals(16000, gate.samples());
        short[] last = gate.since(15000, 8);
        assertEquals(1000, last.length);
        assertEquals(0, last[0]);
        assertEquals(999, last[999]);
        assertEquals(16000, gate.since(0, 8).length);
        assertThrows(IllegalArgumentException.class, () -> gate.since(0, 2));
    }

    @Test public void primedAudioPrecedesLiveFramesWithTheSameGain() throws Exception {
        CommandAudio stream = new CommandAudio(1, 8, 2);
        short[] frame = new short[3200];
        for (int i = 0; i < 320; i++) frame[i * 10 + 8] = 7;
        stream.offerFrame(frame);
        short[] early = new short[5000];
        Arrays.fill(early, (short) 3);
        stream.prime(early);
        short[] first = stream.poll(), second = stream.poll(), live = stream.poll();
        assertEquals(3200, first.length);
        assertEquals(6, first[0]);
        assertEquals(1800, second.length);
        assertEquals(320, live.length);
        assertEquals(14, live[0]);
    }

    @Test public void aTurnKeepsListeningThroughUnknownOnlyEndpoints() {
        assertFalse(WakeProbeService.meaningful(""));
        assertFalse(WakeProbeService.meaningful("[unk]"));
        assertFalse(WakeProbeService.meaningful("[unk] [unk]  [unk]"));
        assertTrue(WakeProbeService.meaningful("[unk] открой окна"));
        assertTrue(WakeProbeService.meaningful("включи кондиционер"));
    }

    @Test public void helloAndNameCutApartAreJoinedWithAtMostAShortGap() {
        CabinSpeech.Utterance hello = new CabinSpeech.Utterance(new short[]{1, 1}, new double[]{1, 0, 0, 0, 0, 0}, 0, 2, 100, 102);
        CabinSpeech.Utterance name = new CabinSpeech.Utterance(new short[]{2, 2, 2}, new double[]{0, 2, 0, 0, 0, 0}, 1, 3, 20000, 20003);
        CabinSpeech.Utterance joined = WakeProbeService.joined(hello, name);
        assertEquals(2 + 4800 + 3, joined.pcm().length);
        assertEquals(1, joined.pcm()[1]);
        assertEquals(2, joined.pcm()[2 + 4800]);
        assertEquals(100, joined.startSample());
        assertEquals(20003, joined.endSample());
        assertEquals(1, joined.microphone());
    }
}
