package dev.denza.apps.feature.media

/**
 * Two things the wheel's play/pause key could do without, kept as switches so a build can say which
 * of them it does.
 *
 * [FOCUS_SURGERY] is off, by the owner's decision of 2026-09-18, and this is why. Before pausing a
 * player with suspended predecessors, a shell-UID helper used to remove those predecessors' entries
 * from the car's audio-focus stack, so that the player just paused would not hand focus back and
 * let its predecessor resume by itself. On the car it threw on six presses out of seven, held every
 * pause back by about 650 ms while it tried, and the one time it succeeded it emptied the focus
 * stack: the firmware routes the wheel's next and previous keys to whichever package owns audio
 * focus, and with nobody left it fell through to the stock player. That press - pause, then next,
 * then the stock player starting - was the fault the owner was reporting. The rule that replaces it
 * is one sentence: the key controls what is audible, and play brings back what was audible last.
 * A player paused while a video was playing may therefore resume when that video is paused, which
 * is the platform's own behaviour, and the owner took that over a stack edited behind the
 * firmware's back. A pause with suspended predecessors is now an ordinary pause, see
 * [MediaResumeCore]; the bridge and its helper stay in the tree until the second step, taking the
 * wheel's next and previous keys, decides whether anything of theirs is wanted.
 *
 * [INTERCEPT_KEYS] is the accessibility filter taking play/pause at all. It stays on; it exists so
 * that build can be made without touching anything else, if the question of 2026-09-18 - whether
 * consuming play/pause before the vendor's handler starves its idea of the current source - is ever
 * worth asking again. That day's first attempt to answer it proved nothing: the player was casting
 * to the owner's home speaker, which holds no focus and owns no track in the car and reads exactly
 * like a broken car. Any reading is only valid while the player plays through the car's speakers.
 */
object MediaKeyExperiment {
    /** The shell helper that rewrites the car's audio-focus stack before a deferred pause. */
    const val FOCUS_SURGERY = false

    /** The accessibility filter taking the wheel's play/pause key at all. */
    const val INTERCEPT_KEYS = true

    /** What the support report prints, so one screenshot says what this build does. */
    val label: String = when {
        !INTERCEPT_KEYS -> "перехват выключен"
        !FOCUS_SURGERY -> "без правки фокуса"
        else -> "с правкой фокуса"
    }
}
