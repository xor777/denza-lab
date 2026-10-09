package dev.denza.apps.feature.media

import android.media.session.PlaybackState
import dev.denza.apps.platform.media.MediaSessionChange
import dev.denza.apps.platform.media.MediaSessionControls
import dev.denza.apps.platform.media.MediaSessionHub
import dev.denza.apps.platform.media.MediaSessionSnapshot
import dev.denza.apps.platform.media.MediaSessionSubscriber
import dev.denza.apps.platform.media.MediaSessions

/**
 * The wheel key's sessions: its subscription to the media-session hub, the targets it keeps from
 * the hub's snapshots, and the one read a press makes before [core] decides.
 *
 * The hub reports what the platform reports; what that means for the key is decided here, by the
 * resume contract (docs/shortcuts-automation-findings.md, "Resume contract"):
 *  - a session becomes a target when it is seen in the active list, and only then. A key that
 *    starts listening does not inherit dormant sessions the hub saw before it, as a new controller
 *    never did;
 *  - leaving the active list is not death. A target stays, dormant, until the hub drops its session,
 *    which the hub does only when the session is destroyed;
 *  - the policy reads playback from the session itself at the moment it asks, so a press acts on
 *    what the session says now and not on its last report.
 *
 * `MediaResumeController` is the Android glue around this - the key filter, the log and the
 * support report - and calls [press] for every DOWN it may take, so a test of [press] is a test of
 * the key's path.
 *
 * Main looper only, like the hub. [isListening] may be read from any thread.
 */
internal class MediaResumeSessions(
    private val hub: MediaSessionHub,
    private val core: MediaResumeCore,
    /** A line for the key's log: a transport command that went out, or a read that failed. */
    private val log: (String, Throwable?) -> Unit,
) {
    private val targets = LinkedHashMap<Any, SessionTarget>()

    // Written on the main looper, read by the support report from whatever thread built it.
    @Volatile
    private var subscribed = false

    private val subscriber = MediaSessionSubscriber(::onSessions)

    /** Whether the key hears sessions at all: subscribed, and the hub listening. */
    val isListening: Boolean
        get() = subscribed && hub.isListening

    /**
     * Subscribes once. Called again - after an access repair - it asks the hub to listen if it could
     * not before, or lost its access since; a hub that listens has nothing to do.
     */
    fun start() {
        if (subscribed) {
            hub.listen()
            return
        }
        subscribed = true
        hub.subscribe(subscriber)
    }

    fun stop() {
        if (subscribed) hub.unsubscribe(subscriber)
        subscribed = false
        clear()
    }

    /**
     * One DOWN the key filter may take: the active list read now, for this press alone, the targets
     * brought up to it, and the policy's answer. A list that cannot be read is a refusal
     * ([MediaResumeReason.SESSION_ACCESS]), which leaves the press to stock routing.
     *
     * The hub tells nobody else about this read, and reads no session's state for it; the policy
     * reads every target's playback from the session itself. A press costs what it cost when the
     * key kept its own listener: one read of the list, and the reads the policy makes.
     */
    fun press(command: MediaResumeCommand): MediaResumeDecision {
        val refused = MediaResumeDecision(accepted = false, reason = MediaResumeReason.SESSION_ACCESS)
        if (!isListening) return refused
        val read = runCatching { reconcile(hub.refresh().getOrThrow()) }
        read.exceptionOrNull()?.let { error ->
            log("could not validate media sessions", error)
            return refused
        }
        if (!isListening) return refused
        return core.perform(command)
    }

    private fun onSessions(sessions: MediaSessions, change: MediaSessionChange) {
        when (change) {
            MediaSessionChange.ListRead -> reconcile(sessions)
            is MediaSessionChange.Playback -> {
                val target = targets[change.token] ?: return
                val session = sessions[change.token] ?: return
                core.onPlayback(target.identity, session.playbackState.toResumePlayback())
            }
            is MediaSessionChange.Metadata -> Unit
        }
    }

    /**
     * The key's own reading of a list: what the hub dropped leaves, what is active and new becomes a
     * target, and the policy is handed the active ones in the platform's order.
     */
    private fun reconcile(sessions: MediaSessions) {
        val gone = targets.keys.filter { sessions[it] == null }
        gone.forEach { token ->
            targets.remove(token)?.live = false
            core.remove(token)
        }
        sessions.active.forEach { session ->
            if (session.token !in targets) targets[session.token] = SessionTarget(session)
        }
        core.reconcile(sessions.active.mapNotNull { targets[it.token] })
    }

    private fun clear() {
        targets.values.forEach { it.live = false }
        targets.clear()
        core.clear()
    }

    private inner class SessionTarget(session: MediaSessionSnapshot) : MediaResumeTarget {
        private val controls: MediaSessionControls = session.controls
        override val identity: Any = session.token
        override val packageName: String = session.packageName

        @Volatile
        var live = true

        override fun playback(): MediaResumePlayback = controls.playbackState().toResumePlayback()

        override fun isLive(): Boolean = live

        /**
         * Pause keeps its advertised-action gate. It is the half of the toggle that was proven on
         * the car, and a press this gate refuses goes to the firmware, which pauses harmlessly.
         * Play has no equivalent gate: there the firmware opens its own player instead.
         */
        override fun canPause(): Boolean {
            val actions = controls.actions() ?: return false
            return actions and PlaybackState.ACTION_PAUSE != 0L
        }

        override fun play() {
            controls.play()
            log("direct media command package=$packageName command=play", null)
        }

        override fun pause() {
            controls.pause()
            log("direct media command package=$packageName command=pause", null)
        }
    }
}

/** A session's `PlaybackState.getState()` in the words the policy decides with. */
internal fun Int?.toResumePlayback(): MediaResumePlayback = when (this) {
    PlaybackState.STATE_PLAYING -> MediaResumePlayback.PLAYING
    PlaybackState.STATE_PAUSED -> MediaResumePlayback.PAUSED
    PlaybackState.STATE_NONE,
    PlaybackState.STATE_STOPPED,
    PlaybackState.STATE_ERROR,
    null,
    -> MediaResumePlayback.ENDED
    else -> MediaResumePlayback.TRANSITIONAL
}
