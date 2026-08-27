package dev.denza.apps.feature.defaultapps

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Narrow suspendable boundary around the shell command used by this feature. */
internal fun interface DefaultAppRoleShell {
    suspend fun execute(command: String): String
}

/** Production adapter. Local ADB is blocking, so it never runs on the caller's dispatcher. */
private class DenzaLocalAdbDefaultAppRoleShell(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DefaultAppRoleShell {
    private val appContext = context.applicationContext
    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DenzaLocalAdb.client(appContext)
    }

    override suspend fun execute(command: String): String = withContext(ioDispatcher) {
        client.shell(command, SHELL_TIMEOUT_MS)
    }
}

/** A provider response that cannot prove the requested role state. */
class DefaultAppRoleAccessException(message: String) : IllegalStateException(message)

/**
 * Reads and writes the three stock AutoVoice default-app roles through passive local ADB.
 *
 * There is no background work or retry loop. Every call is an explicit coroutine operation, and
 * writes are successful only after a one-row preflight, an accepted update response, and an exact
 * readback. DiLink 5.1 emits empty stdout for a successful update; the standard exact one-row
 * report is accepted too, but neither form can bypass the readback. A mutex keeps those steps
 * contiguous when the UI submits concurrent requests.
 */
class DefaultAppRoleRepository internal constructor(
    private val shell: DefaultAppRoleShell,
) {
    constructor(context: Context) : this(DenzaLocalAdbDefaultAppRoleShell(context))

    private val operationMutex = Mutex()

    suspend fun read(role: DefaultAppRole): String = operationMutex.withLock {
        readUnlocked(role)
    }

    suspend fun set(
        role: DefaultAppRole,
        packageName: String,
    ): String = operationMutex.withLock {
        setUnlocked(role, packageName, expectedCurrentPackageName = null)
    }

    /**
     * Replaces a role only while the provider still contains [expectedCurrentPackageName].
     *
     * Used by first-run initialization so a concurrent choice made in stock settings or by
     * another explicit writer cannot be overwritten after our deciding read. The expectation is
     * present in the provider's UPDATE predicate, not only checked before the command.
     */
    suspend fun setIfCurrent(
        role: DefaultAppRole,
        expectedCurrentPackageName: String,
        packageName: String,
    ): String = operationMutex.withLock {
        setUnlocked(role, packageName, expectedCurrentPackageName)
    }

    private suspend fun setUnlocked(
        role: DefaultAppRole,
        packageName: String,
        expectedCurrentPackageName: String?,
    ): String {
        val roleKey = AutoVoicePersonBeanProtocol.roleKey(role)
        AutoVoicePersonBeanProtocol.requirePackageName(packageName, "target")
        expectedCurrentPackageName?.let { expected ->
            AutoVoicePersonBeanProtocol.requirePackageName(expected, "expected current")
        }

        // Refuse to mutate a missing, duplicated, mismatched, or malformed provider row.
        val current = readUnlocked(role)
        if (expectedCurrentPackageName != null && current != expectedCurrentPackageName) {
            throw DefaultAppRoleAccessException(
                "AutoVoice $roleKey changed to $current before the conditional write; " +
                    "expected $expectedCurrentPackageName",
            )
        }

        val updateOutput = shell.execute(
            AutoVoicePersonBeanProtocol.updateCommand(
                roleKey = roleKey,
                packageName = packageName,
                expectedCurrentPackageName = expectedCurrentPackageName,
            ),
        )
        AutoVoicePersonBeanProtocol.requireAcceptedUpdateOutput(roleKey, updateOutput)

        val persisted = readUnlocked(role)
        if (persisted != packageName) {
            throw DefaultAppRoleAccessException(
                "AutoVoice readback for $roleKey was $persisted, expected $packageName",
            )
        }
        return persisted
    }

    private suspend fun readUnlocked(role: DefaultAppRole): String {
        val roleKey = AutoVoicePersonBeanProtocol.roleKey(role)
        val output = shell.execute(AutoVoicePersonBeanProtocol.queryCommand(roleKey))
        return AutoVoicePersonBeanProtocol.parseSinglePackage(roleKey, output)
    }
}

