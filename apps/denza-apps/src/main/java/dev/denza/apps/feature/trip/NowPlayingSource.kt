package dev.denza.apps.feature.trip

import android.content.Context
import android.media.session.PlaybackState
import dev.denza.apps.platform.media.MediaSessionChange
import dev.denza.apps.platform.media.MediaSessionControls
import dev.denza.apps.platform.media.MediaSessionHub
import dev.denza.apps.platform.media.MediaSessionSubscriber
import dev.denza.apps.platform.media.MediaSessions

/**
 * What the car is playing: the title, the artist and whether it plays, for the strip to print.
 *
 * Reads the active media sessions through the process's [MediaSessionHub], which needs the app's
 * notification-listener access (`MediaSessionAccess`). Nothing new is requested or enabled here: if
 * the access is not there, the panel simply has no track and the analyser takes the space back.
 *
 * Works for whatever holds the session, which on this head unit means both a
 * media app such as Yandex Music and the Bluetooth sink fronted by
 * `com.byd.mediacenter`.
 *
 * Follows one session: the one actually playing when the active list is read, else the first in
 * it, so a paused track still shows its title. Between reads of the list it stays with that one and
 * reads its title and state as they change, whatever the other sessions do.
 *
 * Main-thread only, like the rest of the panel.
 */
class NowPlayingSource {

    private var hub: MediaSessionHub? = null
    private var followed: Any? = null
    private var controls: MediaSessionControls? = null

    var title: String? = null
        private set

    var artist: String? = null
        private set

    var playing: Boolean = false
        private set

    /** True when there is a real track to show; drives the panel's layout. */
    val hasTrack: Boolean
        get() = !title.isNullOrBlank()

    private val subscriber = MediaSessionSubscriber { sessions, change ->
        when (change) {
            MediaSessionChange.ListRead -> adopt(sessions)
            is MediaSessionChange.Playback -> if (change.token == followed) readState(sessions)
            is MediaSessionChange.Metadata -> if (change.token == followed) readMetadata()
        }
    }

    fun start(context: Context) {
        start(MediaSessionHub.get(context))
    }

    internal fun start(sessions: MediaSessionHub) {
        if (hub != null) return
        hub = sessions
        sessions.subscribe(subscriber)
    }

    fun stop() {
        val current = hub ?: return
        hub = null
        current.unsubscribe(subscriber)
        followed = null
        controls = null
        title = null
        artist = null
        playing = false
    }

    /**
     * Picks the session to follow: the one actually playing, else the highest
     * priority one, so a paused track still shows its title.
     */
    private fun adopt(sessions: MediaSessions) {
        val candidates = sessions.active
        val chosen = candidates.firstOrNull { it.playbackState == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
        followed = chosen?.token
        controls = chosen?.controls
        readMetadata()
        readState(sessions)
    }

    /** The title and artist, read from the session itself. */
    private fun readMetadata() {
        val track = runCatching { controls?.track() }.getOrNull()
        title = track?.title
        artist = track?.artist
    }

    private fun readState(sessions: MediaSessions) {
        playing = followed?.let(sessions::get)?.playbackState == PlaybackState.STATE_PLAYING
    }
}
