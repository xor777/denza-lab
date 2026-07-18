package dev.denza.apps.feature.navigation

import android.content.Context
import android.os.IBinder
import android.view.Surface
import dev.denza.disharebridge.LocalAdbClient
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object NavigationProxyClient {
    private const val PROCESS_NAME = "denza_apps_proxy"
    private const val KEY_COMMENT = "denza-apps@denza"
    private val lock = Any()
    @Volatile private var pendingToken: String? = null
    @Volatile private var token: String? = null
    @Volatile private var proxy: IClusterTaskProxy? = null
    @Volatile private var connectionLatch = CountDownLatch(0)
    @Volatile var onProxyDied: (() -> Unit)? = null

    fun ensureConnected(context: Context, timeoutMs: Long = 5_000L): IClusterTaskProxy {
        proxy?.let { return it }
        synchronized(lock) {
            proxy?.let { return it }
            val nextToken = randomToken()
            pendingToken = nextToken
            connectionLatch = CountDownLatch(1)
            val adb = LocalAdbClient(context, KEY_COMMENT)
            stopPreviousProxy(adb)
            val apk = shellQuote(context.applicationInfo.sourceDir)
            val command = "CLASSPATH=$apk nohup app_process /system/bin " +
                "dev.denza.apps.feature.navigation.ClusterProxyMain " +
                shellQuote(nextToken) + " </dev/null >/dev/null 2>&1 &"
            adb.shell(command)
        }
        if (!connectionLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("shell proxy connection timeout")
        }
        return proxy ?: throw IllegalStateException("shell proxy did not provide a binder")
    }

    fun currentToken(): String = token ?: throw IllegalStateException("shell proxy is not connected")

    fun createVirtualDisplay(
        proxy: IClusterTaskProxy,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Int = proxy.createVirtualDisplay(
        currentToken(),
        "Denza Navigation",
        width,
        height,
        densityDpi,
        surface,
    )

    fun releaseVirtualDisplay() {
        val service = proxy ?: return
        runCatching { service.releaseVirtualDisplay(currentToken()) }
    }

    fun disconnect() {
        proxy = null
        token = null
        pendingToken = null
    }

    internal fun acceptConnection(candidateToken: String, binder: IBinder) {
        if (candidateToken != pendingToken) return
        val service = IClusterTaskProxy.Stub.asInterface(binder) ?: return
        try {
            binder.linkToDeath({
                proxy = null
                token = null
                onProxyDied?.invoke()
            }, 0)
        } catch (_: android.os.RemoteException) {
            return
        }
        token = candidateToken
        proxy = service
        pendingToken = null
        connectionLatch.countDown()
    }

    private fun stopPreviousProxy(adb: LocalAdbClient) {
        val output = runCatching { adb.shell("pidof $PROCESS_NAME") }.getOrDefault("")
        val pids = output.trim().split(Regex("\\s+"))
            .filter { it.matches(Regex("[1-9][0-9]*")) }
        for (pid in pids) {
            runCatching { adb.shell("kill $pid") }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
