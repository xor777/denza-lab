package dev.denza.apps.feature.defaultapps

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One `PersonBean` row in the fixed `_id, SETTING, VALUE` projection this feature reads. */
internal data class PersonBeanRow(
    val id: Long,
    val setting: String,
    val value: String,
)

/**
 * Narrow suspendable boundary around the provider calls this feature makes.
 *
 * Both members are suspending so the production adapter can move the binder round trip off the
 * caller's thread; nothing here knows about the ContentResolver itself, which is what lets the
 * repository's safety rules be tested without a device.
 */
internal interface PersonBeanAccess {
    suspend fun query(
        selection: String,
        selectionArgs: Array<String>,
    ): List<PersonBeanRow>

    suspend fun update(
        value: String,
        selection: String,
        selectionArgs: Array<String>,
    ): Int
}

/**
 * Production adapter. An ordinary app UID may talk to this provider directly.
 *
 * `com.byd.autovoice` exports `PersonBean` with no read or write permission and no caller check,
 * so the feature needs no privilege path at all: a role reads in about 2 ms and writes in about
 * 3 ms. The retired transport shelled out to the `content` command through local ADB, which spawns
 * an `app_process` VM per call and cost about 1.2 s each - the tile's spinner, the disabled sheet
 * and the slow save were all that spawn. Binder calls still block, so they never run on the
 * caller's dispatcher.
 *
 * Notifications from this provider are not usable: an observer on the row URI and one on the
 * authority root both saw nothing, for our own write and for an external one. A reader invalidates
 * by re-reading.
 */
