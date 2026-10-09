package dev.denza.apps.feature.simulcast

/**
 * What [SimulcastOverlayRider] needs of the overlay over DiShare's dialog: [SimulcastDialogOverlay]
 * on the car, a fake in a test of the rider's gate.
 */
interface DialogOverlay {
    /** Nothing of the overlay's on screen or to finish (see [SimulcastDialogOverlay.isIdle]). */
    fun isIdle(): Boolean

    /** Look at the windows again, shortly, on the main thread. */
    fun scheduleRefresh()

    /** The service is going: every window of the overlay goes, the exit control comes back. */
    fun detach()
}
