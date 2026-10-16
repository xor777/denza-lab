package dev.denza.vehiclecontrol.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class MicrophoneProbeStatsTest {
    @Test public void unalignedReadsKeepEveryChannelIncludingPcmMinimum() throws Exception {
        MicrophoneProbeStats stats = new MicrophoneProbeStats(10);
        stats.add(new short[]{1,2,3}, 3);
        stats.add(new short[]{4,5,6,7,8,9,-32768,11,12}, 9);
        assertEquals(12, stats.totalSamples);
        assertEquals(2, stats.report().getJSONObject(0).getLong("samples"));
        assertEquals(1, stats.report().getJSONObject(9).getLong("samples"));
        assertEquals(32768, stats.report().getJSONObject(9).getInt("peak"));
        assertEquals(1, stats.report().getJSONObject(9).getLong("nonzero"));
    }
    private static String line(int session, boolean silenced, String pkg) {
        return "  session:" + session + " -- source client=VOICE_RECOGNITION, dev=11ch 16000Hz ENCODING_PCM_16BIT -- uid:1000 -- patch:76 -- pack:" + pkg
                + " -- format client=11ch 16000Hz ENCODING_PCM_16BIT, dev=11ch 16000Hz ENCODING_PCM_16BIT -- silenced:" + silenced + " -- effects client=, dev=";
    }
    @Test public void currentSectionIgnoresHistoryAndSharedUidPackageLabel() {
        String first = MicrophoneProbeStats.stockLine("Header\n" + line(161,false,"com.byd.avc") + "\nAudio event log\nrec stop uid:1000 session:129");
        MicrophoneProbeStats.requireSameStock(first, MicrophoneProbeStats.stockLine(line(161,false,"com.byd.xcall")));
    }
    @Test public void rejectsSilencingDisappearanceReplacementAndAmbiguity() {
        assertThrows(IllegalStateException.class, () -> MicrophoneProbeStats.stockLine(""));
        assertThrows(IllegalStateException.class, () -> MicrophoneProbeStats.stockLine(line(161,true,"x")));
        assertThrows(IllegalStateException.class, () -> MicrophoneProbeStats.stockLine(line(161,false,"x") + "\n" + line(162,false,"x")));
        assertThrows(IllegalStateException.class, () -> MicrophoneProbeStats.requireSameStock(
                MicrophoneProbeStats.stockLine(line(161,false,"x")), MicrophoneProbeStats.stockLine(line(162,false,"x"))));
    }
}
