package dev.denza.apps.feature.media

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the steering-wheel Play/Pause key did, for a car nobody can attach a debugger to.
 *
 * The filter's only trace today is `Log.i` under `DenzaMediaResume`, and this firmware ships a
 * global `log.tag=M` that swallows it; an owner without host ADB cannot read logcat at all. Every
 * press the filter refuses is handed back to stock routing, whose Play fallback opens the stock
 * local player - the exact symptom reported from the N9 - and from outside that is indistinguishable
 * from a button we never see. This records the decision, nothing more: no state here is read back
 * by the filter, so the key behaves the same whether or not anyone opens the support report.
 *
 * Free of `android.*` on purpose, so the ring and its rendering are unit-testable like
 * [MediaResumeCore].
 */

/** Why [MediaButtonEnvironment] refused a new press, or that it refused nothing. */
enum class MediaKeyGuard(val label: String) {
    ALLOWED("allowed"),
    AUDIO_MODE("audio-mode"),
    STREAM_MUTE("stream-mute"),
    VENDOR_MUTE("vendor-mute"),
    IN_CALL("in-call"),

    /** A guard read threw. Stock keeps the press, and we could not say which guard would have. */
    UNAVAILABLE("unavailable"),
}

/** What the key filter itself is doing, in the words the support report prints. */
enum class MediaKeyState(val label: String) {
    LISTENING("слушает"),
    NO_SESSION_ACCESS("нет доступа к сессиям"),
    SERVICE_ABSENT("сервис не подключён"),
}

/**
 * One initial DOWN, or one ending of a deferred pause.
 *
 * [keyCode] is null for the latter: a pause that completes after its press is over has no key of
 * its own, and naming the press's code there would be a guess.
 */
data class MediaKeyPress(
    val atEpochMillis: Long,
    val keyCode: Int?,
    /** True when we took it: the press was consumed, or a transport command went out. */
    val handled: Boolean,
    val detail: String,
)

data class MediaKeySnapshot(
    val state: MediaKeyState,
    val rememberedPackage: String?,
    val presses: List<MediaKeyPress>,
)

/** Bounded, oldest first, and small enough that the whole of it fits on one line. */
internal class MediaKeyRing(private val capacity: Int) {
    private val entries = ArrayList<MediaKeyPress>(capacity)

    @Synchronized
    fun add(entry: MediaKeyPress) {
        entries += entry
        while (entries.size > capacity) entries.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<MediaKeyPress> = ArrayList(entries)

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

/** Turns the decision points' own vocabulary into the one word an entry carries. */
internal object MediaKeyDetail {
    /** A code we never intercept - the N9's wheel may well emit one - reaching our filter. */
    const val NOT_MEDIA = "not-media"

    /** Refused before any guard could be read; only [MediaButtonEnvironment] sets a guard. */
    const val NOT_ALLOWED = "not-allowed"

    /** The controller is not attached to the session service, so no press can be taken. */
    const val NOT_LISTENING = "not-listening"

    /** A second DOWN while the first is unreleased: the interceptor never reaches a command. */
    const val ALREADY_DOWN = "already-down"

    fun press(
        pending: String?,
        guard: MediaKeyGuard?,
        media: Boolean,
        allowed: Boolean,
        listening: Boolean,
    ): String {
        if (!media) return NOT_MEDIA
        if (!allowed) return guard?.takeIf { it != MediaKeyGuard.ALLOWED }?.label ?: NOT_ALLOWED
        if (!listening) return NOT_LISTENING
        return pending ?: ALREADY_DOWN
    }
}

/** The three lines the support report prints, in its own terse `Ключ=значение` style. */
object MediaKeyReport {
    private const val NOTHING = "нет"

    fun lines(
        snapshot: MediaKeySnapshot,
        stamp: (Long) -> String = ::wallClock,
    ): List<String> = listOf(
        "Кнопка play/pause=${snapshot.state.label}",
        "Запомненная сессия=${snapshot.rememberedPackage ?: NOTHING}",
        "Последние нажатия=${presses(snapshot.presses, stamp)}",
    )

    fun presses(entries: List<MediaKeyPress>, stamp: (Long) -> String = ::wallClock): String {
        if (entries.isEmpty()) return NOTHING
        return entries.joinToString("; ") { entry ->
            buildString {
                append(stamp(entry.atEpochMillis))
                entry.keyCode?.let { append(' ').append(it) }
                append(' ').append(if (entry.handled) "✓" else "✗")
                append(' ').append(entry.detail)
            }
        }
    }

    /** The car's own clock. A date would cost a third of the line and answer nothing. */
    fun wallClock(epochMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMillis))
}

/**
 * The process-wide record. The accessibility service has no `android:process` of its own, so the
 * filter and the support report share this object.
 *
 * `note*` and `record*` always run in that order on the main looper - every decision point below is
 * an accessibility callback or a post to the service's main-looper handler - so a note cannot land
 * on a press other than the one it was written for. The report reads from any thread, hence the
 * synchronized ring and the copy that leaves it.
 */
object MediaKeyDiagnostics {
    /** Twelve is what fits on one readable line and still spans a driver's whole complaint. */
    const val CAPACITY = 12

    private val ring = MediaKeyRing(CAPACITY)
    private val lock = Any()
    private var pendingDetail: String? = null
    private var pendingGuard: MediaKeyGuard? = null

    /** The outcome the controller reached, in the vocabulary it already logs. */
    @JvmStatic
    fun note(detail: String) {
        synchronized(lock) { pendingDetail = detail }
    }

    /** Which guard answered, so a refusal is a reason rather than a bare false. */
    @JvmStatic
    fun noteGuard(guard: MediaKeyGuard) {
        synchronized(lock) { pendingGuard = guard }
    }

    /** Every initial DOWN of every code that reaches the filter, media or not. */
    @JvmStatic
    fun recordPress(
        keyCode: Int,
        media: Boolean,
        allowed: Boolean,
        listening: Boolean,
        consumed: Boolean,
    ) {
        val detail = synchronized(lock) {
            MediaKeyDetail.press(pendingDetail, pendingGuard, media, allowed, listening)
                .also { pendingDetail = null; pendingGuard = null }
        }
        ring.add(MediaKeyPress(System.currentTimeMillis(), keyCode, consumed, detail))
    }

    /**
     * A decision reached after its press is over - a deferred pause completing, a reconnect ending.
     * [handled] is true when a transport command or a directed media button actually went out.
     */
    @JvmStatic
    fun recordCompletion(detail: String, handled: Boolean) {
        synchronized(lock) {
            pendingDetail = null
            pendingGuard = null
        }
        ring.add(MediaKeyPress(System.currentTimeMillis(), null, handled, detail))
    }

    /**
     * [listening] is the controller's own flag and [rememberedPackage] the package behind the
     * token the core remembers; null in either means no bound service owns a controller.
     */
    @JvmStatic
    fun snapshot(listening: Boolean?, rememberedPackage: String?): MediaKeySnapshot =
        MediaKeySnapshot(
            state = when (listening) {
                null -> MediaKeyState.SERVICE_ABSENT
                true -> MediaKeyState.LISTENING
                false -> MediaKeyState.NO_SESSION_ACCESS
            },
            rememberedPackage = rememberedPackage,
            presses = ring.snapshot(),
        )

    internal fun clearForTest() {
        ring.clear()
        synchronized(lock) {
            pendingDetail = null
            pendingGuard = null
        }
    }
}
