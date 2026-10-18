package dev.denza.apps.feature.hud

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The HUD host's door for a source in another app (docs/hud-output-architecture.md, 5): the
 * YandexHUD module binds it from Navigator's process and publishes into this app's one compositor
 * ([HudHostOutput]). Exported without a permission: [HudHostGate] decides from the caller's
 * package and signature on every attach, and refuses anyone else.
 */
class HudHostService : Service() {
    override fun onBind(intent: Intent?): IBinder = HudHostOutput.get(this).endpoint()
}
