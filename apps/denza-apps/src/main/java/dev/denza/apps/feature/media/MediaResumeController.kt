package dev.denza.apps.feature.media

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import dev.denza.apps.platform.media.MediaSessionHub

/**
 * The steering wheel's play/pause key, answered directly instead of by the firmware's routing.
 *
 * This is the Android half: the key filter, the log and the support report around
 * [MediaResumeSessions], which subscribes to the process's [MediaSessionHub], keeps a
 * [MediaResumeTarget] per session for as long as the session lives and asks the policy in
 * [MediaResumeCore] for every press; what the policy decided goes out as a direct transport command.
 * A package with no session left is not brought back: that press is refused and goes to the
 * firmware. The policy itself is pure and lives next door.
 *
 * The caller decides whether a new DOWN is safe to intercept. Once accepted, repeats and UP for
 * that press remain consumed even if the caller's guard changes before release.
 */
class MediaResumeController(context: Context) {
    private val app = context.applicationContext
    private val core = MediaResumeCore(MediaLastPlayedPreferences(app))
    private val keyInterceptor = MediaResumeKeyInterceptor()
    private val sessions = MediaResumeSessions(MediaSessionHub.get(app), core) { message, error ->
        Log.i(TAG, message, error)
    }

    /** Subscribes once; called again after an access repair, it has the hub listen if it does not. */
    fun start() {
        sessions.start()
    }

    fun stop() {
        sessions.stop()
        keyInterceptor.reset()
    }

    /** The flag the filter itself uses, so the support report cannot disagree with it. */
    fun isListening(): Boolean = sessions.isListening

    /** The persisted last-played package - what a Play press resolves from. No token leaves here. */
    fun rememberedPackage(): String? = core.lastPlayed()

    fun onKeyEvent(event: KeyEvent, allowNewPress: Boolean): Boolean {
        val listening = isListening()
        val relevantInitialDown =
            MediaResumeKeyInterceptor.commandFor(event.keyCode) != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
        val consumed = keyInterceptor.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            allowNewPress = allowNewPress && listening,
            perform = { command -> decide(event.keyCode, sessions.press(command)) },
        )
        if (relevantInitialDown) {
            Log.i(
                TAG,
                "media key=${event.keyCode} received allow=$allowNewPress consumed=$consumed",
            )
        }
        // Every code, not only the four we intercept: a wheel that emits 334 and a wheel that
        // emits nothing look the same from a car whose logcat we cannot read.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MediaKeyDiagnostics.recordPress(
                keyCode = event.keyCode,
                media = MediaResumeKeyInterceptor.commandFor(event.keyCode) != null,
                allowed = allowNewPress,
                listening = listening,
                consumed = consumed,
            )
        }
        return consumed
    }

    /**
     * The one place a media press is accepted or refused.
     *
     * Every branch of the policy ends here, so a press never disappears without a named reason in
     * the log, and the support report's ring is fed from the same line.
     */
    private fun decide(keyCode: Int, decision: MediaResumeDecision): Boolean {
        Log.i(
            TAG,
            "media command ${if (decision.accepted) "accepted" else "skipped"} " +
                "key=$keyCode reason=${decision.reason} " +
                "package=${decision.packageName ?: "-"}",
        )
        MediaKeyDiagnostics.note(MediaKeyDetail.decision(decision))
        return decision.accepted
    }

    private companion object {
        const val TAG = "DenzaMediaResume"
    }
}
