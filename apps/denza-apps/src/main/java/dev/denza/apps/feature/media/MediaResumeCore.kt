package dev.denza.apps.feature.media

/** A media session that can be addressed directly without global media-key routing. */
internal interface MediaResumeTarget {
    val identity: Any

    fun playback(): MediaResumePlayback

    fun isLive(): Boolean

    fun supports(command: MediaResumeCommand): Boolean

    fun supportsPauseCancellation(): Boolean

    fun play()

    fun pause()

    fun pauseForCancellation()
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
 * Remembers only a session that has actually played during this observer lifetime.
 *
 * Active sessions stay ordered by MediaSessionManager priority. A later PLAYING callback wins;
 * when callbacks race, a live read at command time prefers the remembered playing session and
 * otherwise the first playing session in platform order.
 */
internal class MediaResumeCore {
    private var targets = linkedMapOf<Any, MediaResumeTarget>()
    private val playedIdentities = linkedSetOf<Any>()
    private var rememberedIdentity: Any? = null

    @Synchronized
    fun reconcile(active: List<MediaResumeTarget>) {
        val next = linkedMapOf<Any, MediaResumeTarget>()
        active.forEach { target -> next[target.identity] = target }
        playedIdentities.retainAll(next.keys)
        if (rememberedIdentity !in next) rememberedIdentity = null
        targets = next

        val snapshots = next.values.mapNotNull { target ->
            runCatching { TargetSnapshot(target, target.playback()) }.getOrNull()
        }
        snapshots.forEach { snapshot ->
            when (snapshot.playback) {
                MediaResumePlayback.PLAYING -> playedIdentities += snapshot.target.identity
                MediaResumePlayback.ENDED -> forget(snapshot.target.identity)
                MediaResumePlayback.PAUSED,
                MediaResumePlayback.TRANSITIONAL,
                -> Unit
            }
        }

        if (rememberedIdentity == null) {
            snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }
                ?.let { rememberedIdentity = it.target.identity }
        }
    }

    @Synchronized
    fun onPlayback(identity: Any, playback: MediaResumePlayback) {
        if (identity !in targets) return
        when (playback) {
            MediaResumePlayback.PLAYING -> {
                playedIdentities += identity
                rememberedIdentity = identity
            }
            MediaResumePlayback.ENDED -> forget(identity)
            MediaResumePlayback.PAUSED,
            MediaResumePlayback.TRANSITIONAL,
            -> Unit
        }
    }

    @Synchronized
    fun remove(identity: Any) {
        targets.remove(identity)
        forget(identity)
    }

    @Synchronized
    fun perform(command: MediaResumeCommand): Boolean {
        val snapshots = targets.values.mapNotNull { target ->
            runCatching { TargetSnapshot(target, target.playback()) }.getOrNull()
        }
        var remembered = rememberedIdentity
        if (
            snapshots.any {
                it.target.identity == remembered && it.playback == MediaResumePlayback.ENDED
            }
        ) {
            forget(remembered)
            remembered = null
        }
        val playing = snapshots.firstOrNull {
            it.target.identity == remembered && it.playback == MediaResumePlayback.PLAYING
        } ?: snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }

        val selected = playing ?: snapshots.firstOrNull {
            it.target.identity == remembered && it.playback == MediaResumePlayback.PAUSED
        } ?: return false

        val directCommand = when (command) {
            MediaResumeCommand.TOGGLE -> {
                if (selected.playback == MediaResumePlayback.PLAYING) {
                    MediaResumeCommand.PAUSE
                } else {
                    MediaResumeCommand.PLAY
                }
            }
            else -> command
        }

        if (selected.target.identity !in targets) return false
        if (!runCatching { selected.target.isLive() }.getOrDefault(false)) return false
        if (!runCatching { selected.target.supports(directCommand) }.getOrDefault(false)) {
            return false
        }

        if (
            directCommand == MediaResumeCommand.PAUSE &&
            selected.playback == MediaResumePlayback.PLAYING &&
            !cancelPendingResume(snapshots, selected.target.identity)
        ) {
            return false
        }

        if (selected.playback == MediaResumePlayback.PLAYING) {
            playedIdentities += selected.target.identity
            rememberedIdentity = selected.target.identity
        }

        return runCatching {
            when (directCommand) {
                MediaResumeCommand.PLAY -> selected.target.play()
                MediaResumeCommand.PAUSE -> selected.target.pause()
                MediaResumeCommand.TOGGLE -> error("toggle must resolve before dispatch")
            }
        }.isSuccess
    }

    @Synchronized
    fun clear() {
        targets.clear()
        playedIdentities.clear()
        rememberedIdentity = null
    }

    private fun cancelPendingResume(
        snapshots: List<TargetSnapshot>,
        selectedIdentity: Any,
    ): Boolean {
        val pausedPrevious = snapshots.filter { snapshot ->
            snapshot.target.identity != selectedIdentity &&
                snapshot.target.identity in playedIdentities &&
                snapshot.playback == MediaResumePlayback.PAUSED &&
                runCatching {
                    snapshot.target.isLive() && snapshot.target.supportsPauseCancellation()
                }.getOrDefault(false)
        }
        return pausedPrevious.all { snapshot ->
            runCatching { snapshot.target.pauseForCancellation() }.isSuccess
        }
    }

    private fun forget(identity: Any?) {
        playedIdentities.remove(identity)
        if (rememberedIdentity == identity) rememberedIdentity = null
    }

    private data class TargetSnapshot(
        val target: MediaResumeTarget,
        val playback: MediaResumePlayback,
    )
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
