package dev.denza.apps.feature.locale

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb

data class StockRussianLocaleSnapshot(
    val enabled: Boolean? = null,
    val running: Boolean = false,
    val message: String = "Состояние не проверено",
    val details: String? = null,
)

internal data class StockLocaleOverride(
    val tags: Set<String>,
) {
    val russianEnabled: Boolean
        get() = StockRussianLocalePolicy.RUSSIAN_TAG in tags

    val usesSystemDefault: Boolean
        get() = tags.isEmpty()
}

internal enum class StockRussianLocaleChange {
    ALREADY_SET,
    CHANGED,
}

internal object StockRussianLocalePolicy {
    const val TARGET_PACKAGE = "com.byd.carsettings"
    const val RUSSIAN_TAG = "ru-RU"

    private val localeListPattern = Regex("""\[([^]]*)]""")

    fun readCommand(): String = "cmd locale get-app-locales $TARGET_PACKAGE"

    fun writeCommand(enabled: Boolean): String = if (enabled) {
        "cmd locale set-app-locales $TARGET_PACKAGE --locales $RUSSIAN_TAG"
    } else {
        // Android 13 defines an omitted --locales option as an empty locale list. That removes
        // the app override and returns the stock app to the system language.
        "cmd locale set-app-locales $TARGET_PACKAGE"
    }

    fun parseOverride(output: String): StockLocaleOverride? {
        val encoded = localeListPattern.findAll(output).lastOrNull()?.groupValues?.get(1)
            ?: return null
        val tags = encoded
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        return StockLocaleOverride(tags)
    }
}

internal class StockRussianLocaleRepair(
    private val shell: (String) -> String,
) {
    fun inspect(): StockLocaleOverride = readOverride()

    fun setEnabled(enabled: Boolean): Pair<StockRussianLocaleChange, StockLocaleOverride> {
        val before = readOverride()
        val alreadySet = if (enabled) before.russianEnabled else before.usesSystemDefault
        if (alreadySet) return StockRussianLocaleChange.ALREADY_SET to before

        shell(StockRussianLocalePolicy.writeCommand(enabled))
        val after = readOverride()
        check(if (enabled) after.russianEnabled else after.usesSystemDefault) {
            "Locale override verification failed: expected " +
                (if (enabled) StockRussianLocalePolicy.RUSSIAN_TAG else "system default") +
                ", observed ${after.tags.ifEmpty { setOf("<empty>") }}"
        }
        return StockRussianLocaleChange.CHANGED to after
    }

    private fun readOverride(): StockLocaleOverride {
        val output = shell(StockRussianLocalePolicy.readCommand())
        return checkNotNull(StockRussianLocalePolicy.parseOverride(output)) {
            "Unexpected locale service response: ${output.trim().take(500)}"
        }
    }
}

internal object StockRussianLocaleCoordinator {
    fun inspect(context: Context): StockLocaleOverride = withRepair(context) { inspect() }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ): Pair<StockRussianLocaleChange, StockLocaleOverride> = withRepair(context) {
        setEnabled(enabled)
    }

    private fun <T> withRepair(
        context: Context,
        block: StockRussianLocaleRepair.() -> T,
    ): T {
        val session = DenzaLocalAdb.client(context.applicationContext).openPersistentShell()
        return try {
            StockRussianLocaleRepair { command -> session.shell(command) }.block()
        } finally {
            session.close()
        }
    }
}
