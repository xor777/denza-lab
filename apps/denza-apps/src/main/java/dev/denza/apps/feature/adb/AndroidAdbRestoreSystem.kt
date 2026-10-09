package dev.denza.apps.feature.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.SimulcastCoordinator
import dev.denza.apps.StateMarks
import dev.denza.apps.StateSlice
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.core.DenzaRuntimeCoordinator
import dev.denza.disharebridge.LocalAdbClient
import dev.denza.disharebridge.LocalAdbTlsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidAdbRestoreSystem(context: Context) : AdbRestoreSystem {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val accessibilityPrepared = AtomicBoolean(false)
    override val sdk: Int get() = Build.VERSION.SDK_INT
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override suspend fun classicConnect(): Boolean = try {
        DenzaLocalAdb.client(app).shell("printf DENZA_ADB_RESTORE_OK", 1_500).contains("DENZA_ADB_RESTORE_OK")
    } catch (_: LocalAdbClient.AuthorizationRequiredException) {
        throw AdbRestoreKeyUntrustedException()
    } catch (_: Exception) { false }
    override fun permissionHeld(): Boolean = AdbPortRestore.isPermissionHeld(app)
    override suspend fun selfGrant(abandoned: () -> Boolean) {
        AdbPortRestore.ensurePermission(app, abandoned)
    }

    @Suppress("DEPRECATION")
    override fun wifiNetwork(): AdbRestoreWifi? = connectivity.allNetworks.firstNotNullOfOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
        val info = capabilities.transportInfo as? WifiInfo
        val bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" || it.isBlank() }
        val ssid = info?.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" || it.isBlank() }
        // Network handle stays stable across redacted capability callbacks. Hidden identities do
        // not cause every callback to be treated as a new hotspot.
        AdbRestoreWifi(bssid ?: "network:${network.networkHandle}", ssid ?: "wifi")
    }
    override fun readAdbWifiEnabled(): Boolean = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
    override fun writeAdbWifiEnabled(enabled: Boolean) {
        check(Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", if (enabled) 1 else 0)) {
            "Wireless debugging setting was not written"
        }
    }
    override fun tlsPortProperty(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()
    override suspend fun discoverTlsPort(timeoutMs: Long): AdbTlsEndpoint? = AdbTlsDiscovery(app).discover(timeoutMs)
    override suspend fun restartTcpip(endpoint: AdbTlsEndpoint, abandoned: () -> Boolean) {
        LocalAdbTlsClient(app, "denza-apps@denza").restartTcpip(endpoint.host, endpoint.port, abandoned)
    }
    override suspend fun ensureAccessibilityForDialog(abandoned: () -> Boolean) {
        if (abandoned()) return
        if (SimulcastCoordinator.isAccessibilityConnected()) return
        if (!accessibilityPrepared.compareAndSet(false, true)) return
        // Prepare even when no casting app is selected. Join the existing single-flight repair,
        // respecting the split service's ownership and preserving other accessibility entries.
        // A bound service is never churned. Transient boot failures get two bounded retries;
        // callback bursts cannot create another preparation wave in this process.
        try {
            repeat(3) { index ->
                if (abandoned() || SimulcastCoordinator.isAccessibilityConnected()) return
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        SimulcastCoordinator.repairAccess(app, onComplete = { failure ->
                            if (continuation.isActive) {
                                if (failure == null) continuation.resume(Unit)
                                else continuation.resumeWithException(failure)
                            }
                        }, stillWanted = { !abandoned() })
                    }
                    return
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (index == 2) throw failure
                    delay(AdbRestoreManager.WRITE_RETRY_INTERVAL_MS)
                }
            }
        } catch (cancelled: CancellationException) {
            accessibilityPrepared.set(false)
            throw cancelled
        }
    }
    override suspend fun recoverRuntime() {
        // The product's helpers belong to their feature runtimes; the normal recovery pass starts
        // exactly the enabled functions, without a second generic daemon or autostart owner.
        DenzaAppRepository.checkAdbAccess()
        DenzaRuntimeCoordinator.recover(app)
    }
}

/** Main-process lifetime; network callbacks carry hints, and the manager owns all decisions. */
object AdbRestore {
    @Volatile private var manager: AdbRestoreManager? = null
    @Volatile private var app: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun initialize(context: Context) {
        if (manager != null) return
        val application = context.applicationContext
        app = application
        manager = AdbRestoreManager(AndroidAdbRestoreSystem(application), AdbRestorePreferences(application),
            CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                StateMarks.mark(StateSlice.ADB_ACCESS, "adb restore")
                if (manager?.snapshot()?.state == AdbRestoreState.NotNeeded &&
                    AdbRescueCoordinator.snapshot().phase in listOf(AdbRescuePhase.UNAVAILABLE, AdbRescuePhase.ERROR,
                        AdbRescuePhase.AUTHORIZATION_REQUIRED, AdbRescuePhase.AWAITING_CONFIRMATION)) {
                    DenzaAppRepository.checkAdbAccess()
                }
            }
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trigger("wifi") }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) trigger("wifi")
            }
            override fun onLost(network: Network) { trigger("wifi-lost") }
        }
        runCatching {
            application.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), listener)
            callback = listener
        }
    }
    fun trigger(trigger: String) { manager?.attemptIfNeeded(trigger) }
    fun snapshot(): AdbRestoreSnapshot = manager?.snapshot() ?: AdbRestoreSnapshot()
    fun setEnabled(enabled: Boolean) { manager?.setEnabled(enabled) }
    fun recordAutoAllow(outcome: String) { manager?.recordAutoAllow(outcome) }
    @JvmStatic fun isEnabled(context: Context): Boolean = AdbRestorePreferences(context).enabled
    @JvmStatic fun onWifiDialog(service: android.accessibilityservice.AccessibilityService, event: android.view.accessibility.AccessibilityEvent) {
        WifiDebuggingDialogAutoAllow.onEvent(service, event)
    }
    fun recordTrusted(context: Context) { AdbRestorePreferences(context).trustedBefore = true }
}