private class ContentResolverPersonBeanAccess(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonBeanAccess {
    private val resolver = context.applicationContext.contentResolver
    private val uri: Uri = Uri.parse(AutoVoicePersonBeanProtocol.PROVIDER_URI)

    override suspend fun query(
        selection: String,
        selectionArgs: Array<String>,
    ): List<PersonBeanRow> = withContext(ioDispatcher) {
        val cursor = try {
            resolver.query(
                uri,
                AutoVoicePersonBeanProtocol.PROJECTION,
                selection,
                selectionArgs,
                null,
            )
        } catch (error: SecurityException) {
            throw accessFailure("query", error)
        } catch (error: IllegalArgumentException) {
            // Unknown authority: the provider is absent on this build.
            throw accessFailure("query", error)
        } ?: throw DefaultAppRoleAccessException("AutoVoice PersonBean query returned no cursor")

        cursor.use { open ->
            val idColumn = open.requireColumn(AutoVoicePersonBeanProtocol.COLUMN_ID)
            val settingColumn = open.requireColumn(AutoVoicePersonBeanProtocol.COLUMN_SETTING)
            val valueColumn = open.requireColumn(AutoVoicePersonBeanProtocol.COLUMN_VALUE)
            buildList {
                while (open.moveToNext()) {
                    add(
                        PersonBeanRow(
                            id = open.getLong(idColumn),
                            setting = open.getString(settingColumn).orEmpty(),
                            value = open.getString(valueColumn).orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun update(
        value: String,
        selection: String,
        selectionArgs: Array<String>,
    ): Int = withContext(ioDispatcher) {
        val values = ContentValues(1).apply {
            put(AutoVoicePersonBeanProtocol.COLUMN_VALUE, value)
        }
        try {
            resolver.update(uri, values, selection, selectionArgs)
        } catch (error: SecurityException) {
            throw accessFailure("update", error)
        } catch (error: IllegalArgumentException) {
            throw accessFailure("update", error)
        }
    }

    private fun Cursor.requireColumn(name: String): Int =
        getColumnIndex(name).also { index ->
            if (index < 0) {
                throw DefaultAppRoleAccessException(
                    "AutoVoice PersonBean cursor has no $name column",
                )
            }
        }

    private fun accessFailure(
        operation: String,
        error: Exception,
    ): DefaultAppRoleAccessException = DefaultAppRoleAccessException(
        "AutoVoice PersonBean $operation was refused (${error.javaClass.simpleName})",
    )
}

/** A provider response that cannot prove the requested role state. */
class DefaultAppRoleAccessException(message: String) : IllegalStateException(message)

/**
 * Reads and writes the three stock AutoVoice default-app roles through `ContentResolver`.
 *
 * There is no background work or retry loop. Every call is an explicit coroutine operation, and
 * writes are successful only after a one-row preflight, an update that reports exactly one matched
 * row, and an exact readback. A mutex keeps those steps contiguous when the UI submits concurrent
 * requests.
 */
class DefaultAppRoleRepository internal constructor(
    private val access: PersonBeanAccess,
) {
    constructor(context: Context) : this(ContentResolverPersonBeanAccess(context))

    private val operationMutex = Mutex()

    suspend fun read(role: DefaultAppRole): String = operationMutex.withLock {
        readUnlocked(role)
    }

    /**
     * All three roles from one provider query, each reported on its own.
     *
     * A refresh used to send one query per role. One `SETTING IN (?,?,?)` returns the same rows in
     * one binder round trip, and a role whose row is missing or duplicated fails alone rather than
     * taking the other two with it. A transport failure fails all three, which is what three
     * separate failing queries did.
     */
    suspend fun readAll(): Map<DefaultAppRole, Result<String>> = operationMutex.withLock {
        val roles = DefaultAppRole.entries
        val roleKeys = roles.map(AutoVoicePersonBeanProtocol::roleKey)
        val rows = try {
            access.query(
                AutoVoicePersonBeanProtocol.settingInSelection(roleKeys.size),
                roleKeys.toTypedArray(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return@withLock roles.associateWith { Result.failure(error) }
        }

        val bySetting = rows.groupBy(PersonBeanRow::setting)
        roles.associateWith { role ->
            runCatching {
                AutoVoicePersonBeanProtocol.requireSingleRow(
                    role.roleKey,
                    bySetting[role.roleKey].orEmpty(),
                )
            }
        }
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
     * present in the provider's UPDATE predicate, not only checked before the call: the returned
     * matched-row count is the provider's own answer to "was it still that value".
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

        val updated = access.update(
            value = packageName,
            selection = if (expectedCurrentPackageName == null) {
                AutoVoicePersonBeanProtocol.SETTING_SELECTION
            } else {
                AutoVoicePersonBeanProtocol.SETTING_AND_VALUE_SELECTION
            },
            selectionArgs = if (expectedCurrentPackageName == null) {
                arrayOf(roleKey)
            } else {
                arrayOf(roleKey, expectedCurrentPackageName)
            },
        )
        if (updated != 1) {
            throw DefaultAppRoleAccessException(
                when {
                    updated == 0 && expectedCurrentPackageName != null ->
                        "AutoVoice $roleKey changed before the conditional write; " +
                            "expected $expectedCurrentPackageName"

                    updated == 0 -> "AutoVoice update for $roleKey matched no row"
                    else -> "AutoVoice update for $roleKey matched $updated rows; expected one"
                },
            )
        }

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
        val rows = access.query(
            AutoVoicePersonBeanProtocol.SETTING_SELECTION,
            arrayOf(roleKey),
        )
        return AutoVoicePersonBeanProtocol.requireSingleRow(roleKey, rows)
    }
}

internal object AutoVoicePersonBeanProtocol {
    const val PROVIDER_URI = "content://com.byd.autovoice/PersonBean"

    const val COLUMN_ID = "_id"
    const val COLUMN_SETTING = "SETTING"
    const val COLUMN_VALUE = "VALUE"

    val PROJECTION = arrayOf(COLUMN_ID, COLUMN_SETTING, COLUMN_VALUE)

    const val SETTING_SELECTION = "$COLUMN_SETTING=?"
    const val SETTING_AND_VALUE_SELECTION = "$COLUMN_SETTING=? AND $COLUMN_VALUE=?"

    private val ALLOWED_ROLE_KEYS = setOf(
        "DEFAULT_MAP_SWITCH",
        "MUSIC_SWITCH",
        "VIDEO_SWITCH",
    )

    private val PACKAGE_NAME = Regex(
        """[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""",
    )

    fun roleKey(role: DefaultAppRole): String = role.roleKey.also(::requireAllowedRoleKey)

    /** `SETTING IN (?,?,?)` - one placeholder per role key, values never inlined. */
    fun settingInSelection(count: Int): String {
        if (count < 1) {
            throw DefaultAppRoleAccessException("AutoVoice query needs at least one role")
        }
        return "$COLUMN_SETTING IN (${List(count) { "?" }.joinToString(",")})"
    }

    /**
     * The one row this feature is willing to act on, or a refusal.
     *
     * Zero rows, more than one, a row the provider filed under a different SETTING, or a VALUE that
     * is not an Android package name all fail closed - the state of the role is unknown, and a
     * write on top of an unknown state is the thing this repository exists to prevent.
     */
    fun requireSingleRow(
        roleKey: String,
        rows: List<PersonBeanRow>,
    ): String {
        requireAllowedRoleKey(roleKey)
        if (rows.size != 1) {
            throw DefaultAppRoleAccessException(
                "AutoVoice query for $roleKey returned ${rows.size} rows; expected exactly one",
            )
        }
        val row = rows.single()
        if (row.setting != roleKey) {
            throw DefaultAppRoleAccessException(
                "AutoVoice returned ${diagnostic(row.setting)} while reading $roleKey",
            )
        }
        return row.value.also { packageName ->
            requirePackageName(packageName, "stored")
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

private const val MAX_DIAGNOSTIC_CHARS = 300
