package dev.denza.apps.feature.cluster

/**
 * When each layer's diagnostic panels come down, one timer per layer.
 *
 * `ClusterSceneService` runs unrelated things on one main-thread handler: these timers and its own
 * teardown (a queued first-frame notification was a third until 2026-10-09). Showing a
 * camera or a preview used to clear that handler whole. A screen picked in the service panel puts
 * opaque LEFT/RIGHT panels on the *base* layer, over the Contour, for 2.2 seconds; a turn signal
 * inside that window took the base layer's hide with it, and nothing else ever hides the base
 * layer's panels - the camera layer hides its own diagnostic when the camera starts, the base
 * layer has no such path. The panels stayed over the instruments until the display changed or the
 * process died.
 *
 * So a timer belongs to its layer: scheduling one replaces that layer's earlier timer, cancelling
 * one removes only it, and nothing here can reach anything else on the handler.
 */
internal class DiagnosticHideTimers(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var base: Runnable? = null
    private var camera: Runnable? = null

    /** Hide [cameraLayer]'s diagnostic after [delayMs], replacing that layer's earlier timer. */
    fun schedule(cameraLayer: Boolean, delayMs: Long, hide: () -> Unit) {
        cancel(cameraLayer)
        lateinit var task: Runnable
        task = Runnable {
            if (pending(cameraLayer) === task) set(cameraLayer, null)
            hide()
        }
        set(cameraLayer, task)
        postDelayed(task, delayMs)
    }

    /** Drop [cameraLayer]'s pending hide, if it has one, and nothing else. */
    fun cancel(cameraLayer: Boolean) {
        val task = pending(cameraLayer) ?: return
        set(cameraLayer, null)
        remove(task)
    }

    private fun pending(cameraLayer: Boolean): Runnable? = if (cameraLayer) camera else base

    private fun set(cameraLayer: Boolean, task: Runnable?) {
        if (cameraLayer) camera = task else base = task
    }
}
