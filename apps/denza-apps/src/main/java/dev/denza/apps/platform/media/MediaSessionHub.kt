package dev.denza.apps.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The other apps' media sessions, watched once for the whole process.
 *
 * Three features want to know what is playing: the wheel's Play/Pause key (`feature/media`), the
 * speaker covers (`feature/speaker`) and the strip's track line (`feature/trip`). Each used to
 * register its own active-sessions listener and its own callback on every session. This is the one
 * listener and the one callback per session; they subscribe, and each still decides for itself
 * what "playing" means from what it is handed.
 *
 * What it keeps, in [sessions]:
 *  - every session it has seen in the platform's active list, until that session is destroyed. A
 *    session that leaves the list is dormant ([MediaSessionSnapshot.active] false), not gone: the
 *    list is the key-routing order, not the list of sessions that exist, and the wheel key's
 *    contract addresses dormant sessions (docs/shortcuts-automation-findings.md, "Resume contract");
 *  - one callback per session. A registration the system refuses is not tracked, and is reported
 *    once until that session leaves the active list.
 *
 * When it reads the list: when the platform's listener fires, after a session is destroyed and when
 * it starts listening, each published to every subscriber as [MediaSessionChange.ListRead], with
 * every active session's playback state read again; and on [refresh], for a caller that must act on
 * the list as it is this instant - the wheel key. That read is returned to its caller and published
 * to nobody, and it reads no session's state, so a press costs one binder call for the list and the
 * other subscribers nothing; they hear the same change from the platform's listener a moment later.
 *
 * Subscribers come and go; the first one starts the listening and the last one to leave detaches
 * everything. A subscriber that arrives while the hub listens is handed the current sessions at once.
 * One subscriber that throws is logged and does not keep the others from hearing.
 *
 * Losing access: when the notification-listener grant goes, this firmware's `MediaSessionService`
 * disconnects every listener that lost it - it hands the listener an empty list and drops it
 * (`updateActiveSessionListeners`, "is no longer authorized. Disconnecting."). So an empty list is
 * read again here, and a read that is refused means the listener is dead: the hub stops listening,
 * lets go of every session and hands its subscribers nothing, and the next [listen] - a subscriber
 * arriving, starting or restarting - registers it again. When [MediaSessionAccess] repairs the grant
 * it calls [relisten], which puts a new listener on whatever the hub believed.
 *
 * Main looper only, as are the callbacks it registers. [isListening] is the one value another
 * thread may read.
 */
