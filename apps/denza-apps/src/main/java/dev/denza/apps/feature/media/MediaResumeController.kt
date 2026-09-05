package dev.denza.apps.feature.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import dev.denza.apps.feature.hud.YandexNotificationArtworkListener

/**
 * Direct play/pause for the last session observed actually playing.
 *
 * The caller decides whether a new DOWN is safe to intercept. Once accepted, repeats and UP for
 * that press remain consumed even if the caller's guard changes before release.
 */
class MediaResumeController(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val accessComponent =
        ComponentName(app, YandexNotificationArtworkListener::class.java)
    private val core = MediaResumeCore()
    private val keyInterceptor = MediaResumeKeyInterceptor()
    private val sessions = LinkedHashMap<MediaSession.Token, AndroidTarget>()
    private var listening = false

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener(::reconcile)

    fun start() {
        if (listening || manager == null) return
        runCatching {
            manager.addOnActiveSessionsChangedListener(
                sessionsChanged,
                accessComponent,
                handler,
            )
            listening = true
            reconcile(manager.getActiveSessions(accessComponent))
        }.onFailure { error ->
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
            listening = false
            detachAll()
            Log.i(TAG, "media-session access unavailable", error)
        }
    }

    fun stop() {
        if (listening) {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        listening = false
        detachAll()
        keyInterceptor.reset()
    }

    @JvmOverloads
    fun onKeyEvent(event: KeyEvent, allowNewPress: Boolean = true): Boolean =
        keyInterceptor.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            allowNewPress = allowNewPress && listening,
            perform = core::perform,
        )

    private fun reconcile(active: List<MediaController>?) {
        val current = active.orEmpty().associateBy { it.sessionToken }
        sessions.keys.filterNot(current::containsKey).forEach(::remove)
        current.forEach { (token, controller) ->
            if (token !in sessions) {
                sessions[token] = AndroidTarget(controller).also(AndroidTarget::attach)
            }
        }
        core.reconcile(current.keys.mapNotNull(sessions::get))
    }

    private fun remove(token: MediaSession.Token) {
        sessions.remove(token)?.detach()
        core.remove(token)
    }

    private fun detachAll() {
        sessions.values.forEach(AndroidTarget::detach)
        sessions.clear()
        core.clear()
    }

    private inner class AndroidTarget(
        private val controller: MediaController,
    ) : MediaResumeTarget {
        override val identity: Any = controller.sessionToken

        private val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    core.onPlaying(identity)
                }
            }

            override fun onSessionDestroyed() {
                val token = controller.sessionToken
                remove(token)
                refresh()
            }
        }

        fun attach() {
            controller.registerCallback(callback, handler)
        }

        fun detach() {
            runCatching { controller.unregisterCallback(callback) }
        }

        override fun playback(): MediaResumePlayback = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> MediaResumePlayback.PLAYING
            PlaybackState.STATE_PAUSED -> MediaResumePlayback.PAUSED
            else -> MediaResumePlayback.OTHER
        }

        override fun play() {
            controller.transportControls.play()
        }

        override fun pause() {
            controller.transportControls.pause()
        }
    }

    private fun refresh() {
        val service = manager ?: return
        runCatching { reconcile(service.getActiveSessions(accessComponent)) }
            .onFailure { Log.i(TAG, "could not refresh media sessions", it) }
    }

    private companion object {
        const val TAG = "DenzaMediaResume"
    }
}
