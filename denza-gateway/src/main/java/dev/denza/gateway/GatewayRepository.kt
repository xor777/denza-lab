package dev.denza.gateway

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object GatewayRepository {
    private const val PREFS_NAME = "gateway_config"
    private const val KEY_ENDPOINT_MODE = "endpoint_mode"
    private const val KEY_ADB_SERVER_HOST = "adb_server_host"
    private const val KEY_ADB_SERVER_PORT = "adb_server_port"
    private const val KEY_RAW_ADBD_HOST = "raw_adbd_host"
    private const val KEY_RAW_ADBD_PORT = "raw_adbd_port"

    private val mutex = Mutex()
    private val logBuffer = GatewayLogBuffer()
    private val _state = MutableStateFlow(GatewayUiState())
    private var appContext: Context? = null
    private var server: SshGatewayServer? = null

    val state: StateFlow<GatewayUiState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val config = loadConfig(context)
        _state.update { it.copy(config = config, logs = logBuffer.snapshot()) }
        refreshWifi(context)
        log(LogLevel.Info, "Gateway UI initialized")
    }

    fun refreshWifi(context: Context) {
        val binding = NetworkInfoProvider(context.applicationContext).currentWifiBinding()
        _state.update { current ->
            current.copy(
                wifiBinding = binding,
                status = when {
                    current.isRunning -> current.status
                    binding == null -> GatewayStatus.NoWifi
                    current.activeEndpoint != null -> GatewayStatus.Ready
                    else -> GatewayStatus.Stopped
                },
            )
        }
    }

    fun updateEndpointMode(mode: EndpointMode) {
        updateConfig { it.copy(endpointMode = mode) }
    }

    fun updateAdbServerHost(host: String) {
        updateConfig { it.copy(adbServerHost = host.ifBlank { "127.0.0.1" }) }
    }

    fun updateAdbServerPort(port: Int) {
        updateConfig { it.copy(adbServerPort = port.coerceIn(1, 65_535)) }
    }

    fun updateRawAdbdHost(host: String) {
        updateConfig { it.copy(rawAdbdHost = host.ifBlank { "127.0.0.1" }) }
    }

    fun updateRawAdbdPort(port: Int) {
        updateConfig { it.copy(rawAdbdPort = port.coerceIn(1, 65_535)) }
    }

    fun rotateCode() {
        _state.update { it.copy(pairingCode = AccessCodeGenerator.generate()) }
        log(LogLevel.Info, "Pairing code rotated")
    }

    suspend fun testAdb(context: Context) {
        mutex.withLock {
            val binding = NetworkInfoProvider(context.applicationContext).currentWifiBinding()
            _state.update { it.copy(isBusy = true, wifiBinding = binding, lastError = null) }
            log(LogLevel.Info, "Testing ADB endpoint in ${_state.value.config.endpointMode.title} mode")

            val result = AdbProbe().detect(_state.value.config)
            result.onSuccess { endpoint ->
                _state.update {
                    it.copy(
                        status = when {
                            it.isRunning -> it.status
                            binding == null -> GatewayStatus.NoWifi
                            else -> GatewayStatus.Ready
                        },
                        wifiBinding = binding,
                        activeEndpoint = endpoint,
                        isBusy = false,
                        lastError = if (binding == null) "ADB is reachable, but Wi-Fi is required for the gateway" else null,
                    )
                }
                log(LogLevel.Info, "ADB endpoint ready: ${endpoint.kind.title} at ${endpoint.host}:${endpoint.port} (${endpoint.detail})")
                if (binding == null) {
                    log(LogLevel.Warn, "Wi-Fi IPv4 address is missing; SSH gateway cannot start yet")
                }
            }.onFailure { error ->
                val message = error.gatewayMessage()
                _state.update {
                    it.copy(
                        status = GatewayStatus.AdbUnavailable,
                        wifiBinding = binding,
                        activeEndpoint = null,
                        isBusy = false,
                        lastError = message,
                    )
                }
                log(LogLevel.Error, "ADB test failed: $message")
            }
        }
    }

    suspend fun startGateway(context: Context) {
        mutex.withLock {
            stopGatewayLocked()
            val app = context.applicationContext
            val binding = NetworkInfoProvider(app).currentWifiBinding()
            if (binding == null) {
                _state.update {
                    it.copy(
                        status = GatewayStatus.NoWifi,
                        wifiBinding = null,
                        activeEndpoint = null,
                        gatewayActive = false,
                        isBusy = false,
                        lastError = "Connect the car to Wi-Fi first",
                    )
                }
                log(LogLevel.Warn, "Start failed: no active Wi-Fi IPv4 address")
                return
            }

            _state.update {
                it.copy(
                    status = GatewayStatus.Starting,
                    gatewayActive = false,
                    isBusy = true,
                    wifiBinding = binding,
                    lastError = null,
                )
            }
            log(LogLevel.Info, "Starting gateway on ${binding.hostAddress}:${_state.value.config.sshPort}")

            val endpointResult = AdbProbe().detect(_state.value.config)
            val endpoint = endpointResult.getOrElse { error ->
                val message = error.gatewayMessage()
                _state.update {
                    it.copy(
                        status = GatewayStatus.AdbUnavailable,
                        wifiBinding = binding,
                        activeEndpoint = null,
                        gatewayActive = false,
                        isBusy = false,
                        lastError = message,
                    )
                }
                log(LogLevel.Error, "Start failed: $message")
                return
            }

            val hostKeyFile = File(app.filesDir, "ssh_host_key")
            val gateway = SshGatewayServer(
                bindAddress = binding.address,
                port = _state.value.config.sshPort,
                allowedSubnet = binding.subnet,
                endpoint = endpoint,
                hostKeyFile = hostKeyFile,
                codeProvider = { _state.value.pairingCode },
                onLog = ::log,
                onClientCountChanged = { count ->
                    _state.update { current ->
                        if (!current.isRunning) {
                            current
                        } else if (count > 0) {
                            current.copy(status = GatewayStatus.ClientConnected)
                        } else {
                            current.copy(status = GatewayStatus.Running)
                        }
                    }
                },
                onBlockedPeer = { reason ->
                    _state.update {
                        if (it.isRunning) it.copy(status = GatewayStatus.BlockedPeer, lastError = reason) else it
                    }
                },
            )

            try {
                val fingerprint = gateway.start()
                server = gateway
                _state.update {
                    it.copy(
                        status = GatewayStatus.Running,
                        wifiBinding = binding,
                        activeEndpoint = endpoint,
                        hostFingerprint = fingerprint,
                        gatewayActive = true,
                        isBusy = false,
                        lastError = null,
                    )
                }
                log(LogLevel.Info, "Gateway ready; SSH host fingerprint $fingerprint")
            } catch (error: Throwable) {
                runCatching { gateway.stop() }
                val message = error.gatewayMessage()
                _state.update {
                    it.copy(
                        status = GatewayStatus.Error,
                        wifiBinding = binding,
                        activeEndpoint = endpoint,
                        gatewayActive = false,
                        isBusy = false,
                        lastError = message,
                    )
                }
                log(LogLevel.Error, "Gateway failed to start: $message")
            }
        }
    }

    suspend fun stopGateway() {
        mutex.withLock {
            stopGatewayLocked()
            _state.update {
                it.copy(
                    status = GatewayStatus.Stopped,
                    gatewayActive = false,
                    isBusy = false,
                    lastError = null,
                )
            }
        }
    }

    suspend fun onServiceDestroyed() {
        mutex.withLock {
            val wasRunning = _state.value.isRunning
            stopGatewayLocked()
            _state.update {
                if (wasRunning) {
                    it.copy(status = GatewayStatus.Stopped, gatewayActive = false, isBusy = false)
                } else {
                    it.copy(gatewayActive = false, isBusy = false)
                }
            }
        }
    }

    private fun stopGatewayLocked() {
        server?.let { current ->
            runCatching { current.stop() }
                .onFailure { log(LogLevel.Warn, "Gateway stop reported: ${it.gatewayMessage()}") }
        }
        server = null
    }

    private fun updateConfig(transform: (GatewayConfig) -> GatewayConfig) {
        _state.update { it.copy(config = transform(it.config)) }
        appContext?.let { saveConfig(it, _state.value.config) }
        log(LogLevel.Info, "Endpoint mode: ${_state.value.config.endpointMode.title}")
    }

    private fun log(level: LogLevel, message: String) {
        val snapshot = logBuffer.add(level, message)
        _state.update { it.copy(logs = snapshot) }
    }

    private fun loadConfig(context: Context): GatewayConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = runCatching {
            EndpointMode.valueOf(prefs.getString(KEY_ENDPOINT_MODE, EndpointMode.Auto.name) ?: EndpointMode.Auto.name)
        }.getOrDefault(EndpointMode.Auto)
        return GatewayConfig(
            endpointMode = mode,
            adbServerHost = prefs.getString(KEY_ADB_SERVER_HOST, "127.0.0.1") ?: "127.0.0.1",
            adbServerPort = prefs.getInt(KEY_ADB_SERVER_PORT, 5037),
            rawAdbdHost = prefs.getString(KEY_RAW_ADBD_HOST, "127.0.0.1") ?: "127.0.0.1",
            rawAdbdPort = prefs.getInt(KEY_RAW_ADBD_PORT, 5555),
        )
    }

    private fun saveConfig(context: Context, config: GatewayConfig) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT_MODE, config.endpointMode.name)
            .putString(KEY_ADB_SERVER_HOST, config.adbServerHost)
            .putInt(KEY_ADB_SERVER_PORT, config.adbServerPort)
            .putString(KEY_RAW_ADBD_HOST, config.rawAdbdHost)
            .putInt(KEY_RAW_ADBD_PORT, config.rawAdbdPort)
            .apply()
    }
}
