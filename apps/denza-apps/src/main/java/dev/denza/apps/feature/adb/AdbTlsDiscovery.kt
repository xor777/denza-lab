package dev.denza.apps.feature.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.denza.disharebridge.LocalAdbTlsClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlin.coroutines.resume

/** One discovery registration and one resolver at a time; cancellation always releases discovery. */
internal class AdbTlsDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION")
    suspend fun discover(timeoutMs: Long): AdbTlsEndpoint? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val lock = Any()
            val queue = ArrayDeque<NsdServiceInfo>()
            val seen = mutableSetOf<String>()
            var resolving = false
            var started = false
            var stopped = false
            var finished = false
            lateinit var listener: NsdManager.DiscoveryListener

            fun stop() = synchronized(lock) {
                if (started && !stopped) {
                    stopped = true
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
            }
            fun finish(endpoint: AdbTlsEndpoint?) = synchronized(lock) {
                if (finished) return@synchronized
                finished = true
                stop()
                if (continuation.isActive) continuation.resume(endpoint)
            }
            fun resolveNext(): Unit = synchronized(lock) {
                if (finished || resolving || queue.isEmpty()) return@synchronized
                val service = queue.removeFirst()
                resolving = true
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val local = runCatching { info.host != null && LocalAdbTlsClient.isLocalAddress(info.host) }.getOrDefault(false)
                            synchronized(lock) { resolving = false }
                            if (local && info.port in 1..65535) finish(AdbTlsEndpoint(info.host.hostAddress!!, info.port))
                            else resolveNext()
                        }
                    })
                } catch (_: Exception) {
                    resolving = false
                    resolveNext()
                }
            }
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    synchronized(lock) { started = true; if (finished) stop() }
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(lock) {
                        if (finished || !info.serviceType.trimEnd('.').equals(SERVICE_TYPE, true) ||
                            !seen.add(info.serviceName)) return
                        queue.addLast(info)
                    }
                    resolveNext()
                }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, error: Int) = finish(null)
                override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            }
            continuation.invokeOnCancellation { synchronized(lock) { finished = true; stop() } }
            try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: Exception) { finish(null) }
        }
    }

    companion object { const val SERVICE_TYPE = "_adb-tls-connect._tcp" }
}
