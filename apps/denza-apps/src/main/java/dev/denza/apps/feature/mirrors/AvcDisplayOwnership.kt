package dev.denza.apps.feature.mirrors

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Whether the stock renderer still draws into our camera surface.
 *
 * AVC's renderer has one output and one owner field, and AVC takes it back without telling anyone
 * (every PIP surface it creates, the full-screen view reverse opens, a camera-service reconnect).
 * `freeDisplay` nulls that field whoever owns it, so a free after such a steal freezes the stock
 * picture for the rest of its episode, the reverse view included (read from the OTA image,
 * 2026-09-23). Frames stop reaching our texture the moment the renderer draws elsewhere, which is
 * the one ownership signal a client has.
 */
object MirrorFrameWatch {
    /** The camera streams at 25–30 fps; this is many frames of silence, not a hiccup. */
    const val STALL_MS = 700L

    @Volatile private var lastFrameAtMs = -1L

    @JvmStatic fun reset() {
        lastFrameAtMs = -1L
    }

    @JvmStatic fun frame(nowMs: Long) {
        lastFrameAtMs = nowMs
    }

    /** Age of the last frame of the current session, or null before its first frame. */
    @JvmStatic fun ageMs(nowMs: Long): Long? = lastFrameAtMs.takeIf { it >= 0L }?.let { nowMs - it }

    /** True only when frames came and then stopped: the renderer draws somewhere else now. */
    @JvmStatic fun stolen(nowMs: Long): Boolean = ageMs(nowMs)?.let { it >= STALL_MS } ?: false
}

/**
 * A persisted note that AVC's owner field may still hold a surface of ours.
 *
 * It is set before `initDisplay` and cleared after our `freeDisplay` returns. It survives when we
 * skipped the free because AVC had taken its renderer back, and when this process died holding
 * it (a crash, a force-stop, an `install -r` during a camera session): AVC has no death link, and
 * a foreign value in that field makes its next window-creating PIP crash. The monitor clears such
 * a note with [AvcIdleRelease] once AVC reports idle, when a free cannot freeze anything of its.
 */
object AvcDisplayClaim {
    private const val PREFS = "mirrors_avc_display"
    private const val CLAIMED = "claimed"

    @JvmStatic fun claim(context: Context) = write(context, true)

    @JvmStatic fun release(context: Context) = write(context, false)

    @JvmStatic fun isClaimed(context: Context): Boolean =
        prefs(context).getBoolean(CLAIMED, false)

    @SuppressLint("UseKtx")
    private fun write(context: Context, claimed: Boolean) {
        // apply keeps the disk off the camera start path; the write lands within milliseconds,
        // long before a camera session could end in a crash or an install.
        prefs(context).edit().putBoolean(CLAIMED, claimed).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** One `freeDisplay` on AVC's AIDL service, for use only while AVC reports idle. */
internal object AvcIdleRelease {
    private const val TAG = "DenzaAvcIdleRelease"
    private const val DESCRIPTOR = "com.byd.avc.aidl.IAVCAidlInterface"
    private const val FREE_DISPLAY = 9
    private const val BIND_TIMEOUT_MS = 1_000L

    fun release(context: Context): Boolean {
        val bound = ArrayBlockingQueue<IBinder>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bound.offer(service)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val requested = runCatching {
            context.bindService(
                Intent("com.byd.avc.aidl.service").setPackage("com.byd.avc"),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!requested) return false
        return try {
            val binder = bound.poll(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                binder.transact(FREE_DISPLAY, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
            AvcDisplayClaim.release(context)
            Log.i(TAG, "stale AVC display claim released while AVC was idle")
            true
        } catch (error: Exception) {
            Log.w(TAG, "idle release failed", error)
            false
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }
}
