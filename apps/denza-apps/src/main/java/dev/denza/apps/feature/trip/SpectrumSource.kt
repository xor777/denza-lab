package dev.denza.apps.feature.trip

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.IdentityHashMap

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

    /** Почему у анализатора нет звука. Читается «Сервисом»; на панель не выводится (U5). */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Session 0 exposes one shared effect, so every in-process consumer must use
     * this owner set instead of constructing another Visualizer. The trip panel
     * and the speaker-cover foreground service can therefore overlap safely.
     */
    private val owners: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private val running: Boolean
        get() = owners.isNotEmpty()

    /** Band centre frequencies for the axis; null until attached. */
    val centreHz: DoubleArray?
        get() = bandMap?.centreHz

    fun start(context: Context, owner: Any = defaultOwner) {
        if (!owners.add(owner)) return
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

    fun stop(owner: Any = defaultOwner) {
        owners.remove(owner)
        if (running) return
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
        return snapshotAt(out) != null
    }

    /**
     * Copies a fresh frame and returns its capture time. Callers can ignore a
     * frame they already processed instead of treating repeated polls as more
     * observed audio.
     */
    fun snapshotAt(out: DoubleArray): Long? {
        if (!attached) return null
        val capturedAt = lastCaptureUptimeMs
        if (SystemClock.uptimeMillis() - capturedAt > STALE_AFTER_MS) return null
        synchronized(lock) { shared.copyInto(out) }
        return capturedAt
    }

    /**
     * Что анализатор может сказать о себе сервисному разделу.
     *
     * На центральной панели про это нет ни слова: после включения там либо столбики, либо покой,
     * и текст ошибки посреди экрана водителю не сообщение, а мусор (U5). Но «просто не работает»
     * без единого читаемого признака - это то, из-за чего диагноз 27.08.2026 пришлось ставить
     * дампами `media.audio_flinger`, поэтому состояние есть, и оно лежит в «Сервисе».
     */
    data class Diagnostics(
        val granted: Boolean,
        val running: Boolean,
        val attached: Boolean,
        /** Живое состояние самого эффекта, а не наше о нём мнение. */
        val effectEnabled: Boolean?,
        /** Сколько прошло с последнего кадра FFT; `null` - кадров не было вовсе. */
        val sinceLastFrameMs: Long?,
        val lastFailure: String?,
    )

    fun diagnostics(context: Context): Diagnostics {
        val captured = lastCaptureUptimeMs
        return Diagnostics(
            granted = TripAudioAccessCoordinator.isGranted(context.applicationContext),
            running = running,
            attached = attached,
            effectEnabled = visualizer?.let { effect -> runCatching { effect.enabled }.getOrNull() },
            sinceLastFrameMs = if (captured == 0L) null else SystemClock.uptimeMillis() - captured,
            lastFailure = lastFailure,
        )
    }

    private fun attach() {
        if (!running || visualizer != null) return
        val outcome = runCatching {
            val effect = Visualizer(GLOBAL_OUTPUT_MIX_SESSION)
            try {
                configure(effect)
            } catch (error: Throwable) {
                // Эффект уже создан, а значит, уже занял клиентский слот в цепочке сессии 0.
                // Бросить его нерасцепленным - это оставить в машине наш handle, который никто
                // больше не тронет, и на каждый повтор watchdog'а ещё один.
                runCatching { effect.release() }
                throw error
            }
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

    private fun configure(effect: Visualizer) {
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
        // Включение проверяется, а не предполагается.
        //
        // Эффект сессии 0 в машине один, и включить его может только тот клиент, который держит
        // управление; держит его тот, кто создал эффект первым. Живьём (27.08.2026, музыка играла
        // на −6…−15 dBFS) управление оказалось у `system_server`, эффект стоял выключенным, и наш
        // `enabled = true` возвращал отказ. Присваивание отказ не бросает и результата не отдаёт,
        // поэтому источник объявлял себя привязанным, отдавал пустоту, и снаружи это выглядело
        // просто как сломанный анализатор.
        val status = effect.setEnabled(true)
        check(status == Visualizer.SUCCESS && effect.enabled) {
            "эффект сессии 0 занят другим владельцем (setEnabled=$status)"
        }
        Log.i(
            TAG,
            "attached captureSize=" + captureSize +
                " reportedRate=" + effect.samplingRate +
                " enabled=" + effect.enabled +
                " scaling=" + effect.scalingMode,
        )
        bandMap = map
    }

    companion object {
        private const val TAG = "TripSpectrum"

        private val defaultOwner = Any()

        /**
         * Twenty-six columns, which is what the design board draws.
         *
         * It was forty-eight, and at the width this panel gets that is a bar about eleven pixels
         * wide - a picket fence rather than an analyser. Fewer and fatter also means each band
         * covers more of the spectrum, so a single instrument moves a column instead of
         * flickering between two.
         */
        const val BAND_COUNT = 26

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
