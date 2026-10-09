package dev.denza.apps.platform.media

/**
 * One session as [MediaSessionHub] last saw it. A snapshot never changes; the next read is a new one.
 */
class MediaSessionSnapshot internal constructor(
    /** The session's token: one session has one token, whichever controller read it. */
    val token: Any,
    val packageName: String,
    /**
     * In the platform's active list at the hub's last read. A session that left it is dormant: still
     * alive and still commandable until it is destroyed, which is when the hub drops it.
     */
    val active: Boolean,
    /**
     * `PlaybackState.getState()` as the session last reported it, or as the hub last read it; null
     * when the session has no playback state, or when a session first seen by [MediaSessionHub.refresh]
     * has not been read yet.
     */
    val playbackState: Int?,
    /** The session itself, for a live read or a transport command. */
    val controls: MediaSessionControls,
)

/** Every session the hub tracks: the active list in the platform's order, then the dormant ones. */
class MediaSessions internal constructor(
    /** The platform's active list, in its own order, which is the order it routes media keys in. */
    val active: List<MediaSessionSnapshot>,
    /** Sessions that left the active list and are not destroyed, in the order they were first seen. */
    val dormant: List<MediaSessionSnapshot>,
) {
    private val byToken: Map<Any, MediaSessionSnapshot> = (active + dormant).associateBy { it.token }

    /** The session with this token, active or dormant; null once the hub has dropped it. */
    operator fun get(token: Any): MediaSessionSnapshot? = byToken[token]

    companion object {
        val NONE = MediaSessions(emptyList(), emptyList())
    }
}

/** What a subscriber is being handed [MediaSessions] for. */
sealed interface MediaSessionChange {
    /**
     * The active list was read: the platform's listener fired, a session was destroyed, the hub
     * started listening, or the subscriber has just arrived.
     */
    data object ListRead : MediaSessionChange

    /** One session reported a playback state; the snapshot holds it already. */
    data class Playback(val token: Any) : MediaSessionChange

    /** One session reported new metadata; [MediaSessionControls.track] reads it. */
    data class Metadata(val token: Any) : MediaSessionChange
}

fun interface MediaSessionSubscriber {
    /** On the main looper, for every change while subscribed. */
    fun onSessions(sessions: MediaSessions, change: MediaSessionChange)
}

/** Live reads and transport commands on one session. Every call is a binder call. */
interface MediaSessionControls {
    /** `PlaybackState.getState()` now, or null when the session has no playback state. */
    fun playbackState(): Int?

    /** `PlaybackState.getActions()` now, or null when the session has no playback state. */
    fun actions(): Long?

    /** The title and artist now, or null when the session publishes no metadata. */
    fun track(): MediaTrack?

    fun play()

    fun pause()
}

data class MediaTrack(
    val title: String?,
    val artist: String?,
)

/**
 * The platform under the hub: `MediaSessionManager` on the car, a fake in a test.
 *
 * Calls that need the notification-listener grant throw without it, which is how the hub learns
 * that it has none.
 */
internal interface MediaSessionSource {
    /** Starts delivering the active list to [onChanged] whenever it changes, on the main looper. */
    fun listen(onChanged: (List<MediaSessionHandle>?) -> Unit)

    fun unlisten()

    /** The active list now, in the platform's order. */
    fun activeSessions(): List<MediaSessionHandle>
}

/**
 * One session as one controller reaches it. The platform builds a new controller for every list it
 * hands out; the hub keeps the first one it saw for each token, as the features always did.
 */
internal interface MediaSessionHandle : MediaSessionControls {
    val token: Any

    /** Null when the session cannot say: its process died between the list and this read. */
    val packageName: String?

    /** Puts the hub's one callback on the session; false when the system refused it. */
    fun register(events: MediaSessionEvents): Boolean

    /** Takes it off again. Never throws. */
    fun unregister()
}

/** The callback the hub registers on each session, on the main looper. */
internal interface MediaSessionEvents {
    fun onPlaybackState(state: Int?)

    fun onMetadata()

    fun onDestroyed()
}
