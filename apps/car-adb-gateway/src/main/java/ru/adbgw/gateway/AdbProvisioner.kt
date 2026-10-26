package ru.adbgw.gateway

import android.content.Context
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class AdbAuthorizationRequiredException(cause: Throwable? = null) :
    Exception("Подтвердите запрос ADB на экране автомобиля", cause)

class AdbProvisioner(context: Context) {
    private val appContext = context.applicationContext

    // The background path never submits the public key: it proves existing trust and otherwise
    // reports AuthorizationRequired. The one key submission is the explicit foreground request.
    private fun passiveClient() =
        LocalAdbClient(appContext, IDENTITY, LocalAdbClient.AuthorizationPolicy.PASSIVE)

    suspend fun authorizeAndConfigure(
        endpoint: AdbEndpoint,
        applyBackgroundSettings: Boolean = true,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shell = selectShell(endpoint, passiveClient())
                val identity = shell("echo CAG_READY").trim()
                check(identity.contains("CAG_READY")) { "ADB shell did not return the expected identity" }
                val evidence = mutableListOf("ADB shell available at ${endpoint.host}:${endpoint.port}")
                if (applyBackgroundSettings) {
                    bestEffort(shell, "cmd deviceidle whitelist +$PACKAGE_NAME", evidence)
                    bestEffort(shell, "cmd appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow", evidence)
                    bestEffort(shell, "cmd appops set $PACKAGE_NAME RUN_ANY_IN_BACKGROUND allow", evidence)
                    bestEffort(shell, "am set-inactive $PACKAGE_NAME false", evidence)
                }
                evidence
            }
        }

    /**
     * Submits the app's ADB public key exactly once, for a foreground request only.
     *
     * <p>Best-effort: it returns a failure rather than throwing, since the caller then confirms the
     * real state with passive re-checks. Raw adbd is where authorization matters; a car with no raw
     * adbd fails here harmlessly and is served over the smart socket instead.
     */
    suspend fun requestAuthorization(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                passiveClient().requestAuthorization()
                Unit
            }
        }

    private fun selectShell(endpoint: AdbEndpoint, raw: LocalAdbClient): (String) -> String {
        if (endpoint.kind == AdbEndpointKind.RawAdbd) {
            return { command -> rawShell(raw, command) }
        }

        val rawResult = runCatching { raw.shell("echo CAG_READY") }
        rawResult.exceptionOrNull()?.let { mapAuthorization(it) }
        if (rawResult.isSuccess) {
            return { command -> rawShell(raw, command) }
        }
        val smart = SmartAdbShellClient(endpoint.host, endpoint.port)
        return smart::shell
    }

    private fun rawShell(client: LocalAdbClient, command: String): String = try {
        client.shell(command)
    } catch (error: Throwable) {
        mapAuthorization(error)
        throw error
    }

    // By type, not by message words: both failures mean the person must approve the key on screen.
    private fun mapAuthorization(error: Throwable) {
        if (error is LocalAdbClient.AuthorizationRequiredException ||
            error is LocalAdbClient.AuthorizationPendingException
        ) {
            throw AdbAuthorizationRequiredException(error)
        }
    }

    private fun bestEffort(
        shell: (String) -> String,
        command: String,
        evidence: MutableList<String>,
    ) {
        runCatching { shell(command) }
            .onSuccess { evidence += "$command: ${it.trim().ifBlank { "ok" }}" }
            .onFailure { evidence += "$command: unavailable (${it.message ?: it.javaClass.simpleName})" }
    }

    companion object {
        private const val PACKAGE_NAME = "ru.adbgw.gateway"
        private const val IDENTITY = "car-adb-gateway@local"
    }
}

class SmartAdbShellClient(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 1_000,
    private val readTimeoutMillis: Int = 4_000,
) {
    fun shell(command: String): String {
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(AdbProtocol.frameSmartSocketRequest("host:transport-any"))
            output.flush()
            AdbProtocol.readStatus(input)
            output.write(AdbProtocol.frameSmartSocketRequest("shell:$command"))
            output.flush()
            AdbProtocol.readStatus(input)
            return String(input.readBytes(), StandardCharsets.UTF_8)
        }
    }
}
