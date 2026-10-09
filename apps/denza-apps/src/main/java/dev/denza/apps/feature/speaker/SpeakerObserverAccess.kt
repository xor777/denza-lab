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
