package dev.denza.apps.feature.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.service.media.MediaBrowserService
import android.view.KeyEvent

/**
 * Gets a session back for a package that has none left.
 *
 * When the player's process is gone there is no token to command and nothing to remember: the old
 * policy had to hand the press to the firmware, which opens the stock media center. The platform
 * defines two ways back, and both are the ones a carkit or a headset already uses. Neither names a
 * package in this file - the package comes from the persisted record.
 *
 *  1. Connect to the package's exported browser service as a client, take the session token it
 *     publishes and command that. This is the good path: it hands back a controller the policy
 *     keeps, so the next press is an ordinary direct command.
 *  2. If there is no such service, or it refuses us, send the package's own exported media-button
 *     receiver an explicit `ACTION_MEDIA_BUTTON` with `KEYCODE_MEDIA_PLAY`. This is a directed
 *     command to one named component, not a key handed back to the firmware's routing - nothing
 *     of ours reaches the stock player. Nothing comes back either; the session it creates is
 *     picked up by the next active-session read.
 *
 * Path 2 is not hypothetical. Yandex Music's `MusicBrowserService.onGetRoot` runs a Google-style
 * caller allowlist and returns `null` to anyone it does not recognise - see the findings doc. Its
 * media-button receiver is exported and unguarded, and forwards `KEYCODE_MEDIA_PLAY` to the real
 * player service.
 *
 * The press is consumed the moment either path starts, exactly like the deferred pause, because
 * splitting one press between us and the firmware is worse than losing it. When both paths are
 * out, the reason is logged and nothing else happens - no second guess, no stock injection.
 *
 * Everything here runs on [handler]'s looper, which is the main one: `MediaBrowser` builds its own
 * handler from the calling thread.
 */
internal class MediaResumeReconnect(
    context: Context,
    private val handler: Handler,
    private val onOutcome: (MediaResumeDecision) -> Unit,
    private val onController: (MediaController) -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val app = context.applicationContext
    private var attempt: Attempt? = null

    fun inFlight(): Boolean = attempt != null

    fun start(packageName: String): MediaResumeDecision {
        if (attempt != null) {
            return MediaResumeDecision(true, MediaResumeReason.RESUME_IN_FLIGHT, packageName)
        }
        val service = browserService(packageName)
        if (service == null) {
            onOutcome(
                MediaResumeDecision(false, MediaResumeReason.NO_BROWSER_SERVICE, packageName),
            )
            return mediaButton(packageName)
        }

        val started = Attempt(packageName)
        val browser = runCatching { MediaBrowser(app, service, started.callback, null) }.getOrNull()
            ?: return failed(started, MediaResumeReason.RECONNECT_FAILED)
        started.browser = browser
        attempt = started
        handler.postDelayed(started.timeout, timeoutMillis)
        if (runCatching(browser::connect).isFailure) {
            return failed(started, MediaResumeReason.RECONNECT_FAILED)
        }
        return MediaResumeDecision(true, MediaResumeReason.RECONNECT_STARTED, packageName)
    }

    fun cancel() {
        attempt?.let { settle(it, null) }
    }

    /**
     * The package's own declaration of where a media client should knock.
     *
     * Only an exported, enabled service with the platform's browser action qualifies.
     */
    private fun browserService(packageName: String): ComponentName? = runCatching {
        app.packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName),
            0,
        ).mapNotNull { it.serviceInfo }
            .firstOrNull { it.exported && it.enabled }
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /**
     * One press, delivered to one named component, as a complete down/up pair.
     *
     * Only the first receiver is used. A package normally declares exactly one; sending to several
     * would risk two Play commands for one press of the wheel.
     */
    private fun mediaButton(packageName: String): MediaResumeDecision {
        val receiver = runCatching {
            app.packageManager.queryBroadcastReceivers(
                Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName),
                0,
            ).mapNotNull { it.activityInfo }
                .firstOrNull { it.exported && it.enabled }
                ?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull() ?: return MediaResumeDecision(
            false,
            MediaResumeReason.NO_MEDIA_BUTTON_RECEIVER,
            packageName,
        )

        val sent = runCatching {
            app.sendBroadcast(mediaButton(receiver, KeyEvent.ACTION_DOWN))
            app.sendBroadcast(mediaButton(receiver, KeyEvent.ACTION_UP))
        }.isSuccess
        return MediaResumeDecision(
            sent,
            if (sent) {
                MediaResumeReason.MEDIA_BUTTON_SENT
            } else {
                MediaResumeReason.NO_MEDIA_BUTTON_RECEIVER
            },
            packageName,
        )
    }

    private fun mediaButton(receiver: ComponentName, action: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(receiver)
            .putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY),
            )

    /** Reports why the browser path ended, then answers with what is left of the press. */
    private fun failed(started: Attempt, reason: String): MediaResumeDecision {
        settle(started, MediaResumeDecision(false, reason, started.packageName))
        return mediaButton(started.packageName)
    }

    /** Idempotent: a connection that fails and then times out must end the press only once. */
    private fun settle(started: Attempt, outcome: MediaResumeDecision?) {
        if (started.settled) return
        started.settled = true
        if (attempt === started) attempt = null
        handler.removeCallbacks(started.timeout)
        runCatching { started.browser?.disconnect() }
        outcome?.let(onOutcome)
    }

    private inner class Attempt(val packageName: String) {
        var browser: MediaBrowser? = null
        var settled = false

        val timeout = Runnable {
            onOutcome(failed(this, MediaResumeReason.RECONNECT_TIMEOUT))
        }

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (attempt !== this@Attempt) return
                val token = runCatching { browser?.sessionToken }.getOrNull()
                val controller = token?.let {
                    runCatching { MediaController(app, it) }.getOrNull()
                }
                if (controller == null) {
                    onOutcome(failed(this@Attempt, MediaResumeReason.RECONNECT_FAILED))
                    return
                }
                // Registered before the command, so the session this press revives is already a
                // target of the policy when its first state change arrives.
                onController(controller)
                val played = runCatching { controller.transportControls.play() }.isSuccess
                if (played) {
                    settle(
                        this@Attempt,
                        MediaResumeDecision(
                            true,
                            MediaResumeReason.RECONNECT_PLAYED,
                            packageName,
                        ),
                    )
                } else {
                    onOutcome(failed(this@Attempt, MediaResumeReason.RECONNECT_FAILED))
                }
            }

            override fun onConnectionFailed() = fail()

            override fun onConnectionSuspended() = fail()

            private fun fail() {
                if (settled) return
                onOutcome(failed(this@Attempt, MediaResumeReason.RECONNECT_FAILED))
            }
        }
    }

    private companion object {
        /**
         * A player that has to be started cold needs more than a frame and less than a thought.
         * Past this the press is simply lost, which is the honest answer - the alternative is a
         * command arriving seconds after the driver gave up and pressed again.
         */
        const val TIMEOUT_MILLIS = 3_000L
    }
}
