package dev.denza.apps.feature.navigation

import android.content.Context
import dev.denza.apps.SimulcastCoordinator

data class SteeringWheelNavigationAccess(
    val desired: Boolean,
    val serviceEnabled: Boolean,
    val serviceConnected: Boolean,
) {
    val ready: Boolean = desired && serviceEnabled && serviceConnected
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
            serviceEnabled = SimulcastCoordinator.isAccessibilityEnabled(context),
            serviceConnected = SimulcastCoordinator.isAccessibilityConnected(),
        )

    fun reconcile(context: Context, onComplete: (Throwable?) -> Unit) {
        if (!SteeringWheelNavigationAccessPolicy.shouldRepair(inspect(context))) {
            onComplete(null)
            return
        }
        SimulcastCoordinator.repairAccess(context, onComplete)
    }

    fun isRepairing(): Boolean = SimulcastCoordinator.isAccessibilityRepairRunning()
}
