package dev.denza.apps.feature.speaker

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.denza.apps.feature.hud.YandexNotificationArtworkListener

/** Watches every active session; the trip strip deliberately follows only one. */
internal class SpeakerMediaSessionObserver(
    context: Context,
    private val onPlaying: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(app, YandexNotificationArtworkListener::class.java)
    private val controllers = LinkedHashMap<MediaSession.Token, ObservedController>()
    private var listening = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener(::reconcile)

    fun start() {
        if (listening || manager == null) return
        val result = runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
            listening = true
            reconcile(manager.getActiveSessions(listenerComponent))
        }
        if (result.isFailure) {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
            listening = false
            Log.i(TAG, "media-session access unavailable", result.exceptionOrNull())
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        if (listening) {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        listening = false
        controllers.values.forEach { it.detach() }
        controllers.clear()
    }

    private fun reconcile(active: List<MediaController>?) {
        val current = active.orEmpty().associateBy { it.sessionToken }
        val removed = controllers.keys.filterNot(current::containsKey)
        removed.forEach { token -> controllers.remove(token)?.detach() }
        current.forEach { (token, controller) ->
            if (token !in controllers) {
                controllers[token] = ObservedController(controller).also { it.attach() }
            } else {
                controllers[token]?.readState()
            }
        }
    }

    private inner class ObservedController(
        private val controller: MediaController,
    ) {
        private val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = readState(state)
            override fun onSessionDestroyed() = refresh()
        }

        fun attach() {
            controller.registerCallback(callback, handler)
            readState()
        }

        fun detach() {
            runCatching { controller.unregisterCallback(callback) }
        }

        fun readState(state: PlaybackState? = controller.playbackState) {
            if (state?.state == PlaybackState.STATE_PLAYING) {
                onPlaying(controller.packageName)
            }
        }
    }

    private fun refresh() {
        val service = manager ?: return
        runCatching { reconcile(service.getActiveSessions(listenerComponent)) }
    }

    private companion object {
        const val TAG = "DenzaSpeakerSessions"
    }
}
