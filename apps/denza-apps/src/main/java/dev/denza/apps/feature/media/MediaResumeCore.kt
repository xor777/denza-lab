package dev.denza.apps.feature.media

/** A media session that can be addressed directly without global media-key routing. */
internal interface MediaResumeTarget {
    val identity: Any

    val packageName: String

    fun playback(): MediaResumePlayback

    fun isLive(): Boolean

    fun canPause(): Boolean

    fun play()

    fun pause()
}

internal enum class MediaResumePlayback {
    PLAYING,
    PAUSED,
    TRANSITIONAL,
    ENDED,
}

internal enum class MediaResumeCommand {
    PLAY,
    PAUSE,
    TOGGLE,
}

/**
 * What one media press resolved to, and why.
 *
 * `accepted` is the only thing the key interceptor reads: it decides whether this press belongs to
 * us or to the firmware. `reason` exists so a refusal is never silent - every path out of the
 * policy names itself from one vocabulary, which is also where a diagnostics record hooks in.
 */
internal data class MediaResumeDecision(
    val accepted: Boolean,
    val reason: String,
    val packageName: String? = null,
)

/** The whole reason vocabulary. Nothing outside this object may invent a reason string. */
internal object MediaResumeReason {
    const val PLAY = "play"
    const val PLAY_TRANSPORT = "play-transport"
    const val ALREADY_PLAYING = "already-playing"
    const val PAUSE = "pause"
    const val PAUSE_DEFERRED = "pause-deferred"
    const val PAUSE_IN_FLIGHT = "pause-in-flight"
    const val PAUSE_COMPLETE = "pause-already-complete"
    const val PAUSE_PREPARATION = "pause-preparation"
    const val PAUSE_TRANSPORT = "pause-transport"
    const val PAUSE_UNSUPPORTED = "pause-unsupported"
    const val SESSION_ACCESS = "session-access"
    const val SESSION_ACCESS_AFTER_PREPARATION = "session-access-after-preparation"
    const val STALE_AFTER_PREPARATION = "stale-target-after-preparation"
    const val NO_TARGET = "no-target"
    const val STOCK_NO_HISTORY = "stock-no-history"
    const val RESUME_IN_FLIGHT = "resume-in-flight"
    const val RECONNECT_STARTED = "reconnect-started"
    const val RECONNECT_PLAYED = "reconnect-played"
    const val RECONNECT_FAILED = "reconnect-failed"
    const val RECONNECT_TIMEOUT = "reconnect-timeout"
    const val NO_BROWSER_SERVICE = "no-browser-service"
    const val MEDIA_BUTTON_SENT = "media-button-sent"
    const val NO_MEDIA_BUTTON_RECEIVER = "no-media-button-receiver"
}

/** The package whose session was last seen actually playing, and the wall clock at that moment. */
internal data class MediaLastPlayed(
    val packageName: String,
    val atMillis: Long,
)

/**
 * Survives this process. Everything else the policy knows dies with it.
 *
 * The timestamp is written but never read by the policy: there is deliberately no expiry, because
 * the driver's expectation after a night's parking is the same as after a red light - the wheel
 * resumes what was playing. It is stored so that a time limit stays one comparison away in
 * [MediaResumeCore.lastPlayedPackage] if a car ever proves that wrong.
 */
internal interface MediaLastPlayedStore {
    fun lastPlayed(): MediaLastPlayed?

    fun remember(packageName: String)
}

/**
 * Decides what a steering-wheel media press means.
 *
 * Identity here is the **package**, not the session token. A token dies with the player's process,
 * with our own service, and sometimes just because a player rebuilt its session; a package is what
 * the driver actually meant. Tokens still address sessions - they are the only thing a
 * `MediaController` can be built from - but nothing is remembered by token across a restart.
 *
 * Play resolves in one order:
 *  1. something is playing right now - the wheel key is a toggle, so this is the pause path;
 *  2. a live session (in the active list or not) belongs to the last-played package - play it;
 *  3. no live session for that package - reconnect to it through the platform's browser contract;
 *  4. nothing ever played - leave the press to the firmware, which will open its own player.
 *
 * Only case 4 is a legitimate hand-over. Every other rejection on Play launches the stock media
 * center on this firmware, which is exactly the defect this policy exists to remove.
 */
