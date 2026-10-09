package dev.denza.apps.platform.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler

/**
 * [MediaSessionSource] on the car: `MediaSessionManager`, asked in the name of the listener
 * [MediaSessionAccess] keeps enabled, with every callback on [handler].
 */
internal class AndroidMediaSessionSource(
    context: Context,
    private val handler: Handler,
) : MediaSessionSource {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = MediaSessionAccess.component(context)
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun listen(onChanged: (List<MediaSessionHandle>?) -> Unit) {
        val service = checkNotNull(manager) { "MediaSessionManager unavailable" }
        val next = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onChanged(controllers?.map(::ControllerHandle))
        }
        // Kept before it is added, so a refused add can still be removed by [unlisten].
        listener = next
        service.addOnActiveSessionsChangedListener(next, component, handler)
    }

    override fun unlisten() {
        val current = listener ?: return
        listener = null
        manager?.removeOnActiveSessionsChangedListener(current)
    }

    override fun activeSessions(): List<MediaSessionHandle> =
        checkNotNull(manager) { "MediaSessionManager unavailable" }
            .getActiveSessions(component)
            .map(::ControllerHandle)

    private inner class ControllerHandle(
        private val controller: MediaController,
    ) : MediaSessionHandle {
        private var callback: MediaController.Callback? = null

        override val token: Any = controller.sessionToken

        /**
         * Read only when the hub asks: the controller caches it after one binder call. Null when that
         * call finds the session's process gone - this firmware's `MediaController.getPackageName`
         * answers null rather than throwing.
         */
        override val packageName: String?
            get() = controller.packageName

        override fun register(events: MediaSessionEvents): Boolean {
            val next = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    events.onPlaybackState(state?.state)

                override fun onMetadataChanged(metadata: MediaMetadata?) = events.onMetadata()

                override fun onSessionDestroyed() = events.onDestroyed()
            }
            val registered = runCatching { controller.registerCallback(next, handler) }.isSuccess
            if (registered) callback = next
            return registered
        }

        override fun unregister() {
            val current = callback ?: return
            callback = null
            runCatching { controller.unregisterCallback(current) }
        }

        override fun playbackState(): Int? = controller.playbackState?.state

        override fun actions(): Long? = controller.playbackState?.actions

        override fun track(): MediaTrack? = controller.metadata?.let { metadata ->
            MediaTrack(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            )
        }

        override fun play() = controller.transportControls.play()

        override fun pause() = controller.transportControls.pause()
    }
}
