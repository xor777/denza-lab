package dev.denza.apps.feature.media

/** Package identity used by the privileged focus helper without exposing a MediaSession token. */
data class MediaPauseSession(
    val packageName: String,
    val uid: Int,
)

/** The playing session and the paused, previously-playing sessions that may resume behind it. */
data class MediaPausePreparationRequest(
    val current: MediaPauseSession,
    val predecessors: List<MediaPauseSession>,
)

fun interface MediaPausePreparationCompletion {
    fun onComplete(success: Boolean)
}

/**
 * Starts the bounded asynchronous work needed before pausing a session with suspended predecessors.
 *
 * Returning `true` means the request was accepted and [completion] will be called once. Returning
 * `false` leaves the key press to the platform.
 */
interface MediaPausePreparation {
    fun isReady(): Boolean

    fun prepare(
        request: MediaPausePreparationRequest,
        completion: MediaPausePreparationCompletion,
    ): Boolean
}
