package dev.denza.apps.feature.media

import android.content.Context
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps shell startup and focus management off Accessibility's key-event deadline. */
class MediaFocusPauseBridge(context: Context) : MediaPausePreparation, AutoCloseable {
    private val app = context.applicationContext
    private val client = DenzaLocalAdb.client(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var path: String? = null

    fun warm() {
        if (closed || path != null || !busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val bytes = app.assets.open("media-focus-pause-proxy.jar").use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val staged = "/data/local/tmp/denza-media-focus-$hash.jar"
                if (client.shell("sha256sum '$staged' 2>/dev/null").substringBefore(' ') != hash) {
                    val payload = Base64.getEncoder().encodeToString(bytes)
                    client.shell("printf '%s' '$payload' | base64 -d > '$staged' && chmod 644 '$staged'")
                    check(client.shell("sha256sum '$staged'").substringBefore(' ') == hash)
                }
                if (!closed) path = staged
                Log.i(TAG, "focus preparation ready")
            } catch (error: Exception) {
                Log.i(TAG, "focus preparation unavailable", error)
            } finally {
                busy.set(false)
            }
        }
    }

    override fun isReady(): Boolean = !closed && path != null

    override fun prepare(request: MediaPausePreparationRequest, completion: MediaPausePreparationCompletion): Boolean {
        val staged = path ?: return false
        if (closed || !busy.compareAndSet(false, true)) return false
        val all = listOf(request.current) + request.predecessors
        if (all.size !in 2..17 || all.any { !PACKAGE.matches(it.packageName) || it.uid <= 0 }) {
            busy.set(false)
            return false
        }
        return runCatching {
            executor.execute {
                val success = runCatching {
                    check(!closed)
                    val arguments = all.joinToString(" ") { "'${it.packageName}' ${it.uid}" }
                    val output = client.shell(
                        "CLASSPATH='$staged' app_process /system/bin --nice-name=denza-media-focus " +
                            "dev.denza.apps.feature.media.MediaFocusPauseProxyMain $arguments",
                        5000,
                    )
                    Log.i(TAG, output.trim())
                    val ready = output.lineSequence()
                        .firstOrNull { it.startsWith(READY) }
                        ?.removePrefix(READY)
                        ?.trim()
                    // The one line of the whole feature that edits the car's own audio-focus stack,
                    // written where it can be read: whether it ran, and whose focus it took. The
                    // wheel's next and previous keys are routed by that stack, and this says in one
                    // entry whether we are the reason they stopped finding the right player.
                    MediaKeyDiagnostics.recordCompletion(
                        "focus ${ready ?: "unchanged"} " +
                            request.predecessors.joinToString(",") { it.packageName },
                        ready != null,
                    )
                    ready != null
                }.getOrElse { error ->
                    Log.i(TAG, "focus preparation failed", error)
                    MediaKeyDiagnostics.recordCompletion("focus-helper-failed", false)
                    false
                }
                busy.set(false)
                completion.onComplete(success && !closed)
            }
        }.onFailure { busy.set(false) }.isSuccess
    }

    override fun close() {
        closed = true
        path = null
        executor.shutdown()
    }

    private companion object {
        const val TAG = "DenzaMediaFocus"
        const val READY = "DENZA_MEDIA_FOCUS_READY "
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
