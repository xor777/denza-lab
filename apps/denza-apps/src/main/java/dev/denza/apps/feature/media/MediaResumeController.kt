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
class MediaResumeController internal constructor(
    private val core: MediaResumeCore,
    private val sessions: MediaResumeSessions,
    /** One line of the key's log; `Log.i` under `DenzaMediaResume` in the product. */
    private val log: (String) -> Unit,
) {
    constructor(context: Context) : this(
        MediaResumeCore(MediaLastPlayedPreferences(context.applicationContext)),
        context.applicationContext,
    )

    private constructor(core: MediaResumeCore, app: Context) : this(
        core,
        MediaResumeSessions(MediaSessionHub.get(app), core) { message, error -> Log.i(TAG, message, error) },
        { message -> Log.i(TAG, message) },
    )

    private val keyInterceptor = MediaResumeKeyInterceptor()

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

    /**
     * One key: its code, `KeyEvent.ACTION_DOWN` or `ACTION_UP` and its repeat count. True consumes
     * it. The answer is final once the interceptor gives it: the log line and the support report's
     * ring after it only tell about it, and a throw in them must not hand a press already answered
     * to the firmware as well.
     */
    fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int, allowNewPress: Boolean): Boolean {
        val listening = isListening()
        val consumed = keyInterceptor.onKeyEvent(
            keyCode = keyCode,
            action = action,
            repeatCount = repeatCount,
            allowNewPress = allowNewPress && listening,
            perform = { command -> decide(keyCode, sessions.press(command)) },
        )
        runCatching { tell(keyCode, action, repeatCount, allowNewPress, listening, consumed) }
        return consumed
    }

    private fun tell(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        allowNewPress: Boolean,
        listening: Boolean,
        consumed: Boolean,
    ) {
        val initialDown = action == KeyEvent.ACTION_DOWN && repeatCount == 0
        if (initialDown && MediaResumeKeyInterceptor.commandFor(keyCode) != null) {
            log("media key=$keyCode received allow=$allowNewPress consumed=$consumed")
        }
        // Every code, not only the four we intercept: a wheel that emits 334 and a wheel that
        // emits nothing look the same from a car whose logcat we cannot read.
        if (initialDown) {
            MediaKeyDiagnostics.recordPress(
                keyCode = keyCode,
                media = MediaResumeKeyInterceptor.commandFor(keyCode) != null,
                allowed = allowNewPress,
                listening = listening,
                consumed = consumed,
            )
        }
    }

    /**
     * The one place a media press is accepted or refused.
     *
     * Every branch of the policy ends here, so a press never disappears without a named reason in
     * the log, and the support report's ring is fed from the same line. The policy has decided, and
     * its command has gone out, before either: neither may undo the answer by throwing.
     */
    private fun decide(keyCode: Int, decision: MediaResumeDecision): Boolean {
        runCatching {
            log(
                "media command ${if (decision.accepted) "accepted" else "skipped"} " +
                    "key=$keyCode reason=${decision.reason} " +
                    "package=${decision.packageName ?: "-"}",
            )
            MediaKeyDiagnostics.note(MediaKeyDetail.decision(decision))
        }
        return decision.accepted
    }

    private companion object {
        const val TAG = "DenzaMediaResume"
    }
}
