package dev.denza.apps.feature.hud

import android.content.Context
import dev.denza.apps.platform.media.MediaSessionAccess

/**
 * The HUD's own question about the shared notification listener: only while guidance is on.
 *
 * The listener and its repair belong to [MediaSessionAccess], because the wheel key, the speakers
 * and the strip read media sessions through the same grant. The HUD asks for it only when it has a
 * use for the turn arrows it reads from Yandex's notification.
 */
object HudNotificationAccess {
    fun ensure(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (
            !YANDEX_NOTIFICATION_ARTWORK_ENABLED ||
            !HudGuidanceSettings.isEnabled(app)
        ) {
            MediaSessionAccess.notWanted(onComplete)
            return
        }
        MediaSessionAccess.ensure(app, onComplete)
    }
}
