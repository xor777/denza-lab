package dev.denza.apps.feature.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import dev.denza.apps.feature.hud.YandexNotificationArtworkListener

/**
 * Direct play/pause for the last session observed actually playing.
 *
 * The caller decides whether a new DOWN is safe to intercept. Once accepted, repeats and UP for
 * that press remain consumed even if the caller's guard changes before release.
 */
class MediaResumeController @JvmOverloads constructor(
    context: Context,
    private val pausePreparation: MediaPausePreparation? = null,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val accessComponent =
        ComponentName(app, YandexNotificationArtworkListener::class.java)
    private val core = MediaResumeCore()
    private val keyInterceptor = MediaResumeKeyInterceptor()
    private val sessions = LinkedHashMap<MediaSession.Token, AndroidTarget>()
    private var listening = false
    private var pauseOperation: PauseOperation? = null
    private var nextPauseOperationId = 0L

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener(::reconcile)

    fun start() {
        if (listening || manager == null) return
        runCatching {
            manager.addOnActiveSessionsChangedListener(
                sessionsChanged,
                accessComponent,
                handler,
            )
            listening = true
            reconcile(manager.getActiveSessions(accessComponent))
        }.onFailure { error ->
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
            listening = false
            detachAll()
            Log.i(TAG, "media-session access unavailable", error)
        }
    }

    fun stop() {
        if (listening) {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        listening = false
        pauseOperation = null
        detachAll()
        keyInterceptor.reset()
    }

    fun onKeyEvent(event: KeyEvent, allowNewPress: Boolean): Boolean {
        val relevantInitialDown =
            MediaResumeKeyInterceptor.commandFor(event.keyCode) != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
        val consumed = keyInterceptor.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            allowNewPress = allowNewPress && listening,
            perform = { command ->
                decide(
                    event.keyCode,
                    when {
                        pauseOperation != null -> MediaResumeDecision(
                            accepted = true,
                            reason = MediaResumeReason.PAUSE_IN_FLIGHT,
                        )

                        !refreshBeforeCommand() -> MediaResumeDecision(
                            accepted = false,
                            reason = MediaResumeReason.SESSION_ACCESS,
                        )

                        else -> core.perform(command, ::deferPause)
                    },
                )
            },
        )
        if (relevantInitialDown) {
            Log.i(
                TAG,
                "media key=${event.keyCode} received allow=$allowNewPress consumed=$consumed",
            )
        }
        return consumed
    }

    /**
     * The one place a media press is accepted or refused.
     *
     * Every branch of the policy - the key path, the deferred pause completing later - ends here,
     * so a press never disappears without a named reason in the log.
     */
    private fun decide(keyCode: Int?, decision: MediaResumeDecision): Boolean {
        Log.i(
            TAG,
            "media command ${if (decision.accepted) "accepted" else "skipped"} " +
                "key=${keyCode ?: "-"} reason=${decision.reason} " +
                "package=${decision.packageName ?: "-"}",
        )
        return decision.accepted
    }

    private fun deferPause(
        selected: MediaResumeTarget,
        predecessors: List<MediaResumeTarget>,
    ): Boolean {
        val preparation = pausePreparation ?: return false
        if (!runCatching(preparation::isReady).getOrDefault(false)) return false
        val selectedTarget = selected as? AndroidTarget ?: return false
        val current = selectedTarget.pauseSession ?: return false
        val predecessorSessions = predecessors.map { target ->
            (target as? AndroidTarget)?.pauseSession ?: return false
        }.distinct()
        if (predecessorSessions.isEmpty()) return false

        val operation = PauseOperation(++nextPauseOperationId, selectedTarget)
        pauseOperation = operation
        val accepted = runCatching {
            preparation.prepare(
                MediaPausePreparationRequest(current, predecessorSessions),
                MediaPausePreparationCompletion { success ->
                    handler.post { completeDeferredPause(operation, success) }
                },
            )
        }.getOrDefault(false)
        if (!accepted && pauseOperation === operation) pauseOperation = null
        return accepted
    }

    private fun completeDeferredPause(operation: PauseOperation, prepared: Boolean) {
        if (!listening || pauseOperation !== operation) return
        pauseOperation = null
        val target = operation.target.packageName
        if (!prepared) {
            decide(null, MediaResumeDecision(false, MediaResumeReason.PAUSE_PREPARATION, target))
            return
        }
        if (!refreshBeforeCommand()) {
            decide(
                null,
                MediaResumeDecision(
                    false,
                    MediaResumeReason.SESSION_ACCESS_AFTER_PREPARATION,
                    target,
                ),
            )
            return
        }
        val completion = core.completeDeferredPause(operation.target)
        decide(
            null,
            MediaResumeDecision(
                accepted = completion == DeferredPauseCompletion.DISPATCHED,
                reason = when (completion) {
                    DeferredPauseCompletion.DISPATCHED -> MediaResumeReason.PAUSE
                    DeferredPauseCompletion.ALREADY_PAUSED -> MediaResumeReason.PAUSE_COMPLETE
                    DeferredPauseCompletion.STALE -> MediaResumeReason.STALE_AFTER_PREPARATION
                    DeferredPauseCompletion.FAILED -> MediaResumeReason.PAUSE_TRANSPORT
                },
                packageName = target,
            ),
        )
    }

    private fun reconcile(active: List<MediaController>?) {
        if (!listening) return
        val current = active.orEmpty().associateBy { it.sessionToken }
        sessions.keys.filterNot(current::containsKey).forEach(::remove)
        current.forEach { (token, controller) ->
            if (token !in sessions) {
                sessions[token] = AndroidTarget(controller).also(AndroidTarget::attach)
            }
        }
        core.reconcile(current.keys.mapNotNull(sessions::get))
    }

    private fun remove(token: MediaSession.Token) {
        sessions.remove(token)?.detach()
        core.remove(token)
    }

    private fun detachAll() {
        sessions.values.forEach(AndroidTarget::detach)
        sessions.clear()
        core.clear()
    }

    private inner class AndroidTarget(
        private val controller: MediaController,
    ) : MediaResumeTarget {
        override val identity: Any = controller.sessionToken
        override val packageName: String = controller.packageName
        val pauseSession: MediaPauseSession? = runCatching {
            MediaPauseSession(
                packageName = controller.packageName,
                uid = app.packageManager.getApplicationInfo(controller.packageName, 0).uid,
            )
        }.getOrNull()
        @Volatile
        private var destroyed = false

        private val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!isCurrent()) return
                core.onPlayback(identity, state.toResumePlayback())
            }

            override fun onSessionDestroyed() {
                if (!isCurrent()) return
                destroyed = true
                val token = controller.sessionToken
                remove(token)
                refresh()
            }
        }

        fun attach() {
            destroyed = false
            controller.registerCallback(callback, handler)
        }

        fun detach() {
            destroyed = true
            runCatching { controller.unregisterCallback(callback) }
        }

        override fun playback(): MediaResumePlayback =
            controller.playbackState.toResumePlayback()

        override fun isLive(): Boolean = !destroyed

        override fun supports(command: MediaResumeCommand): Boolean {
            val actions = controller.playbackState?.actions ?: return false
            val required = when (command) {
                MediaResumeCommand.PLAY -> PlaybackState.ACTION_PLAY
                MediaResumeCommand.PAUSE -> PlaybackState.ACTION_PAUSE
                MediaResumeCommand.TOGGLE -> return false
            }
            return actions and required != 0L
        }

        override fun play() {
            controller.transportControls.play()
            Log.i(TAG, "direct media command package=${controller.packageName} command=play")
        }

        override fun pause() {
            controller.transportControls.pause()
            Log.i(TAG, "direct media command package=${controller.packageName} command=pause")
        }

        private fun isCurrent(): Boolean =
            listening && sessions[controller.sessionToken] === this
    }

    private fun PlaybackState?.toResumePlayback(): MediaResumePlayback = when (this?.state) {
        PlaybackState.STATE_PLAYING -> MediaResumePlayback.PLAYING
        PlaybackState.STATE_PAUSED -> MediaResumePlayback.PAUSED
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR,
        null,
        -> MediaResumePlayback.ENDED
        else -> MediaResumePlayback.TRANSITIONAL
    }

    private fun refresh() {
        if (!listening) return
        val service = manager ?: return
        runCatching { reconcile(service.getActiveSessions(accessComponent)) }
            .onFailure { Log.i(TAG, "could not refresh media sessions", it) }
    }

    /** Reconciliation is part of accepting a DOWN, so a failed read leaves it to stock routing. */
    private fun refreshBeforeCommand(): Boolean {
        if (!listening) return false
        val service = manager ?: return false
        return runCatching {
            reconcile(service.getActiveSessions(accessComponent))
        }.fold(
            onSuccess = { listening },
            onFailure = { error ->
                Log.i(TAG, "could not validate media sessions", error)
                false
            },
        )
    }

    private companion object {
        const val TAG = "DenzaMediaResume"
    }

    private data class PauseOperation(
        val id: Long,
        val target: MediaResumeTarget,
    )
}
