package dev.denza.apps.feature.mirrors

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** How the owner set the stock turn-signal camera (the value AVC's `what=1011` reports in `arg2`). */
enum class AvcTurnCameraChoice(val wire: Int) {
    /** Floating PIP; the left image goes to the instrument panel. The stock default. */
    PIP_LEFT_ON_METER(0),

    /** Floating PIP; both images stay on the head unit. */
    PIP_ON_HEAD_UNIT(1),

    /** The lever opens the full-screen surround view instead of a PIP. */
    FULL_SCREEN(2),

    /** The lever opens nothing. */
    OFF(3),
    ;

    companion object {
        fun fromWire(value: Int): AvcTurnCameraChoice? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Mirrors take over AVC's turn card, so they need one to exist, and the head-unit card is the one
 * AVC switches sides on without rebuilding (no crash path on a fast left-to-right). Turning Mirrors
 * on sets the stock choice to [WANTED] and remembers what the owner had; turning them off gives
 * that back, unless the owner has chosen something else since.
 */
object MirrorStockChoicePolicy {
    val WANTED = AvcTurnCameraChoice.PIP_ON_HEAD_UNIT

    data class Step(
        val write: AvcTurnCameraChoice? = null,
        /** Store this as the owner's choice to give back; null leaves the stored one alone. */
        val remember: AvcTurnCameraChoice? = null,
        val forget: Boolean = false,
    )

    fun onEnable(current: AvcTurnCameraChoice, remembered: AvcTurnCameraChoice?): Step =
        if (current == WANTED) Step() else Step(write = WANTED, remember = remembered ?: current)

    fun onDisable(current: AvcTurnCameraChoice, remembered: AvcTurnCameraChoice?): Step = when {
        remembered == null -> Step()
        current == WANTED && remembered != WANTED -> Step(write = remembered, forget = true)
        else -> Step(forget = true)
    }
}

/** The stock AVC mode ids that matter here (`com.byd.avc.util.Event`). */
object AvcStockMode {
    const val IDLE = 5000
    const val PIP_LEFT = 5095
    const val PIP_RIGHT = 5096
    const val PIP_RIGHT_PORTRAIT = 5099

    /**
     * Every mode but idle and a turn card draws through a view AVC creates and binds itself
     * (reverse, full screen, the radar and CMS cards).
     */
    fun bindsRendererItself(mode: Int): Boolean = mode != IDLE && turnSide(mode) == null

    /** The side of a turn-signal PIP, or null for idle, full-screen and the radar/CMS views. */
    fun turnSide(mode: Int): MirrorSide? = when (mode) {
        PIP_LEFT -> MirrorSide.LEFT
        PIP_RIGHT, PIP_RIGHT_PORTRAIT -> MirrorSide.RIGHT
        else -> null
    }
}

/**
 * The stock AVC's exported Messenger, `com.byd.avc/.AutoVideoService` (action
 * `com.byd.action.AVCSERVICE`): no permission, no caller check. Read from the OTA image and
 * live-proven from an ordinary app UID on 2026-09-23 (6–11 ms per round trip). The handler runs
 * on AVC's main thread, the one that rebuilds the PIP, so callers ask when a transition needs the
 * answer, never on an idle timer.
 *
 * Calls block the calling thread up to [timeoutMs] and return null on any failure; a missing
 * answer is never turned into a guess.
 */
internal class AvcStockClient(
    private val context: Context,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
) : AutoCloseable {
    private val replyThread = HandlerThread("denza-avc-stock").apply { start() }
    private val replies = LinkedBlockingQueue<Message>()
    private val replyTo = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(message: Message) {
            replies.offer(Message.obtain(message))
        }
    })

    @Volatile private var service: Messenger? = null
    @Volatile private var bindRequested = false
    @Volatile private var closed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            unbind()
        }
    }

    /** AVC's current mode, e.g. [AvcStockMode.PIP_LEFT]; null when AVC did not answer. */
    fun mode(): Int? = ask(WHAT_MODE, WHAT_MODE)?.arg2

    fun turnCameraChoice(): AvcTurnCameraChoice? =
        ask(WHAT_LIGHT_READ, WHAT_LIGHT_STATE)?.let { AvcTurnCameraChoice.fromWire(it.arg2) }

    /**
     * Writes the owner's stock choice, as the stock settings page would (`what=1013`; AVC persists
     * it in `/collect2/autovideo/initSettingParam.json`). Returns the choice AVC reports after the
     * write, which is the old one on a car whose AVC has no PIP.
     */
    fun writeTurnCameraChoice(choice: AvcTurnCameraChoice): AvcTurnCameraChoice? =
        ask(WHAT_LIGHT_WRITE, WHAT_LIGHT_STATE, choice.wire)?.let { AvcTurnCameraChoice.fromWire(it.arg2) }

    /** A late answer to an earlier, timed-out question is skipped by its `what`, not taken. */
    @Synchronized
    private fun ask(what: Int, answer: Int, arg1: Int = 0): Message? {
        if (closed) return null
        val messenger = service ?: run {
            ensureBound()
            return null
        }
        replies.clear()
        return try {
            messenger.send(Message.obtain(null, what, arg1, 0).also { it.replyTo = replyTo })
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var reply: Message? = null
            while (reply == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) break
                reply = replies.poll(left, TimeUnit.NANOSECONDS)?.takeIf { it.what == answer }
            }
            reply
        } catch (error: Exception) {
            Log.w(TAG, "AVC stock query $what failed", error)
            null
        }
    }

    private fun ensureBound() {
        if (bindRequested || closed) return
        // Flags 0 attaches to the running service only: creating it would run AVC's
        // AbsAndroidService.onCreate, whose context state 6002 hides a showing meter PIP.
        bindRequested = runCatching {
            context.bindService(
                Intent(ACTION).setPackage(PACKAGE),
                connection,
                0,
            )
        }.onFailure { Log.w(TAG, "AVC stock bind failed", it) }.getOrDefault(false)
    }

    private fun unbind() {
        if (!bindRequested) return
        bindRequested = false
        runCatching { context.unbindService(connection) }
    }

    override fun close() {
        closed = true
        service = null
        unbind()
        replyThread.quitSafely()
    }

    companion object {
        private const val TAG = "DenzaAvcStock"
        private const val PACKAGE = "com.byd.avc"
        private const val ACTION = "com.byd.action.AVCSERVICE"
        private const val WHAT_MODE = 35
        private const val WHAT_LIGHT_READ = 1011
        private const val WHAT_LIGHT_STATE = 1012
        private const val WHAT_LIGHT_WRITE = 1013
        private const val REPLY_TIMEOUT_MS = 150L
    }
}
