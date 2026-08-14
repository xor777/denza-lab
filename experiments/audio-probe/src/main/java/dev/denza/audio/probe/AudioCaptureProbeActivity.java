package dev.denza.audio.probe;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Short-lived, host-driven evaluation of the capture paths a spectrum analyser
 * could feed from on this head unit. The probe answers one question: can a
 * normal, non-privileged app observe the audio other apps (or the Bluetooth
 * A2DP sink) are playing? The microphone is deliberately out of scope.
 *
 * Nothing here is a product surface. The probe writes its findings to logcat
 * under {@code AudioCaptureProbe} and finishes on its own.
 *
 * Optional intent extras:
 *   --ez tone true      play a 2 s reference tone from this app while probing
 *   --ei session <id>   additionally attach a Visualizer to that audio session
 *   --ez bands true     dump log-spaced spectrum bands as text bars
 */
public final class AudioCaptureProbeActivity extends Activity {
    private static final String TAG = "AudioCaptureProbe";

    /** Visualizer sampling window; long enough to survive a quiet music passage. */
    private static final int SAMPLE_ROUNDS = 60;
    private static final long SAMPLE_INTERVAL_MS = 50L;

    /**
     * The mix runs at 48 kHz even though {@link Visualizer#getSamplingRate()}
     * reports 44100 on this head unit. Verified against two reference tones:
     * 440 Hz landed in bin 9 and 1000 Hz in bin 21, which only holds at 48 kHz.
     * Using the reported rate would skew the band edges by about 9%.
     */
    private static final int CALIBRATED_RATE_HZ = 48000;

