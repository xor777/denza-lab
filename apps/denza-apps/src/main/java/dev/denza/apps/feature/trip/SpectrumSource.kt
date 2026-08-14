package dev.denza.apps.feature.trip

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Feeds the panel's analyser from whatever the car is playing.
 *
 * Attaches a [Visualizer] to audio session 0 — the global output mix — so the
 * source is whatever holds the speakers: a media app, or the Bluetooth A2DP sink
 * fronted by `com.byd.mediacenter`. Nothing here is tied to a particular player.
 * The path was verified on the live car; `AudioPlaybackCapture` was tried first
 * and returns silence on this head unit. See docs/audio-capture-findings.md.
 *
 * Capture runs on the Visualizer's own callback thread and hands the renderer a
 * copied snapshot, so the draw path never blocks on audio.
 */
class SpectrumSource {

    private val lock = Any()
    private val shared = DoubleArray(BAND_COUNT)
    private val scratch = DoubleArray(BAND_COUNT)

    private var visualizer: Visualizer? = null
    private var bandMap: SpectrumBandMap? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastCaptureUptimeMs = 0L

    /** True once the effect is attached and delivering. */
    @Volatile
    var attached: Boolean = false
        private set

    /** Why the analyser has no audio, for the panel's quiet fallback caption. */
    @Volatile
    var lastFailure: String? = null
        private set

    private var running = false

    /** Band centre frequencies for the axis; null until attached. */
    val centreHz: DoubleArray?
        get() = bandMap?.centreHz

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        if (TripAudioAccessCoordinator.isGranted(app)) {
            attach()
            return
        }
        // Same self-heal as the panel's location access: grant over the local ADB
        // channel rather than putting a microphone dialog in front of the driver
        // for a feature that never opens the microphone.
        TripAudioAccessCoordinator.ensureAccess(app) { granted ->
            handler.post {
                if (!running) return@post
                if (granted) {
                    attach()
                } else {
                    lastFailure = "нет разрешения на аудио"
                }
            }
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(watchdog)
        releaseEffect()
    }

    /**
     * Keeps the capture alive for as long as the panel is up.
     *
     * Two failures were possible and both left the analyser dead until the app
     * was restarted. Creating the effect can fail for reasons that pass — the
     * audio server busy, another client still holding session 0 as it shuts
     * down, the permission landing a moment later — and the old code set
     * `running` before that attempt, so nothing ever tried again. And an effect
     * that attaches can go quiet without erroring, which looks identical to
     * silence. So: retry while unattached, and rebuild if an attached capture
     * stops delivering.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (!attached) {
                attach()
            } else if (SystemClock.uptimeMillis() - lastCaptureUptimeMs > REVIVE_AFTER_MS) {
                Log.w(TAG, "capture went quiet, rebuilding the effect")
                releaseEffect()
                attach()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun releaseEffect() {
        attached = false
        val current = visualizer ?: return
        visualizer = null
        runCatching {
            current.setDataCaptureListener(null, Visualizer.getMaxCaptureRate(), false, false)
            current.enabled = false
            current.release()
        }
    }

    /**
     * Copies the most recent band magnitudes into [out].
     *
     * @return false when the capture is not attached or has gone stale, so the
     *   caller can settle the display instead of freezing it on the last frame.
     */
    fun snapshot(out: DoubleArray): Boolean {
        if (!attached) return false
        if (SystemClock.uptimeMillis() - lastCaptureUptimeMs > STALE_AFTER_MS) return false
        synchronized(lock) { shared.copyInto(out) }
        return true
    }

    private fun attach() {
        if (!running || visualizer != null) return
        val outcome = runCatching {
            val effect = Visualizer(GLOBAL_OUTPUT_MIX_SESSION)
            // The session 0 effect is shared across the device. If anything else
            // already holds it — another visualiser, or a probe that did not
            // release cleanly — it comes back already enabled, and configuring an
            // enabled effect throws IllegalStateException("wrong state: 2").
            // The analyser would then silently show nothing at all, which is
            // exactly how a working capture path can look broken.
            runCatching { effect.enabled = false }
            val captureSize = Visualizer.getCaptureSizeRange()[1]
            effect.captureSize = captureSize
            // AS_PLAYED: the default normalising mode scales silence up into
            // convincing noise, which would leave the bars dancing to nothing.
            effect.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            val map = SpectrumBandMap(
                bandCount = BAND_COUNT,
                captureSize = captureSize,
                sampleRateHz = CALIBRATED_RATE_HZ,
                minHz = MIN_HZ,
                maxHz = MAX_HZ,
            )
            effect.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft == null) return
                        map.magnitudes(fft, scratch)
                        synchronized(lock) { scratch.copyInto(shared) }
                        lastCaptureUptimeMs = SystemClock.uptimeMillis()
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            effect.enabled = true
            Log.i(
                TAG,
                "attached captureSize=" + captureSize +
                    " reportedRate=" + effect.samplingRate +
                    " enabled=" + effect.enabled +
                    " scaling=" + effect.scalingMode,
            )
            bandMap = map
            effect
        }
        val effect = outcome.getOrNull()
        if (effect == null) {
            // Constructing the effect without RECORD_AUDIO fails with error -3.
            lastFailure = outcome.exceptionOrNull()?.message ?: "аудио недоступно"
            Log.w(TAG, "attach failed", outcome.exceptionOrNull())
            return
        }
        visualizer = effect
        lastFailure = null
        lastCaptureUptimeMs = SystemClock.uptimeMillis()
        attached = true
    }

    companion object {
        private const val TAG = "TripSpectrum"

        const val BAND_COUNT = 48

        /** Audio session 0: the whole output mix rather than one app's track. */
        const val GLOBAL_OUTPUT_MIX_SESSION = 0

        /**
         * `getSamplingRate()` reports 44100 here but the mix runs at 48 kHz;
         * trusting the reported value skews every band by about 9%.
         */
        const val CALIBRATED_RATE_HZ = 48000

        /**
         * The lowest bin the FFT actually resolves is bin 1 at 46.875 Hz, and
         * everything below it already lives inside that bin. Starting the scale
         * under it bought no extra bass — it only produced several bands whose
         * centres all clamped onto bin 1 and drew as identical bars.
         */
        const val MIN_HZ = 45.0
        const val MAX_HZ = 14000.0
        const val STALE_AFTER_MS = 900L
        const val WATCHDOG_INTERVAL_MS = 2_000L
        const val REVIVE_AFTER_MS = 4_000L
    }
}