internal object AutoVoicePersonBeanProtocol {
    private const val PROVIDER_URI = "content://com.byd.autovoice/PersonBean"

    private val ALLOWED_ROLE_KEYS = setOf(
        "DEFAULT_MAP_SWITCH",
        "MUSIC_SWITCH",
        "VIDEO_SWITCH",
    )

    private val PACKAGE_NAME = Regex(
        """[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""",
    )
    private val QUERY_ROW = Regex(
        """Row:\s+\d+\s+_id=(\d+),\s*SETTING=([^,\s]+),\s*VALUE=([^,\s]+)""",
    )
    private val EXPLICIT_SINGLE_ROW_UPDATE = Regex("""Updated[ \t]+1[ \t]+rows?\.?""")

    fun roleKey(role: DefaultAppRole): String = role.roleKey.also { roleKey ->
        if (roleKey !in ALLOWED_ROLE_KEYS) {
            throw DefaultAppRoleAccessException("Unsupported AutoVoice role: $roleKey")
        }
    }

    fun queryCommand(roleKey: String): String {
        requireAllowedRoleKey(roleKey)
        return "content query --uri $PROVIDER_URI " +
            "--projection _id:SETTING:VALUE --where \"SETTING='$roleKey'\""
    }

    fun updateCommand(
        roleKey: String,
        packageName: String,
        expectedCurrentPackageName: String? = null,
    ): String {
        requireAllowedRoleKey(roleKey)
        requirePackageName(packageName, "target")
        expectedCurrentPackageName?.let { expected ->
            requirePackageName(expected, "expected current")
        }
        val where = buildString {
            append("SETTING='$roleKey'")
            if (expectedCurrentPackageName != null) {
                append(" AND VALUE='$expectedCurrentPackageName'")
            }
        }
        return "content update --uri $PROVIDER_URI " +
            "--bind VALUE:s:$packageName --where \"$where\""
    }

    fun parseSinglePackage(
        roleKey: String,
        output: String,
    ): String {
        requireAllowedRoleKey(roleKey)
        val lines = output
            .replace("\r", "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val rows = lines.filter { it.startsWith("Row:") }
        if (rows.size != 1 || lines.size != 1) {
            throw DefaultAppRoleAccessException(
                "AutoVoice query for $roleKey returned ${rows.size} rows; expected exactly one " +
                    "(${diagnostic(output)})",
            )
        }

        val match = QUERY_ROW.matchEntire(rows.single())
            ?: throw DefaultAppRoleAccessException(
                "AutoVoice row for $roleKey has an unexpected shape (${diagnostic(output)})",
            )
        val storedRoleKey = match.groupValues[2]
        if (storedRoleKey != roleKey) {
            throw DefaultAppRoleAccessException(
                "AutoVoice returned $storedRoleKey while reading $roleKey",
            )
        }

        return match.groupValues[3].also { packageName ->
            requirePackageName(packageName, "stored")
        }
    }

    fun requireAcceptedUpdateOutput(
        roleKey: String,
        output: String,
    ) {
        requireAllowedRoleKey(roleKey)
        val normalized = output.replace("\r", "").trim()
        if (normalized.isNotEmpty() && !EXPLICIT_SINGLE_ROW_UPDATE.matches(normalized)) {
            throw DefaultAppRoleAccessException(
                "AutoVoice update for $roleKey returned unexpected non-empty output " +
                    "(${diagnostic(output)})",
            )
        }
    }

    fun requirePackageName(
        packageName: String,
        source: String,
    ) {
        if (!PACKAGE_NAME.matches(packageName)) {
            throw DefaultAppRoleAccessException(
                "Invalid $source Android package name: ${diagnostic(packageName)}",
            )
        }
    }

    private fun requireAllowedRoleKey(roleKey: String) {
        if (roleKey !in ALLOWED_ROLE_KEYS) {
            throw DefaultAppRoleAccessException("Unsupported AutoVoice role: $roleKey")
        }
    }

    private fun diagnostic(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .ifEmpty { "empty" }
        .take(MAX_DIAGNOSTIC_CHARS)
}

private const val SHELL_TIMEOUT_MS = 3_000
private const val MAX_DIAGNOSTIC_CHARS = 300