    private static final int BAND_COUNT = 16;
    private static final double BAND_MIN_HZ = 40d;
    private static final double BAND_MAX_HZ = 16000d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean playTone = getIntent().getBooleanExtra("tone", false);
        final int explicitSession = getIntent().getIntExtra("session", -1);
        final boolean dumpBands = getIntent().getBooleanExtra("bands", false);
        new Thread(() -> {
            try {
                runProbes(playTone, explicitSession, dumpBands);
            } catch (Throwable t) {
                Log.e(TAG, "probe aborted", t);
            } finally {
                Log.i(TAG, "RESULT probe=done");
                finish();
            }
        }, "audio-probe").start();
    }

    private void runProbes(boolean playTone, int explicitSession, boolean dumpBands) {
        Log.i(TAG, "=== audio capture probe start ===");
        reportPlaybackState();

        ToneSource tone = null;
        if (playTone) {
            tone = new ToneSource();
            tone.start();
            sleep(300L);
        }
        try {
            // The decisive test: the global output mix carries every app plus the
            // Bluetooth sink, so a working Visualizer here is source-agnostic.
            probeVisualizer(0, "output-mix", dumpBands);

            for (int session : discoverSessions(explicitSession)) {
                probeVisualizer(session, "session-" + session, false);
            }

        } finally {
            if (tone != null) {
                tone.stop();
            }
        }
        Log.i(TAG, "=== audio capture probe end ===");
    }

    /** Logs what is playing right now so a null result can be told from a silent car. */
    private void reportPlaybackState() {
        AudioManager am = getSystemService(AudioManager.class);
        Log.i(TAG, "music-active=" + am.isMusicActive()
                + " volume=" + am.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
        Log.i(TAG, "active-playback-configs=" + configs.size());
        for (AudioPlaybackConfiguration config : configs) {
            AudioAttributes attributes = config.getAudioAttributes();
            Log.i(TAG, "  playback usage=" + attributes.getUsage()
                    + " content=" + attributes.getContentType()
                    + " flags=0x" + Integer.toHexString(attributes.getFlags())
                    + " session=" + reflectSessionId(config));
        }
    }

    /**
     * Session ids are not public API on {@link AudioPlaybackConfiguration}; the
     * host passes one in when reflection is blocked by the hidden-API policy.
     */
    private List<Integer> discoverSessions(int explicitSession) {
        Set<Integer> sessions = new LinkedHashSet<>();
        if (explicitSession > 0) {
            sessions.add(explicitSession);
        }
        AudioManager am = getSystemService(AudioManager.class);
        for (AudioPlaybackConfiguration config : am.getActivePlaybackConfigurations()) {
            int session = reflectSessionId(config);
            if (session > 0) {
                sessions.add(session);
            }
        }
        return new ArrayList<>(sessions);
    }

    private int reflectSessionId(AudioPlaybackConfiguration config) {
        try {
            Method method = AudioPlaybackConfiguration.class.getMethod("getSessionId");
            Object value = method.invoke(config);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Attaches a Visualizer to {@code session} and reports whether real samples
     * arrive. Scaling is forced to AS_PLAYED: the default normalising mode
     * amplifies silence into plausible-looking noise and would fake a pass.
     */
    private void probeVisualizer(int session, String label, boolean dumpBands) {
        Visualizer visualizer = null;
        try {
            visualizer = new Visualizer(session);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range[1];
            visualizer.setCaptureSize(captureSize);
            visualizer.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            visualizer.setEnabled(true);

            byte[] waveform = new byte[captureSize];
            byte[] fft = new byte[captureSize];
            int samplingRateMilliHz = visualizer.getSamplingRate();

            int maxWaveDeviation = 0;
            double maxBinMagnitude = 0d;
            int dominantBin = -1;
            int okReads = 0;
            int failedReads = 0;

            for (int round = 0; round < SAMPLE_ROUNDS; round++) {
                if (visualizer.getWaveForm(waveform) == Visualizer.SUCCESS) {
                    okReads++;
                    for (byte sample : waveform) {
                        // Waveform bytes are unsigned 8-bit PCM centred on 128.
                        int deviation = Math.abs((sample & 0xFF) - 128);
                        if (deviation > maxWaveDeviation) {
                            maxWaveDeviation = deviation;
                        }
                    }
                } else {
                    failedReads++;
                }
                if (visualizer.getFft(fft) == Visualizer.SUCCESS) {
                    for (int bin = 1; bin < captureSize / 2; bin++) {
                        double real = fft[2 * bin];
                        double imaginary = fft[2 * bin + 1];
                        double magnitude = Math.hypot(real, imaginary);
                        if (magnitude > maxBinMagnitude) {
                            maxBinMagnitude = magnitude;
                            dominantBin = bin;
                        }
                    }
                    if (dumpBands && round % 4 == 0) {
                        logBands(fft, captureSize, round);
                    }
                }
                sleep(SAMPLE_INTERVAL_MS);
            }

            String peakRms = "n/a";
            try {
                Visualizer.MeasurementPeakRms measurement = new Visualizer.MeasurementPeakRms();
                if (visualizer.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS) {
                    peakRms = "peak=" + (measurement.mPeak / 100d) + "dB rms="
                            + (measurement.mRms / 100d) + "dB";
                }
            } catch (Throwable t) {
                peakRms = "error:" + t.getClass().getSimpleName();
            }

            double dominantHz = dominantBin < 0 ? -1d
                    : dominantBin * (samplingRateMilliHz / 1000d) / captureSize;
            boolean sawSignal = maxWaveDeviation > 2 || maxBinMagnitude > 2d;

            Log.i(TAG, "RESULT visualizer[" + label + "] created=true"
                    + " signal=" + sawSignal
                    + " maxWaveDeviation=" + maxWaveDeviation
                    + " maxBinMagnitude=" + String.format("%.1f", maxBinMagnitude)
                    + " dominantHz=" + String.format("%.0f", dominantHz)
                    + " reads=" + okReads + "/" + (okReads + failedReads)
                    + " rate=" + (samplingRateMilliHz / 1000) + "Hz"
                    + " captureSize=" + captureSize
                    + " " + peakRms);
        } catch (Throwable t) {
            Log.w(TAG, "RESULT visualizer[" + label + "] created=false"
                    + " error=" + t.getClass().getName() + ": " + t.getMessage());
        } finally {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                    visualizer.release();
                } catch (Throwable ignored) {
                    // Releasing a half-built effect is best effort.
                }
            }
        }
    }

    /**
     * Collapses the raw FFT into log-spaced bands and draws them as text bars.
     * This is the shape a spectrum analyser would actually render, so the dump
     * shows whether the bands carry independent movement or just track loudness
     * together.
     */
    private void logBands(byte[] fft, int captureSize, int round) {
        double binWidthHz = (double) CALIBRATED_RATE_HZ / captureSize;
        StringBuilder line = new StringBuilder("BANDS ");
        line.append(String.format("%02d", round)).append(' ');
        for (int band = 0; band < BAND_COUNT; band++) {
            double lowHz = BAND_MIN_HZ
                    * Math.pow(BAND_MAX_HZ / BAND_MIN_HZ, (double) band / BAND_COUNT);
            double highHz = BAND_MIN_HZ
                    * Math.pow(BAND_MAX_HZ / BAND_MIN_HZ, (double) (band + 1) / BAND_COUNT);
            int lowBin = Math.max(1, (int) Math.floor(lowHz / binWidthHz));
            int highBin = Math.min(captureSize / 2 - 1, (int) Math.ceil(highHz / binWidthHz));

            double sum = 0d;
            int counted = 0;
            for (int bin = lowBin; bin <= highBin; bin++) {
                double real = fft[2 * bin];
                double imaginary = fft[2 * bin + 1];
                sum += Math.hypot(real, imaginary);
                counted++;
            }
            double mean = counted == 0 ? 0d : sum / counted;
            // 0..9 on a dB scale; a linear scale leaves every band but the bass flat.
            double db = mean <= 0d ? -60d : 20d * Math.log10(mean / 128d);
            int level = (int) Math.round(Math.max(0d, Math.min(9d, (db + 60d) / 6d)));
            line.append(level);
        }
        Log.i(TAG, line.toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A 440 Hz reference tone on the media path. Only useful as a positive
     * control: a Visualizer that sees this but not another app's music is
     * restricted to its own process and no use to us.
     */
    private static final class ToneSource {
        private AudioTrack track;

        void start() {
            int rate = 48000;
            short[] samples = new short[rate];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(samples, 0, samples.length);
            track.setLoopPoints(0, samples.length, -1);
            track.play();
            Log.i(TAG, "reference tone 440Hz playing");
        }

        void stop() {
            if (track == null) {
                return;
            }
            try {
                track.stop();
                track.release();
            } catch (Throwable ignored) {
                // Best effort.
            }
            Log.i(TAG, "reference tone stopped");
        }
    }
}
