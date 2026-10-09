package dev.denza.apps.platform.media

/**
 * The platform under [MediaSessionHub], played by hand.
 *
 * Like the car, it hands out a new controller for a session on every read, so a test that counts one
 * callback per session counts it across controllers and not per object.
 */
internal class FakeSession(
    val token: String,
    var packageName: String?,
    var state: Int? = null,
) {
    var actions: Long? = null
    var track: MediaTrack? = null

    /** How many registrations the system refuses from now on, one per attempt. */
    var refuseRegistrations = 0

    /** The callbacks on this session right now: one, or none. Anything else is a leak. */
    val callbacks = ArrayList<MediaSessionEvents>()
    var registrations = 0
    var stateReads = 0
    var trackReads = 0
    var plays = 0
    var pauses = 0

    fun controller(): MediaSessionHandle = FakeHandle(this)

    /** The player changing state, which the session reports to every callback on it. */
    fun report(state: Int?) {
        this.state = state
        callbacks.toList().forEach { it.onPlaybackState(state) }
    }

    fun reportMetadata(track: MediaTrack?) {
        this.track = track
        callbacks.toList().forEach { it.onMetadata() }
    }

    fun destroy() {
        callbacks.toList().forEach { it.onDestroyed() }
    }

    override fun toString(): String = token
}

private class FakeHandle(private val session: FakeSession) : MediaSessionHandle {
    private var registered: MediaSessionEvents? = null

    override val token: Any get() = session.token
    override val packageName: String? get() = session.packageName

    override fun register(events: MediaSessionEvents): Boolean {
        if (session.refuseRegistrations > 0) {
            session.refuseRegistrations -= 1
            return false
        }
        session.registrations += 1
        registered = events
        session.callbacks += events
        return true
    }

    override fun unregister() {
        registered?.let { session.callbacks.remove(it) }
        registered = null
    }

    override fun playbackState(): Int? {
        session.stateReads += 1
        return session.state
    }

    override fun actions(): Long? = session.actions

    override fun track(): MediaTrack? {
        session.trackReads += 1
        return session.track
    }

    override fun play() {
        session.plays += 1
    }

    override fun pause() {
        session.pauses += 1
    }
}

internal class FakeMediaSessionSource : MediaSessionSource {
    /** The platform's active list as it stands, in its order. */
    var active: List<FakeSession> = emptyList()

    /** No notification-listener grant: listening and reading both throw. */
    var refuseAccess = false

    /** A read of the list that fails, with access in place. */
    var failReads = false

    var listener: ((List<MediaSessionHandle>?) -> Unit)? = null
        private set
    var listReads = 0
        private set

    override fun listen(onChanged: (List<MediaSessionHandle>?) -> Unit) {
        if (refuseAccess) throw SecurityException("no notification-listener access")
        listener = onChanged
    }

    override fun unlisten() {
        listener = null
    }

    override fun activeSessions(): List<MediaSessionHandle> {
        if (refuseAccess) throw SecurityException("no notification-listener access")
        if (failReads) throw IllegalStateException("read failed")
        listReads += 1
        return active.map(FakeSession::controller)
    }

    /** The platform's listener firing with this as the new active list. */
    fun deliver(vararg sessions: FakeSession) {
        active = sessions.toList()
        listener?.invoke(active.map(FakeSession::controller))
    }

    /**
     * The grant taken away, as this firmware's `MediaSessionService` handles it: the listener is
     * dropped and handed an empty list on its way out, and every read is refused from then on.
     */
    fun revoke() {
        refuseAccess = true
        val leaving = listener
        listener = null
        leaving?.invoke(emptyList())
    }

    /** The platform dropping the listener without a word; the hub cannot know. */
    fun dropListenerSilently() {
        listener = null
    }
}

internal class RecordingSubscriber : MediaSessionSubscriber {
    val changes = ArrayList<MediaSessionChange>()
    val handed = ArrayList<MediaSessions>()

    val last: MediaSessions get() = handed.last()

    override fun onSessions(sessions: MediaSessions, change: MediaSessionChange) {
        handed += sessions
        changes += change
    }
}

internal fun MediaSessions.activeTokens(): List<Any> = active.map { it.token }

internal fun MediaSessions.dormantTokens(): List<Any> = dormant.map { it.token }

internal fun fakeHub(source: FakeMediaSessionSource, log: MutableList<String> = ArrayList()) =
    MediaSessionHub(source) { message, _ -> log += message }
