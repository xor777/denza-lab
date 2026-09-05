package dev.denza.apps.feature.media

/** A media session that can be addressed directly without global media-key routing. */
internal interface MediaResumeTarget {
    val identity: Any

    fun playback(): MediaResumePlayback

    fun play()

    fun pause()
}

internal enum class MediaResumePlayback {
    PLAYING,
    PAUSED,
    OTHER,
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
    private var rememberedIdentity: Any? = null

    @Synchronized
    fun reconcile(active: List<MediaResumeTarget>) {
        val next = linkedMapOf<Any, MediaResumeTarget>()
        active.forEach { target -> next[target.identity] = target }
        if (rememberedIdentity !in next) rememberedIdentity = null
        targets = next

        if (rememberedIdentity == null) {
            firstPlaying(next.values)?.let { rememberedIdentity = it.identity }
        }
    }

    @Synchronized
    fun onPlaying(identity: Any) {
        if (identity in targets) rememberedIdentity = identity
    }

    @Synchronized
    fun remove(identity: Any) {
        targets.remove(identity)
        if (rememberedIdentity == identity) rememberedIdentity = null
    }

    @Synchronized
    fun perform(command: MediaResumeCommand): Boolean {
        val snapshots = targets.values.mapNotNull { target ->
            runCatching { TargetSnapshot(target, target.playback()) }.getOrNull()
        }
        val remembered = rememberedIdentity
        val playing = snapshots.firstOrNull {
            it.target.identity == remembered && it.playback == MediaResumePlayback.PLAYING
        } ?: snapshots.firstOrNull { it.playback == MediaResumePlayback.PLAYING }

        val selected = playing ?: snapshots.firstOrNull {
            it.target.identity == remembered && it.playback == MediaResumePlayback.PAUSED
        } ?: return false

        if (selected.playback == MediaResumePlayback.PLAYING) {
            rememberedIdentity = selected.target.identity
        }

        return runCatching {
            when (command) {
                MediaResumeCommand.PLAY -> selected.target.play()
                MediaResumeCommand.PAUSE -> selected.target.pause()
                MediaResumeCommand.TOGGLE -> {
                    if (selected.playback == MediaResumePlayback.PLAYING) {
                        selected.target.pause()
                    } else {
                        selected.target.play()
                    }
                }
            }
        }.isSuccess
    }

    @Synchronized
    fun clear() {
        targets.clear()
        rememberedIdentity = null
    }

    private fun firstPlaying(active: Collection<MediaResumeTarget>): MediaResumeTarget? =
        active.firstOrNull { target ->
            runCatching { target.playback() == MediaResumePlayback.PLAYING }.getOrDefault(false)
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
        const val KEYCODE_HEADSETHOOK = 79
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
