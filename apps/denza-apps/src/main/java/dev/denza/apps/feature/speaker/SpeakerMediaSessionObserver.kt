package dev.denza.apps.feature.speaker

import android.content.Context
import android.media.session.PlaybackState
import dev.denza.apps.platform.media.MediaSessionChange
import dev.denza.apps.platform.media.MediaSessionHub
import dev.denza.apps.platform.media.MediaSessionSubscriber
import dev.denza.apps.platform.media.MediaSessions

/**
 * Hears every active session that plays; the trip strip deliberately follows only one.
 *
 * The sessions come from the process's [MediaSessionHub]; what counts as playing for the covers is
 * decided here, by [SpeakerPlayback].
 */
internal class SpeakerMediaSessionObserver(
    private val hub: MediaSessionHub,
    private val onPlaying: (String) -> Unit,
) {
    constructor(context: Context, onPlaying: (String) -> Unit) :
        this(MediaSessionHub.get(context), onPlaying)

    private val playback = SpeakerPlayback()
    private var listening = false

    private val subscriber = MediaSessionSubscriber { sessions, change ->
        playback.playing(sessions, change).forEach(onPlaying)
    }

    /**
     * Subscribing hands over the active sessions at once, so a player already going is heard here,
     * as it always was when the observer started. A hub that is not listening - no access when it
     * was first asked, or access lost since - is asked to listen again.
     */
    fun start() {
        if (listening) return
        listening = true
        playback.reset()
        hub.subscribe(subscriber)
    }

    /** After an access repair: the sessions again, as a fresh start would have read them. */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        if (listening) hub.unsubscribe(subscriber)
        listening = false
    }
}

/**
 * What the covers count as playing, out of what the hub reports.
 *
 * Only the active list is the covers' business: the observer used to let go of a session the moment
 * it left that list, and a dormant session reporting PLAYING is not one the car is routing. So a read
 * of the list names every active session that plays, in the platform's order, and a playback report
 * names its session only when that session is active and the report is PLAYING. Each name is a
 * trigger, not a state: [SpeakerCoverService] decides whether it is worth a report.
 *
 * A read of the list that changes nothing about the active sessions - the same sessions in the same
 * order in the same states as the covers last heard - names nobody. This firmware pushes the list on
 * every session created or destroyed, active or not (`MediaSessionService.destroySessionLocked`), so
 * a paused player in the background dying, or one opening a session it has not activated yet, would
 * otherwise re-report whatever was playing once the service's repeat guard had run out. A report is
 * a write to the car, and nothing started playing.
 */
internal class SpeakerPlayback {
    /** The active sessions and their states as last heard; null before the first list. */
    private var lastActive: List<Pair<Any, Int?>>? = null

    /** A new subscription hears its first list in full, as switching the feature on always did. */
    fun reset() {
        lastActive = null
    }

    fun playing(sessions: MediaSessions, change: MediaSessionChange): List<String> {
        val active = sessions.active.map { it.token to it.playbackState }
        val unchanged = active == lastActive
        lastActive = active
        return when (change) {
            MediaSessionChange.ListRead -> if (unchanged) {
                emptyList()
            } else {
                sessions.active
                    .filter { it.playbackState == PlaybackState.STATE_PLAYING }
                    .map { it.packageName }
            }

            is MediaSessionChange.Playback -> listOfNotNull(
                sessions[change.token]
                    ?.takeIf { it.active && it.playbackState == PlaybackState.STATE_PLAYING }
                    ?.packageName,
            )

            is MediaSessionChange.Metadata -> emptyList()
        }
    }
}
