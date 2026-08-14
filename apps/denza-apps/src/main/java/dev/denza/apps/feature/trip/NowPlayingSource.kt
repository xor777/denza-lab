package dev.denza.apps.feature.trip

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.denza.apps.feature.hud.YandexNotificationArtworkListener

/**
 * What the car is playing, and the means to control it.
 *
 * Reads the active [android.media.session.MediaSession] through
 * [MediaSessionManager], which needs notification-listener access — the app
 * already holds it for the HUD's artwork listener, and that same component is
 * the token used here. Nothing new is requested or enabled: if the access is
 * not there, the panel simply has no track and the analyser takes the space
 * back.
 *
 * Works for whatever holds the session, which on this head unit means both a
 * media app such as Yandex Music and the Bluetooth sink fronted by
 * `com.byd.mediacenter`.
 *
 * Main-thread only, like the rest of the panel.
 */
class NowPlayingSource {

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var listenerComponent: ComponentName? = null
    private var running = false

    var title: String? = null
        private set

    var artist: String? = null
        private set

    var playing: Boolean = false
        private set

    /** True when there is a real track to show; drives the panel's layout. */
    val hasTrack: Boolean
        get() = !title.isNullOrBlank()

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> adopt(controllers) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = readMetadata()
        override fun onPlaybackStateChanged(state: PlaybackState?) = readState()
        override fun onSessionDestroyed() = refresh()
    }

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        val component = ComponentName(app, YandexNotificationArtworkListener::class.java)
        listenerComponent = component
        val service = app.getSystemService(MediaSessionManager::class.java) ?: return
        manager = service
        // Without notification-listener access this throws; the panel then just
        // runs without a track strip rather than failing.
        runCatching {
            service.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            adopt(service.getActiveSessions(component))
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        detach()
        manager = null
        title = null
        artist = null
        playing = false
    }

    fun toggle() {
        val transport = controller?.transportControls ?: return
        if (playing) transport.pause() else transport.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun refresh() {
        val service = manager ?: return
        val component = listenerComponent ?: return
        runCatching { adopt(service.getActiveSessions(component)) }
    }

    /**
     * Picks the session to follow: the one actually playing, else the highest
     * priority one, so a paused track still shows its title.
     */
    private fun adopt(controllers: List<MediaController>?) {
        if (!running) return
        val candidates = controllers.orEmpty()
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) {
            readMetadata()
            readState()
            return
        }
        detach()
        controller = chosen
        chosen?.registerCallback(callback, handler)
        readMetadata()
        readState()
    }

    private fun detach() {
        controller?.let { runCatching { it.unregisterCallback(callback) } }
        controller = null
    }

    private fun readMetadata() {
        val metadata = controller?.metadata
        title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
    }

    private fun readState() {
        playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
    }
}