internal class MediaResumeCore(private val store: MediaLastPlayedStore) {
    private val targets = linkedMapOf<Any, Entry>()
    private var rememberedIdentity: Any? = null
    private var sequence = 0L

    /**
     * Takes the platform's active-session list as what it is: the key-routing order, not the list
     * of sessions that exist.
     *
     * A player that deactivates its session when it pauses vanishes from this list while its
     * session is still alive and still answers `play()` - this vehicle's own `MediaSessionRecord`
     * routes a controller's `play` straight to the app's callback and only consults `mIsActive` in
     * `isActive()`. So a session that leaves the list becomes dormant here, not forgotten. Only
     * `onSessionDestroyed` removes one.
     */
    @Synchronized
    fun reconcile(active: List<MediaResumeTarget>) {
        val next = linkedMapOf<Any, Entry>()
        active.forEach { target ->
            val entry = targets[target.identity]?.also { it.target = target }
                ?: Entry(target, ++sequence)
            entry.active = true
            next[target.identity] = entry
        }
        targets.forEach { (identity, entry) ->
            if (identity !in next) {
                entry.active = false
                next[identity] = entry
            }
        }
        targets.clear()
        targets.putAll(next)
        if (rememberedIdentity !in targets) rememberedIdentity = null

        val snapshots = snapshots()
        snapshots.forEach { snapshot ->
            when (snapshot.playback) {
                MediaResumePlayback.PLAYING -> markPlaying(snapshot.entry)
                MediaResumePlayback.ENDED -> forget(snapshot.entry)
                MediaResumePlayback.PAUSED,
                MediaResumePlayback.TRANSITIONAL,
                -> Unit
            }
        }

        if (rememberedIdentity == null) {
            snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }
                ?.let { rememberedIdentity = it.entry.identity }
        }
    }

    /** Takes over a session this policy obtained itself, which no active-session read reported yet. */
    @Synchronized
    fun adopt(target: MediaResumeTarget) {
        val existing = targets[target.identity]
        if (existing != null) {
            existing.target = target
            return
        }
        targets[target.identity] = Entry(target, ++sequence).apply { active = false }
    }

    @Synchronized
    fun onPlayback(identity: Any, playback: MediaResumePlayback) {
        val entry = targets[identity] ?: return
        when (playback) {
            MediaResumePlayback.PLAYING -> {
                markPlaying(entry)
                rememberedIdentity = identity
            }
            MediaResumePlayback.ENDED -> forget(entry)
            MediaResumePlayback.PAUSED,
            MediaResumePlayback.TRANSITIONAL,
            -> Unit
        }
    }

    @Synchronized
    fun remove(identity: Any) {
        targets.remove(identity)
        if (rememberedIdentity == identity) rememberedIdentity = null
    }

    @Synchronized
    fun perform(
        command: MediaResumeCommand,
        deferPause: (MediaResumeTarget, List<MediaResumeTarget>) -> Boolean = { _, _ -> false },
        reconnect: (String) -> MediaResumeDecision = {
            MediaResumeDecision(false, MediaResumeReason.NO_BROWSER_SERVICE, it)
        },
    ): MediaResumeDecision {
        val snapshots = snapshots()
        var remembered = rememberedIdentity
        if (
            snapshots.any {
                it.entry.identity == remembered && it.playback == MediaResumePlayback.ENDED
            }
        ) {
            targets[remembered]?.let(::forget)
            remembered = null
        }
        val playing = snapshots.firstOrNull {
            it.entry.identity == remembered && it.playback == MediaResumePlayback.PLAYING
        } ?: snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }

        if (playing != null) {
            // The wheel sends one toggle. Something audible is the pause half of it, whichever
            // package owns it; an explicit Play key on a playing session has nothing left to do.
            return if (command == MediaResumeCommand.PLAY) {
                MediaResumeDecision(true, MediaResumeReason.ALREADY_PLAYING, playing.entry.packageName)
            } else {
                pause(playing, snapshots, deferPause)
            }
        }

        // Nothing is playing. An explicit Pause key has nothing to cancel, and the firmware's own
        // pause handling is harmless, so that press stays where it always was.
        if (command == MediaResumeCommand.PAUSE) {
            return MediaResumeDecision(false, MediaResumeReason.NO_TARGET)
        }

        val last = lastPlayedPackage()
            ?: return MediaResumeDecision(false, MediaResumeReason.STOCK_NO_HISTORY)
        val candidate = resolve(last, snapshots)
            ?: return runCatching { reconnect(last) }.getOrElse {
                MediaResumeDecision(false, MediaResumeReason.RECONNECT_FAILED, last)
            }
        return play(candidate)
    }

    /** Completes an accepted asynchronous pause only if the exact planned target is still current. */
    @Synchronized
    fun completeDeferredPause(planned: MediaResumeTarget): DeferredPauseCompletion {
        if (targets[planned.identity]?.target !== planned) return DeferredPauseCompletion.STALE
        if (!runCatching(planned::isLive).getOrDefault(false)) {
            return DeferredPauseCompletion.STALE
        }

        val snapshots = snapshots()
        val plannedSnapshot = snapshots.firstOrNull { it.entry.target === planned }
            ?: return DeferredPauseCompletion.STALE
        if (plannedSnapshot.playback == MediaResumePlayback.PAUSED) {
            return DeferredPauseCompletion.ALREADY_PAUSED
        }
        if (plannedSnapshot.playback != MediaResumePlayback.PLAYING) {
            return DeferredPauseCompletion.STALE
        }

        val remembered = rememberedIdentity
        val playing = snapshots.firstOrNull {
            it.entry.identity == remembered && it.playback == MediaResumePlayback.PLAYING
        } ?: snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }
        if (playing?.entry?.target !== planned) return DeferredPauseCompletion.STALE
        if (!runCatching(planned::canPause).getOrDefault(false)) {
            return DeferredPauseCompletion.STALE
        }

        return if (runCatching(planned::pause).isSuccess) {
            DeferredPauseCompletion.DISPATCHED
        } else {
            DeferredPauseCompletion.FAILED
        }
    }

    @Synchronized
    fun clear() {
        targets.clear()
        rememberedIdentity = null
    }

    /**
     * The pause half of the toggle, unchanged since it was proven on the car.
     *
     * A playing session whose predecessors are paused and were playing themselves cannot simply be
     * paused: the predecessor's transient focus loss would end and it would start instead. Those
     * presses go to [deferPause], which owns them from that moment on.
     */
    private fun pause(
        selected: TargetSnapshot,
        snapshots: List<TargetSnapshot>,
        deferPause: (MediaResumeTarget, List<MediaResumeTarget>) -> Boolean,
    ): MediaResumeDecision {
        val target = selected.entry.target
        val refused = MediaResumeDecision(false, MediaResumeReason.NO_TARGET, selected.entry.packageName)
        if (selected.entry.identity !in targets) return refused
        if (!runCatching(target::isLive).getOrDefault(false)) return refused
        if (!runCatching(target::canPause).getOrDefault(false)) {
            return MediaResumeDecision(
                false,
                MediaResumeReason.PAUSE_UNSUPPORTED,
                selected.entry.packageName,
            )
        }

        markPlaying(selected.entry)
        rememberedIdentity = selected.entry.identity

        val predecessors = snapshots.mapNotNull { snapshot ->
            snapshot.entry.target.takeIf {
                snapshot.entry.identity != selected.entry.identity &&
                    snapshot.entry.played &&
                    snapshot.playback == MediaResumePlayback.PAUSED &&
                    runCatching(it::isLive).getOrDefault(false)
            }
        }
        if (predecessors.isNotEmpty()) {
            val accepted = runCatching { deferPause(target, predecessors) }.getOrDefault(false)
            return MediaResumeDecision(
                accepted,
                if (accepted) MediaResumeReason.PAUSE_DEFERRED else MediaResumeReason.PAUSE_PREPARATION,
                selected.entry.packageName,
            )
        }

        val dispatched = runCatching(target::pause).isSuccess
        return MediaResumeDecision(
            dispatched,
            if (dispatched) MediaResumeReason.PAUSE else MediaResumeReason.PAUSE_TRANSPORT,
            selected.entry.packageName,
        )
    }

    /**
     * Plays a session of the last-played package.
     *
     * No `ACTION_PLAY` check: the platform does not enforce the advertised action bits, and a
     * player that only advertises `ACTION_PLAY_PAUSE` - or advertises nothing while stopped - is
     * still perfectly able to start. The bit gate is what used to hand these presses to the
     * firmware, and the firmware answers a Play by opening its own player.
     */
    private fun play(selected: TargetSnapshot): MediaResumeDecision {
        val target = selected.entry.target
        val dispatched = runCatching(target::play).isSuccess
        return MediaResumeDecision(
            dispatched,
            if (dispatched) MediaResumeReason.PLAY else MediaResumeReason.PLAY_TRANSPORT,
            selected.entry.packageName,
        )
    }

    /**
     * The session of [packageName] most likely to be the one the driver was listening to.
     *
     * One package can own several sessions at once - a player with a cast route, a browser service
     * and a foreground player all publish their own. Prefer the one the platform still routes keys
     * to, then the one most recently seen playing, then the newest.
     */
    private fun resolve(packageName: String, snapshots: List<TargetSnapshot>): TargetSnapshot? =
        snapshots.filter {
            it.entry.packageName == packageName &&
                runCatching(it.entry.target::isLive).getOrDefault(false)
        }.minWithOrNull(
            compareBy(
                { !it.entry.active },
                { -it.entry.playingOrder },
                { -it.entry.addedOrder },
            ),
        )

    /** The single read of the persisted record; a time limit, if one is ever wanted, belongs here. */
    private fun lastPlayedPackage(): String? =
        runCatching { store.lastPlayed() }.getOrNull()?.packageName?.takeIf(String::isNotBlank)

    private fun markPlaying(entry: Entry) {
        entry.played = true
        entry.playingOrder = ++sequence
        runCatching { store.remember(entry.packageName) }
    }

    private fun forget(entry: Entry) {
        entry.played = false
        if (rememberedIdentity == entry.identity) rememberedIdentity = null
    }

    private fun snapshots(): List<TargetSnapshot> = targets.values.mapNotNull { entry ->
        runCatching { TargetSnapshot(entry, entry.target.playback()) }.getOrNull()
    }

    /** One session, and what this policy has seen it do. */
    private class Entry(
        var target: MediaResumeTarget,
        val addedOrder: Long,
    ) {
        var active = true
        var played = false
        var playingOrder = 0L

        val identity: Any get() = target.identity
        val packageName: String get() = target.packageName
    }

    private class TargetSnapshot(
        val entry: Entry,
        val playback: MediaResumePlayback,
    )
}

