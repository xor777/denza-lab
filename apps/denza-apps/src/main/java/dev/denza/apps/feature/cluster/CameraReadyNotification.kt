package dev.denza.apps.feature.cluster

/** Cosmetic Binder work runs after the first-frame callback, never on the camera startup path. */
internal class CameraReadyNotification(
    private val post: (() -> Unit) -> Unit,
    private val isCurrentReady: (Long) -> Boolean,
    private val onFailure: (RuntimeException) -> Unit,
) {
    fun afterFirstFrame(generation: Long, publish: () -> Unit) {
        post {
            // Hide, a newer Show, failure or service destruction can supersede this queued work.
            if (isCurrentReady(generation)) {
                try {
                    publish()
                } catch (error: RuntimeException) {
                    // A failed notification is not a failed video session.
                    onFailure(error)
                }
            }
        }
    }
}
