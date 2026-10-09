package dev.denza.apps.feature.media

/**
 * The one switch left of the wheel's play/pause experiments, kept so a build can say what it does.
 *
 * [INTERCEPT_KEYS] is the accessibility filter taking play/pause at all. It stays on; it exists so
 * that build can be made without touching anything else, if the question of 2026-09-18 - whether
 * consuming play/pause before the vendor's handler starves its idea of the current source - is ever
 * worth asking again. That day's first attempt to answer it proved nothing: the player was casting
 * to the owner's home speaker, which holds no focus and owns no track in the car and reads exactly
 * like a broken car. Any reading is only valid while the player plays through the car's speakers.
 *
 * Its sibling, `FOCUS_SURGERY` - a shell-UID helper that removed suspended predecessors' entries
 * from the car's audio-focus stack before a pause - was switched off on 2026-09-18 and taken out of
 * the product on 2026-10-09. It lives in `research/media-focus/`, whose README says what it did,
 * why it failed on the car and how to bring it back. A pause with suspended predecessors is an
 * ordinary pause, see [MediaResumeCore].
 */
object MediaKeyExperiment {
    /** The accessibility filter taking the wheel's play/pause key at all. */
    const val INTERCEPT_KEYS = true

    /** What the support report prints, so one screenshot says what this build does. */
    val label: String = if (INTERCEPT_KEYS) "без правки фокуса" else "перехват выключен"
}