internal enum class DeferredPauseCompletion {
    DISPATCHED,
    ALREADY_PAUSED,
    STALE,
    FAILED,
}

/** Owns a complete press only when the direct media command was accepted. */
internal class MediaResumeKeyInterceptor {
    private val pressed = mutableSetOf<Int>()
    private val owned = mutableSetOf<Int>()

    @Synchronized
    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        allowNewPress: Boolean,
        perform: (MediaResumeCommand) -> Boolean,
    ): Boolean {
        val command = commandFor(keyCode) ?: return false

        if (action == ACTION_UP) {
            pressed.remove(keyCode)
            return owned.remove(keyCode)
        }
        if (action != ACTION_DOWN) return false

        if (keyCode in pressed) return keyCode in owned
        pressed += keyCode

        if (repeatCount != 0 || !allowNewPress) return false
        if (perform(command)) owned += keyCode
        return keyCode in owned
    }

    @Synchronized
    fun reset() {
        pressed.clear()
        owned.clear()
    }

    internal companion object {
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127
        const val KEYCODE_BYD_MEDIA_TOGGLE = 386

        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1

        fun commandFor(keyCode: Int): MediaResumeCommand? = when (keyCode) {
            KEYCODE_MEDIA_PLAY -> MediaResumeCommand.PLAY
            KEYCODE_MEDIA_PAUSE -> MediaResumeCommand.PAUSE
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_BYD_MEDIA_TOGGLE,
            -> MediaResumeCommand.TOGGLE
            else -> null
        }
    }
}
