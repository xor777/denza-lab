package dev.denza.apps.feature.navigation

import android.content.Context
import dev.denza.apps.platform.accessibility.AccessibilityHealth
import dev.denza.apps.platform.accessibility.AccessibilityRepair

data class SteeringWheelNavigationAccess(
    val desired: Boolean,
    val service: AccessibilityHealth,
) {
    val ready: Boolean = desired && service.ready()
}

object SteeringWheelNavigationAccessPolicy {
    fun shouldRepair(access: SteeringWheelNavigationAccess): Boolean =
        access.desired && !access.ready
}

/** Keeps the persisted ★ toggle responsible for the shared accessibility service. */
object SteeringWheelNavigationAccessCoordinator {
    fun inspect(context: Context): SteeringWheelNavigationAccess =
        SteeringWheelNavigationAccess(
            desired = NavigationSettings.steeringWheelButtonEnabled(context),
            service = AccessibilityHealth.read(context),
        )

    fun reconcile(context: Context, onComplete: (Throwable?) -> Unit) {
        if (!SteeringWheelNavigationAccessPolicy.shouldRepair(inspect(context))) {
            onComplete(null)
            return
        }
        AccessibilityRepair.repair(context, onComplete)
    }

    fun isRepairing(): Boolean = AccessibilityRepair.isRunning()
}
