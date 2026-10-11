package dev.denza.apps

import android.content.Context
import dev.denza.apps.core.RuntimeRecoveryRider
import dev.denza.apps.feature.adb.WifiDebuggingDialogRider
import dev.denza.apps.feature.alice.AliceVoiceKeyRider
import dev.denza.apps.feature.hud.HudGuidanceRider
import dev.denza.apps.feature.media.MediaKeyRider
import dev.denza.apps.feature.navigation.SteeringWheelKeyRider
import dev.denza.apps.feature.simulcast.SimulcastOverlayRider
import dev.denza.apps.feature.speaker.SpeakerForegroundRider
import dev.denza.apps.feature.weather.NativeWeatherRider
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * Every feature that rides on [SimulcastAccessibilityService], in the one order the service hands
 * them its connect, events, keys and going (`RiderDispatch`).
 *
 * The order is the one the service's own body had: the events went to the wireless-debugging
 * dialog, the speakers, the weather, HUD guidance and the projection's overlay, in that order; a
 * key is offered to Play/Pause, ★ and then the Navigator voice key, each owning a different code.
 * `DenzaAccessibilityRidersTest` holds this list to that.
 */
object DenzaAccessibilityRiders {
    /** A fresh set for one service instance: each rider keeps that instance's state. */
    fun create(): List<AccessibilityRider> = listOf(
        WifiDebuggingDialogRider(),
        SpeakerForegroundRider(),
        NativeWeatherRider(),
        HudGuidanceRider(),
        MediaKeyRider(),
        SteeringWheelKeyRider { context: Context ->
            DenzaAppRepository.performNavigationActionFromSteeringWheel(context)
        },
        AliceVoiceKeyRider(),
        SimulcastOverlayRider(),
        RuntimeRecoveryRider(),
    )
}
