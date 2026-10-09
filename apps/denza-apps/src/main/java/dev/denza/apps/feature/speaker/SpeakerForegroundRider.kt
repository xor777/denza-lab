package dev.denza.apps.feature.speaker

import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * «Динамики» hears which app came to the front: a player the car does not speak for may raise the
 * covers on opening ([SpeakerCoverService.onForegroundPackage], which does nothing unless the
 * feature's service runs).
 */
class SpeakerForegroundRider : AccessibilityRider {
    override val name: String = "speaker-foreground"
    override val eventTypes: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    override fun onEvent(event: AccessibilityEvent) {
        val packageName = event.packageName ?: return
        SpeakerCoverService.onForegroundPackage(packageName.toString())
    }
}
