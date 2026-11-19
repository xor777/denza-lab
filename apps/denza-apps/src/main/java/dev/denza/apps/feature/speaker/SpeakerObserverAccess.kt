package dev.denza.apps.feature.speaker

import dev.denza.apps.platform.accessibility.AccessibilityHealth

/**
 * The eager list hears a player come to the front through the app's shared accessibility service.
 * As the watcher starts, that service is repaired unless it is ready - switched on and bound - by
 * the one check every rider makes ([AccessibilityHealth.ready]).
 *
 * Until 2026-10-09 the speakers asked only whether it was switched on, and so never repaired one
 * that was on but not bound: crashed, or not back after an update.
 */
internal object SpeakerObserverAccess {
    fun ensure(health: AccessibilityHealth, repair: () -> Unit) {
        if (!health.ready()) repair()
    }
}

/**
 * The start commands of a switched-on watcher. The first begins watching; every one, the first
 * included, looks at access again ([checkAccess] is told which it is).
 *
 * Until 2026-11-19 a start found the watcher already watching and returned, so the tile's «Нет
 * доступа» press - which switches the feature on again, a start command to the running service -
 * never reached the repair: one failed repair stayed failed until the service was recreated.
 */
internal class SpeakerWatchStarts(
    private val beginWatching: () -> Unit,
    private val checkAccess: (firstStart: Boolean) -> Unit,
) {
    var watching = false
        private set

    fun onStart() {
        val first = !watching
        if (first) {
            watching = true
            beginWatching()
        }
        checkAccess(first)
    }
}
