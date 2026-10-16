package dev.denza.vehiclecontrol.probe;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** PCM statistics only. Keeps channel alignment across short, unaligned reads. */
final class MicrophoneProbeStats {
    private final long[] samples, nonzero, fingerprint;
    private final double[] squares, sum;
    private final int[] peaks;
    private int channel;
    long totalSamples;

    MicrophoneProbeStats(int channels) {
        samples = new long[channels]; nonzero = new long[channels];
        fingerprint = new long[channels]; Arrays.fill(fingerprint, 0xcbf29ce484222325L);
        squares = new double[channels]; sum = new double[channels]; peaks = new int[channels];
    }

    void add(short[] pcm, int count) {
        for (int i = 0; i < count; i++) {
            int value = pcm[i];
            samples[channel]++; if (value != 0) nonzero[channel]++;
            squares[channel] += (double) value * value; sum[channel] += value;
            peaks[channel] = Math.max(peaks[channel], Math.abs(value));
            fingerprint[channel] = (fingerprint[channel] ^ (value & 0xffff)) * 0x100000001b3L;
            channel = (channel + 1) % samples.length; totalSamples++;
        }
    }

    JSONArray report() throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < samples.length; i++) {
            double rms = samples[i] == 0 ? 0 : Math.sqrt(squares[i] / samples[i]);
            out.put(new JSONObject().put("channel", i).put("samples", samples[i])
                    .put("nonzero", nonzero[i]).put("peak", peaks[i]).put("rms", rms)
                    .put("mean", samples[i] == 0 ? 0 : sum[i] / samples[i])
                    .put("dbfs", rms == 0 ? -120 : 20 * Math.log10(rms / 32768))
                    .put("fingerprint", Long.toUnsignedString(fingerprint[i], 16)));
        }
        return out;
    }

    private static final Pattern STOCK = Pattern.compile(
            "^\\s*session:(\\d+) -- source client=VOICE_RECOGNITION,.*-- uid:1000 --.*-- silenced:(true|false) --.*$",
            Pattern.MULTILINE);

    static String stockLine(String dump) {
        Matcher matcher = STOCK.matcher(dump);
        if (!matcher.find()) throw new IllegalStateException("Stock VOICE_RECOGNITION recording missing");
        String line = matcher.group().trim();
        if (!"false".equals(matcher.group(2))) throw new IllegalStateException("Stock recording silenced");
        if (matcher.find()) throw new IllegalStateException("Ambiguous stock recordings");
        return line;
    }

    static void requireSameStock(String baseline, String current) {
        // Ignore the misleading shared-UID package label, but preserve session, source and format.
        String stableBaseline = baseline.replaceAll(" -- pack:[^ ]+", "");
        String stableCurrent = current.replaceAll(" -- pack:[^ ]+", "");
        if (!stableBaseline.equals(stableCurrent)) throw new IllegalStateException("Stock recording changed");
    }
}
