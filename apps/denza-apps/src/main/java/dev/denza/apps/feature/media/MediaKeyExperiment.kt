package dev.denza.apps.feature.media

/**
 * The two switches of the 2026-09-18 question: did we break the wheel's next and previous keys?
 *
 * Those keys were never ours. They pass the filter untouched and the firmware routes them, by the
 * corpus, to the controller of whichever package owns audio focus, and to `com.byd.mediacenter`
 * when there is none. On the car they now always reach the stock player, and the owner says they
 * worked before the media builds of 2026-09-05 and 2026-09-11. Two things we do could explain that,
 * and both of them are ours:
 *
 *  1. [FOCUS_SURGERY] - before pausing a player with suspended predecessors, a shell-UID helper
 *     removes those predecessors' audio-focus entries. The corpus says the removal notifies nobody,
 *     so the removed app keeps believing it holds focus and never asks again. What is left at the
 *     top of the stack is then the stock player, which is exactly where the stray keys land.
 *  2. [INTERCEPT_KEYS] - we consume play/pause before the vendor's own handler sees it. Whatever
 *     state that handler kept about the current source is no longer refreshed by those presses, so
 *     next and previous may be aimed at a target that stopped moving on 2026-09-05.
 *
 * Turning one switch off per build is the whole experiment, and the report line says which build is
 * on the car. [FOCUS_SURGERY] is off from build 49: on 2026-09-18 at 19:23:23 the helper ran, and
 * from 19:23:35 the vehicle's own `IviVehicleAudioBroker` began abandoning audio focus every 2.2
 * seconds, the focus stack stayed empty, and Yandex Music could hold a focus request for 39 ms and
 * never got an audio track - its session counted a track forward with no sound at all. That is the
 * suspect switched off while the question is open, not a proven verdict.
 */
object MediaKeyExperiment {
    /** The shell helper that rewrites the car's audio-focus stack before a deferred pause. */
    const val FOCUS_SURGERY = false

    /** The accessibility filter taking the wheel's play/pause key at all. */
    const val INTERCEPT_KEYS = true

    /** What the support report prints, so one screenshot says which rung the car is on. */
    val label: String = when {
        !INTERCEPT_KEYS -> "перехват выключен (ступень 2)"
        !FOCUS_SURGERY -> "без правки фокуса (ступень 1)"
        else -> "обычный"
    }
}