class MediaSessionHub internal constructor(
    private val source: MediaSessionSource,
    private val log: (String, Throwable?) -> Unit,
) {
    private val subscribers = ArrayList<MediaSessionSubscriber>()

    /** Every session with our callback on it, in the order they were first seen. */
    private val tracked = LinkedHashMap<Any, Tracked>()

    /** The tracked sessions in the platform's active list, in its order. */
    private var activeTokens: Set<Any> = emptySet()

    /** Sessions whose callback registration was refused; each is reported once, not per read. */
    private val refused = HashSet<Any>()

    /** Written on the main looper, read by the support report from whatever thread built it. */
    @Volatile
    var isListening: Boolean = false
        private set

    /** The sessions as last read. [MediaSessions.NONE] while the hub is not listening. */
    var sessions: MediaSessions = MediaSessions.NONE
        private set

    /**
     * Starts handing [subscriber] the sessions. The first subscriber starts the listening; one that
     * arrives while the hub listens is handed the current sessions at once, as a list read.
     */
    fun subscribe(subscriber: MediaSessionSubscriber) {
        if (subscriber in subscribers) return
        subscribers += subscriber
        if (isListening) {
            deliver(subscriber, sessions, MediaSessionChange.ListRead)
        } else {
            listen()
        }
    }

    /** The last subscriber to leave takes every callback and the listener with it. */
    fun unsubscribe(subscriber: MediaSessionSubscriber) {
        if (!subscribers.remove(subscriber)) return
        if (subscribers.isEmpty()) detach()
    }

    /**
     * Listens, if it does not already; whether it does now.
     *
     * The hub tries when its first subscriber arrives, and a refusal - no notification-listener
     * access yet, or access lost since - is not retried by itself. This is for a subscriber that
     * starts again, or is told that access is back. On success every subscriber is handed the
     * sessions.
     */
    fun listen(): Boolean {
        if (isListening) return true
        if (subscribers.isEmpty()) return false
        return attach()
    }

    /**
     * Listens again from scratch, whatever the hub believes: the platform's listener taken off and a
     * new one put on, the list read anew and every subscriber handed it.
     *
     * For the moment access has just been granted again. A listener the platform disconnected
     * without the hub noticing - an empty list it never got to read - would otherwise stay dead
     * while the hub thought it listened. Sessions it already tracks are kept, dormant ones included.
     */
    fun relisten(): Boolean {
        if (subscribers.isEmpty()) return false
        if (isListening) {
            runCatching { source.unlisten() }
            isListening = false
        }
        return attach()
    }

    private fun attach(): Boolean {
        val read = runCatching {
            source.listen(::onListChanged)
            isListening = true
            reconcile(source.activeSessions(), rereadStates = true)
        }
        if (read.isFailure) {
            runCatching { source.unlisten() }
            isListening = false
            forgetAll()
            log("media-session access unavailable", read.exceptionOrNull())
            return false
        }
        publish(MediaSessionChange.ListRead)
        return true
    }

    /**
     * Reads the active list now and returns it, published to nobody.
     *
     * For a caller that must act on the sessions as they are this instant: a steering-wheel press.
     * A session that has just appeared is tracked from here, so the press can address it. No
     * playback state is read - the caller reads the states it acts on itself.
     */
    fun refresh(): Result<MediaSessions> {
        if (!isListening) return Result.failure(IllegalStateException("not listening"))
        return runCatching {
            reconcile(source.activeSessions(), rereadStates = false)
            sessions
        }
    }

    private fun onListChanged(active: List<MediaSessionHandle>?) {
        if (!isListening) return
        val list = if (active.isNullOrEmpty()) confirmEmpty() ?: return else active
        runCatching { reconcile(list, rereadStates = true) }
            .onFailure { error -> log("could not read media sessions", error) }
        publish(MediaSessionChange.ListRead)
    }

    /**
     * An empty list is also how the platform says goodbye to a listener whose access it has taken
     * away, so it is read again. The list when that read answers; null, with the hub no longer
     * listening, when it is refused.
     */
    private fun confirmEmpty(): List<MediaSessionHandle>? =
        runCatching { source.activeSessions() }.getOrElse { error ->
            runCatching { source.unlisten() }
            isListening = false
            forgetAll()
            log("media-session access lost", error)
            publish(MediaSessionChange.ListRead)
            null
        }

    private fun reconcile(active: List<MediaSessionHandle>?, rereadStates: Boolean) {
        val current = active.orEmpty().associateBy { it.token }
        current.forEach { (token, handle) ->
            val known = tracked[token]
            if (known == null) {
                track(token, handle, rereadStates)
            } else if (rereadStates) {
                known.reread()
            }
        }
        refused.retainAll(current.keys)
        activeTokens = current.keys.filterTo(LinkedHashSet()) { it in tracked }
        sessions = snapshot()
    }

    /**
     * A session is tracked only once our callback is on it; a refused registration is not, and
     * neither is a session whose package cannot be read - a controller whose session died between
     * the list and this read answers null.
     */
    private fun track(token: Any, handle: MediaSessionHandle, readState: Boolean) {
        val packageName = runCatching { handle.packageName }.getOrNull()
        if (packageName == null) {
            if (refused.add(token)) log("media session without a package", null)
            return
        }
        val session = Tracked(handle, packageName)
        if (!handle.register(session)) {
            if (refused.add(token)) {
                log("media session callback refused package=$packageName", null)
            }
            return
        }
        if (readState) session.reread()
        tracked[token] = session
    }

    private fun detach() {
        if (isListening) runCatching { source.unlisten() }
        isListening = false
        forgetAll()
    }

    private fun forgetAll() {
        tracked.values.forEach { it.handle.unregister() }
        tracked.clear()
        refused.clear()
        activeTokens = emptySet()
        sessions = MediaSessions.NONE
    }

    private fun snapshot(): MediaSessions = MediaSessions(
        active = activeTokens.mapNotNull { tracked[it]?.snapshot(active = true) },
        dormant = tracked.values
            .filter { it.handle.token !in activeTokens }
            .map { it.snapshot(active = false) },
    )

    private fun publish(change: MediaSessionChange) {
        val current = sessions
        subscribers.toList().forEach { subscriber ->
            // One that left while an earlier one was being told hears nothing more.
            if (subscriber in subscribers) deliver(subscriber, current, change)
        }
    }

    /** One subscriber's failure is its own: it is logged, and the next one still hears. */
    private fun deliver(
        subscriber: MediaSessionSubscriber,
        sessions: MediaSessions,
        change: MediaSessionChange,
    ) {
        runCatching { subscriber.onSessions(sessions, change) }
            .onFailure { error -> log("media-session subscriber failed on $change", error) }
    }

    private inner class Tracked(
        val handle: MediaSessionHandle,
        val packageName: String,
    ) : MediaSessionEvents {
        private var state: Int? = null

        fun reread() {
            runCatching { handle.playbackState() }.onSuccess { state = it }
        }

        fun snapshot(active: Boolean) = MediaSessionSnapshot(
            token = handle.token,
            packageName = packageName,
            active = active,
            playbackState = state,
            controls = handle,
        )

        /** A callback from a session this hub has let go of, or from before a detach, is not news. */
        private fun isCurrent(): Boolean = isListening && tracked[handle.token] === this

        override fun onPlaybackState(state: Int?) {
            if (!isCurrent()) return
            this.state = state
            sessions = snapshot()
            publish(MediaSessionChange.Playback(handle.token))
        }

        override fun onMetadata() {
            if (!isCurrent()) return
            publish(MediaSessionChange.Metadata(handle.token))
        }

        /** The one way a session leaves the hub while it listens. The list is read again after it. */
        override fun onDestroyed() {
            if (!isCurrent()) return
            tracked.remove(handle.token)
            handle.unregister()
            runCatching { reconcile(source.activeSessions(), rereadStates = true) }
                .onFailure { error ->
                    activeTokens = activeTokens - handle.token
                    sessions = snapshot()
                    log("could not refresh media sessions", error)
                }
            publish(MediaSessionChange.ListRead)
        }
    }

    companion object {
        private const val TAG = "DenzaMediaSessions"

        @Volatile
        private var instance: MediaSessionHub? = null

        /** The process's hub, built on first use; its callbacks arrive on the main looper. */
        fun get(context: Context): MediaSessionHub = instance ?: synchronized(this) {
            instance ?: MediaSessionHub(
                source = AndroidMediaSessionSource(
                    context.applicationContext,
                    Handler(Looper.getMainLooper()),
                ),
                log = { message, error -> Log.i(TAG, message, error) },
            ).also { instance = it }
        }
    }
}
