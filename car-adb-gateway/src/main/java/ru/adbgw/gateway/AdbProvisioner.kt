package ru.adbgw.gateway

import android.content.Context
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class AdbAuthorizationRequiredException(cause: Throwable? = null) :
    Exception("Подтвердите системный запрос отладки по USB на экране", cause)

class AdbProvisioner(context: Context) {
    private val appContext = context.applicationContext

    suspend fun authorizeAndConfigure(
        endpoint: AdbEndpoint,
        applyBackgroundSettings: Boolean = true,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shell = selectShell(endpoint)
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

    private fun selectShell(endpoint: AdbEndpoint): (String) -> String {
        val raw = LocalAdbClient(appContext, "car-adb-gateway@local")
        if (endpoint.kind == AdbEndpointKind.RawAdbd) {
            return { command -> rawShell(raw, command) }
        }

        val rawResult = runCatching { raw.shell("echo CAG_READY") }
        rawResult.exceptionOrNull()?.let { error ->
            if (error.message.orEmpty().contains("authorization pending", ignoreCase = true)) {
                throw AdbAuthorizationRequiredException(error)
            }
        }
        if (rawResult.isSuccess) {
            return { command -> rawShell(raw, command) }
        }
        val smart = SmartAdbShellClient(endpoint.host, endpoint.port)
        return smart::shell
    }

    private fun rawShell(client: LocalAdbClient, command: String): String = try {
        client.shell(command)
    } catch (error: Throwable) {
        if (error.message.orEmpty().contains("authorization pending", ignoreCase = true)) {
            throw AdbAuthorizationRequiredException(error)
        }
        throw error
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
