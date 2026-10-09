package dev.denza.apps.core

import android.accessibilityservice.AccessibilityService
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.StateMarks
import dev.denza.apps.platform.accessibility.AccessibilityRider

/**
 * What the app as a whole does as its shared accessibility service connects and goes; last of the
 * riders, after every feature has attached or let go.
 *
 * Connected: everything that reads the service is read again - the projection, HUD guidance, the
 * wheel button ([StateMarks.accessibilityChanged]) - and the enabled runtimes are recovered: the
 * system can recreate this long-lived process without reopening MainActivity (notably after an APK
 * replacement), and a persisted split toggle must never stay visually on while its router is
 * absent. Gone: the same reads, and the ★ switch asks for the service back.
 */
class RuntimeRecoveryRider : AccessibilityRider {
    override val name: String = "runtime-recovery"

    override fun onConnected(service: AccessibilityService) {
        StateMarks.accessibilityChanged("a11y connected")
        DenzaRuntimeCoordinator.recover(service)
    }

    override fun onDisconnected(service: AccessibilityService) {
        StateMarks.accessibilityChanged("a11y gone")
        DenzaAppRepository.recoverNavigationSteeringWheelAccess(service)
    }
}
